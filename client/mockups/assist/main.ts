// Test bench for client/src/assist: mounts the module's two elements where a page would, and adds the few
// controls needed to drive the server's simulated mode. Everything comes from the address (see index.html).
// `window.__assist` gives an automated run the module, what the speaker was handed and the raw messages.
import { createAssist, createBrowserSpeaker, withoutCodes, type AssistStatus, type Lang, type ServerMessage, type Speaker, type Utterance } from '../../src/assist/index'

const query = new URLSearchParams(location.search)
const api = query.get('api') ?? ''
const job = query.get('job') ?? ''
const key = query.get('key')
let lang: Lang = query.get('lang') === 'en' ? 'en' : 'el'
const viewpoint = query.get('view') === 'headset' ? 'headset' : 'fixed'
const role = query.get('role') === 'camera' ? 'camera' : 'display'
// `given`: the page opens the camera itself and hands the stream over, as the headset mode does
const held: MediaStream | null = query.get('camera') === 'given' ? await navigator.mediaDevices.getUserMedia({ video: true }) : null
const cameraMode = held ?? (query.get('camera') === 'none' ? 'none' : 'open')
let step = Number(query.get('step')) || 2
let action: number | null = query.get('action') === 'none' ? null : Number(query.get('action') ?? 0) || 0

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T
document.documentElement.lang = lang

// ------------------------------------------------------------------ a speaker that keeps count

interface Spoken {
  t: number
  text: string
  lang: Lang
  kind: string
  event: string | null
  eventId: number | null
}
const spoken: Spoken[] = []
const speakCodes = query.get('codes') !== '0' // ?codes=0: hardware codes are shown but not read aloud
const ownSpeaker = query.get('speaker') === 'default' // ?speaker=default: the module's own speaker, nothing counted here
const voice = createBrowserSpeaker({ prepare: speakCodes ? undefined : withoutCodes })
const recording: Speaker = {
  speak(u: Utterance) {
    spoken.push({ t: Math.round(performance.now()), text: u.text, lang: u.lang, kind: u.kind, event: u.event?.event ?? null, eventId: u.event?.id ?? null })
    voice.speak(u)
    render()
  },
  stop: () => voice.stop(),
  unlock: () => voice.unlock(),
  can: (l) => voice.can(l),
}

// ------------------------------------------------------------------ the module

const raw: ServerMessage[] = []
const statuses: AssistStatus[] = []
const assist = createAssist({
  jobId: job,
  key,
  apiBase: api,
  lang,
  viewpoint,
  role,
  camera: cameraMode,
  device: 'assist-bench',
  speaker: role === 'camera' ? null : ownSpeaker ? undefined : recording,
  speakCodes,
  onStatus(status) {
    statuses.push(status)
    if (statuses.length > 400) statuses.shift()
    render(status)
  },
  onEvent(event) {
    log(`#${event.id} ${event.event}${event.sim ? ' (simulated)' : ''}`, event.say?.[lang] ?? '')
  },
  onError(error) {
    log(`error ${error.code}`, error.message)
  },
})
assist.debug.onMessage = (message) => {
  if (message.type === 'ack' || message.type === 'status') return // too many to keep
  raw.push(message)
  if (raw.length > 200) raw.shift()
  if (message.type === 'sim') log('sim', JSON.stringify(message.script))
}
$('presence').append(assist.preview, assist.line)

declare global {
  interface Window {
    __assist: { assist: typeof assist; spoken: Spoken[]; statuses: AssistStatus[]; raw: ServerMessage[]; voice: typeof voice; held: MediaStream | null }
  }
}
window.__assist = { assist, spoken, statuses, raw, voice, held }

// ------------------------------------------------------------------ the bench

const logEl = $<HTMLOListElement>('log')
function log(head: string, body = ''): void {
  const li = document.createElement('li')
  const b = document.createElement('b')
  b.textContent = head
  li.append(b, body ? ` ${body}` : '')
  logEl.prepend(li)
  while (logEl.children.length > 8) logEl.lastElementChild?.remove()
}

