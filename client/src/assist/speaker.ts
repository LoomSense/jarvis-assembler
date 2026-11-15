// The voice. `Speaker` is the small interface the assist talks to; `createBrowserSpeaker` is the one built
// here, on the browser's speech synthesis (PLAN §6.4 "deterministic fallback"). The project's live voice
// model can take its place later: it gets the whole event, with the `perception` line it needs.
import type { GuidanceEvent, Lang } from './protocol'

/** How much a sentence matters when two meet: a warning, a finished step, a request, a confirmation. */
export type SayKind = 'warning' | 'done' | 'info' | 'confirmation'

export interface Utterance {
  /** the ready sentence, in the page language */
  text: string
  lang: Lang
  kind: SayKind
  step: number | null
  /** the event behind it; absent for the module's own notices */
  event?: GuidanceEvent
}

export interface Speaker {
  /** Say it. Must never overlap its own speech. May drop it (a confirmation that waits behind a warning). */
  speak(utterance: Utterance): void
  /** Silence now and forget whatever waits. */
  stop(): void
  /** Called inside the tap that starts guidance: browsers let speech begin only from a gesture. */
  unlock?(): void
  /** Can it speak this language? false: it has no voice for it. null: not known yet. */
  can?(lang: Lang): boolean | null
}

const RANK: Record<SayKind, number> = { confirmation: 0, info: 1, done: 2, warning: 3 }

export function kindOf(event: GuidanceEvent): SayKind {
  switch (event.event) {
    case 'wrong_part':
    case 'wrong_hardware':
    case 'flip_needed':
    case 'out_of_order':
      return 'warning'
    case 'step_complete':
      return 'done'
    case 'right_part':
    case 'right_hardware':
      return 'confirmation'
    case 'show_part_result':
      return event.match === false ? 'warning' : event.match ? 'confirmation' : 'info'
    default:
      return 'info'
  }
}

/** For `prepare`: "the short screw, 108560." becomes "the short screw." Step and page numbers stay. */
export function withoutCodes(text: string): string {
  return text.replace(/,?\s+\d{5,}(?=[\s.,;:]|$)/g, '')
}

export interface BrowserSpeakerOptions {
  /** speaking rate, 1 = the voice's own */
  rate?: number
  /** a sentence that waited longer than this behind another is dropped: by then it may no longer be true */
  maxWaitMs?: number
  /**
   * Change what is said aloud; the line on the screen keeps the full sentence. A six-digit hardware code
   * is read as a whole number, which takes seconds: `withoutCodes` leaves the codes to the screen.
   */
  prepare?: (text: string, lang: Lang) => string
  /** the chosen voice or what is being said changed */
  onChange?: () => void
}

export interface BrowserSpeaker extends Speaker {
  /** the browser has speech synthesis at all */
  readonly supported: boolean
  /** something is being said right now */
  readonly speaking: boolean
  /** name of the voice used for a language, or null */
  voiceName(lang: Lang): string | null
  can(lang: Lang): boolean | null
  unlock(): void
}

const TAG: Record<Lang, string> = { el: 'el-GR', en: 'en-US' }
const PLAIN = /^(Melina|Nikos|Samantha|Alex|Daniel|Karen|Moira|Tessa|Google|Microsoft)\b/i

export function createBrowserSpeaker(options: BrowserSpeakerOptions = {}): BrowserSpeaker {
  const synth: SpeechSynthesis | null = typeof speechSynthesis === 'undefined' ? null : speechSynthesis
  const maxWaitMs = options.maxWaitMs ?? 8000
  let voices: SpeechSynthesisVoice[] = []
  let current: Utterance | null = null
  let waiting: { utterance: Utterance; at: number } | null = null
  let watchdog = 0
  let unlocked = false
  // Chrome drops an utterance that nothing refers to, and then `onend` never fires: keep it here.
  const alive: { utterance: SpeechSynthesisUtterance | null } = { utterance: null }

  const load = (): void => {
    const list = synth?.getVoices() ?? []
    if (list.length === voices.length) return
    voices = list
    options.onChange?.()
  }
  load()
  synth?.addEventListener?.('voiceschanged', load)

  /**
   * The best voice for a language: the platform's default when it speaks it, then a plain voice (macOS lists
   * its novelty voices first among the English ones), then one on the device, then the usual region.
   */
  function pick(lang: Lang): SpeechSynthesisVoice | null {
    if (!voices.length) load()
    let best: SpeechSynthesisVoice | null = null
    let bestScore = -1
    for (const v of voices) {
      const tag = v.lang.replace('_', '-').toLowerCase()
      if (tag !== lang && !tag.startsWith(lang + '-')) continue
      const score = (v.default ? 8 : 0) + (PLAIN.test(v.name) ? 4 : 0) + (v.localService ? 2 : 0) + (tag === TAG[lang].toLowerCase() ? 1 : 0)
      if (score > bestScore) {
        best = v
        bestScore = score
      }
    }
    return best
  }

  function next(): void {
    const w = waiting
    waiting = null
    if (w && performance.now() - w.at <= maxWaitMs) begin(w.utterance)
    else options.onChange?.()
  }

  function begin(utterance: Utterance): void {
    if (!synth) return
    const voice = pick(utterance.lang)
    // The voices are known and none speaks this language: stay silent. Another language's voice would
    // read only the numbers of the sentence. With no list yet (some phones) the language tag decides.
    if (!voice && voices.length) return next()
    const u = new SpeechSynthesisUtterance(options.prepare ? options.prepare(utterance.text, utterance.lang) : utterance.text)
    u.lang = voice?.lang ?? TAG[utterance.lang]
    if (voice) u.voice = voice
    u.rate = options.rate ?? 1
    const finish = (): void => {
      if (current !== utterance) return
      current = null
      alive.utterance = null
      clearTimeout(watchdog)
      next()
    }
    u.onend = finish
    u.onerror = finish
    current = utterance
    alive.utterance = u
    // `onend` is not reliable on every engine: never let a lost one silence the sentences after it
    watchdog = window.setTimeout(() => {
      if (current !== utterance) return
      current = null
      alive.utterance = null
      synth.cancel()
      window.setTimeout(next, 80) // a `speak` straight after `cancel` can be swallowed
    }, 5000 + utterance.text.length * 160)
    try {
      synth.resume()
      synth.speak(u)
    } catch {
      finish()
    }
    options.onChange?.()
  }

  return {
    supported: !!synth,
    get speaking() {
      return current !== null
    },
    voiceName: (lang) => pick(lang)?.name ?? null,
    can: (lang) => (!synth ? false : voices.length ? pick(lang) !== null : null),

    speak(utterance) {
      if (!synth || !utterance.text) return
      if (!current) return begin(utterance)
      // Something is being said. One sentence may wait: the most important, and of equals the newest.
      // So a newer warning replaces a waiting confirmation, and a confirmation never pushes a warning out.
      if (!waiting || RANK[utterance.kind] >= RANK[waiting.utterance.kind]) waiting = { utterance, at: performance.now() }
    },

    stop() {
      waiting = null
      current = null
      alive.utterance = null
      clearTimeout(watchdog)
      synth?.cancel()
      options.onChange?.()
    },

    unlock() {
      if (!synth || unlocked) return
      unlocked = true
      const u = new SpeechSynthesisUtterance(' ')
      u.volume = 0
      try {
        synth.speak(u)
      } catch {
        // speech stays locked; the sentence is still on the screen
      }
    },
  }
}
