import { apiFetch } from '../api'

// The coach that sees: one Gemini Live session from the browser. It is sent the camera's picture once a second
// and the microphone, and it answers in speech, starting within about a second.
//
//   token    POST /api/live/token {job_id, mode: "see", lang}: single use; the model, the prompt
//            (prompts/live_coach.md, with the guide's steps) and the tools are locked inside it
//   socket   wss://generativelanguage.googleapis.com/ws/…{api_version}.GenerativeService.BidiGenerateContentConstrained
//   send     {setup}, {realtimeInput: {video | audio | text}}, {toolResponse}
//   receive  {serverContent: {modelTurn: {parts: [{inlineData: PCM 24 kHz}]}, outputTranscription, interrupted,
//            turnComplete}}, {toolCall}
//
// `[LOOK]` is sent every few seconds while nobody speaks. The model answers the single word "ok" when nothing
// changed; such a turn is neither played nor shown.

const FRAME_MS = 1000
const FRAME_EDGE = 768
const LOOK_MS = 6000
const MIC_RATE = 16000

export interface CoachOptions {
  job: string
  key: string | null
  lang: 'el' | 'en'
  video: HTMLVideoElement
  /** made inside the tap that starts the page, so that iOS lets it play */
  audio: AudioContext
  /** the microphone, when the person allowed it */
  mic: MediaStream | null
  /** the coach's words as they are spoken; `done` at the end of the sentence */
  onWords(text: string, done: boolean): void
  /** a tool the model called; the returned object is its answer */
  onTool(name: string, args: Record<string, unknown>): Record<string, unknown>
  onState(state: 'connecting' | 'on' | 'off', why?: string): void
}

export interface Coach {
  /** a line for the model: `[STEP…]`, `[CHECK…]`, or a question in the person's place */
  tell(text: string): void
  setMic(on: boolean): void
  readonly on: boolean
  stop(): void
}

const b64 = (bytes: Uint8Array): string => {
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(s)
}

