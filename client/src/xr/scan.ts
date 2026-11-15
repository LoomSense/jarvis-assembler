import { apiFetch } from '../api';
import { Camera } from '../assist/camera';
import { t, type Lang, type StringKey } from './i18n';
import type { Media } from './media';

// Scan → identify → (open or build the guide) → load → locate, for the headset, the desktop preview and a phone.
//
//   camera    getUserMedia; on a Quest through media.ts (only a `facing back` passthrough camera is kept, which
//             needs the browser's "Headset cameras" permission); elsewhere the environment camera, else any
//   identify  one JPEG (long edge 768 px, quality 0.7) → POST /api/identify {image, lang}
//   match     → that job; else `query` → POST /api/catalog/open {q} and GET /api/jobs/{id} every 3 s until done
//   load      the caller swaps the job in (xrMain.ts), then
//   locate    a fresh JPEG → POST /api/perception/{job}/locate {image} → the step to continue at
//
// The camera is used only for these still pictures (and live guidance). It does not track the object: WebXR on
// Quest gives no camera intrinsics or pose. Registration is the two-corner snap (register.ts) plus anchors.

const LONG_EDGE = 768;
const QUALITY = 0.7;
const POLL_MS = 3000;

export interface IdentifyResult {
  seen: { kind?: string; name?: string; type?: string; article?: string; text?: string } | null;
  match: { job_id: string; product?: string; ready?: boolean } | null;
  query: string | null;
  confidence: number;
  message: string;
  simulated?: boolean;
}

export interface LocateResult {
  next_step: number | null;
  action?: number | null;
  done_through?: number | null;
  confidence: number;
  steps?: unknown[];
  message: string;
  simulated?: boolean;
}

interface OpenResult {
  job_id: string;
  ready: boolean;
  product?: string;
}

interface JobStatus {
  job_id: string;
  stage?: string | null;
  status?: string | null;
  progress?: number;
  error?: string;
  title?: string | null;
}

export type ScanPhase = 'idle' | 'camera' | 'ready' | 'identifying' | 'retry' | 'opening' | 'building' | 'loading' | 'locating' | 'error';

export interface ScanState {
  phase: ScanPhase;
  /** the server's sentence, or ours */
  title?: string;
  body?: string;
  /** meta line: product, stage, percent */
  meta?: string;
  /** the camera picture is worth showing */
  live: boolean;
  simulated?: boolean;
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

function detailOf(data: unknown): string | null {
  if (!data || typeof data !== 'object') return null;
  const d = (data as { detail?: unknown; message?: unknown }).detail ?? data;
  if (typeof d === 'string') return d;
  if (d && typeof d === 'object' && typeof (d as { message?: unknown }).message === 'string') return (d as { message: string }).message;
  if (typeof (data as { message?: unknown }).message === 'string') return (data as { message: string }).message;
  return null;
}

async function call<T>(path: string, key: string | null, body?: unknown): Promise<T> {
  const init: RequestInit = body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  const r = await apiFetch(path, key, init);
  let data: unknown = null;
  try {
    data = await r.json();
  } catch {
    /* not JSON: an HTML error page or nothing */
  }
  if (!r.ok) throw new HttpError(r.status, detailOf(data) ?? `HTTP ${r.status}`);
  return data as T;
}

/** Base64 of the JPEG bytes, without the `data:` prefix. */
export function toBase64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

const STAGE: Record<string, StringKey> = {
  ingest: 'stageRead',
  extract_plan: 'stageRead',
  await_approval: 'stageWait',
  parts: 'stageParts',
  place: 'stageSteps',
  steps: 'stageSteps',
  refs: 'stageSteps',
  export: 'stageSteps',
};

export interface ScanOptions {
  key: string | null;
  lang: Lang;
  media: Media;
  /** a Quest: only its passthrough camera will do, through media.ts */
  headset: boolean;
  inSession: () => boolean;
  onChange: () => void;
  onSay: (text: string) => void;
  /** swap the job in; rejects when it cannot be loaded */
  loadJob: (jobId: string) => Promise<void>;
  onLocated: (r: LocateResult | null) => void;
}

export class ScanFlow {
  readonly cam: Camera;
  state: ScanState = { phase: 'idle', live: false };
  /** the job found by the last scan, once loaded */
  job: string | null = null;
  /** the product's name as the scan found it */
  product = '';
  private run = 0; // a newer run (or cancel) makes the older one stop at its next await
  private timer = 0;

