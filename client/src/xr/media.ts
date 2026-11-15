// Microphone and passthrough camera, asked for on the 2D page before the session: a permission prompt that
// appears inside an immersive session can end it. Both are optional. The streams are kept open and handed to
// whoever wires the live-vision agent (getCameraStream) and the voice agent (getMicStream).
//
// Camera on Quest (reports from real headsets, 2025-2026): getUserMedia lists `camera 0, facing front` (the
// inward avatar camera) and `camera 1/2, facing back` (the left and right passthrough cameras). The back
// cameras need the site permission "Headset cameras"; the prompt can come with only "Virtual cameras" ticked,
// and then `{ideal: 'environment'}` silently returns the avatar camera. So: ask for exactly the environment
// camera first, choose by the device label, and never keep a stream that is not a back camera on a headset.

export type MicState = 'idle' | 'on' | 'denied' | 'missing' | 'unavailable'
export type CameraState = 'idle' | 'headset' | 'generic' | 'inward-only' | 'denied' | 'missing' | 'unavailable'

export interface MediaStatus {
  asking: boolean
  asked: boolean
  mic: MicState
  micLabel: string
  camera: CameraState
  cameraLabel: string
  /** labels of every camera the page can see, for the pre-flight list */
  cameras: string[]
  /** why the camera could not be opened: the getUserMedia error names, e.g. "NotFoundError/NotReadableError" */
  cameraError: string
  /** how many video inputs enumerateDevices lists (-1: not asked); 0 means the browser exposes no camera at all */
  videoInputs: number
}

const BACK = /facing back/i

const errorName = (err: unknown) => (err && typeof err === 'object' && 'name' in err ? String((err as { name: unknown }).name) : '')
const denied = (err: unknown) => ['NotAllowedError', 'SecurityError', 'PermissionDeniedError'].includes(errorName(err))
const stop = (stream: MediaStream | null) => stream?.getTracks().forEach((t) => t.stop())

export class Media {
  micStream: MediaStream | null = null
  cameraStream: MediaStream | null = null
  status: MediaStatus = { asking: false, asked: false, mic: 'idle', micLabel: '', camera: 'idle', cameraLabel: '', cameras: [], cameraError: '', videoInputs: -1 }

  /**
   * @param headset true on a Quest: only a `facing back` camera is acceptable there
   * @param anyCamera development: keep whatever camera the device has
   */
  constructor(private headset: boolean, private anyCamera: boolean, private onChange: () => void) {}

  private set(patch: Partial<MediaStatus>): void {
    this.status = { ...this.status, ...patch }
    this.onChange()
  }

  /** Ask for the microphone (unless `mic: false`, the scan needs only the camera), then the camera. Call from a tap. Never throws. */
  async request(opts: { mic?: boolean } = {}): Promise<void> {
    if (this.status.asking) return
    const md = navigator.mediaDevices
    if (!md?.getUserMedia) {
      this.set({ asked: true, mic: 'unavailable', camera: 'unavailable' })
      return
    }
    this.set({ asking: true })

    if (!this.micStream && opts.mic !== false) {
      try {
        this.micStream = await md.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } })
        this.set({ mic: 'on', micLabel: this.micStream.getAudioTracks()[0]?.label ?? '' })
      } catch (err) {
        this.set({ mic: denied(err) ? 'denied' : 'missing' })
      }
    }

    if (!this.cameraStream) await this.openCamera(md)
    this.set({ asking: false, asked: true })
  }

  private async openCamera(md: MediaDevices): Promise<void> {
    const size = { width: { ideal: 1280 }, height: { ideal: 960 } } // the passthrough cameras' best 4:3 mode
    let stream: MediaStream | null = null
    let refused = false
    const errors: string[] = []
    try {
      stream = await md.getUserMedia({ video: { facingMode: { exact: 'environment' }, ...size } })
    } catch (err) {
      refused = denied(err)
      errors.push(errorName(err) || 'Error')
    }
    if (!stream && !refused) {
      // No environment camera was offered. Open any camera once: device labels are empty until a camera
      // permission exists, and the labels tell which case this is.
      try {
        stream = await md.getUserMedia({ video: size })
      } catch (err) {
        refused = denied(err)
        errors.push(errorName(err) || 'Error')
      }
    }
    if (!stream) {
      let videoInputs = -1
      try {
        videoInputs = (await md.enumerateDevices()).filter((d) => d.kind === 'videoinput').length
      } catch {
        // unknown
      }
      console.warn('[media] no camera:', errors.join('/'), 'video inputs:', videoInputs)
      this.set({ camera: refused ? 'denied' : 'missing', cameraError: errors.join('/'), videoInputs })
      return
    }

    let labels: MediaDeviceInfo[] = []
    try {
      labels = (await md.enumerateDevices()).filter((d) => d.kind === 'videoinput')
    } catch {
      // keep going with the label of the track we have
    }
    const cameras = labels.map((d) => d.label).filter(Boolean)
    let label = stream.getVideoTracks()[0]?.label ?? ''
    const back = labels.filter((d) => BACK.test(d.label))

    if (!BACK.test(label) && back.length) {
      // a back camera exists but we were given another one: open it by its id
      stop(stream)
      try {
        stream = await md.getUserMedia({ video: { deviceId: { exact: back[0].deviceId }, ...size } })
        label = stream.getVideoTracks()[0]?.label ?? back[0].label
      } catch {
        stream = null
      }
    }

    if (stream && BACK.test(label)) {
      this.cameraStream = stream
      this.set({ camera: 'headset', cameraLabel: label, cameras })
    } else if (this.headset && !this.anyCamera) {
      // On a headset anything else is the inward avatar camera: useless for watching the work.
      stop(stream)
      this.set({ camera: 'inward-only', cameraLabel: label, cameras })
    } else if (stream) {
      // a phone or a laptop (or development with ?cam=any): whatever camera the device has
      this.cameraStream = stream
      this.set({ camera: 'generic', cameraLabel: label || 'camera', cameras })
    } else {
      stop(stream)
      this.set({ camera: 'missing', cameras })
    }
  }
}
