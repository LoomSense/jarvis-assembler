// Live guidance, the browser half: camera frames go to the perception socket, and what the server works
// out comes back as a `status` (what it believes it sees) and as guidance events (one ready sentence each),
// which are shown and spoken. Protocol: server/perception/README.md. One entry: `createAssist(options)`.
//
// Nothing here depends on the rest of the client. A page drives it with start / setStep / stop; the headset
// mode hands over the camera it already opened and forwards its hand summaries.
import { Camera, type CameraState } from './camera'
import { FrameLoop, type FrameStats } from './frameLoop'
import { createPresence, type Presence } from './presence'
import {
  FALLBACK_LIMITS,
  packFrame,
  readLimits,
  socketUrl,
  type ErrorMessage,
  type Expected,
  type GuidanceEvent,
  type HandSummary,
  type Held,
  type HelloMessage,
  type Lang,
  type Limits,
  type Role,
  type ServerMessage,
  type StatusMessage,
  type StepState,
  type Verdict,
  type View,
  type Viewpoint,
} from './protocol'
import { createBrowserSpeaker, kindOf, withoutCodes, type Speaker } from './speaker'
import { WORDS, type Tone } from './words'

export interface AssistOptions {
  /** a job built by the server */
  jobId: string
  /** the demo key from the page URL. It goes into the socket URL and nowhere else. */
  key: string | null
  /** API base. Default: VITE_API_URL from the build, otherwise the page's own origin. */
  apiBase?: string
  /** the page language: the sentence shown and spoken */
  lang: Lang
  /** `fixed`: a phone or a laptop on a stand. `headset`: the camera moves with the person (also a hand-held phone). */
  viewpoint: Viewpoint
  /**
   * `open` (default): open a camera on start(), the back one when there is one.
   * A MediaStream: use the camera the caller already holds; it is never stopped here.
   * `none`: this device sends no frames (another device is the camera).
   */
  camera?: MediaStream | 'open' | 'none'
  /** `display` (default) drives the step. `camera` only watches: it follows the step of the session. */
  role?: Role
  /** a label for the server's logs, at most 40 characters */
  device?: string
  /** Default: the browser's speech synthesis for a `display`, none for a `camera`. null: silent. */
  speaker?: Speaker | null
  /**
   * For the default speaker only. false: hardware codes are shown but not read aloud. A Greek voice reads
   * "108560" as a whole number: the wrong-screw sentence took 10.5 s with the codes and 4.5 s without.
   * Default true: the sentence is spoken as the server wrote it.
   */
  speakCodes?: boolean
  muted?: boolean
  /** Stop sending frames while the document is hidden. Default true. */
  pauseWhenHidden?: boolean
  onStatus?: (status: AssistStatus) => void
  onEvent?: (event: GuidanceEvent) => void
  onError?: (error: AssistError) => void
}

export type Link = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'refused'

/** Whether anything is really watching. `simulated` and `unavailable` are never reported as `live`. */
export type Vision = 'off' | 'live' | 'simulated' | 'paused' | 'unavailable'

/** Why vision is not `live`, when it is `paused` or `unavailable`. */
export type Why =
  | 'server' // the server has no vision: no key, or the key or its credit was refused (its reason is in `reason`)
  | 'no_questions' // the server cannot watch this step; the next step may be fine
  | 'refused' // the server refused this job (its reason is in `reason`)
  | 'camera_denied'
  | 'camera_missing'
  | 'camera_ended'
  | 'camera_unsupported'
  | 'reconnecting'
  | 'failing' // the server's decision calls fail; it says when they work again
  | 'hidden' // this tab is hidden, so no frames are sent
  | null

export interface Sentence {
  text: string
  /** `event`: the server's guidance. `notice`: the module's own line about its state. */
  from: 'event' | 'notice'
  event: GuidanceEvent | null
}

export interface AssistStatus {
  running: boolean
  link: Link
  camera: CameraState | 'none'
  vision: Vision
  why: Why
  /** the server's own words for `why: 'server'` and `why: 'refused'` */
  reason: string | null
  /** true whenever the answers come from the server's debug script */
  simulated: boolean
  /** frames are leaving this device now */
  capturing: boolean
  step: number | null
  action: number | null
  page: number | null
  state: StepState
  expected: Expected | null
  holding: Held
  verdict: Verdict
  view: View
  handsPresent: boolean | null
  /** frames per second the server asks for */
  rateHz: number
  /** one value for a ring or a ghost: what to show right now */
  tone: Tone
  /** the sentence to show now, in the page language */
  say: Sentence | null
  speech: 'on' | 'muted' | 'no_voice' | 'off'
  limits: Limits
  frames: FrameStats
  /** the server's last `status`, untouched */
  server: StatusMessage | null
}