export async function startCoach(o: CoachOptions): Promise<Coach | null> {
  o.onState('connecting')
  let tok: { token: string; model: string; api_version: string }
  try {
    const res = await apiFetch('/api/live/token', o.key, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ job_id: o.job, mode: 'see', lang: o.lang }),
    })
    if (!res.ok) throw new Error(`token ${res.status}`)
    tok = await res.json()
  } catch (e) {
    o.onState('off', String(e))
    return null
  }
  const url = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.${tok.api_version}.GenerativeService.BidiGenerateContentConstrained?access_token=${encodeURIComponent(tok.token)}`
  const ws = new WebSocket(url)
  let open = false
  let closed = false
  let micOn = !!o.mic
  const timers: number[] = []

  // ---- speech out: PCM 24 kHz chunks, played one after another
  let playAt = 0
  let playing: AudioBufferSourceNode[] = []
  let lastSound = 0
  const play = (pcm: Int16Array) => {
    const buf = o.audio.createBuffer(1, pcm.length, 24000)
    const ch = buf.getChannelData(0)
    for (let i = 0; i < pcm.length; i++) ch[i] = pcm[i] / 32768
    const src = o.audio.createBufferSource()
    src.buffer = buf
    src.connect(o.audio.destination)
    playAt = Math.max(playAt, o.audio.currentTime + 0.03)
    src.start(playAt)
    playAt += buf.duration
    lastSound = performance.now() + (playAt - o.audio.currentTime) * 1000
    playing.push(src)
    src.onended = () => (playing = playing.filter((x) => x !== src))
  }
  const hush = () => {
    for (const s of playing) try { s.stop() } catch { /* already ended */ }
    playing = []
    playAt = 0
  }
  const speaking = () => performance.now() < lastSound + 400

  // ---- every turn is held back until its first words show it is something to say: not "ok", not a stray
  // note of the model to itself. That costs a fraction of a second and keeps noise out of the person's ear.
  let looking = true
  /** when the last line was sent that has not been answered yet (0: none): no `[LOOK]` on top of an open turn */
  let asked = 0
  let held: Int16Array[] = []
  let words = ''
  const meaningful = (t: string) => {
    const s = t.toLowerCase().replace(/[^a-zα-ωάέήίόύώ ]/g, '').trim()
    return s.length >= 4 && !/^(ok|okay|οκ|εντάξει)$/.test(s) && !/^\s*[<\[(]/.test(t) && !/no response/i.test(t)
  }

  const send = (msg: unknown) => {
    if (open && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg))
  }

  ws.onopen = () => ws.send(JSON.stringify({ setup: { model: `models/${tok.model}` } }))
  ws.onmessage = async (ev) => {
    const raw = typeof ev.data === 'string' ? ev.data : await (ev.data as Blob).text()
    let m: Record<string, any>
    try { m = JSON.parse(raw) } catch { return }
    if (m.setupComplete) {
      open = true
      o.onState('on')
      begin()
      return
    }
    for (const call of m.toolCall?.functionCalls ?? []) {
      const response = o.onTool(call.name, call.args ?? {})
      send({ toolResponse: { functionResponses: [{ id: call.id, name: call.name, response }] } })
    }
    const sc = m.serverContent
    if (!sc) return
    if (sc.interrupted) { hush(); held = []; looking = true; words = '' }
    for (const part of sc.modelTurn?.parts ?? []) {
      const data = part.inlineData?.data
      if (!data) continue
      const bin = atob(data)
      const bytes = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
      const pcm = new Int16Array(bytes.buffer, 0, bytes.length >> 1)
      if (looking) held.push(pcm)
      else play(pcm)
    }
    const said = sc.outputTranscription?.text
    if (said) {
      words += said
      if (looking && meaningful(words)) {
        looking = false
        for (const pcm of held) play(pcm)
        held = []
      }
      if (!looking) o.onWords(words.trim(), false)
    }
    if (sc.turnComplete) {
      if (!looking && words.trim()) o.onWords(words.trim(), true)
      asked = 0
      looking = true
      held = []
      words = ''
    }
  }
  ws.onclose = (ev) => {
    closed = true
    open = false
    for (const t of timers) clearInterval(t)
    o.onState('off', ev.reason || `closed ${ev.code}`)
  }
  ws.onerror = () => o.onState('off', 'socket error')

  // ---- what goes in: the camera once a second, the microphone, and a look now and then
  const canvas = document.createElement('canvas')
  let lastTold = 0
  const frame = () => {
    const v = o.video
    if (!v.videoWidth || document.hidden) return
    const k = Math.min(1, FRAME_EDGE / Math.max(v.videoWidth, v.videoHeight))
    canvas.width = Math.round(v.videoWidth * k)
    canvas.height = Math.round(v.videoHeight * k)
    canvas.getContext('2d')!.drawImage(v, 0, 0, canvas.width, canvas.height)
    const data = canvas.toDataURL('image/jpeg', 0.6).split(',')[1]
    send({ realtimeInput: { video: { mimeType: 'image/jpeg', data } } })
  }
  const look = () => {
    const open = asked && performance.now() - asked < 12000
    if (speaking() || open || performance.now() - lastTold < LOOK_MS - 500 || document.hidden) return
    asked = performance.now()
    looking = true
    held = []
    words = ''
    send({ realtimeInput: { text: '[LOOK]' } })
  }
  let micNode: ScriptProcessorNode | null = null
  const begin = () => {
    frame()
    timers.push(window.setInterval(frame, FRAME_MS), window.setInterval(look, LOOK_MS))
    if (o.mic) {
      const src = o.audio.createMediaStreamSource(o.mic)
      micNode = o.audio.createScriptProcessor(4096, 1, 1)
      const ratio = o.audio.sampleRate / MIC_RATE
      micNode.onaudioprocess = (e) => {
        if (!micOn || !open) return
        const input = e.inputBuffer.getChannelData(0)
        const out = new Int16Array(Math.floor(input.length / ratio))
        for (let i = 0; i < out.length; i++) {
          const x = Math.max(-1, Math.min(1, input[Math.floor(i * ratio)]))
          out[i] = x < 0 ? x * 0x8000 : x * 0x7fff
        }
        send({ realtimeInput: { audio: { mimeType: `audio/pcm;rate=${MIC_RATE}`, data: b64(new Uint8Array(out.buffer)) } } })
      }
      const quiet = o.audio.createGain()
      quiet.gain.value = 0 // the processor must reach the output to run; it adds no sound
      src.connect(micNode)
      micNode.connect(quiet)
      quiet.connect(o.audio.destination)
    }
  }

  return {
    tell(text) {
      lastTold = performance.now()
      asked = lastTold
      looking = true
      held = []
      words = ''
      send({ realtimeInput: { text } })
    },
    setMic(on) {
      micOn = on && !!o.mic
    },
    get on() {
      return open && !closed
    },
    stop() {
      closed = true
      for (const t of timers) clearInterval(t)
      micNode?.disconnect()
      hush()
      try { ws.close() } catch { /* never opened */ }
    },
  }
}
