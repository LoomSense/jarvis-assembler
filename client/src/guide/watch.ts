// Live guidance on the guide page: the camera watches the hands, the server says what it sees, the voice says the
// sentence and the page shows the few words that still work with the sound off.
//
// One quiet round control in the top left corner starts and stops it. While it runs, that spot is the camera's
// live picture with a ring that tells the state by its shape. Nothing here ever presses Next.
//
// The module behind it is `src/assist` (camera, socket, speech). This file only joins it to the page.
import { createAssist, type Assist, type AssistStatus, type GuidanceEvent } from '../assist'
import type { Lang } from '../types'
import type { Guide } from './guide'
import { firstSentence, WATCH } from './words'

export interface WatchPage {
  /** the page's root: gets the class `hinted` and the length `--lift` while a hint is shown */
  stage: HTMLElement
  /** the round control; the live picture is put inside it */
  button: HTMLButtonElement
  /** a few quiet words beside the control: simulated, paused, no vision, camera refused */
  word: HTMLElement
  /** the page's own line of words, which makes room for the hint under it */
  now: HTMLElement
  /** the short line under the page's own words */
  hint: HTMLElement
  /** off screen: the server's full sentence, for a screen reader */
  heard: HTMLElement
}

export interface WatchOptions {
  guide: Guide
  page: WatchPage
  job: string
  key: string | null
  /** where the perception socket is; undefined: the build's API base, else the page's own origin */
  apiBase?: string
  lang: Lang
  /** `?assist=1`: the person came here to build with camera help, so the control asks for its one tap */
  invite?: boolean
}

export interface Watch {
  setLang(lang: Lang): void
  /** for the console and the check scripts */
  debug: { readonly assist: Assist | null; readonly status: AssistStatus | null }
}

// The brand's status shapes (BRAND.md §3), 24 x 24, one stroke: circle with a check, octagon with a cross,
// triangle with a bar, diamond with a question mark. A state is never told by colour alone.
const MARKS = {
  right: '<circle cx="12" cy="12" r="9"/><path d="M8 12.4l2.7 2.7L16 9.6"/>',
  wrong: '<path d="M8.3 3h7.4L21 8.3v7.4L15.7 21H8.3L3 15.7V8.3z"/><path d="M9.2 9.2l5.6 5.6M14.8 9.2l-5.6 5.6"/>',
  check: '<path d="M12 3.6 21.4 20H2.6z"/><path d="M12 10v4.4M12 17.2v.1"/>',
  unsure: '<path d="M12 2.6 21.4 12 12 21.4 2.6 12z"/><path d="M10 10a2.1 2.1 0 1 1 3.1 1.9c-.7.4-1.1.9-1.1 1.7M12 16.3v.1"/>',
} as const

interface Short {
  mark: keyof typeof MARKS | null
  text: string
}