export interface AssistError {
  /** a server error code (`unknown_step`, `unknown_job`, ...) */
  code: string
  message: string
}

interface Handlers {
  status: (status: AssistStatus) => void
  event: (event: GuidanceEvent) => void
  error: (error: AssistError) => void
}

export interface Assist {
  /**
   * Begin: opens the camera and the socket. Call it from a tap (camera permission, speech).
   * A `display` gives the step it is on; a `camera` gives none and follows the session.
   * Resolves when the camera question is answered. Never throws.
   */
  start(step?: number | null, action?: number | null): Promise<void>
  /** The step or the action on the page changed. `action` is the 0-based index in the step's manifest actions. */
  setStep(step: number, action?: number | null): void
  /** Forward a hand summary from the headset; about 10 a second are passed on. */
  sendHands(summary: HandSummary): void
  /** Ask for one close look at what the person holds up; the answer is the event `show_part_result`. */
  showPart(expected?: string): void
  /** "I know": no more of this warning on this step. Without a name, the last warning given. */
  iKnow(event?: string): void
  /** End guidance, close the socket and release a camera that was opened here. */
  stop(): void
  /** stop() and remove everything the module put in the page. */
  destroy(): void
  setMuted(muted: boolean): void
  setLang(lang: Lang): void
  on<K extends keyof Handlers>(type: K, handler: Handlers[K]): () => void
  readonly status: AssistStatus
  /** the last few events, oldest first */
  readonly events: readonly GuidanceEvent[]
  readonly muted: boolean
  readonly lang: Lang
  /** A small round live preview with the state ring. Mount it where you want; made on first use. */
  readonly preview: HTMLElement
  /** The current sentence as one line of text. Mount it where you want; made on first use. */
  readonly line: HTMLElement
  /** For test pages. */
  readonly debug: {
    /** send any JSON message, for example `{type: "sim", holding: "108363"}` */
    send(message: Record<string, unknown>): boolean
    /** close the socket as if the network had dropped it */
    drop(): void
    /** every message of the server, as parsed */
    onMessage: ((message: ServerMessage) => void) | null
  }
}

const KEEP_EVENTS = 8
const RETRY_FIRST_MS = 500
const RETRY_MAX_MS = 5000
const CONNECT_TIMEOUT_MS = 8000
const COUNTERS_EVERY_MS = 250
/** hand summaries: at most one per 90 ms while nothing changed, and never closer than 30 ms */
const HANDS_SAME_MS = 90
const HANDS_ANY_MS = 30
/** the socket's send buffer above this means the last frame has not left yet: do not add another */
const BACKLOG_BYTES = 16_000
/**
 * While the server says vision is unavailable it asks for no frames. But when the key or its credit was
 * refused it tries again only when a frame arrives (every 30 s at most), so one frame goes out now and then.
 */
const PROBE_HZ = 0.1
/** the server closes after these and trying again would not help */
const REFUSALS = ['unknown_job', 'unknown_role', 'not_built']

const NO_HOLD: Held = { kind: 'unknown', id: null, name: null, confidence: 0 }