  constructor(private o: ScanOptions) {
    this.cam = new Camera(() => this.o.onChange());
  }

  private set(s: Partial<ScanState> & { phase: ScanPhase }): void {
    this.state = { live: false, ...s };
    this.o.onChange();
  }

  private say(text: string | undefined): void {
    if (text) this.o.onSay(text);
  }

  get busy(): boolean {
    return !['idle', 'ready', 'retry', 'error'].includes(this.state.phase);
  }

  /** Open the camera and wait for Identify. Call from a tap or a pinch. */
  async start(): Promise<void> {
    const run = ++this.run;
    this.set({ phase: 'camera', title: t('scanCamera', {}, this.o.lang) });
    const why = await this.openCamera();
    if (run !== this.run) return;
    if (why) return this.fail(why);
    this.set({ phase: 'ready', title: t('scanShow', {}, this.o.lang), body: t('scanShowBody', {}, this.o.lang), live: true });
  }

  /** Stop whatever is running; the camera stays open for "Where am I?" and live guidance. */
  cancel(): void {
    this.run++;
    window.clearTimeout(this.timer);
    this.set({ phase: 'idle' });
  }

  /** Null when the camera is on, else the sentence that says why not. */
  private async openCamera(): Promise<string | null> {
    if (this.cam.stream) return null;
    const lang = this.o.lang;
    const m = this.o.media;
    if (m.cameraStream) return (await this.cam.open(m.cameraStream)) ? null : t('camNone', {}, lang);
    if (this.o.headset) {
      // A permission prompt inside an immersive session can end it: ask only when the answer is already yes.
      if (this.o.inSession() && !(await granted())) return t('camExitAR', {}, lang);
      await m.request({ mic: false });
      if (m.cameraStream) return (await this.cam.open(m.cameraStream)) ? null : t('camNone', {}, lang);
      const c = m.status.camera;
      return t(c === 'inward-only' ? 'camInward' : c === 'denied' ? 'camDenied' : 'camNone', {}, lang);
    }
    if (await this.cam.open()) return null;
    return t(this.cam.state === 'denied' ? 'camDenied' : 'camNone', {}, lang);
  }

  /** One frame as base64 JPEG, waiting up to 2 s for the first picture. */
  private async frame(): Promise<string | null> {
    for (let i = 0; i < 20 && !this.cam.ready; i++) await new Promise((r) => setTimeout(r, 100));
    const jpeg = await this.cam.grab(LONG_EDGE, QUALITY);
    return jpeg ? toBase64(jpeg) : null;
  }

  private fail(title: string, body?: string): void {
    this.set({ phase: 'error', title, body, live: !!this.cam.stream });
    this.say(title);
  }

  private failed(err: unknown): void {
    const lang = this.o.lang;
    // the server's own sentence when it sent one; a bare status (no such route, a proxy error) is "no answer"
    if (err instanceof HttpError && !/^HTTP \d+$/.test(err.message)) this.fail(err.message);
    else if (err instanceof HttpError) this.fail(t('scanNoServer', {}, lang), err.message);
    else this.fail(t('scanNoServer', {}, lang));
    console.warn('[scan]', err);
  }

  /** The Identify pinch: picture → what is it → which guide. */
  async identify(): Promise<void> {
    const run = ++this.run;
    const lang = this.o.lang;
    if (!this.cam.stream) {
      const why = await this.openCamera();
      if (run !== this.run) return;
      if (why) return this.fail(why);
    }
    this.set({ phase: 'identifying', title: t('scanLooking', {}, lang), live: true });
    const image = await this.frame();
    if (run !== this.run) return;
    if (!image) return this.fail(t('camNone', {}, lang));
    let r: IdentifyResult;
    try {
      r = await call<IdentifyResult>('/api/identify', this.o.key, { image, lang });
    } catch (err) {
      if (run === this.run) this.failed(err);
      return;
    }
    if (run !== this.run) return;
    const seen = [r.seen?.name, r.seen?.type, r.seen?.article].filter(Boolean).join(' · ');
    if (r.match?.job_id || r.query) this.say(r.message); // said while still `identifying` (the coach may keep it quiet)
    if (r.match?.job_id) {
      if (r.match.ready === false) return this.follow(run, r.match.job_id, r.match.product ?? seen, r.simulated);
      return this.load(run, r.match.job_id, r.match.product ?? seen, r.simulated);
    }
    if (r.query) return this.open(run, r.query, seen || r.query, r.simulated);
    this.set({
      phase: 'retry',
      title: r.message || t('scanLabel', {}, lang),
      body: r.message ? t('scanLabel', {}, lang) : t('scanLabelBody', {}, lang),
      meta: seen || undefined,
      live: true,
      simulated: r.simulated,
    });
    this.say(r.message || t('scanLabel', {}, lang));
  }

