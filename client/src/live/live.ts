import '../styles/live.css'
import { fetchManifest, fileUrl } from '../api'
import { createAssist, type Assist, type AssistStatus, type GuidanceEvent } from '../assist'
import { appLink } from '../routes'
import { startCoach, type Coach } from './coach'

// Build with the camera (`?mode=live&job=<id>`): the phone is held over the real parts, the whole screen is the
// camera's picture, and what the server recognises in it is shown and spoken as it happens:
//
//   what it sees     the piece or screw in the hands, by name, and whether it is the one this action needs
//   what to do now   the guide's own instruction for the current action, with the part's picture
//   what went wrong  wrong screw, wrong part, turned the wrong way, out of order: said aloud, shown in red
//   when it is done  the step is seen finished, said aloud, and the page moves to the next step by itself
//
// Every frame goes to the perception socket (src/assist, server/perception): gemini-decision-1 answers the
// step's questions in about half a second to a second per frame. Nothing here draws on the real parts: the
// browser of a phone has no position of the camera in the room.

type Lang = 'el' | 'en'
interface Action { type: string; text?: { en?: string; el?: string }; partId?: string; code?: string; node?: string }
interface Step { n: number; title: string; actions: Action[]; crop?: string | null; hardware?: { code: string; qty: number }[] }
interface Named { id?: string; code?: string; name: string; short?: string; names?: { en?: string; el?: string }; thumb?: string }

const W = {
  en: {
    start: 'Start the camera', startHelp: 'Hold the phone over the parts. It watches each step, says what it sees and tells you what to do next.',
    step: 'Step', of: 'of', live: 'live', paused: 'paused', connecting: 'connecting', off: 'not watching',
    sees: 'I see', nothing: 'Show me the part you picked up', right: 'That is the right one', wrong: 'Not this one',
    needs: 'This action needs', unknown: 'I am not sure what that is. Hold it closer.',
    waiting: 'Pick up the part', inHand: 'Part in hand', placing: 'Putting it in place', complete: 'Step finished', idle: 'Ready',
    done: 'Done, next', back: 'Back', what: 'What is this?', mute: 'Sound', guide: 'The guide’s drawing', close: 'Close',
    finished: 'It is built.', toGuide: 'Open the 3D steps', dark: 'It is too dark here', blocked: 'Something is in the way of the camera',
    poor: 'Hold the phone steadier', moved: 'Point the camera at the parts again', noCamera: 'The camera could not be opened. Allow it in the browser and try again.',
    noVision: 'The server is not watching right now', sim: 'simulated', move: 'action',
    coach: 'coach', coachOff: 'voice coach off', mic: 'Microphone', askHeld: 'What am I holding, and is it right for this action?',
  },
  el: {
    start: 'Άνοιξε την κάμερα', startHelp: 'Κράτα το κινητό πάνω από τα κομμάτια. Βλέπει κάθε βήμα, λέει τι βλέπει και σου λέει τι να κάνεις μετά.',
    step: 'Βήμα', of: 'από', live: 'ζωντανά', paused: 'παύση', connecting: 'σύνδεση', off: 'δεν βλέπει',
    sees: 'Βλέπω', nothing: 'Δείξε μου το κομμάτι που πήρες', right: 'Αυτό είναι το σωστό', wrong: 'Όχι αυτό',
    needs: 'Εδώ χρειάζεται', unknown: 'Δεν είμαι σίγουρος τι είναι. Φέρ’ το πιο κοντά.',
    waiting: 'Πάρε το κομμάτι', inHand: 'Το κρατάς', placing: 'Το βάζεις στη θέση του', complete: 'Το βήμα τελείωσε', idle: 'Έτοιμο',
    done: 'Έγινε, επόμενο', back: 'Πίσω', what: 'Τι είναι αυτό;', mute: 'Ήχος', guide: 'Το σχέδιο του οδηγού', close: 'Κλείσιμο',
    finished: 'Χτίστηκε.', toGuide: 'Άνοιξε τα βήματα σε 3D', dark: 'Είναι πολύ σκοτεινά εδώ', blocked: 'Κάτι κρύβει την κάμερα',
    poor: 'Κράτα το κινητό πιο σταθερά', moved: 'Στρέψε ξανά την κάμερα στα κομμάτια', noCamera: 'Η κάμερα δεν άνοιξε. Δώσε άδεια στον browser και δοκίμασε ξανά.',
    noVision: 'Ο server δεν βλέπει αυτή τη στιγμή', sim: 'προσομοίωση', move: 'κίνηση',
    coach: 'βοηθός', coachOff: 'ο φωνητικός βοηθός είναι κλειστός', mic: 'Μικρόφωνο', askHeld: 'Τι κρατάω, και είναι το σωστό για αυτή την κίνηση;',
  },
} as const

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)

