import '../styles/live.css'
import { apiFetch, fetchManifest, fileUrl } from '../api'
import { appLink } from '../routes'
import { startCoach, type Coach } from './coach'

// Build with the camera (`?mode=live&job=<id>`). The phone is held over the real parts. Three things on the
// screen, and no more: which step this is, what to do in it, and one button, "I'm done, check it".
//
//   the step     its title and what it adds, read aloud once by the voice coach (Gemini Live, coach.ts)
//   the check    one photo goes to POST /api/perception/{job}/verify: a careful look that answers whether the
//                step is finished, what is in place and what is still missing (about 5 s). The answer fills the
//                screen: a tick and the page moves on, or a cross with what is missing.
//   who asks     the person (the button, or saying "I'm done"), or the coach by itself when the pictures show
//                the step's pieces all in place
//
// The coach speaks little: the step, a warning when it clearly sees a mistake, answers to questions, and the
// check's sentence. Its words are shown as one line. `?coach=0`: no voice, the button alone.

type Lang = 'el' | 'en'
interface Action { type: string; text?: { en?: string; el?: string } }
interface Step { n: number; title: string; actions: Action[]; crop?: string | null; ar?: string; pickParts?: string[]; hardware?: { code: string; qty: number }[] }
interface Named { id?: string; code?: string; name: string; short?: string; names?: { en?: string; el?: string } }
interface Verdict { step: number; done: boolean; visible: boolean; certain?: boolean; have: string; wrong?: string[]; missing: string[]; say: { en: string; el: string }; message: string }