const readEl = $<HTMLDListElement>('read')
function render(status: AssistStatus = assist.status): void {
  const f = status.frames
  const drops = Object.entries(f.dropped)
    .map(([why, n]) => `${why} ${n}`)
    .join(', ')
  const rows: [string, string][] = [
    ['link', status.link],
    ['vision', `${status.vision}${status.why ? ` (${status.why})` : ''}${status.simulated ? ' · SIMULATED' : ''}`],
    ['camera', `${status.camera}${status.capturing ? ' · sending' : ''}`],
    ['step · action', `${status.step ?? '–'} · ${status.action ?? '–'}${status.page ? ` · p. ${status.page}` : ''}`],
    ['state', status.state],
    ['expects', status.expected?.name ?? '–'],
    ['holding', status.holding.kind === 'unknown' ? '–' : `${status.holding.name ?? status.holding.kind} ${status.holding.confidence ? status.holding.confidence.toFixed(2) : ''}`],
    ['verdict · view', `${status.verdict} · ${status.view}`],
    ['asked rate', `${status.rateHz} /s, cap ${status.limits.maxHz}`],
    ['sent · acked', `${f.sent} · ${f.acked + f.lateAcks}${f.expired ? ` · ${f.expired} timed out` : ''}`],
    ['un-acked', `${f.unacked} now, ${f.unackedPeak} peak, limit ${status.limits.maxUnacked}`],
    ['last second', `${f.hz} sent`],
    ['used · dropped', `${f.used} · ${drops || '0'}`],
    ['ack · grab', `${f.ackMs ?? '–'} ms · ${f.grabMs ?? '–'} ms, ${Math.round(f.bytes / 1024)} kB`],
    ['speech', `${status.speech}, ${spoken.length} handed over${voice.voiceName(lang) ? `, ${voice.voiceName(lang)}` : ''}`],
  ]
  readEl.replaceChildren(
    ...rows.flatMap(([k, v]) => {
      const dt = document.createElement('dt')
      dt.textContent = k
      const dd = document.createElement('dd')
      dd.textContent = v
      return [dt, dd]
    }),
  )
  $('o-step').textContent = String(step)
  $('o-action').textContent = action === null ? '–' : String(action)
  const mute = document.querySelector<HTMLButtonElement>('[data-do="mute"]')
  mute?.setAttribute('aria-pressed', String(assist.muted))
  const langBtn = document.querySelector<HTMLButtonElement>('[data-do="lang"]')
  if (langBtn) langBtn.textContent = lang.toUpperCase()
}

const actions: Record<string, () => void> = {
  start: () => void assist.start(role === 'camera' ? null : step, action),
  stop: () => assist.stop(),
  mute: () => assist.setMuted(!assist.muted),
  lang: () => {
    lang = lang === 'el' ? 'en' : 'el'
    document.documentElement.lang = lang
    assist.setLang(lang)
  },
  drop: () => assist.debug.drop(),
  'step-': () => assist.setStep((step = Math.max(1, step - 1)), (action = 0)),
  'step+': () => assist.setStep(++step, (action = 0)),
  'action-': () => assist.setStep(step, (action = Math.max(0, (action ?? 0) - 1))),
  'action+': () => assist.setStep(step, (action = (action ?? -1) + 1)),
  show: () => assist.showPart(),
  know: () => assist.iKnow(),
}

$('bench').addEventListener('click', (e) => {
  const button = (e.target as HTMLElement).closest('button')
  if (!button) return
  const sim = button.dataset.sim
  if (sim) {
    if (!assist.debug.send({ type: 'sim', ...JSON.parse(sim) })) log('sim', 'not connected')
  } else actions[button.dataset.do ?? '']?.()
  render()
})

if (!job) log('address', 'add ?api=http://127.0.0.1:PORT&job=…&key=… and press Start')
render()
setInterval(render, 500) // the counters between two status messages