  private async open(run: number, q: string, product: string, simulated?: boolean): Promise<void> {
    const lang = this.o.lang;
    this.set({ phase: 'opening', title: t('scanFetching', {}, lang), meta: product, simulated });
    let r: OpenResult;
    try {
      r = await call<OpenResult>('/api/catalog/open', this.o.key, { q });
    } catch (err) {
      if (run === this.run) this.failed(err);
      return;
    }
    if (run !== this.run) return;
    if (r.ready) return this.load(run, r.job_id, r.product ?? product, simulated);
    return this.follow(run, r.job_id, r.product ?? product, simulated);
  }

  /** A guide being built: its status every 3 s until it is done or fails. */
  private follow(run: number, jobId: string, product: string, simulated?: boolean): void {
    const lang = this.o.lang;
    const tick = async (): Promise<void> => {
      let s: JobStatus;
      try {
        s = await call<JobStatus>(`/api/jobs/${encodeURIComponent(jobId)}`, this.o.key);
      } catch (err) {
        if (run !== this.run) return;
        // a passing network error is not the end of a build that takes minutes
        if (err instanceof HttpError && err.status !== 502 && err.status !== 503) return this.failed(err);
        this.timer = window.setTimeout(() => void tick(), POLL_MS);
        return;
      }
      if (run !== this.run) return;
      if (s.status === 'done') return this.load(run, jobId, product, simulated);
      if (s.status === 'error') return this.fail(t('scanFailed', {}, lang), s.error?.split('\n')[0]?.slice(0, 120));
      const pct = Math.round((s.progress ?? 0) * 100);
      const stage = s.stage && STAGE[s.stage] ? t(STAGE[s.stage], {}, lang) : (s.stage ?? '');
      this.set({
        phase: 'building',
        title: t('scanBuilding', {}, lang),
        body: s.status === 'waiting_approval' ? t('scanApprove', {}, lang) : stage || undefined,
        meta: [product, `${pct}%`].filter(Boolean).join(' · '),
        simulated,
      });
      this.timer = window.setTimeout(() => void tick(), POLL_MS);
    };
    this.set({ phase: 'building', title: t('scanBuilding', {}, lang), meta: product, simulated });
    void tick();
  }

  private async load(run: number, jobId: string, product: string, simulated?: boolean): Promise<void> {
    const lang = this.o.lang;
    this.product = product;
    this.set({ phase: 'loading', title: t('scanLoading', {}, lang), meta: product, simulated });
    try {
      await this.o.loadJob(jobId);
    } catch (err) {
      if (run === this.run) this.fail(t('scanFailed', {}, lang), String((err as Error).message ?? err).slice(0, 120));
      return;
    }
    if (run !== this.run) return;
    this.job = jobId;
    await this.locateIn(run, jobId);
  }

  /** "Where am I?": a fresh picture of the assembly → the step to continue at. */
  async locate(jobId: string): Promise<void> {
    const run = ++this.run;
    if (!this.cam.stream) {
      const why = await this.openCamera();
      if (run !== this.run) return;
      if (why) return this.fail(why);
    }
    await this.locateIn(run, jobId);
  }

  private async locateIn(run: number, jobId: string): Promise<void> {
    const lang = this.o.lang;
    this.set({ phase: 'locating', title: t('scanLocating', {}, lang), live: true });
    const image = await this.frame();
    if (run !== this.run) return;
    let r: LocateResult | null = null;
    if (image) {
      try {
        r = await call<LocateResult>(`/api/perception/${encodeURIComponent(jobId)}/locate`, this.o.key, { image, lang });
      } catch (err) {
        console.warn('[scan] locate', err);
      }
    }
    if (run !== this.run) return;
    this.set({ phase: 'idle' });
    this.o.onLocated(r); // the caller says r.message with the step (two sayings would cut each other off)
  }
}

/** The camera permission is already granted (so asking opens no prompt). Unknown counts as no. */
async function granted(): Promise<boolean> {
  try {
    const s = await navigator.permissions.query({ name: 'camera' as PermissionName });
    return s.state === 'granted';
  } catch {
    return false;
  }
}