export function createAssist(options: AssistOptions): Assist {
  const role: Role = options.role ?? 'display'
  const cameraMode = options.camera ?? 'open'
  const pauseWhenHidden = options.pauseWhenHidden ?? true
  const apiBase = options.apiBase ?? String(import.meta.env.VITE_API_URL ?? '')

  let lang: Lang = options.lang
  let muted = options.muted ?? false
  let running = false
  let destroyed = false

  // the socket
  let ws: WebSocket | null = null
  let link: Link = 'idle'
  let attempt = 0 // connections that failed in a row
  let everOpen = false
  let retryTimer = 0
  let openTimer = 0
  let refusal: ErrorMessage | null = null

  // the session
  let wanted: { step: number; action: number | null } | null = null // what the page last reported
  let started = false // `start` was sent in this run
  let hello: HelloMessage | null = null
  let server: StatusMessage | null = null
  let limits: Limits = FALLBACK_LIMITS
  let sim = false
  let simSaid = false
  let session = ''
  let lastEventId = 0
  let lastHands = { at: 0, key: '' }
  let blindStep: number | null = null // the server said it has no questions for this step

  // what comes back
  const events: GuidanceEvent[] = []
  let shown: { text: string; event: GuidanceEvent } | null = null
  let shownTimer = 0
  let presence: Presence | null = null
  let emitQueued = false
  let countersTimer = 0
  let onDebugMessage: ((message: ServerMessage) => void) | null = null
  const handlers: { [K in keyof Handlers]: Set<Handlers[K]> } = { status: new Set(), event: new Set(), error: new Set() }
  if (options.onStatus) handlers.status.add(options.onStatus)
  if (options.onEvent) handlers.event.add(options.onEvent)
  if (options.onError) handlers.error.add(options.onError)

  const speaker: Speaker | null =
    options.speaker !== undefined
      ? options.speaker
      : role === 'camera'
        ? null
        : createBrowserSpeaker({ onChange: changed, prepare: options.speakCodes === false ? withoutCodes : undefined })
  const camera = new Camera(changed)
  const loop = new FrameLoop({
    ready: canSend,
    wantedHz: () => (!server ? 0 : server.show_part ? limits.maxHz : server.vision === 'unavailable' ? PROBE_HZ : server.rate_hz),
    limits: () => limits,
    grab: (l) => camera.grab(l.longEdgePx, l.jpegQuality),
    send: sendFrame,
    changed: countersChanged,
  })

  // ---------------------------------------------------------------- telling the caller

  function fire<K extends keyof Handlers>(type: K, payload: Parameters<Handlers[K]>[0]): void {
    for (const handler of handlers[type]) {
      try {
        ;(handler as (p: typeof payload) => void)(payload)
      } catch (err) {
        console.error('assist: a listener threw', err) // one listener must not stop the guidance
      }
    }
  }

  /** Something changed: tell the listeners once, after the current work is done. */
  function changed(): void {
    if (emitQueued || destroyed) return
    emitQueued = true
    queueMicrotask(() => {
      emitQueued = false
      if (destroyed) return
      const status = snapshot()
      presence?.setStream(camera.stream)
      presence?.update(status, lang)
      fire('status', status)
    })
  }

  /** Only the frame counters changed: a few times a second is enough. */
  function countersChanged(): void {
    if (countersTimer) return
    countersTimer = window.setTimeout(() => {
      countersTimer = 0
      changed()
    }, COUNTERS_EVERY_MS)
  }

  const visible = (): boolean => !pauseWhenHidden || document.visibilityState !== 'hidden'
  const stepNow = (): number | null => (wanted ? wanted.step : (server?.step ?? null))

  function notice(vision: Vision, why: Why): string | null {
    const w = WORDS[lang]
    switch (why) {
      case 'refused':
        return w.refused
      case 'server':
        return w.unavailable
      case 'no_questions':
        return w.stepUnwatched
      case 'camera_denied':
        return w.cameraDenied
      case 'camera_missing':
        return w.cameraMissing
      case 'camera_ended':
        return w.cameraEnded
      case 'camera_unsupported':
        return w.cameraUnsupported
      case 'reconnecting':
        if (attempt < 2) return null // not for a blip that the first retry mends
        return everOpen ? w.reconnecting : w.cannotConnect
      case 'failing':
        return w.paused
      default:
        return vision === 'simulated' ? w.simulated : null
    }
  }

  function snapshot(): AssistStatus {
    const cam: CameraState | 'none' = cameraMode === 'none' ? 'none' : camera.state
    let vision: Vision
    let why: Why = null
    if (!running) vision = 'off'
    else if (link === 'refused') [vision, why] = ['unavailable', 'refused']
    else if (link !== 'open' || !hello) [vision, why] = link === 'reconnecting' ? ['paused', 'reconnecting'] : ['off', null]
    else if ((server?.vision ?? hello.vision) === 'unavailable') [vision, why] = ['unavailable', 'server']
    else if (cam === 'denied' || cam === 'missing' || cam === 'ended' || cam === 'unsupported') [vision, why] = ['unavailable', `camera_${cam}`]
    else if (cam === 'idle' || cam === 'asking') vision = 'off'
    else if (cam === 'none' && !server?.source) vision = 'off' // no other device is sending frames
    else if (blindStep !== null && blindStep === stepNow()) [vision, why] = ['unavailable', 'no_questions']
    else if (server?.vision === 'paused') [vision, why] = ['paused', 'failing']
    else if (cam === 'on' && !visible()) [vision, why] = ['paused', 'hidden']
    else vision = sim ? 'simulated' : 'live'

    let tone: Tone
    if (vision === 'off' || vision === 'paused' || vision === 'unavailable') tone = vision
    else if (!server || server.step === null) tone = 'watching'
    else if (server.show_part || shown?.event.event === 'unsure_show_part') tone = 'unsure'
    else if (server.verdict === 'wrong') tone = 'wrong'
    else if (shown && ['flip_needed', 'out_of_order', 'more_light', 'realign_camera'].includes(shown.event.event)) tone = 'check'
    else if (server.view === 'dark' || server.view === 'poor' || server.view === 'moved') tone = 'check'
    else if (server.verdict === 'right' || server.state === 'STEP_COMPLETE') tone = 'right'
    else tone = 'watching'

    const own = notice(vision, why)
    // When nothing is watching, the line says so at once: an earlier sentence about what was seen gives way.
    const blind = own !== null && (vision === 'unavailable' || vision === 'paused')
    const say: Sentence | null = shown && !blind ? { text: shown.text, from: 'event', event: shown.event } : own ? { text: own, from: 'notice', event: null } : null
    const reason = why === 'refused' ? (refusal?.message ?? null) : why === 'server' ? (server?.reason ?? hello?.reason ?? null) : null
    return {
      running,
      link,
      camera: cam,
      vision,
      why,
      reason,
      simulated: sim,
      capturing: running && canSend() && loop.stats().hz > 0,
      step: stepNow(),
      action: wanted ? wanted.action : (server?.action ?? null),
      page: server?.page ?? null,
      state: server?.state ?? 'IDLE',
      expected: server?.expected ?? null,
      holding: server?.holding ?? NO_HOLD,
      verdict: server?.verdict ?? 'unknown',
      view: server?.view ?? 'none',
      handsPresent: server?.hands_present ?? null,
      rateHz: server?.rate_hz ?? 0,
      tone,
      say,
      speech: !speaker ? 'off' : muted ? 'muted' : speaker.can?.(lang) === false ? 'no_voice' : 'on',
      limits,
      frames: loop.stats(),
      server,
    }
  }

  // ---------------------------------------------------------------- the socket

  function sendJson(message: Record<string, unknown>): boolean {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false
    try {
      ws.send(JSON.stringify(message))
      return true
    } catch {
      return false
    }
  }

  function canSend(): boolean {
    const step = stepNow()
    // `server.step === step`: the server has switched to the step the frame would be stamped with
    return !!ws && ws.readyState === WebSocket.OPEN && !!hello && !!server && step !== null && server.step === step && step !== blindStep && visible() && camera.ready
  }

  function sendFrame(seq: number, jpeg: Uint8Array): boolean {
    const step = stepNow()
    if (!ws || ws.readyState !== WebSocket.OPEN || step === null) return false
    if (ws.bufferedAmount > BACKLOG_BYTES) return false // a slow uplink: the newest picture is worth more than a queue
    try {
      ws.send(packFrame({ seq, step, ts: Date.now(), source: options.viewpoint }, jpeg))
      return true
    } catch {
      return false
    }
  }

  function connect(): void {
    clearTimeout(retryTimer)
    retryTimer = 0
    if (!running || ws) return
    link = attempt || everOpen ? 'reconnecting' : 'connecting'
    refusal = null
    let sock: WebSocket
    try {
      // the URL carries the demo key: it is never logged or kept
      sock = new WebSocket(socketUrl(apiBase, options.jobId, { role, key: options.key, source: options.viewpoint, device: options.device?.slice(0, 40) }))
    } catch {
      return retryLater()
    }
    sock.binaryType = 'arraybuffer'
    ws = sock
    clearTimeout(openTimer)
    openTimer = window.setTimeout(() => {
      if (ws === sock && !hello) sock.close() // no answer: give up on this one and try again
    }, CONNECT_TIMEOUT_MS)
    sock.onmessage = (e) => {
      if (ws !== sock || typeof e.data !== 'string') return
      let message: ServerMessage
      try {
        message = JSON.parse(e.data)
      } catch {
        return
      }
      if (message && typeof message === 'object') onMessage(message)
    }
    sock.onclose = () => {
      if (ws === sock) onClosed()
    }
    changed()
  }

  function onClosed(): void {
    ws = null
    hello = server = null
    clearTimeout(openTimer)
    loop.forget()
    if (!running) link = 'idle'
    else if (refusal) link = 'refused'
    else retryLater()
    changed()
  }

  function retryLater(): void {
    attempt++
    const wait = Math.min(RETRY_MAX_MS, RETRY_FIRST_MS * 2 ** (attempt - 1)) * (0.8 + 0.4 * Math.random())
    link = 'reconnecting'
    clearTimeout(retryTimer)
    retryTimer = window.setTimeout(connect, wait)
    changed()
  }

  /** A reason to try at once: the tab is back, or the network is. */
  function retryNow(): void {
    if (!running || ws || link === 'refused') return
    attempt = Math.min(attempt, 1)
    connect()
  }

  function onMessage(message: ServerMessage): void {
    if (message.type === 'hello') sim = message.vision === 'simulated' || message.sim === true
    else if (message.sim === true) sim = true
    switch (message.type) {
      case 'hello':
        onHello(message)
        break
      case 'status':
        server = message
        if (!sim && message.vision === 'simulated') sim = true
        loop.kick()
        changed()
        break
      case 'ack':
        if (message.dropped === 'no_questions' && blindStep !== stepNow()) {
          blindStep = stepNow() // no frame of this step is looked at: say so and stop sending them
          changed()
        }
        loop.onAck(message)
        break
      case 'event':
        onEvent(message)
        break
      case 'error':
        if (REFUSALS.includes(message.code)) refusal = message
        fire('error', { code: message.code, message: message.message })
        changed()
        break
    }
    onDebugMessage?.(message)
  }

  function onHello(message: HelloMessage): void {
    hello = message
    blindStep = null
    limits = readLimits(message.limits)
    attempt = 0
    everOpen = true
    link = 'open'
    clearTimeout(openTimer)
    if (message.session !== session) {
      session = message.session // a new server session numbers its events from 1 again
      lastEventId = 0
    }
    if (wanted) {
      // The first time, and whenever the server has forgotten the step (it restarted): `start`.
      // After a short drop the session is still there, with its cooldowns and mutes: only mend a difference.
      if (!started || message.step === null) tellStart()
      else syncStep(message.step, message.action)
    }
    if (sim && !simSaid) {
      simSaid = true // said once, so that nobody takes scripted answers for seen ones
      if (speaker && !muted) speaker.speak({ text: WORDS[lang].simulated, lang, kind: 'info', step: null })
    }
    changed()
  }

  function tellStart(): void {
    if (!wanted) return
    started = sendJson({ type: 'start', step: wanted.step, ...(wanted.action !== null && { action: wanted.action }) })
  }

  /** Bring the server from the step and action it has to the ones the page is on. */
  function syncStep(serverStep: number | null, serverAction: number | null): void {
    if (!wanted) return
    const sameStep = serverStep === wanted.step
    if (sameStep && serverAction === wanted.action) return
    if (sameStep && wanted.action !== null) sendJson({ type: 'action', index: wanted.action, step: wanted.step })
    else sendJson({ type: 'step', n: wanted.step, ...(wanted.action !== null && { action: wanted.action }) })
  }

  // ---------------------------------------------------------------- guidance events

  function onEvent(event: GuidanceEvent): void {
    if (typeof event.id === 'number') {
      if (event.id <= lastEventId) return // never the same event twice
      lastEventId = event.id
    }
    events.push(event)
    if (events.length > KEEP_EVENTS) events.shift()
    const text = event.say?.[lang] || event.say?.en || ''
    // `vision_paused` has no sentence of its own on the line: the state it announces is shown for as long as it lasts
    if (text && event.event !== 'vision_paused') show(text, event)
    if (event.speak === true && text && speaker && !muted) speaker.speak({ text, lang, kind: kindOf(event), step: event.step, event })
    fire('event', event)
    changed()
  }

  function show(text: string, event: GuidanceEvent): void {
    shown = { text, event }
    clearTimeout(shownTimer)
    shownTimer = window.setTimeout(shownTimeUp, Math.min(10_000, Math.max(4000, 2500 + 70 * text.length)))
  }

  function clearShown(): void {
    clearTimeout(shownTimer)
    if (!shown) return
    shown = null
    changed()
  }

  /** The sentence has had its time on the line. */
  function shownTimeUp(): void {
    // The server gives a warning once and not again while the same thing stays in the hand. So while it
    // still believes the hand holds the wrong thing, the words that explain the ring stay with it.
    const e = shown?.event
    if (e && (e.event === 'wrong_part' || e.event === 'wrong_hardware') && server?.verdict === 'wrong' && server.step === e.step) {
      shownTimer = window.setTimeout(shownTimeUp, 1000)
      return
    }
    clearShown()
  }

  // ---------------------------------------------------------------- the page

  const onVisibility = (): void => {
    if (visible()) {
      retryNow() // phones close the sockets of a tab in the background
      camera.resume()
      loop.kick()
    }
    changed()
  }
  document.addEventListener('visibilitychange', onVisibility)
  window.addEventListener('online', retryNow)

  function ensurePresence(): Presence {
    if (!presence) {
      presence = createPresence()
      presence.setStream(camera.stream)
      presence.update(snapshot(), lang)
    }
    return presence
  }

  function setStep(step: number, action: number | null = null): void {
    if (destroyed || !Number.isInteger(step)) return
    const before = wanted
    if (before && before.step === step && before.action === action) return
    wanted = { step, action }
    if (running && hello) {
      if (!started) tellStart()
      else syncStep(before ? before.step : null, before ? before.action : null)
    }
    loop.kick()
    changed()
  }

  function stop(): void {
    if (!running) return
    running = false
    if (started && role === 'display') sendJson({ type: 'stop' }) // a camera that leaves does not end the guidance
    started = false
    loop.stop()
    camera.close()
    speaker?.stop()
    clearTimeout(retryTimer)
    clearTimeout(openTimer)
    retryTimer = 0
    const sock = ws
    ws = null
    hello = server = null
    sock?.close(1000)
    link = 'idle'
    attempt = 0
    clearShown()
    changed()
  }

  return {
    async start(step, action = null) {
      if (destroyed) return
      if (running) {
        if (step != null) setStep(step, action)
        return
      }
      if (!options.jobId) return fire('error', { code: 'no_job', message: 'createAssist needs the id of a job built by the server' })
      if (step != null) wanted = { step, action }
      running = true
      started = everOpen = simSaid = false
      attempt = 0
      speaker?.unlock?.() // still inside the caller's tap
      connect() // steps and actions work without a camera, so the socket does not wait for it
      if (cameraMode !== 'none') await camera.open(cameraMode === 'open' ? null : cameraMode)
      if (!running) return void camera.close() // stopped while the permission question was up
      loop.start()
      changed()
    },
    setStep,
    sendHands(h) {
      if (!hello || !ws || ws.readyState !== WebSocket.OPEN) return
      const now = performance.now()
      const present = h.present !== false
      const key = `${+!!h.hand_at_target}${+!!h.still}${+!!h.raised}${+present}`
      const since = now - lastHands.at
      if (since < HANDS_ANY_MS || (key === lastHands.key && since < HANDS_SAME_MS)) return
      lastHands = { at: now, key }
      sendJson({ type: 'hands', seq: h.seq, hand_at_target: !!h.hand_at_target, still: !!h.still, raised: !!h.raised, ts: h.ts, present })
    },
    showPart(expected) {
      sendJson({ type: 'show_part', ...(expected && { expected }) })
      loop.kick()
    },
    iKnow(event) {
      sendJson({ type: 'mute', ...(event && { event }) })
    },
    stop,
    destroy() {
      stop()
      destroyed = true
      clearTimeout(countersTimer)
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('online', retryNow)
      presence?.destroy()
      presence = null
      for (const set of Object.values(handlers)) set.clear()
    },
    setMuted(value) {
      if (muted === value) return
      muted = value
      if (muted) speaker?.stop()
      changed()
    },
    setLang(value) {
      if (lang === value) return
      lang = value
      if (shown) shown.text = shown.event.say?.[lang] || shown.event.say?.en || shown.text
      changed()
    },
    on(type, handler) {
      handlers[type].add(handler)
      return () => void handlers[type].delete(handler)
    },
    get status() {
      return snapshot()
    },
    get events() {
      return events.slice()
    },
    get muted() {
      return muted
    },
    get lang() {
      return lang
    },
    get preview() {
      return ensurePresence().preview
    },
    get line() {
      return ensurePresence().line
    },
    debug: {
      send: sendJson,
      drop: () => ws?.close(4999),
      get onMessage() {
        return onDebugMessage
      },
      set onMessage(fn) {
        onDebugMessage = fn
      },
    },
  }
}