export function startWatch(o: WatchOptions): Watch {
  const { guide, page } = o
  let lang = o.lang
  let assist: Assist | null = null
  let last: AssistStatus | null = null
  let hintKey = ''
  let started = false // the control was tapped once: the invitation is over

  const describe = document.createElement('span') // the ring's state in words, for a screen reader
  describe.className = 'sr'
  describe.id = 'watch-state'
  page.button.after(describe)
  page.button.setAttribute('aria-describedby', describe.id)
  page.button.hidden = false

  // ------------------------------------------------------------------ the few words on screen

  /** How a part id or a hardware code is written inside a sentence: a code as it is, a name in lower case ("the end slat"). */
  const named = (id: string | null, article = true): string | null => {
    const label = id ? guide.label(id) : null
    if (!label) return null
    return /^\d+$/.test(label) ? label : (article ? WATCH[lang].the : '') + label.toLocaleLowerCase(lang)
  }

  /** The shortest form of a guidance event that still works with the sound off. null: nothing to show. */
  function shortOf(e: GuidanceEvent): Short | null {
    const w = WATCH[lang]
    const wrong = (): Short => {
      const seen = named(e.observed_id)
      if (!seen) return { mark: 'wrong', text: w.notThis }
      if (!e.expected_id) return { mark: 'wrong', text: w.notHere.replace('{x}', named(e.observed_id, false)!) }
      // the page already shows what the action needs: name it only when the server means something else
      const needs = e.expected_id !== guide.wanted() ? named(e.expected_id) : null
      return { mark: 'wrong', text: w.hold.replace('{x}', seen) + (needs ? `, ${w.needs.replace('{x}', needs)}` : '') }
    }
    switch (e.event) {
      case 'wrong_hardware':
      case 'wrong_part':
        return wrong()
      case 'right_hardware':
      case 'right_part':
        return { mark: 'right', text: w.right }
      case 'show_part_result':
        return e.match === true ? { mark: 'right', text: w.right } : e.match === false ? wrong() : { mark: 'unsure', text: w.unsure }
      case 'flip_needed':
        return { mark: 'check', text: guide.turnOf(e.step ?? guide.place().step) }
      case 'out_of_order': {
        const seen = named(e.observed_id, false)
        return { mark: 'check', text: seen ? `${w.notYet}: ${seen}` : w.notYet }
      }
      case 'unsure_show_part':
        return { mark: 'unsure', text: w.show }
      case 'more_light':
        return { mark: 'check', text: w.light }
      case 'realign_camera':
        return { mark: 'check', text: w.moved }
      case 'step_complete': // the cue is on Next; the sentence is spoken
      case 'vision_paused': // the ring and the word beside it say it for as long as it lasts
      case 'vision_resumed':
        return null
      default: {
        const sentence = firstSentence(lang, e.say) // an event this page does not know: the server's own first sentence
        return sentence ? { mark: null, text: sentence } : null
      }
    }
  }

  /**
   * Why nothing is being watched, or that the answers are scripted, in two or three words. While answers come
   * from the server's script the word stays for as long as they do; a reason why nothing is watched takes its place.
   */
  function stateWord(s: AssistStatus): string {
    const w = WATCH[lang]
    if (s.vision === 'simulated') return w.simulated // scripted answers are never passed off as seen ones
    if (s.vision === 'unavailable') {
      return (
        s.why === 'camera_denied'
          ? w.cameraDenied
          : s.why === 'camera_missing'
            ? w.cameraMissing
            : s.why === 'camera_ended'
              ? w.cameraEnded
              : s.why === 'camera_unsupported'
                ? w.cameraUnsupported
                : s.why === 'refused'
                  ? w.refused
                  : s.why === 'no_questions'
                    ? w.stepUnwatched
                    : w.noVision
      )
    }
    if (s.vision === 'paused') {
      // a drop that the first retry mends gets no words: the module gives its notice from the second failure on
      return s.why === 'failing' ? w.cantSee : s.why === 'reconnecting' && s.say?.from === 'notice' ? w.reconnecting : ''
    }
    return ''
  }

  function setHint(short: Short | null): void {
    const key = short ? `${short.mark}|${short.text}` : ''
    if (key === hintKey) return
    hintKey = key
    page.hint.replaceChildren()
    if (short) {
      if (short.mark) {
        page.hint.insertAdjacentHTML('beforeend', `<svg viewBox="0 0 24 24" aria-hidden="true">${MARKS[short.mark]}</svg>`)
      }
      const words = document.createElement('span')
      words.textContent = short.text // names and sentences from the server and the manifest are never parsed as HTML
      page.hint.append(words)
      page.hint.dataset.mark = short.mark ?? 'none'
    }
    page.stage.classList.toggle('hinted', !!short)
    makeRoom()
  }

  /**
   * The page's own line moves up just far enough for the hint to sit under it, between the two buttons.
   * Both can take one line or more (a long name, a turn the page has no short words for), so it is measured.
   */
  function makeRoom(): void {
    if (!hintKey) return
    let words = 0
    for (const child of page.now.children) words = Math.max(words, (child as HTMLElement).offsetHeight)
    const slack = Math.max(0, (page.now.offsetHeight - words) / 2) // the line's words are centred in its box
    page.stage.style.setProperty('--lift', `${Math.max(0, Math.round(page.hint.offsetHeight + 6 - slack))}px`)
  }

  // ------------------------------------------------------------------ what the server believes, on the page

  function render(status: AssistStatus | null): void {
    last = status
    const on = !!status?.running
    page.button.setAttribute('aria-pressed', String(on))
    page.button.setAttribute('aria-label', WATCH[lang].control)
    page.button.title = WATCH[lang].control
    page.word.textContent = on ? stateWord(status!) : o.invite && !started ? WATCH[lang].tapToStart : ''
    page.button.classList.toggle('invite', !!o.invite && !started && !on)
    describe.textContent = on ? (assist?.preview.getAttribute('aria-label') ?? '') : ''
    if (!on || !status) {
      setHint(null)
      guide.setHeld(null)
      guide.cueNext(false)
      return
    }

    const at = guide.place()
    // a sentence about another step or action than the one on screen is not shown: it is no longer about this
    const e = status.say?.from === 'event' ? status.say.event : null
    const current = !!e && (e.step === null || e.step === at.step) && (e.action === null || at.action === null || e.action === at.action)
    setHint(e && current ? shortOf(e) : null)

    // The thing itself reacts only to what the server sees now, on the step and action the page shows.
    const server = status.server
    const seeing =
      (status.vision === 'live' || status.vision === 'simulated') &&
      !!server &&
      server.step === at.step &&
      (server.action === null || at.action === null || server.action === at.action)
    guide.setHeld(seeing && (status.verdict === 'right' || status.verdict === 'wrong') ? { verdict: status.verdict, key: status.holding.id } : null)
    guide.cueNext(seeing && status.state === 'STEP_COMPLETE')
    makeRoom() // the page's own words may have changed under the same hint
  }

  function make(): Assist {
    const made = createAssist({
      jobId: o.job,
      key: o.key,
      apiBase: o.apiBase,
      lang,
      viewpoint: 'fixed', // a laptop's own camera, or a phone on a stand
      speakCodes: false, // a six-digit code read aloud takes ten seconds; the screen shows it
      device: 'guide-page',
    })
    page.button.append(made.preview)
    page.heard.append(made.line) // the full sentence, for a screen reader; the module silences it while it is spoken
    made.on('status', render)
    return made
  }

  // The tap is the gesture the browser wants for the camera question and for speech: everything starts inside it.
  page.button.addEventListener('click', () => {
    if (assist?.status.running) {
      assist.stop()
      return
    }
    started = true
    assist ??= make()
    const at = guide.place()
    void assist.start(at.step, at.action)
  })
  guide.onPlace((at) => {
    assist?.setStep(at.step, at.action)
    if (last) render(assist ? assist.status : last) // a hint about the action just left goes at once
  })
  window.addEventListener('pagehide', () => assist?.stop())
  render(null)

  return {
    setLang(l) {
      lang = l
      assist?.setLang(l)
      hintKey = '' // the same event, said again in the other language
      render(assist ? assist.status : null)
    },
    debug: {
      get assist() {
        return assist
      },
      get status() {
        return assist ? assist.status : null
      },
    },
  }
}
