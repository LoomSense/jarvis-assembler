// The perception socket as the server built it (server/perception/README.md): what crosses the wire,
// the binary frame, and the limits the server announces in `hello`.

export type Lang = 'el' | 'en'
/** Where the frames are taken from: a camera on a stand, or one that moves with the person. */
export type Viewpoint = 'fixed' | 'headset'
export type Role = 'display' | 'camera'
export type ServerVision = 'live' | 'simulated' | 'paused' | 'unavailable'

export interface Say {
  en: string
  el: string
}

// ---------------------------------------------------------------- server to client

export interface HelloMessage {
  type: 'hello'
  protocol: number
  job_id: string
  session: string
  role: Role
  device: string
  source: Viewpoint
  vision: 'live' | 'simulated' | 'unavailable'
  reason: string | null
  title?: string
  steps: number[]
  step: number | null
  action: number | null
  devices: Record<string, number>
  limits?: Record<string, unknown>
  sim?: boolean
}

export interface Held {
  kind: 'part' | 'hardware' | 'none' | 'unknown'
  id: string | null
  /** display string, for example "long screw 108363" */
  name: string | null
  confidence: number
}

export interface Expected {
  kind: 'part' | 'hardware' | null
  id: string | null
  name: string | null
  action_type: 'place' | 'fasten' | 'turn' | null
  actions: number
  text: Partial<Say> | null
}

export type Verdict = 'right' | 'wrong' | 'none' | 'unknown'
export type View = 'ok' | 'blocked' | 'poor' | 'dark' | 'moved' | 'none'
export type StepState = 'IDLE' | 'WAITING_FOR_PART' | 'PART_IN_HAND' | 'PLACING' | 'STEP_COMPLETE'

export interface StatusMessage {
  type: 'status'
  ts: number
  step: number | null
  action: number | null
  page: number | null
  state: StepState
  expected: Expected | null
  holding: Held
  verdict: Verdict
  hands_present: boolean | null
  view: View
  vision: ServerVision
  reason: string | null
  source: Viewpoint | null
  /** frames per second the server wants now; 0 while the hands move, or when it looks at nothing */
  rate_hz: number
  hands: { present: boolean; at_target: boolean; still: boolean; raised: boolean; hz: number } | null
  muted: string[]
  show_part: boolean
  devices: Record<string, number>
  voice_state: string | null
  frames: { used: number; dropped: number; decision_ms_p50: number | null }
  sim?: boolean
}

export type EventName =
  | 'wrong_part'
  | 'flip_needed'
  | 'wrong_hardware'
  | 'out_of_order'
  | 'step_complete'
  | 'unsure_show_part'
  | 'vision_paused'
  | 'right_part'
  | 'right_hardware'
  | 'more_light'
  | 'realign_camera'
  | 'vision_resumed'
  | 'show_part_result'
  | (string & {})

export interface GuidanceEvent {
  type: 'event'
  event: EventName
  /** running number within one server session */
  id: number
  step: number | null
  action: number | null
  page: number | null
  /** display strings; the ids are in `expected_id` and `observed_id` */
  expected: string | null
  observed: string | null
  expected_id: string | null
  observed_id: string | null
  evidence: Record<string, number>
  p: number | null
  /** the ready sentence in both languages */
  say: Say
  /** the line for a voice model (PLAN §6.4) */
  perception: string
  /** false: the event only changes what the screen shows */
  speak: boolean
  turn_complete: boolean
  /** times this event was already given on this step */
  repeat: number
  seq: number | null
  latency_ms: number | null
  ts: number
  reason?: string
  message?: string | null
  match?: boolean | null
  sim?: boolean
}

export interface AckMessage {
  type: 'ack'
  /** null when the server could not read the frame header */
  seq: number | null
  latency_ms: number
  used: boolean
  dropped: string | null
  decision_ms: number | null
  sim?: boolean
}

export interface ErrorMessage {
  type: 'error'
  code: string
  message: string
  sim?: boolean
}

export interface SimMessage {
  type: 'sim'
  script: Record<string, unknown>
  sim?: boolean
}

export type ServerMessage = HelloMessage | StatusMessage | GuidanceEvent | AckMessage | ErrorMessage | SimMessage

// ---------------------------------------------------------------- client to server

/** The headset's hand summary (PLAN §6.0). Extra fields are ignored. */
export interface HandSummary {
  seq: number
  hand_at_target: boolean
  still: boolean
  raised: boolean
  ts: number
  /** false when no hand is tracked */
  present?: boolean
}

// ---------------------------------------------------------------- limits

/** What the server allows. Read from `hello.limits`; the numbers here are only used when it sends none. */
export interface Limits {
  maxUnacked: number
  maxHz: number
  ackTimeoutMs: number
  longEdgePx: number
  jpegQuality: number
  maxFrameBytes: number
}

export const FALLBACK_LIMITS: Limits = {
  maxUnacked: 2,
  maxHz: 4,
  ackTimeoutMs: 2000,
  longEdgePx: 768,
  jpegQuality: 0.7,
  maxFrameBytes: 1_500_000,
}

function within(value: unknown, low: number, high: number, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= low && value <= high ? value : fallback
}

/** Each limit from the server when it is a usable number, otherwise the fallback. */
export function readLimits(raw: Record<string, unknown> | null | undefined): Limits {
  const r = raw ?? {}
  const f = FALLBACK_LIMITS
  return {
    maxUnacked: Math.floor(within(r.max_unacked, 1, 16, f.maxUnacked)),
    maxHz: within(r.max_hz, 0.1, 15, f.maxHz),
    ackTimeoutMs: within(r.ack_timeout_ms, 250, 30_000, f.ackTimeoutMs),
    longEdgePx: Math.round(within(r.long_edge_px, 160, 2048, f.longEdgePx)),
    jpegQuality: within(r.jpeg_quality, 0.3, 0.95, f.jpegQuality),
    maxFrameBytes: within(r.max_frame_bytes, 10_000, 20_000_000, f.maxFrameBytes),
  }
}

// ---------------------------------------------------------------- the binary frame

export interface FrameHeader {
  seq: number
  step?: number
  /** capture time, ms since the epoch */
  ts: number
  hint?: 'low' | 'medium' | 'high'
  source?: Viewpoint
}

/** `[uint32 header_len, big-endian][JSON header, UTF-8][JPEG bytes]` */
export function packFrame(header: FrameHeader, jpeg: Uint8Array): Uint8Array<ArrayBuffer> {
  const head = new TextEncoder().encode(JSON.stringify(header))
  const buf = new Uint8Array(4 + head.length + jpeg.length)
  new DataView(buf.buffer).setUint32(0, head.length) // big-endian is the DataView default
  buf.set(head, 4)
  buf.set(jpeg, 4 + head.length)
  return buf
}

/** `ws(s)://HOST/ws/perception/{job}?...`. `base` is the API base: empty for the page's own origin. */
export function socketUrl(base: string, jobId: string, query: Record<string, string | null | undefined>): string {
  const url = new URL(`${base.replace(/\/+$/, '')}/ws/perception/${encodeURIComponent(jobId)}`, location.href)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  for (const [name, value] of Object.entries(query)) if (value) url.searchParams.set(name, value)
  return url.toString()
}