const W = {
  en: {
    start: 'Start', startHelp: 'Hold the phone so the camera sees the parts. Build one step, then let it check your work.',
    step: 'Step', of: 'of', check: 'I’m done, check it', checking: 'Checking your work', hold: 'Hold the phone still',
    done: 'Step finished', notYet: 'Not finished yet', cantSee: 'I can’t see it', next: 'Next step', isWrong: 'Something is the wrong way', maybe: 'Check this',
    skip: 'Skip', back: 'Back', drawing: 'Show the drawing', close: 'OK', mic: 'Microphone', steps: 'See this step in 3D',
    built: 'It is built.', failed: 'The check did not answer. Try again.',
    noCamera: 'The camera could not be opened. Allow it in the browser and try again.',
  },
  el: {
    start: 'Ξεκίνα', startHelp: 'Κράτα το κινητό ώστε η κάμερα να βλέπει τα κομμάτια. Χτίσε ένα βήμα και μετά άφησέ το να ελέγξει τη δουλειά σου.',
    step: 'Βήμα', of: 'από', check: 'Τελείωσα, έλεγξέ το', checking: 'Ελέγχω τη δουλειά σου', hold: 'Κράτα το κινητό σταθερό',
    done: 'Το βήμα τελείωσε', notYet: 'Δεν τελείωσε ακόμα', cantSee: 'Δεν το βλέπω', next: 'Επόμενο βήμα', isWrong: 'Κάτι μπήκε λάθος', maybe: 'Έλεγξε αυτό',
    skip: 'Παράλειψη', back: 'Πίσω', drawing: 'Δείξε το σχέδιο', close: 'Εντάξει', mic: 'Μικρόφωνο', steps: 'Δες το βήμα σε 3D',
    built: 'Χτίστηκε.', failed: 'Ο έλεγχος δεν απάντησε. Δοκίμασε ξανά.',
    noCamera: 'Η κάμερα δεν άνοιξε. Δώσε άδεια στον browser και δοκίμασε ξανά.',
  },
} as const

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)
const AUTO_GAP_MS = 9000 // between two checks the coach asks for by itself
const AFTER_FAIL_MS = 14000

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
  <div id="live" data-state="idle">
    <video playsinline muted autoplay></video>
    <header>
      <a class="round" href="${esc(appLink({ mode: 'ready', job }))}" aria-label="${w.back}"><svg viewBox="0 0 24 24"><path d="M14.5 5.5 8 12l6.5 6.5"/></svg></a>
      <div class="where"><b></b><ol class="bar" aria-hidden="true"></ol></div>
      <a class="round steps" aria-label="${w.steps}" title="${w.steps}"><svg viewBox="0 0 24 24"><path d="M12 3 20 7.5v9L12 21l-8-4.5v-9z"/><path d="M12 12 20 7.5M12 12v9M12 12 4 7.5"/></svg></a>
      <button class="round mic" type="button" aria-pressed="true" aria-label="${w.mic}" hidden><svg viewBox="0 0 24 24"><path d="M12 4a2.5 2.5 0 0 0-2.5 2.5v5a2.5 2.5 0 0 0 5 0v-5A2.5 2.5 0 0 0 12 4ZM6.5 11.5a5.5 5.5 0 0 0 11 0M12 17v3"/></svg></button>
    </header>

    <p class="said" aria-live="polite" hidden></p>

    <footer>
      <button class="card" type="button" aria-label="${w.drawing}">
        <span class="text"><strong></strong><small></small></span>
        <img class="crop" alt="" hidden />
      </button>
      <button class="primary check" type="button">${w.check}</button>
      <div class="row"><button class="quiet prev" type="button">${w.back}</button><button class="quiet skip" type="button">${w.skip}</button></div>
    </footer>

    <div class="result" role="alert" hidden>
      <i></i><b></b><p></p><ul></ul>
      <button class="primary" type="button"></button>
    </div>

    <div class="gate">
      <h1></h1><p>${w.startHelp}</p>
      <button class="primary go" type="button">${w.start}</button>
      <p class="err" role="alert"></p>
    </div>
    <div class="sheet" hidden><img alt="${w.drawing}" /><button class="primary" type="button">${w.close}</button></div>
  </div>`
  const $ = <T extends HTMLElement>(sel: string) => app.querySelector<T>(sel)!
  const root = $('#live')
  const video = $<HTMLVideoElement>('video')

  let manifest
  try {
    manifest = await fetchManifest(job, key)
  } catch {
    $('.gate h1').textContent = w.failed
    return
  }
  const steps = manifest.steps as unknown as Step[]
  const named = new Map<string, Named>()
  for (const p of manifest.parts as unknown as Named[]) if (p.id) named.set(p.id, p)
  for (const h of manifest.hardware as unknown as Named[]) if (h.code) named.set(h.code, h)
  const nameOf = (id: string) => {
    const n = named.get(id)
    return n ? (n.names?.[lang] ?? n.short ?? n.name) : id.replace(/_/g, ' ')
  }
  $('.gate h1').textContent = (manifest as { title?: string }).title ?? ''

  // How large each piece is, so that two panels of one colour can be told apart: "38 × 46 cm, thin".
  // From the built part's bounding box (parts/<id>.mates.json); a guide built before that file was kept has none.
  const sizes = new Map<string, string>()
  const thin = lang === 'el' ? 'λεπτό' : 'thin'
  void Promise.all([...named.keys()].map(async (id) => {
    try {
      const res = await fetch(fileUrl(job, `parts/${id}.mates.json`, key))
      if (!res.ok) return
      const d = (await res.json()) as { kind?: string; bbox_mm: { min: number[]; max: number[] } }
      const [a, b, c] = [0, 1, 2].map((i) => d.bbox_mm.max[i] - d.bbox_mm.min[i]).sort((x, y) => y - x)
      const cm = (mm: number) => String(Math.round(mm / 10))
      sizes.set(id, d.kind === 'hardware' ? `${Math.round(a)} mm` : `${cm(a)} × ${cm(b)} cm${c <= 6 ? `, ${thin}` : ''}`)
    } catch {
      /* no size for this piece */
    }
  })).then(() => paint())

  // The plan writes the product name and the step titles in English only. In Greek the page asks the server for
  // them (translated once per guide and kept) and shows the English ones until they arrive, or if they do not.
  const greek: { title?: string; steps: Record<string, string> } = { steps: {} }
  const titleOf = (s: Step) => (lang === 'el' && greek.steps[String(s.n)]) || s.title
  if (lang === 'el') {
    void apiFetch(`/api/perception/${encodeURIComponent(job)}/titles`, key)
      .then((r) => (r.ok ? r.json() : null))
      .then((t: { title?: string; steps?: Record<string, string> } | null) => {
        if (!t) return
        greek.title = t.title
        greek.steps = t.steps ?? {}
        if (t.title) $('.gate h1').textContent = t.title
        paint()
      })
      .catch(() => {})
  }

  let at = Math.max(0, Math.min(steps.length - 1, Number(q.get('step') || 1) - 1))
  let finished = false
  let coach: Coach | null = null
  let busy = false // a check is running or its result is on show
  let lastAuto = 0
  let saidTimer = 0
  const current = () => steps[at]

  // ---- the step
  const needs = (s: Step): string => {
    const parts = new Map<string, number>()
    for (const p of s.pickParts ?? []) parts.set(p, (parts.get(p) ?? 0) + 1)
    const sized = (id: string) => (sizes.has(id) ? `${nameOf(id)} (${sizes.get(id)})` : nameOf(id))
    const list = [...parts].map(([id, n]) => `${n}× ${sized(id)}`)
    for (const h of s.hardware ?? []) list.push(`${h.qty}× ${sized(h.code)}`)
    return list.join(' · ')
  }
  const paint = () => {
    const s = current()
    $('.where b').textContent = finished ? w.built : `${w.step} ${at + 1} ${w.of} ${steps.length}`
    $<HTMLAnchorElement>('.steps').href = appLink({ job, step: String(current().n) }) // the same step, as the 3D walk
    $('.bar').innerHTML = steps.map((_, i) => `<li class="${i < at || finished ? 'done' : i === at ? 'now' : ''}"></li>`).join('')
    $('.card strong').textContent = finished ? w.built : titleOf(s)
    $('.card small').textContent = finished ? '' : needs(s)
    const crop = $<HTMLImageElement>('.crop')
    // the picture of the piece this step adds (its first part, else its hardware): which one to pick up
    const piece = s.pickParts?.[0] ?? s.hardware?.[0]?.code
    crop.hidden = !piece || finished
    if (piece) crop.src = fileUrl(job, `renders/part-${piece}-shaded.png`, key)
    $<HTMLButtonElement>('.check').hidden = finished
    $<HTMLButtonElement>('.prev').disabled = at === 0 && !finished
    $<HTMLButtonElement>('.skip').hidden = finished
  }
  /** The coach is given the step in the guide's English words; it says it in the page's language. */
  const tellStep = () => {
    if (!coach?.on) return
    if (speaking) return void (tellLater = true) // the check's sentence is still being said: the step waits for it
    const s = current()
    if (finished) return coach.tell('[RESULT] Every step is finished. Say that it is built.')
    const actions = s.actions.map((a) => a.text?.en).filter(Boolean).join(' ')
    coach.tell(`[STEP ${s.n}] ${s.title}. ${actions}`.slice(0, 900))
  }
  const goTo = (i: number, done = false) => {
    finished = done
    at = Math.max(0, Math.min(steps.length - 1, i))
    lastAuto = performance.now() // a new step is not checked the moment it starts
    paint()
    tellStep()
  }
  const forward = () => (at < steps.length - 1 ? goTo(at + 1) : goTo(at, true))

  // ---- the coach's words: one line, gone after a few seconds
  const said = (text: string, warn = false) => {
    const el = $('.said')
    el.textContent = text
    el.dataset.warn = String(warn)
    el.hidden = !text
    clearTimeout(saidTimer)
    saidTimer = window.setTimeout(() => (el.hidden = true), warn ? 8000 : 5000)
  }

  // ---- the check's sentence is said aloud in the guide's own voice (POST /api/tts, the headset's voice). The
  // coach's Live session may paraphrase or stay silent; a mistake must be heard exactly as it was found.
  let audioCtx: AudioContext | null = null
  let speaking: AudioBufferSourceNode | null = null
  let tellLater = false
  const speak = async (text: string, fallback: string) => {
    if (!text) return
    try {
      const res = await apiFetch('/api/tts', key, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) })
      if (!res.ok || !audioCtx) throw new Error(String(res.status))
      const buf = await audioCtx.decodeAudioData(await res.arrayBuffer())
      speaking?.stop()
      const src = audioCtx.createBufferSource()
      src.buffer = buf
      src.connect(audioCtx.destination)
      // one voice at a time: while this sentence plays, the coach is silent and does not hear it through the microphone
      coach?.hold(true)
      src.onended = () => {
        if (speaking !== src) return
        speaking = null
        window.setTimeout(() => {
          if (speaking) return
          coach?.hold(false)
          if (tellLater) {
            tellLater = false
            tellStep()
          }
        }, 500)
      }
      src.start()
      speaking = src
    } catch {
      coach?.tell(fallback) // no speech route, or it failed: the coach says it
    }
  }

  // ---- the check: one photo, one answer that fills the screen
  const photo = (): string | null => {
    if (!video.videoWidth) return null
    const k = Math.min(1, 1280 / Math.max(video.videoWidth, video.videoHeight))
    const c = document.createElement('canvas')
    c.width = Math.round(video.videoWidth * k)
    c.height = Math.round(video.videoHeight * k)
    c.getContext('2d')!.drawImage(video, 0, 0, c.width, c.height)
    return c.toDataURL('image/jpeg', 0.82).split(',')[1]
  }
  const result = $('.result')
  const closeResult = () => {
    result.hidden = true
    root.dataset.state = 'idle'
    busy = false
  }
  const check = async (auto: boolean) => {
    if (busy || finished) return
    const image = photo()
    if (!image) return
    busy = true
    const n = current().n
    const button = result.querySelector('button')!
    button.hidden = true
    if (!auto) {
      // asked by the person: say at once that it is looking. Asked by the coach: stay out of the way until it knows.
      root.dataset.state = 'checking'
      result.hidden = false
      result.dataset.v = 'wait'
      result.querySelector('b')!.textContent = w.checking
      result.querySelector('p')!.textContent = w.hold
      result.querySelector('ul')!.innerHTML = ''
    }
    let v: Verdict | null = null
    try {
      const res = await apiFetch(`/api/perception/${encodeURIComponent(job)}/verify`, key, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ image, step: n, lang }),
      })
      if (res.ok) v = (await res.json()) as Verdict
    } catch {
      /* shown below */
    }
    if (n !== current().n) return closeResult() // the person moved on while it was looking
    if (!v) {
      if (auto) return closeResult()
      result.dataset.v = 'unsure'
      result.querySelector('b')!.textContent = w.failed
      result.querySelector('p')!.textContent = ''
      button.textContent = w.close
      button.hidden = false
      button.onclick = closeResult
      return
    }
    const sentence = v.say?.[lang] || v.message || ''
    if (v.done) {
      root.dataset.state = 'done'
      result.hidden = false
      result.dataset.v = 'done'
      result.querySelector('b')!.textContent = w.done
      result.querySelector('p')!.textContent = v.have || sentence
      result.querySelector('ul')!.innerHTML = ''
      navigator.vibrate?.(60)
      void speak(sentence, `[RESULT] Step ${n} is finished. Say: "${sentence}"`)
      window.setTimeout(() => {
        closeResult()
        if (n === current().n) forward()
      }, 2800)
      return
    }
    const mistakes = v.wrong ?? []
    // a check the coach asked for that finds the step merely unfinished shows nothing: no cross for work in
    // progress. A mistake it found is shown and said whoever asked.
    if (auto && !mistakes.length) {
      lastAuto = performance.now() + (AFTER_FAIL_MS - AUTO_GAP_MS)
      return closeResult()
    }
    root.dataset.state = 'wrong'
    result.hidden = false
    // a cross only for a mistake both looks agree on; a doubt is a question mark; work still to do is neither
    const kind = !v.visible ? 'unsure' : mistakes.length ? (v.certain === false ? 'unsure' : 'wrong') : 'todo'
    result.dataset.v = kind
    result.querySelector('b')!.textContent =
      !v.visible ? w.cantSee : mistakes.length ? (v.certain === false ? w.maybe : w.isWrong) : w.notYet
    result.querySelector('p')!.textContent = sentence
    result.querySelector('ul')!.innerHTML = [...mistakes, ...(v.missing ?? [])].slice(0, 3).map((m) => `<li>${esc(m)}</li>`).join('')
    navigator.vibrate?.(mistakes.length ? [80, 50, 80] : 40)
    void speak(sentence, `[RESULT] Step ${n} is not finished. Say: "${sentence}"`)
    button.textContent = w.close
    button.hidden = false
    button.onclick = closeResult
  }

  $('.check').addEventListener('click', () => void check(false))
  $('.prev').addEventListener('click', () => (finished ? goTo(at) : goTo(at - 1)))
  $('.skip').addEventListener('click', forward)
  $('.card').addEventListener('click', () => {
    const s = current()
    if (!s.crop) return
    $<HTMLImageElement>('.sheet img').src = fileUrl(job, s.crop, key)
    $('.sheet').hidden = false
  })
  $('.sheet button').addEventListener('click', () => ($('.sheet').hidden = true))
  $('.crop').addEventListener('error', (e) => ((e.target as HTMLElement).hidden = true))

  // ---- start: one tap opens the camera and the microphone and lets the voice speak
  $('.go').addEventListener('click', async () => {
    const err = $('.err')
    err.textContent = ''
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    const audio = new Ctx() // made inside the tap, so that the phone lets the voices play
    void audio.resume()
    audioCtx = audio
    const wish = { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } }
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: q.get('mic') === '0' ? false : { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        video: wish,
      })
    } catch {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: wish })
      } catch {
        err.textContent = w.noCamera
        return
      }
    }
    const mic = stream.getAudioTracks().length ? new MediaStream(stream.getAudioTracks()) : null
    video.srcObject = new MediaStream(stream.getVideoTracks())
    void video.play().catch(() => {})
    $('.gate').hidden = true
    paint()
    if (q.get('coach') !== '0') {
      coach = await startCoach({
        job, key, lang, video, audio, mic,
        onWords: (text) => said(text, /^(Πρόσεξε|Careful)/i.test(text)),
        onTool: (name, args) => {
          if (name === 'check_step') {
            // by itself the coach may ask only now and then
            const quiet = performance.now() - lastAuto < AUTO_GAP_MS
            if (!quiet) {
              lastAuto = performance.now()
              void check(true)
            }
            return { started: !quiet }
          }
          if (name === 'next_step') forward()
          else if (name === 'previous_step') goTo(at - 1)
          else if (name === 'go_to_step') goTo(Number(args.step || 1) - 1)
          const s = current()
          return { step: s.n, of: steps.length, title: s.title }
        },
        onState: (state) => {
          root.dataset.coach = state
        },
      })
      const micBtn = $<HTMLButtonElement>('.mic')
      micBtn.hidden = !coach || !mic
      micBtn.addEventListener('click', () => {
        const on = micBtn.getAttribute('aria-pressed') !== 'true'
        micBtn.setAttribute('aria-pressed', String(on))
        coach?.setMic(on)
      })
      tellStep()
    }
  })
  window.addEventListener('pagehide', () => coach?.stop())
  paint()
}
