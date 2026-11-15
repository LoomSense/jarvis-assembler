// When a frame may leave this device. Three rules, all with the server's own numbers (`hello.limits`):
//   - never more than `maxUnacked` frames without an ack; a slot is freed after `ackTimeoutMs` without one
//   - never faster than the rate the server asks for in `status`, and never faster than `maxHz`
//   - nothing at all while the host says it is not ready (tab hidden, socket down, no step, no picture)
// A real decision takes about a second, so acks are expected to arrive late: the un-acked rule is what
// paces the frames then, and the rate rule only matters when the server answers fast.
import type { AckMessage, Limits } from './protocol'

export interface FrameLoopHost {
  /** May a frame go out now? */
  ready(): boolean
  /** Frames per second the server wants now; 0: none. */
  wantedHz(): number
  limits(): Limits
  /** One JPEG of the camera, or null when there is no picture. */
  grab(limits: Limits): Promise<Uint8Array | null>
  /** Put the frame on the socket. False: it did not go out. */
  send(seq: number, jpeg: Uint8Array): boolean
  /** The counters changed. */
  changed?(): void
}

export interface FrameStats {
  sent: number
  /** acks that matched a frame still waiting */
  acked: number
  /** acks for a frame whose slot was already freed */
  lateAcks: number
  /** frames the server used for a decision */
  used: number
  /** frames the server did not use, by its reason */
  dropped: Record<string, number>
  /** slots freed because no ack came in time */
  expired: number
  /** frames waiting for an ack right now */
  unacked: number
  /** the most that ever waited at once */
  unackedPeak: number
  /** frames sent in the last second */
  hz: number
  /** send to ack, last frame, ms */
  ackMs: number | null
  /** canvas grab and JPEG encoding, last frame, ms */
  grabMs: number | null
  /** size of the last JPEG */
  bytes: number
}

const IDLE_POLL_MS = 200
/** After `busy` (every call is in flight and a newer frame of ours took the waiting place) or `backoff`: the
 *  server has no use for another frame right now, so the next one is held this long. */
const BUSY_HOLD_MS = 400

export class FrameLoop {
  private seq = 0
  private pending = new Map<number, number>() // seq -> when it was sent
  private gone = new Set<number>() // seqs whose slot was freed without an ack
  private recent: number[] = [] // send times of the last second
  private lastSendAt = -Infinity
  private holdUntil = 0
  private timer = 0
  private running = false
  private grabbing = false
  private counts = { sent: 0, acked: 0, lateAcks: 0, used: 0, expired: 0, unackedPeak: 0, bytes: 0 }
  private dropped: Record<string, number> = {}
  private ackMs: number | null = null
  private grabMs: number | null = null

  constructor(private readonly host: FrameLoopHost) {}

  start(): void {
    if (this.running) return
    this.running = true
    this.plan(0)
  }

  stop(): void {
    this.running = false
    clearTimeout(this.timer)
    this.timer = 0
    this.pending.clear()
  }

  /** The socket closed: its acks will never come. */
  forget(): void {
    this.pending.clear()
    this.holdUntil = 0
  }

  /** Something changed (an ack, a new rate, the tab came back): look again now. */
  kick(): void {
    if (this.running && !this.grabbing) this.plan(0)
  }

  onAck(ack: AckMessage): void {
    const now = performance.now()
    const sentAt = ack.seq === null ? undefined : this.pending.get(ack.seq)
    if (sentAt !== undefined) {
      this.pending.delete(ack.seq as number)
      this.counts.acked++
      this.ackMs = Math.round(now - sentAt)
    } else if (ack.seq !== null && this.gone.delete(ack.seq)) {
      this.counts.lateAcks++
    }
    if (ack.used) this.counts.used++
    else {
      const why = ack.dropped ?? 'unknown'
      this.dropped[why] = (this.dropped[why] ?? 0) + 1
      if (why === 'busy' || why === 'backoff') this.holdUntil = now + BUSY_HOLD_MS // no upload for nothing
    }
    this.host.changed?.()
    this.kick()
  }

  stats(): FrameStats {
    const now = performance.now()
    while (this.recent.length && now - this.recent[0] > 1000) this.recent.shift()
    return {
      ...this.counts,
      dropped: { ...this.dropped },
      unacked: this.pending.size,
      hz: this.recent.length,
      ackMs: this.ackMs,
      grabMs: this.grabMs,
    }
  }

  private plan(ms: number): void {
    clearTimeout(this.timer)
    this.timer = window.setTimeout(this.tick, Math.max(0, ms))
  }

  private expire(now: number, limits: Limits): void {
    for (const [seq, sentAt] of this.pending) {
      if (now - sentAt < limits.ackTimeoutMs) continue
      this.pending.delete(seq)
      this.gone.add(seq)
      this.counts.expired++
    }
    if (this.gone.size > 64) this.gone = new Set([...this.gone].slice(-32))
  }

  private tick = async (): Promise<void> => {
    this.timer = 0
    if (!this.running || this.grabbing) return
    const limits = this.host.limits()
    let now = performance.now()
    this.expire(now, limits)

    const hz = Math.min(this.host.wantedHz(), limits.maxHz)
    if (!this.host.ready() || !(hz > 0)) return this.plan(IDLE_POLL_MS)

    const due = Math.max(this.lastSendAt + 1000 / hz, this.holdUntil)
    if (now < due) return this.plan(Math.min(due - now, 1000))

    if (this.pending.size >= limits.maxUnacked) {
      // every slot is taken: an ack kicks the loop, or the oldest frame times out
      const oldest = Math.min(...this.pending.values())
      return this.plan(Math.min(Math.max(oldest + limits.ackTimeoutMs - now, 10), 250))
    }

    this.grabbing = true
    let jpeg: Uint8Array | null = null
    try {
      jpeg = await this.host.grab(limits)
    } catch {
      jpeg = null
    }
    this.grabbing = false
    if (!this.running) return
    const grabbed = performance.now()
    this.grabMs = Math.round(grabbed - now)
    now = grabbed

    // the world may have changed while the JPEG was being made
    if (jpeg && jpeg.length <= limits.maxFrameBytes && this.host.ready() && this.pending.size < limits.maxUnacked) {
      const seq = ++this.seq
      if (this.host.send(seq, jpeg)) {
        this.pending.set(seq, now)
        this.lastSendAt = now
        this.recent.push(now)
        this.counts.sent++
        this.counts.bytes = jpeg.length
        this.counts.unackedPeak = Math.max(this.counts.unackedPeak, this.pending.size)
        this.host.changed?.()
      }
    }
    // The next frame is timed from this send, so two sends are never closer than the rate allows.
    this.plan(jpeg ? 1000 / hz : 100)
  }
}
