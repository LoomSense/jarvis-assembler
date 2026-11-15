// The camera: one the module opens itself (a laptop webcam, a phone), or a stream the caller already holds
// (the headset mode opens the passthrough camera before it enters AR). Frames are grabbed from a hidden
// <video> through a canvas (PLAN §7 "Camera capture": works on iOS, where ImageCapture does not exist).

export type CameraState =
  | 'idle'
  | 'asking'
  | 'on'
  /** the person or the browser said no */
  | 'denied'
  /** no camera on this device, or it could not be opened */
  | 'missing'
  /** no camera API here: an insecure page (plain http on a network address) or an old browser */
  | 'unsupported'
  /** the camera was on and then went away */
  | 'ended'

const DENIED = ['NotAllowedError', 'SecurityError', 'PermissionDeniedError']

function isDenied(err: unknown): boolean {
  return !!err && typeof err === 'object' && 'name' in err && DENIED.includes(String((err as { name: unknown }).name))
}

export class Camera {
  state: CameraState = 'idle'
  stream: MediaStream | null = null
  /** plays the stream off screen; frames are drawn from it */
  readonly video = document.createElement('video')

  private own = false
  private generation = 0
  private readonly holder = document.createElement('div')
  private readonly canvas = document.createElement('canvas')
  private ctx: CanvasRenderingContext2D | null = null
  private track: MediaStreamTrack | null = null

  constructor(private readonly onChange: () => void) {
    const v = this.video
    v.muted = true
    v.autoplay = true
    v.playsInline = true
    v.setAttribute('playsinline', '')
    // Kept in the page and not `display: none`: some browsers stop decoding a video that is not rendered.
    this.holder.setAttribute('aria-hidden', 'true')
    this.holder.style.cssText = 'position:fixed;left:0;top:0;width:2px;height:2px;overflow:hidden;opacity:0;pointer-events:none;z-index:-1'
    v.style.cssText = 'width:2px;height:2px'
    this.holder.append(v)
  }

  private set(state: CameraState): void {
    if (this.state === state) return
    this.state = state
    this.onChange()
  }

  /**
   * Open the camera, or take the one given. Call from a tap when it has to ask for permission.
   * Never throws; the outcome is in `state`.
   */
  async open(given?: MediaStream | null): Promise<boolean> {
    if (this.stream) return true
    const generation = ++this.generation
    let stream: MediaStream | null = given ?? null
    if (!stream) {
      const md = navigator.mediaDevices
      if (!md?.getUserMedia) {
        this.set('unsupported')
        return false
      }
      this.set('asking')
      try {
        // the back camera when there is one (a phone), otherwise whatever the device has (a laptop)
        stream = await md.getUserMedia({ audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } } })
      } catch (err) {
        let refused = isDenied(err)
        if (!refused) {
          try {
            stream = await md.getUserMedia({ audio: false, video: true })
          } catch (again) {
            refused = isDenied(again)
          }
        }
        if (!stream) {
          if (generation === this.generation) this.set(refused ? 'denied' : 'missing')
          return false
        }
      }
      if (generation !== this.generation) {
        stream.getTracks().forEach((t) => t.stop()) // closed while the permission prompt was up
        return false
      }
    }
    const track = stream.getVideoTracks()[0]
    if (!track || track.readyState === 'ended') {
      if (!given) stream.getTracks().forEach((t) => t.stop())
      this.set('missing')
      return false
    }
    this.own = !given
    this.stream = stream
    this.track = track
    track.addEventListener('ended', this.onEnded)
    if (!this.holder.isConnected) document.body.append(this.holder)
    this.video.srcObject = stream
    void this.video.play().catch(() => {
      // autoplay of a muted, inline camera stream is allowed everywhere we know; `ready` stays false if not
    })
    this.set('on')
    return true
  }

  private onEnded = (): void => {
    this.release()
    this.set('ended')
  }

  private release(): void {
    this.generation++
    this.track?.removeEventListener('ended', this.onEnded)
    if (this.own) this.stream?.getTracks().forEach((t) => t.stop()) // a stream that was handed over stays the caller's
    this.stream = this.track = null
    this.video.srcObject = null
    this.holder.remove()
  }

  close(): void {
    this.release()
    this.set('idle')
  }

  /** The tab is back: some browsers pause a video while the page is hidden and do not start it again. */
  resume(): void {
    if (this.stream && this.video.paused) void this.video.play().catch(() => {})
  }

  /** The video has a picture to grab. */
  get ready(): boolean {
    return this.state === 'on' && this.video.readyState >= 2 && this.video.videoWidth > 0
  }

  /** One JPEG of what the camera sees now, `longEdge` pixels on its long side (never enlarged). */
  async grab(longEdge: number, quality: number): Promise<Uint8Array | null> {
    const v = this.video
    if (!this.ready) return null
    const scale = Math.min(1, longEdge / Math.max(v.videoWidth, v.videoHeight))
    const w = Math.max(2, Math.round(v.videoWidth * scale))
    const h = Math.max(2, Math.round(v.videoHeight * scale))
    const c = this.canvas
    if (c.width !== w || c.height !== h) {
      c.width = w
      c.height = h
    }
    this.ctx ??= c.getContext('2d', { alpha: false })
    if (!this.ctx) return null
    this.ctx.drawImage(v, 0, 0, w, h)
    const blob = await new Promise<Blob | null>((done) => c.toBlob(done, 'image/jpeg', quality))
    return blob ? new Uint8Array(await blob.arrayBuffer()) : null
  }
}