export async function startLive(app: HTMLElement): Promise<void> {
  const q = new URLSearchParams(location.search)
  const job = q.get('job')
  const key = q.get('key')
  const lang: Lang = q.get('lang') === 'en' ? 'en' : 'el'
  const w = W[lang]
  document.documentElement.lang = lang
  document.documentElement.dataset.theme = 'dark'
  if (!job) return location.replace(appLink({}))

  app.innerHTML = `
  <div id="live" data-tone="idle">
    <video playsinline muted autoplay></video>
    <div class="frame" aria-hidden="true"></div>
    <header>
      <a class="round" href="${esc(appLink({ mode: 'ready', job }))}" aria-label="${w.back}"><svg viewBox="0 0 24 24"><path d="M14.5 5.5 8 12l6.5 6.5"/></svg></a>
      <p class="where"><b></b><span class="title"></span></p>
      <p class="link" role="status"><i></i><span></span></p>
      <button class="round mic" type="button" aria-pressed="true" aria-label="${w.mic}" hidden><svg viewBox="0 0 24 24"><path d="M12 4a2.5 2.5 0 0 0-2.5 2.5v5a2.5 2.5 0 0 0 5 0v-5A2.5 2.5 0 0 0 12 4ZM6.5 11.5a5.5 5.5 0 0 0 11 0M12 17v3"/></svg></button>
    </header>
    <p class="said" aria-live="polite" hidden><b></b><span></span></p>
    <section class="sees" aria-live="polite"><small></small><strong></strong><span></span></section>
    <p class="toast" role="alert" hidden></p>
    <footer>
      <div class="now">
        <img class="thumb" alt="" hidden />
        <div><small></small><p></p></div>
        <button class="crop" type="button" hidden aria-label="${w.guide}"><img alt="" /></button>
      </div>
      <div class="row">
        <button class="ghost prev" type="button">${w.back}</button>
        <button class="ghost ask" type="button">${w.what}</button>
        <button class="primary next" type="button">${w.done}</button>
      </div>
    </footer>
    <div class="gate">
      <h1></h1><p>${w.startHelp}</p>
      <button class="primary go" type="button">${w.start}</button>
      <p class="err" role="alert"></p>
    </div>
    <div class="sheet" hidden><img alt="${w.guide}" /><button class="ghost" type="button">${w.close}</button></div>
  </div>`
  const $ = <T extends HTMLElement>(sel: string) => app.querySelector<T>(sel)!
  const root = $('#live')

  let manifest
  try {
    manifest = await fetchManifest(job, key)
  } catch {
    $('.gate h1').textContent = w.noVision
    return
  }
  const steps = manifest.steps as unknown as Step[]
  const named = new Map<string, Named>()
  for (const p of manifest.parts as unknown as Named[]) if (p.id) named.set(p.id, p)
  for (const h of manifest.hardware as unknown as Named[]) if (h.code) named.set(h.code, h)
  const nameOf = (id: string | null | undefined) => {
    const n = id ? named.get(id) : null
    return n ? (n.names?.[lang] ?? n.short ?? n.name) : (id ?? '')
  }
  $('.gate h1').textContent = (manifest as { title?: string }).title ?? ''

  let at = { step: Math.max(0, Math.min(steps.length - 1, Number(q.get('step') || 1) - 1)), action: 0 }
  let assist: Assist | null = null
  let coach: Coach | null = null
  let toldStep = ''
  let finished = false

  const current = () => steps[at.step]
  const action = () => current().actions[at.action] ?? null

  const paintStep = () => {
    const s = current()
    const a = action()
    $('.where b').textContent = `${w.step} ${at.step + 1} ${w.of} ${steps.length}`
    $('.where .title').textContent = s.title
    const id = a?.code ?? a?.partId ?? null
    $('.now small').textContent = s.actions.length > 1 ? `${w.move} ${at.action + 1} ${w.of} ${s.actions.length}` : ''
    $('.now p').textContent = finished ? w.finished : (a?.text?.[lang] ?? a?.text?.en ?? s.title)
    const thumb = $<HTMLImageElement>('.thumb')
    thumb.hidden = !id
    if (id) thumb.src = fileUrl(job, `renders/part-${id}-shaded.png`, key)
    const crop = $<HTMLButtonElement>('.crop')
    crop.hidden = !s.crop
    if (s.crop) (crop.firstElementChild as HTMLImageElement).src = fileUrl(job, s.crop, key)
    $<HTMLButtonElement>('.prev').disabled = at.step === 0 && at.action === 0
    assist?.setStep(s.n, at.action)
    tellStep()
  }
  /** The coach is told the action in the guide's English words; it says it in the page's language. */
  const tellStep = () => {
    if (!coach?.on) return
    const s = current()
    const a = action()
    const line = finished ? '[STEP done] Every step is finished. Tell the person it is built.'
      : `[STEP ${s.n} ACTION ${at.action + 1}] ${a?.text?.en ?? a?.text?.el ?? s.title}`
    if (line === toldStep) return
    toldStep = line
    coach.tell(line)
  }

  const go = (d: 1 | -1) => {
    const s = current()
    if (d > 0) {
      if (at.action < s.actions.length - 1) at = { step: at.step, action: at.action + 1 }
      else if (at.step < steps.length - 1) at = { step: at.step + 1, action: 0 }
      else finished = true
    } else {
      finished = false
      if (at.action > 0) at = { step: at.step, action: at.action - 1 }
      else if (at.step > 0) at = { step: at.step - 1, action: Math.max(0, steps[at.step - 1].actions.length - 1) }
    }
    paintStep()
  }
  $('.next').addEventListener('click', () => go(1))
  $('.prev').addEventListener('click', () => go(-1))
  $('.ask').addEventListener('click', () => (coach?.on ? coach.tell(w.askHeld) : assist?.showPart()))
  $('.thumb').addEventListener('error', (e) => ((e.target as HTMLElement).hidden = true))
  $('.crop').addEventListener('click', () => {
    $<HTMLImageElement>('.sheet img').src = ($('.crop').firstElementChild as HTMLImageElement).src
    $('.sheet').hidden = false
  })
  $('.sheet button').addEventListener('click', () => ($('.sheet').hidden = true))

  // ---- what the server sees, as it says it
  const STATE = { IDLE: w.idle, WAITING_FOR_PART: w.waiting, PART_IN_HAND: w.inHand, PLACING: w.placing, STEP_COMPLETE: w.complete } as const
  const paintStatus = (st: AssistStatus) => {
    const link = $('.link')
    const vision = st.vision
    link.dataset.vision = vision
    const ms = st.server?.frames?.decision_ms_p50
    link.querySelector('span')!.textContent =
      vision === 'live' ? `${w.live}${ms ? ` · ${(ms / 1000).toFixed(1)} s` : ''}`
      : vision === 'simulated' ? w.sim
      : st.link === 'connecting' || st.link === 'reconnecting' ? w.connecting
      : vision === 'paused' ? w.paused : w.off
    const sees = $('.sees')
    const held = st.holding
    const need = st.expected?.name ?? nameOf(action()?.code ?? action()?.partId)
    const view = st.view
    let tone = 'idle'
    let head: string = STATE[st.state] ?? w.idle
    let big: string = ''
    let small: string = ''
    if (view === 'dark' || view === 'blocked' || view === 'poor' || view === 'moved') {
      big = w[view]
      tone = 'warn'
    } else if (held && (held.kind === 'part' || held.kind === 'hardware') && held.name) {
      head = w.sees
      big = held.name
      if (st.verdict === 'right') { small = w.right; tone = 'ok' }
      else if (st.verdict === 'wrong') { small = need ? `${w.wrong}. ${w.needs}: ${need}` : w.wrong; tone = 'bad' }
    } else if (held?.kind === 'unknown') {
      big = w.unknown
    } else {
      big = need ? `${w.needs}: ${need}` : w.nothing
      if (st.state === 'STEP_COMPLETE') tone = 'ok'
    }
    if (vision === 'unavailable' || vision === 'off') { big = st.reason || w.noVision; tone = 'warn' }
    sees.querySelector('small')!.textContent = head
    sees.querySelector('strong')!.textContent = big
    sees.querySelector('span')!.textContent = small
    root.dataset.tone = tone
  }

  let toastTimer = 0
  const toast = (text: string, tone: string) => {
    const t = $('.toast')
    t.textContent = text
    t.dataset.tone = tone
    t.hidden = false
    clearTimeout(toastTimer)
    toastTimer = window.setTimeout(() => (t.hidden = true), 5000)
  }
  const BAD = new Set(['wrong_part', 'wrong_hardware', 'flip_needed', 'out_of_order'])
  const onEvent = (e: GuidanceEvent) => {
    const text = e.say?.[lang] || e.say?.en || e.message || ''
    if (text) toast(text, BAD.has(e.event) ? 'bad' : e.event === 'step_complete' || e.event.startsWith('right_') ? 'ok' : 'info')
    if (BAD.has(e.event)) navigator.vibrate?.([60, 40, 60])
    if (coach?.on && e.speak !== false && (e.perception || text)) coach.tell(`[CHECK] ${e.perception || e.say?.en || text}`)
    // the camera saw the step finished: move on by itself, after the sentence has had a moment
    if (e.event === 'step_complete' && e.step === current().n) {
      navigator.vibrate?.(40)
      window.setTimeout(() => {
        if (e.step !== current().n) return
        if (at.step < steps.length - 1) at = { step: at.step + 1, action: 0 }
        else finished = true
        paintStep()
      }, 1800)
    }
  }

  // ---- start: one tap opens the camera (the browser asks) and lets the voice speak
  $('.go').addEventListener('click', async () => {
    const err = $('.err')
    err.textContent = ''
    // made inside the tap, so that the phone lets the coach's voice play
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    const audio = new Ctx()
    void audio.resume()
    const videoWish = { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } }
    let stream: MediaStream
    try {
      // the camera and the microphone in one question; `?mic=0` asks for the camera alone
      stream = await navigator.mediaDevices.getUserMedia({
        audio: q.get('mic') === '0' ? false : { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        video: videoWish,
      })
    } catch {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: videoWish })
      } catch {
        err.textContent = w.noCamera
        return
      }
    }
    const camera = new MediaStream(stream.getVideoTracks())
    const mic = stream.getAudioTracks().length ? new MediaStream(stream.getAudioTracks()) : null
    const video = $<HTMLVideoElement>('video')
    video.srcObject = camera
    void video.play().catch(() => {})
    $('.gate').hidden = true
    // the coach that sees (Gemini Live): `?coach=0` leaves only the step checks and the browser's own voice
    const said = $('.said')
    if (q.get('coach') !== '0') {
      coach = await startCoach({
        job, key, lang, video, audio, mic,
        onWords: (text) => {
          said.hidden = false
          said.querySelector('span')!.textContent = text
        },
        onTool: (name, args) => {
          if (name === 'next_step') { at = { step: Math.min(steps.length - 1, at.step + 1), action: 0 }; paintStep() }
          else if (name === 'previous_step') { at = { step: Math.max(0, at.step - 1), action: 0 }; paintStep() }
          else if (name === 'go_to_step') {
            const n = Math.max(1, Math.min(steps.length, Number(args.step) || 1))
            at = { step: n - 1, action: 0 }
            paintStep()
          } else if (name === 'show_part_request') assist?.showPart()
          const s = current()
          return { step: s.n, of: steps.length, title: s.title, action: at.action + 1, instruction: action()?.text?.en ?? s.title }
        },
        onState: (state) => {
          root.dataset.coach = state
          said.querySelector('b')!.textContent = state === 'on' ? w.coach : state === 'off' ? w.coachOff : ''
          if (state === 'off') said.querySelector('span')!.textContent = ''
          said.hidden = state === 'connecting'
        },
      })
      const micBtn = $<HTMLButtonElement>('.mic')
      micBtn.hidden = !coach || !mic
      micBtn.addEventListener('click', () => {
        const on = micBtn.getAttribute('aria-pressed') !== 'true'
        micBtn.setAttribute('aria-pressed', String(on))
        coach?.setMic(on)
      })
    }
    // a phone in the hand moves with the person, like a headset: the server then waits for a still moment
    // with the coach speaking, the step checks stay silent: their findings are handed to the coach to say
    assist = createAssist({ jobId: job, key, lang, viewpoint: 'headset', camera, device: 'phone-live', ...(coach ? { speaker: null } : {}) })
    assist.on('status', paintStatus)
    assist.on('event', onEvent)
    paintStep()
    await assist.start(current().n, at.action)
    paintStatus(assist.status)
  })
  window.addEventListener('pagehide', () => {
    assist?.stop()
    coach?.stop()
  })
  paintStep()
}
