import * as THREE from 'three';
import { apiFetch } from '../api';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { XRHandModelFactory } from 'three/addons/webxr/XRHandModelFactory.js';
import { PRODUCTS, type DemoProduct } from './demoAssembly';
import { loadJob } from './jobAssembly';
import { AssemblyView, type GhostState } from './ghost';
import { calloutText } from './stepGuides';
import { LANG, t } from './i18n';
import { CARD_H, CARD_W, PartsCard, inventory, makeTag } from './partsCard';
import { prefetch, resetSpeech, say } from './speech';
import { displayName } from './stepGuides';
import { nowWords } from './nowWords';
import { GUIDE_WINDOW_H, GUIDE_WINDOW_W, GuideWindow } from './guideWindow';
import { HandChecker } from './handChecks';
import { HandJudge } from './handJudge';
import { dimsText } from './dims';
import type { DemoPart } from './demoAssembly';
import { Live } from './live';
import { Media } from './media';
import { AG_XR } from './palette';
import { StepPanel, type PanelButton, type PanelContent } from './panels';
import { Placement, type Pointer } from './placement';
import { CornerSnap, type SnapEvent } from './register';
import { ScanFlow, type LocateResult } from './scan';
import { PlaneVisuals, arSupported, grantedFeatures, startAR } from './session';
import '../styles/xr.css';

/**
 * Quest 3 phase-2 experience (PLAN §6.0), opened with `?mode=xr`.
 * Outside a headset the same page is a desktop preview: orbit with the mouse, click the
 * panel buttons, keys 1–5 switch the ghost state, arrow keys change step.
 *
 * Flow in the headset: (scan: identify the product, load its guide, find the step) → place (pinch on the table)
 * → align (touch two corners of the real object; drag/rotate as the fallback) → guide (ghost of the next part,
 * Back / Next / Where am I?).
 */
type Mode = 'preview' | 'place' | 'align' | 'drag' | 'scan' | 'guide' | 'done';

const params = new URLSearchParams(location.search);
// Hand joint spheres only with `?hands=1` (off by default since Sun 15 Nov).
const SHOW_HANDS = params.get('hands') === '1';
// `?sim=1`: no real furniture. Placed parts are drawn solid, the plate goes on any empty
// surface, and a "Show mistake" button plays the wrong-part warning (stand-in for perception).
// `?product=lack|knagglig` picks the demo product; simulation defaults to the LACK side table.
const SIMULATE = params.get('sim') === '1';
// `?job=<id>`: a job built by the 3D pipeline (manifest + GLB at /files/<id>/), loaded in startXR.
let JOB = params.get('job');
// `?scan=1`: start with the scan (camera → identify → guide → step). The Scan button does the same at any time.
const SCAN = params.get('scan') === '1';
const HEADSET = /OculusBrowser|Quest/i.test(navigator.userAgent);
let PRODUCT: DemoProduct = PRODUCTS[(params.get('product') ?? (SIMULATE ? 'lack' : 'knagglig')) as keyof typeof PRODUCTS] ?? PRODUCTS.knagglig;
let FOOTPRINT = PRODUCT.footprint;
let PRODUCT_NAME = LANG === 'el' ? PRODUCT.nameEl : PRODUCT.name;
// Hands-free confirm: a hand held still on the target for DWELL_S counts as "done" (`?auto=0` turns it off).
const AUTO_CONFIRM = params.get('auto') !== '0';
const DWELL_S = 1.5;
// The ghost turns green when a hand is at it and red when a hand stays where another part goes (handJudge.ts);
// `?judge=0` leaves the ghost to live guidance and the buttons.
const JUDGE = params.get('judge') !== '0';
// `?debug=1` shows tracking diagnostics in the panel footer instead of the user tips.
const DEBUG = params.get('debug') === '1';
const PREVIEW_TABLE_Y = 0.75;
const GUIDE_LABEL = LANG === 'el' ? 'Οδηγίες' : 'Instructions';
const SKIP_LABEL = LANG === 'el' ? 'Παράλειψη' : 'Skip';
const CAMERA_LABEL = LANG === 'el' ? 'Κάμερα' : 'Camera';
// Live guidance (live.ts): on with a job unless `?live=0`. `?auto=1` lets a step the server sees finished move on by
// itself; otherwise Next is only cued. The server is this page's origin; `?api=<base>` in development builds only.
const LIVE = params.get('live') !== '0'; // with a job
const AUTO_ADVANCE = params.get('auto') === '1';
const API = (import.meta.env.DEV && params.get('api')) || String(import.meta.env.VITE_API_URL ?? '');

/** The guide page (src/guide) for the same job at the current step, keeping the key and the language. */
function guideUrl(step: number): string {
  const q = new URLSearchParams();
  if (JOB) q.set('job', JOB);
  const key = params.get('key');
  if (key) q.set('key', key);
  q.set('step', String(step + 1));
  const lang = params.get('lang');
  if (lang) q.set('lang', lang);
  return `${location.pathname}?${q}`;
}

// Panel beside the work area, in the plate's frame: to the right, above the table,
// tilted back and turned towards the user (BRAND.md §5).
const PANEL_ON_PLATE = {
  pos: new THREE.Vector3(0.49, 0.2, 0.06), // x is set from the product's footprint in startXR
  rot: new THREE.Euler(-0.26, -0.4, 0, 'YXZ'),
};

interface ControllerData {
  source: XRInputSource | null;
  dragging: boolean;
  /** turning the «Οδηγίες» window's assembly */
  turning?: boolean;
  line: THREE.Line;
}

export async function startXR(root: HTMLElement): Promise<void> {
  // Development only: `?emu=1|bare` emulates a Quest 3 (IWER) with a synthetic room, to try the session on a laptop.
  // In a production build the condition is the constant false, so neither emu.ts nor iwer is bundled.
  if (import.meta.env.DEV && params.has('emu')) {
    const { installEmulator } = await import('./emu');
    Object.assign(window, { __xrDevice: await installEmulator(params.get('emu') !== 'bare', params.get('room') || 'home_office') });
  }
  // Development only: `?mock=scan|scan-build|scan-none` answers the scan's server calls with a script (scanMock.ts).
  if (import.meta.env.DEV && params.get('mock')?.startsWith('scan')) {
    const { installScanMock } = await import('./scanMock');
    installScanMock(params.get('mock')!, params);
  }
  if (JOB) {
    try {
      PRODUCT = await loadJob(JOB, params.get('key') ?? undefined);
      FOOTPRINT = PRODUCT.footprint;
      PRODUCT_NAME = LANG === 'el' ? PRODUCT.nameEl : PRODUCT.name;
    } catch (err) {
      console.error('[xr] could not load job, using the built-in demo', err);
    }
  }
  PANEL_ON_PLATE.pos.x = FOOTPRINT.x / 2 + StepPanel.WIDTH / 2 + 0.06;

  // ---------- renderer, scene ----------
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.setClearColor(0x000000, 0); // transparent, so passthrough shows behind
  renderer.xr.enabled = true;
  root.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(55, window.innerWidth / window.innerHeight, 0.01, 20);
  camera.position.set(0.15, 1.35, 0.85);

  scene.add(new THREE.HemisphereLight(0xffffff, 0x3a3a3a, 1.6));
  const sun = new THREE.DirectionalLight(0xffffff, 1.4);
  sun.position.set(0.6, 2, 1);
  scene.add(sun);

  // ---------- desktop-only scenery ----------
  const preview = new THREE.Group();
  preview.add(new THREE.GridHelper(4, 40, AG_XR.accentDeep, '#202024'));
  const table = new THREE.Mesh(
    new THREE.BoxGeometry(1.2, PREVIEW_TABLE_Y, 0.7),
    new THREE.MeshStandardMaterial({ color: '#2a2a2e', roughness: 0.9 }),
  );
  table.position.y = PREVIEW_TABLE_Y / 2 - 0.001;
  preview.add(table);
  scene.add(preview);

  const controls = new OrbitControls(camera, renderer.domElement);
  controls.target.set(0.3, PREVIEW_TABLE_Y + 0.14, 0);
  controls.enableDamping = true;

  // Frame the box and the panel (about 1 m wide) whatever the window shape.
  function framePreview(): void {
    // The product plus the panel beside it; taller products (the LACK table) need more room.
    const halfW = FOOTPRINT.x / 2 + 0.55;
    const halfH = Math.max(0.45, FOOTPRINT.x * 0.9);
    const vFov = THREE.MathUtils.degToRad(camera.fov);
    const hFov = 2 * Math.atan(Math.tan(vFov / 2) * camera.aspect);
    const dist = Math.max(halfW / Math.tan(hFov / 2), halfH / Math.tan(vFov / 2)) * 1.1;
    const dir = new THREE.Vector3(0.1, 0.55, 1).normalize();
    camera.position.copy(controls.target).addScaledVector(dir, dist);
    controls.update();
  }
  framePreview();
  // Dev only: lets a test script move the preview camera (e.g. to check panels from behind).
  if (import.meta.env.DEV) Object.assign(window, { __xrPreview: { camera, controls } });

  // ---------- world content ----------
  const planes = new PlaneVisuals();
  const placement = new Placement(camera, planes, FOOTPRINT);
  const makeAssembly = () => new AssemblyView(PRODUCT.steps, { solidPlaced: SIMULATE, finish: PRODUCT.finish, lang: LANG });
  // `let`: a scan can load another job after the start (useProduct below)
  let assembly = makeAssembly();
  const panel = new StepPanel();

  // ---------- before-you-start screens and saved progress ----------
  // Tips the guide draws before step 1, then a parts check, then the steps.
  let TIPS = PRODUCT.tips ?? [];
  let PARTS = inventory(PRODUCT.steps);
  let prepIndex = 0; // 0..TIPS.length-1 = tip screens, TIPS.length = parts check
  let prepDone = false;
  let missingInfo = false;
  let SAVE_KEY = `ag-xr-progress:${PRODUCT.id}`;
  function saveProgress(): void {
    try {
      localStorage.setItem(SAVE_KEY, JSON.stringify({ step: assembly.stepIndex, action: assembly.actionIndex, prepDone }));
    } catch {
      /* storage blocked: progress is simply not kept */
    }
  }
  function clearProgress(): void {
    try {
      localStorage.removeItem(SAVE_KEY);
    } catch {
      /* ignore */
    }
  }
  /** What the voice says for action `a` of step `s`: the action's own text, else "<label>. <instruction>". */
  function speechFor(s: number, a: number): string {
    const own = assembly.textOf(s, a);
    if (own) return own;
    const step = PRODUCT.steps[s];
    const text = LANG === 'el' && step.el ? step.el : step;
    const lead = assembly.actionCountOf(s) > 1 ? assembly.labelOf(s, a) : text.title;
    return `${lead}. ${text.instruction}`;
  }
  // Every line the voice will read, fetched ahead so each step speaks without a wait.
  function prefetchLines(): void {
    const lines: string[] = TIPS.map((tip) => {
      const x = LANG === 'el' ? tip.el : tip.en;
      return `${x.title}. ${x.body}`;
    });
    PRODUCT.steps.forEach((_, si) => {
      for (let a = 0; a < assembly.actionCountOf(si); a++) lines.push(speechFor(si, a));
    });
    void prefetch(lines);
  }
  prefetchLines();
  function restoreProgress(): void {
    try {
      const saved = JSON.parse(localStorage.getItem(SAVE_KEY) ?? 'null') as { step: number; action: number; prepDone: boolean } | null;
      if (saved) {
        prepDone = saved.prepDone;
        if (saved.step > 0 || saved.action > 0) {
          assembly.setStep(saved.step, saved.action);
        }
      }
    } catch {
      /* no saved progress */
    }
  }
  restoreProgress();
  // Continue where the person was on another device: the job's perception session remembers the last step and
  // action a display reported (the guide page with live guidance on, or this headset). Applied when it arrives,
  // unless the wearer has already moved; the local save above stays the fallback.
  let movedByWearer = false;
  if (JOB) {
    const url = new URL(`${API.replace(/\/+$/, '')}/api/perception/${encodeURIComponent(JOB)}/position`, location.href);
    const key = params.get('key');
    if (key) url.searchParams.set('key', key);
    fetch(url, { cache: 'no-store' })
      .then((r) => (r.ok && (r.headers.get('content-type') ?? '').includes('json') ? r.json() : null))
      .then((p: { step?: unknown; action?: unknown } | null) => {
        if (!p || !Number.isInteger(p.step) || movedByWearer) return;
        const at = fromManifest(p.step as number, Number.isInteger(p.action) ? (p.action as number) : null);
        if (!at) return;
        prepDone = true;
        assembly.setStep(at.step, at.action);
        console.info('[xr] continuing at step', p.step, 'action', p.action);
        refreshPanel();
      })
      .catch(() => {});
  }

  /** The manifest's step number and 0-based action for where the assembly view is (live guidance, the server). */
  function manifestPlace(): { step: number; action: number | null } {
    const step = PRODUCT.steps[assembly.stepIndex];
    const n = step?.n ?? assembly.stepIndex + 1;
    if (assembly.isTurn) return { step: n, action: step?.turnAction ?? null };
    return { step: n, action: assembly.currentParts[0]?.actionIndex ?? null };
  }
  /** The other way round: the assembly view's step and action for a manifest step number and action. */
  function fromManifest(n: number, action: number | null): { step: number; action: number } | null {
    const s = PRODUCT.steps.findIndex((st) => st.n === n);
    if (s < 0) return null;
    const step = PRODUCT.steps[s];
    if (action === null || action === step.turnAction) return { step: s, action: 0 };
    const moving = step.parts.filter((p) => !p.base);
    const i = moving.findIndex((p) => p.actionIndex === action);
    return { step: s, action: (i < 0 ? 0 : i) + (step.turn ? 1 : 0) };
  }
  // Parts card to the right of the step panel, top-aligned, same tilt (it is the panel's child).
  function makeCard(): PartsCard {
    const c = new PartsCard(PRODUCT.steps, PRODUCT.finish);
    c.position.set(StepPanel.WIDTH / 2 + 0.014 + CARD_W / 2, StepPanel.HEIGHT / 2 - CARD_H / 2, 0);
    panel.add(c);
    return c;
  }
  let card = makeCard();
  if (import.meta.env.DEV)
    Object.assign(window, {
      __xrCard: () => card,
      __xrGuide: () => guideWindow,
      __xrAssembly: () => assembly,
      __xrButton: (id: string) => onButton(id),
      __xrPanel: panel,
      __xrPlate: () => placement.plateRoot,
      __xrSnap: () => snap,
      __xrScan: () => scan,
      __xrMode: () => mode,
    });
  // «Οδηγίες» in the session: the guide page's view of the step, in a window beside the parts card (guideWindow.ts).
  // Outside a session the button opens the guide page itself.
  let guideWindow: GuideWindow | null = null;
  function loadGuideWindow(): void {
    if (!JOB) return;
    const job = JOB;
    GuideWindow.load(job, params.get('key'), LANG, renderer)
      .then((w) => {
        if (job !== JOB) return; // another job was loaded meanwhile
        guideWindow = w;
        w.position.set(StepPanel.WIDTH / 2 + 0.028 + CARD_W + GUIDE_WINDOW_W / 2, StepPanel.HEIGHT / 2 - GUIDE_WINDOW_H / 2, 0);
        panel.add(w);
        w.follow(assembly.stepIndex, assembly.actionIndex);
      })
      .catch((err) => console.warn('[xr] the guide window could not load; «Οδηγίες» opens the page only', err));
  }
  loadGuideWindow();
  function openGuide(): void {
    if (renderer.xr.isPresenting && guideWindow) {
      guideWindow.visible = !guideWindow.visible;
      guideWindow.follow(assembly.stepIndex, assembly.actionIndex);
      return;
    }
    const url = guideUrl(assembly.stepIndex);
    if (!window.open(url, '_blank')) location.assign(url);
  }
  // "What is this?" tag shown above whichever part the pointer or mouse is on.
  const tag = makeTag();
  assembly.add(tag.sprite);
  function pointAt(ray: THREE.Ray | null): void {
    const part = ray ? assembly.partAt(ray) : null;
    tag.sprite.visible = !!part;
    if (!part) return;
    tag.set(`${nameOf(part)} · ${dimsText(part)}`);
    tag.sprite.position.copy(assembly.partTop(part));
  }
  /** A part by the name the wearer knows, a screw with its code. */
  function nameOf(part: DemoPart): string {
    return part.kind === 'screw' && displayName(part) !== part.name ? `${displayName(part)} · ${part.name}` : displayName(part);
  }
  // The real part in the hand, as the camera names it (live guidance): its name and size, by the hand.
  const heldTag = makeTag();
  scene.add(heldTag.sprite);
  const byRef = new Map<string, DemoPart>();
  for (const st of PRODUCT.steps) for (const p of st.parts) if (p.ref && !byRef.has(p.ref)) byRef.set(p.ref, p);
  function heldText(): string | null {
    const h = live?.view.holding;
    if (!h) return null;
    const part = byRef.get(h.id);
    const mark = h.right === true ? '✓ ' : h.right === false ? '✕ ' : '';
    return part ? `${mark}${nameOf(part)} · ${dimsText(part)}` : `${mark}${h.id}`;
  }
  placement.plateRoot.add(assembly);
  scene.add(planes, placement.reticle, placement.plateRoot);

  // Once the plate is snapped to the real object (two corners), the panel stands beside that object.
  let registered = false;
  function panelOnPlate(): void {
    placement.plateRoot.add(panel);
    if (registered) return panelByObject();
    panel.position.copy(PANEL_ON_PLATE.pos);
    panel.rotation.copy(PANEL_ON_PLATE.rot);
  }

  /**
   * The panel next to the real object: to its right, its near edge 6 cm from the object's edge, a little behind
   * the object's middle, its centre 0.15 m above the object's top; turned once to face the wearer and tilted back.
   * Again after every snap and every turn (the object's height changes with its pose).
   */
  function panelByObject(): void {
    const box = assembly.builtBox(new THREE.Box3());
    if (box.isEmpty()) return;
    placement.plateRoot.add(panel);
    panel.position.set(box.max.x + 0.06 + StepPanel.WIDTH / 2, box.max.y + 0.15, (box.min.z * 3 + box.max.z) / 4);
    placement.plateRoot.updateMatrixWorld(true);
    const at = panel.getWorldPosition(new THREE.Vector3());
    const head = camera.getWorldPosition(new THREE.Vector3()); // follows the headset in a session
    const yaw = Math.atan2(head.x - at.x, head.z - at.z);
    const world = new THREE.Quaternion().setFromEuler(new THREE.Euler(PANEL_ON_PLATE.rot.x, yaw, 0, 'YXZ'));
    panel.quaternion.copy(placement.plateRoot.quaternion.clone().invert().multiply(world));
  }

  // ---------- controllers and hands ----------
  const controllers: THREE.XRTargetRaySpace[] = [];
  const hands: THREE.XRHandSpace[] = [];
  const handFactory = new XRHandModelFactory();
  const raycaster = new THREE.Raycaster();

  for (let i = 0; i < 2; i++) {
    const c = renderer.xr.getController(i);
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0, 0), new THREE.Vector3(0, 0, -1)]),
      new THREE.LineBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.5 }),
    );
    line.scale.z = 0.6;
    line.visible = false;
    c.add(line);
    const data: ControllerData = { source: null, dragging: false, line };
    c.userData = data;
    c.addEventListener('connected', (e) => {
      data.source = e.data;
      line.visible = true;
    });
    c.addEventListener('disconnected', () => {
      data.source = null;
      data.dragging = false;
      line.visible = false;
    });
    c.addEventListener('selectstart', () => onSelectStart(c));
    c.addEventListener('selectend', () => onSelectEnd(c));
    scene.add(c);
    controllers.push(c);

    const hand = renderer.xr.getHand(i);
    if (SHOW_HANDS) hand.add(handFactory.createHandModel(hand, 'spheres'));
    scene.add(hand);
    hands.push(hand);
  }

  const handChecker = new HandChecker(hands, camera);
  handChecker.onSummary = (s) => live?.sendHands(s); // about ten a second; the module passes them on

  // ---------- live guidance (live.ts): the ghost's own states, the server's sentence, Next cued ----------
  let autoGhost = false; // the ghost's state was set by live guidance or the hand judge, not by the wearer
  let wrongBy: 'hand' | 'live' | null = null;
  const judge = new HandJudge<DemoPart>();
  /**
   * One ghost for both judges. Red when the camera sees the wrong part in the hand or a hand stays where another
   * part goes; green when a hand is at the ghost or the camera sees the right part; amber when the camera is unsure.
   */
  function resolveGhost(): void {
    if (advancing) return;
    if (!(mode === 'guide' || mode === 'preview') || !prepDone) {
      // setting up or moving the plate: nothing is judged, and a colour it left behind goes
      if (autoGhost) assembly.setState('next');
      autoGhost = false;
      assembly.markWrongAt(null);
      return;
    }
    const lv = live?.view.ghost ?? null;
    const hv = JUDGE ? judge.verdict : 'none';
    const want: GhostState = lv === 'wrong' || hv === 'wrong' ? 'wrong' : hv === 'good' || lv === 'correct' ? 'good' : lv === 'unsure' ? 'unsure' : 'next';
    wrongBy = want !== 'wrong' ? null : lv === 'wrong' ? 'live' : 'hand';
    if (want !== 'next' && assembly.state !== want) {
      assembly.setState(want);
      autoGhost = true;
      if (want === 'wrong') buzz();
      if (want === 'good') tone(1318.5, 0, 0.16, 0.04);
    } else if (want === 'next' && autoGhost) {
      assembly.setState('next');
      autoGhost = false;
    }
    assembly.markWrongAt(wrongBy === 'hand' && judge.wrongSpot ? judge.wrongAt : null);
    refreshPanel();
  }
  let advancing = false; // Next was pressed: green, fade, then the next ghost
  const makeLive = (): Live | null =>
    LIVE && JOB
    ? new Live({
        job: JOB,
        key: params.get('key'),
        apiBase: API || undefined,
        lang: LANG,
        autoAdvance: AUTO_ADVANCE,
        onSay: (text) => void say(text, LANG),
        onAdvance: () => onButton('next'),
        onView: () => {
          resolveGhost();
          refreshPanel();
        },
      })
    : null;
  let live = makeLive();
  if (import.meta.env.DEV) Object.assign(window, { __xrLive: () => live });

  /** How far a hand is along the wearer's right, metres (turning the guide window's assembly). */
  function along(c: THREE.XRTargetRaySpace): number {
    const head = renderer.xr.getCamera();
    const right = new THREE.Vector3(1, 0, 0).applyQuaternion(head.getWorldQuaternion(new THREE.Quaternion()));
    return c.getWorldPosition(new THREE.Vector3()).dot(right);
  }

  function rayOf(c: THREE.XRTargetRaySpace): THREE.Ray {
    raycaster.setFromXRController(c);
    return raycaster.ray.clone();
  }

  function activePointer(): (Pointer & { c: THREE.XRTargetRaySpace }) | null {
    const live = controllers.filter((c) => (c.userData as ControllerData).source);
    const c =
      live.find((c) => (c.userData as ControllerData).source?.handedness === 'right') ?? live[0];
    return c ? { c, source: (c.userData as ControllerData).source, ray: rayOf(c) } : null;
  }

  function panelHit(ray: THREE.Ray): THREE.Intersection | undefined {
    if (!panel.visible) return undefined;
    raycaster.ray.copy(ray);
    return raycaster.intersectObject(panel.mesh, false)[0];
  }

  // ---------- audio (BRAND: press = scale + short click; success chime; error buzz) ----------
  let audio: AudioContext | null = null;
  function tone(freq: number, at: number, dur: number, gain: number, type: OscillatorType = 'sine'): void {
    if (!audio) return;
    const o = audio.createOscillator();
    const g = audio.createGain();
    o.type = type;
    o.frequency.value = freq;
    g.gain.setValueAtTime(0.0001, audio.currentTime + at);
    g.gain.exponentialRampToValueAtTime(gain, audio.currentTime + at + 0.015);
    g.gain.exponentialRampToValueAtTime(0.0001, audio.currentTime + at + dur);
    o.connect(g).connect(audio.destination);
    o.start(audio.currentTime + at);
    o.stop(audio.currentTime + at + dur + 0.02);
  }
  /** Two rising notes, soft. */
  function chime(): void {
    tone(880, 0, 0.18, 0.06);
    tone(1318.5, 0.09, 0.28, 0.06);
  }
  /** One low, short note. */
  function buzz(): void {
    tone(196, 0, 0.22, 0.05, 'triangle');
  }
  function click(): void {
    if (!audio) return;
    const o = audio.createOscillator();
    const g = audio.createGain();
    o.frequency.value = 1400;
    g.gain.setValueAtTime(0.05, audio.currentTime);
    g.gain.exponentialRampToValueAtTime(0.0001, audio.currentTime + 0.05);
    o.connect(g).connect(audio.destination);
    o.start();
    o.stop(audio.currentTime + 0.06);
  }

  // ---------- another job after the start (the scan) ----------
  /** Free a removed subtree's GPU memory (geometries, materials, their textures). */
  function disposeTree(root: THREE.Object3D): void {
    root.traverse((o) => {
      const m = o as THREE.Mesh;
      m.geometry?.dispose();
      const mats = Array.isArray(m.material) ? m.material : m.material ? [m.material] : [];
      for (const mat of mats) {
        (mat as THREE.MeshBasicMaterial).map?.dispose();
        mat.dispose();
      }
    });
  }

  /**
   * Swap a product in after the start: a new assembly view, parts card and guide window, the plate's size for its
   * footprint, its tips and saved progress, and live guidance for its job. The plate stays where it is.
   */
  function useProduct(p: DemoProduct, job: string | null): void {
    PRODUCT = p;
    FOOTPRINT = p.footprint;
    PRODUCT_NAME = LANG === 'el' ? p.nameEl : p.name;
    JOB = job;
    PANEL_ON_PLATE.pos.x = FOOTPRINT.x / 2 + StepPanel.WIDTH / 2 + 0.06;
    registered = false; // another object: snap again

    const oldAssembly = assembly;
    assembly = makeAssembly();
    assembly.add(tag.sprite); // moves the tag out of the old view
    tag.sprite.visible = false;
    placement.plateRoot.remove(oldAssembly);
    placement.plateRoot.add(assembly);
    disposeTree(oldAssembly);

    panel.remove(card);
    disposeTree(card);
    card = makeCard();

    if (guideWindow) {
      panel.remove(guideWindow);
      disposeTree(guideWindow);
      guideWindow = null;
    }
    loadGuideWindow();

    TIPS = p.tips ?? [];
    PARTS = inventory(p.steps);
    SAVE_KEY = `ag-xr-progress:${p.id}`;
    prepIndex = 0;
    prepDone = false;
    missingInfo = false;
    restoreProgress();
    prefetchLines();

    placement.setFootprint(FOOTPRINT);
    if (panel.parent === placement.plateRoot) panelOnPlate();
    if (!renderer.xr.isPresenting) framePreview();

    live?.destroy();
    live = makeLive();
    if (live && session) {
      const at = manifestPlace();
      live.start(at.step, at.action, media.cameraStream ?? scan.cam.stream);
    }
    lastContentKey = '';
    lastPlace = '';
    console.info('[xr] now guiding', p.name, job ? `(job ${job})` : '');
  }

  /** Tell live guidance where the assembly view is, if it changed (Next/Back do it through refreshPanel). */
  let lastPlace = '';
  function tellLive(): void {
    const at = manifestPlace();
    const key = `${at.step}:${at.action}`;
    if (key === lastPlace) return;
    lastPlace = key;
    judge.reset(); // another part: a hand already at its place is reported again
    live?.setStep(at.step, at.action);
  }

  // ---------- scan: camera → identify → guide → locate (scan.ts) ----------
  let scanBack: Mode = 'preview'; // where Cancel goes
  let scanned = false; // a job came from a scan: after it, place (in a session) and snap to the corners
  let lead = ''; // said before the next panel's own words (the locate sentence before the step)
  const scan = new ScanFlow({
    key: params.get('key'),
    lang: LANG,
    get media() {
      return media;
    },
    headset: HEADSET,
    inSession: () => !!session,
    onChange: () => refreshPanel(),
    // the coach's own cards say what identify found; the server's sentence still speaks a retry or an error
    onSay: (text) => {
      if (coach && scan.state.phase === 'identifying') return;
      void say(text, LANG);
    },
    loadJob: async (id) => {
      const p = await loadJob(id, params.get('key') ?? undefined);
      useProduct(p, id);
    },
    onLocated: (r) => located(r),
  });

  // First-run coach: one card at a time, 1/4 … 4/4, each said once; Skip leaves it.
  let coach = false;
  let coachNote = ''; // the locate sentence, kept on card 4
  const coachSpoken = new Set<number>();
  function coachCard(): number {
    const ph = scan.state.phase;
    if (mode === 'align' || mode === 'place') return 3;
    if (ph === 'loading' || ph === 'locating') return 2;
    if (ph === 'identifying' || ph === 'opening' || ph === 'building') return 1;
    return 0;
  }

  function startScan(withCoach = true): void {
    coach = withCoach;
    coachSpoken.clear();
    coachNote = '';
    if (mode !== 'scan') scanBack = mode === 'done' || mode === 'align' || mode === 'drag' ? (session ? 'guide' : 'preview') : mode;
    snap.stop();
    if (session && panel.parent !== scene && !placement.placed) placePanelInFront();
    mode = 'scan';
    void scan.start();
  }

  function leaveScan(): void {
    coach = false;
    scan.cancel();
    setMode(scanBack);
  }

  /** The locate answer (or null when there was none): jump there, say why, then snap to the real object. */
  function located(r: LocateResult | null): void {
    movedByWearer = true;
    prepDone = true;
    advancing = false;
    autoGhost = false;
    const n = r?.next_step;
    if (Number.isInteger(n) && n! > assembly.stepCount) {
      lead = r?.message ?? '';
      finish();
      return;
    }
    if (Number.isInteger(n) && n! >= 1) {
      const at = fromManifest(n!, null) ?? { step: n! - 1, action: 0 };
      assembly.setStep(at.step, at.action);
    }
    lead = r ? r.message : t('scanLocateFailed');
    coachNote = lead;
    tellLive();
    if (scanned && !SIMULATE) {
      scanned = false;
      // in a session the plate must be on the table first; placement.onPlaced then starts the snap. The coach
      // skips the "pinch on the table" card: the plate goes in front for now, the two corners put it right.
      if (session && !placement.placed) {
        placePanelInFront();
        setMode('place');
        if (coach) placement.placeFallback();
      } else beginSnap(false);
      return;
    }
    coach = false;
    if (registered && panel.parent === placement.plateRoot) panelByObject();
    setMode(session ? 'guide' : 'preview');
  }

  // ---------- registration: touch two corners of the real object (register.ts) ----------
  const snap = new CornerSnap();
  placement.plateRoot.add(snap.marker);
  scene.add(snap.ring);
  let snapAfterTurn = false;
  let snapNote = ''; // "Corners 52 cm apart, the model is 40 cm. Try again." / "Measured 41 cm · model 40 cm"

  function beginSnap(afterTurn: boolean): void {
    snapAfterTurn = afterTurn;
    snapNote = '';
    snap.start(assembly.builtBox(new THREE.Box3()));
    setMode('align');
  }

  function endAlign(): void {
    snap.stop();
    snapAfterTurn = false;
    coach = false;
    if (registered && panel.parent === placement.plateRoot) panelByObject();
    setMode(session ? 'guide' : 'preview');
  }

  const cm = (m: number) => Math.round(m * 100);
  function onSnap(e: SnapEvent | null): void {
    if (!e) return;
    click();
    if (e.kind === 'corner') {
      snapNote = '';
      refreshPanel();
    } else if (e.kind === 'off') {
      buzz();
      snapNote = t('snapOff', { got: cm(e.measured), want: cm(e.model) });
      refreshPanel();
      void say(snapNote, LANG);
    } else {
      placement.setPose(e.result.pos, e.result.yaw);
      registered = true;
      panelOnPlate();
      chime();
      lead = t('snapDone');
      console.info('[xr] snapped: measured', cm(e.result.measured), 'cm, model', cm(e.result.model), 'cm');
      endAlign();
    }
  }

  /** In a session: an index fingertip pressing the panel is not touching a corner. */
  const nearPanel = (p: THREE.Vector3): boolean => {
    const local = panel.worldToLocal(p.clone());
    return Math.abs(local.z) < 0.08 && Math.abs(local.x) < StepPanel.WIDTH / 2 + 0.05 && Math.abs(local.y) < StepPanel.HEIGHT / 2 + 0.05;
  };
  function fingertips(): ({ p: THREE.Vector3; r: number } | null)[] {
    return hands.map((h) => {
      const tip = h.joints['index-finger-tip'];
      if (!tip?.visible) return null;
      return { p: tip.getWorldPosition(new THREE.Vector3()), r: tip.jointRadius ?? 0 };
    });
  }

  // ---------- the camera picture on the panel, while scanning ----------
  const camTex = new THREE.VideoTexture(scan.cam.video);
  camTex.colorSpace = THREE.SRGBColorSpace;
  const camView = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: camTex, toneMapped: false, depthWrite: false, transparent: true }));
  camView.renderOrder = 11;
  camView.position.set(StepPanel.WELL.x, StepPanel.WELL.y, 0.002);
  camView.visible = false;
  panel.add(camView);
  function updateCamView(): void {
    const v = scan.cam.video;
    const show = mode === 'scan' && scan.state.live && scan.cam.ready;
    camView.visible = show;
    if (!show) return;
    // fit the picture inside the well, keeping its shape
    const inset = 0.008;
    const w = StepPanel.WELL.w - inset * 2;
    const h = StepPanel.WELL.h - inset * 2;
    const aspect = v.videoWidth / v.videoHeight;
    if (w / h > aspect) camView.scale.set(h * aspect, h, 1);
    else camView.scale.set(w, w / aspect, 1);
  }

  // ---------- modes and the panel ----------
  let mode: Mode = 'preview';
  let session: XRSession | null = null;
  let features: string[] | null = null;
  let panelNeedsFront = false;
  let lastContentKey = '';
  let dwell = 0; // seconds the hand has been held still on the target
  let dwellCooldown = 0;

  function setMode(m: Mode): void {
    mode = m;
    refreshPanel();
  }

  function diagnostics(): string {
    const parts = [
      `planes ${planes.count}`,
      `hit-test ${placement.hitTestGranted ? 'on' : 'off'}`,
      `anchor ${placement.anchorStatus}`,
      `hands ${hands.filter((h) => h.joints['wrist']?.visible).length}`,
    ];
    const hs = handChecker.last;
    if (hs && mode === 'guide') parts.push(hs.hand_at_target ? 'hand at target' : hs.still ? 'hands still' : 'hands moving');
    return parts.join(' · ');
  }

  function footer(tip?: string): string | undefined {
    if (DEBUG) return diagnostics();
    return tip;
  }

  // The screens before step 1, short: a tip is its title and the guide's drawing; the parts check is one count.
  // "Skip" leaves them all at once.
  function prepContent(): PanelContent {
    if (missingInfo) {
      return {
        status: 'unsure',
        title: t('missingTitle'),
        body: t('missingBody', { code: PARTS.find((p) => p.code)?.code ?? '' }),
        buttons: [
          { id: 'prep-back', label: t('back') },
          { id: 'prep-ok', label: t('startAnyway'), primary: true },
        ],
      };
    }
    const tip = TIPS[prepIndex];
    if (tip) {
      const text = LANG === 'el' ? tip.el : tip.en;
      return {
        status: 'warn',
        title: text.title,
        image: tip.crop,
        buttons: [
          { id: 'scan', label: t('scan') },
          { id: 'prep-skip', label: SKIP_LABEL },
          { id: 'prep-ok', label: t('gotIt'), primary: true },
        ],
      };
    }
    return {
      status: 'info',
      title: t('checkTitle'),
      body: `${PARTS.length} ${LANG === 'el' ? 'είδη' : 'kinds'} · ${PARTS.reduce((n, p) => n + p.total, 0)} ${LANG === 'el' ? 'κομμάτια' : 'pieces'}`,
      buttons: [
        { id: 'scan', label: t('scan') },
        { id: 'prep-missing', label: t('missing') },
        { id: 'prep-ok', label: t('haveAll'), primary: true },
      ],
    };
  }

  /** What the voice reads for a panel: the action and its instruction, never diagnostics. */
  function speechOf(c: PanelContent): string {
    if (mode === 'scan' || ((mode === 'align' || mode === 'place') && coach)) {
      // scan.ts says the server's sentences itself; a coach card is said once
      const card = coachCard();
      const ph = scan.state.phase;
      if (!coach || coachSpoken.has(card) || (mode === 'scan' && ['camera', 'retry', 'error', 'idle'].includes(ph))) return mode === 'scan' ? '' : c.title;
      coachSpoken.add(card);
      return card === 1 ? c.title : [c.title, c.body].filter(Boolean).join('. ');
    }
    if (mode === 'align' && !SIMULATE) return c.title; // which corner to touch, hands-free
    if (mode === 'place' || mode === 'align' || mode === 'drag') return '';
    if ((mode === 'guide' || mode === 'preview') && prepDone && assembly.state !== 'wrong') return speechFor(assembly.stepIndex, assembly.actionIndex);
    const lead = c.focus ?? c.title;
    const list = c.chips && !prepDone ? ` ${c.chips.map((x) => x.split(' · ')[0]).join(', ')}.` : '';
    return `${lead}. ${c.body ?? ''}${list}`;
  }

  function scanContent(): PanelContent {
    const s = scan.state;
    if (coach && s.phase !== 'error') {
      const card = coachCard();
      let title = s.title ?? t('scanCamera');
      let body = s.body;
      let meta = '';
      if (card === 0 && s.phase !== 'retry' && s.phase !== 'camera') {
        title = t('coachLook');
        body = undefined;
      } else if (card === 1) {
        if (s.phase === 'identifying') title = t('coachIdentifying');
        meta = s.meta ?? '';
      } else if (card === 2) {
        title = t('coachFound', { product: scan.product || PRODUCT_NAME });
        body = t('coachFinding');
      }
      const buttons: PanelButton[] = [{ id: 'scan-cancel', label: t('skip') }];
      if (s.phase === 'ready' || s.phase === 'retry') buttons.push({ id: 'scan-identify', label: t('identify'), primary: true });
      return {
        status: s.phase === 'retry' ? 'unsure' : 'info',
        meta: [`${card + 1}/4`, meta, s.simulated ? 'sim' : ''].filter(Boolean).join(' · '),
        progress: { index: card, total: 4 },
        title,
        body,
        video: s.live,
        foot: footer(),
        buttons,
      };
    }
    const buttons: PanelButton[] = [{ id: 'scan-cancel', label: t('cancel') }];
    if (s.phase === 'ready' || s.phase === 'retry') buttons.push({ id: 'scan-identify', label: t('identify'), primary: true });
    else if (s.phase === 'error') buttons.push({ id: 'scan-again', label: t('tryAgain'), primary: true });
    return {
      status: s.phase === 'error' ? 'error' : s.phase === 'retry' ? 'unsure' : 'info',
      meta: [t('scan'), s.meta, s.simulated ? 'sim' : ''].filter(Boolean).join(' · '),
      title: s.title ?? t('scanCamera'),
      body: s.body,
      video: s.live,
      foot: footer(),
      buttons,
    };
  }

  /** The corner to touch, or the drag fallback. */
  function alignContent(): PanelContent {
    if (SIMULATE || mode === 'drag') {
      return {
        status: 'info',
        meta: t('setup2'),
        title: SIMULATE ? t('chooseSpot') : t('lineUp'),
        body: SIMULATE ? t('chooseSpotBody', { product: PRODUCT_NAME }) : t('lineUpBody'),
        foot: footer(t('pinchHint')),
        buttons: [
          { id: 'rot-l', label: '⟲ 5°' },
          { id: 'rot-r', label: '⟳ 5°' },
          ...(SIMULATE ? [{ id: 'replace', label: t('replace') }] : [{ id: 'snap-corners', label: t('corners') }]),
          { id: 'done-align', label: t('done'), primary: true },
        ],
      };
    }
    const first = snap.corner === 0;
    if (coach) {
      return {
        status: snapNote && first ? 'unsure' : 'info',
        meta: `4/4 · ${t('snapMeta')}`,
        progress: { index: 3, total: 4 },
        title: first ? t('coachCorners') : t('snapRight'),
        body: first ? t('coachFirst') : session ? t('snapHold') : t('snapClick'),
        foot: footer(snapNote || coachNote || undefined),
        buttons: [
          { id: 'snap-drag', label: t('drag') },
          { id: 'done-align', label: t('skip'), primary: true },
        ],
      };
    }
    return {
      status: snapNote && first ? 'unsure' : 'info',
      meta: snapAfterTurn ? `${t('snapTurned')} · ${t('snapMeta')}` : `${t('setup2')} · ${t('snapMeta')}`,
      title: snapAfterTurn && first ? t('snapAgain') : first ? t('snapLeft') : t('snapRight'),
      body: snapAfterTurn && first ? t('snapLeft') : session ? t('snapHold') : t('snapClick'),
      foot: footer(snapNote || (session ? t('pinchHint') : undefined)),
      buttons: [
        { id: 'snap-drag', label: t('drag') },
        ...(session ? [{ id: 'replace', label: t('replace') }] : []),
        { id: 'done-align', label: t('skip'), primary: true },
      ],
    };
  }

  function content(): PanelContent {
    if (mode === 'scan') return scanContent();
    if ((mode === 'guide' || mode === 'preview') && !prepDone) return prepContent();
    const total = assembly.stepCount;
    switch (mode) {
      case 'place':
        if (coach) return alignContent(); // card 4 while the plate goes down in front
        return {
          status: 'info',
          meta: t('setup1'),
          title: placement.reticleValid ? t('pinchPlace') : t('lookTable'),
          body: placement.reticleValid ? t('pinchPlaceBody') : t('lookTableBody'),
          foot: footer(t('pinchHint')),
          buttons: [
            { id: 'scan', label: t('scan') },
            { id: 'place-front', label: t('placeFront'), primary: true },
          ],
        };
      case 'align':
      case 'drag':
        return alignContent();
      case 'done':
        return {
          status: 'success',
          meta: PRODUCT_NAME,
          progress: { index: total - 1, total },
          title: t('allDone'),
          body: t('allDoneBody', { product: PRODUCT_NAME }),
          foot: footer(),
          buttons: [
            { id: 'move', label: t('movePlate') },
            { id: 'restart', label: t('startOver'), primary: true },
          ],
        };
      default: {
        // One short line of what to do now, in the guide page's words; Back / Next and «Οδηγίες».
        // The guide drawing stays on the right while it fits.
        const step = assembly.step;
        const state = assembly.state;
        const part = assembly.currentParts[0];
        const byHand = state === 'wrong' && wrongBy === 'hand';
        const other = byHand && judge.wrongSpot ? nameOf(judge.wrongSpot.what) : '';
        const status = state === 'correct' || state === 'good' ? 'success' : state === 'wrong' ? 'error' : state === 'unsure' ? 'unsure' : 'info';
        const buttons: PanelButton[] = [{ id: 'back', label: t('back') }];
        if (JOB) buttons.push({ id: 'guide', label: GUIDE_LABEL });
        if (JOB) buttons.push({ id: 'locate', label: t('whereAmI') });
        buttons.push({ id: 'next', label: assembly.isLast ? t('finish') : t('next'), primary: true, cue: !!live?.view.cue });
        // Move: the two-corner snap again (in the preview, two clicks on the table); Scan when there is room
        if (mode === 'guide' || !SIMULATE) buttons.unshift({ id: 'move', label: t('move') });
        if (!JOB) buttons.splice(1, 0, { id: 'scan', label: t('scan') });
        return {
          status,
          title: byHand ? t('notThere') : state === 'wrong' ? t('notThat') : nowWords(step, part, LANG),
          image: step.crop || undefined,
          // a correction or a confirmation from live guidance says the server's own sentence; his own otherwise
          // otherwise: where it goes when the hand is at the wrong place, "right place" when it is at the ghost,
          // and the part's size, to tell it from its look-alikes
          body:
            (byHand ? (other ? t('goesHere', { other }) : t('goesHereBare')) : undefined) ||
            (autoGhost && live?.view.sentence) ||
            (state === 'wrong' ? t('needs', { what: calloutText(step) }) : undefined) ||
            (state === 'good' && judge.verdict === 'good' ? `${t('rightSpot')}. ${AUTO_CONFIRM ? t('holdHint') : ''}`.trim() : undefined) ||
            (part ? (part.kind === 'screw' ? `${displayName(part)} · ${dimsText(part)}` : dimsText(part)) : undefined),
          foot: footer((heldText() && t('holding', { what: heldText()! })) || (live?.view.word ?? undefined)),
          buttons,
        };
      }
    }
  }

  function refreshPick(): void {
    const inSteps = mode === 'guide' || mode === 'preview' || mode === 'done';
    card.visible = inSteps && (prepDone || (!missingInfo && prepIndex >= TIPS.length));
    if (card.visible) card.refresh(assembly, mode === 'done');
  }

  function refreshPanel(): void {
    const c = content();
    const key = JSON.stringify(c);
    if (key === lastContentKey) return;
    lastContentKey = key;
    panel.setContent(c);
    guideWindow?.follow(assembly.stepIndex, assembly.actionIndex);
    refreshPick();
    const words = speechOf(c);
    void say(lead && words ? `${lead} ${words}` : lead || words, LANG);
    if (mode !== 'scan') lead = '';
    if ((mode === 'guide' || mode === 'preview') && prepDone) {
      saveProgress();
      tellLive();
    }
  }

  function goToStep(i: number): void {
    assembly.setStep(i);
    refreshPanel();
  }

  function finish(): void {
    clearProgress();
    assembly.complete();
    setMode('done');
    refreshPick();
  }

  let advanceTimer = 0;
  let mistakeTimer = 0;
  function onButton(id: string): void {
    panel.press(id);
    click();
    switch (id) {
      case 'place-front':
        placement.placeFallback();
        break;
      case 'rot-l':
        placement.rotate(5);
        break;
      case 'rot-r':
        placement.rotate(-5);
        break;
      case 'replace':
        snap.stop();
        registered = false;
        placement.reset();
        placePanelInFront();
        setMode('place');
        break;
      case 'done-align':
        endAlign();
        break;
      case 'move':
        if (SIMULATE) setMode('align');
        else beginSnap(false);
        break;
      case 'snap-drag':
        snap.stop();
        setMode('drag');
        break;
      case 'snap-corners':
        beginSnap(snapAfterTurn);
        break;
      case 'scan':
        startScan();
        break;
      case 'scan-cancel':
        leaveScan();
        break;
      case 'scan-identify':
        scanned = true;
        void scan.identify();
        break;
      case 'scan-again':
        void scan.start();
        break;
      case 'locate':
        if (!JOB) break;
        coach = false;
        scanBack = session ? 'guide' : 'preview';
        scanned = false;
        mode = 'scan';
        void scan.locate(JOB);
        break;
      case 'back':
        movedByWearer = true;
        advancing = false;
        autoGhost = false;
        window.clearTimeout(advanceTimer);
        assembly.retreat();
        refreshPanel();
        break;
      case 'repeat':
        assembly.replay();
        resetSpeech();
        void say(speechOf(content()), LANG);
        break;
      case 'guide':
        openGuide();
        break;
      case 'mistake':
        // Stand-in for a `wrong_part` perception event: red dashed ghost for 3 s, then back.
        window.clearTimeout(mistakeTimer);
        assembly.setState('wrong');
        buzz();
        refreshPanel();
        mistakeTimer = window.setTimeout(() => {
          if (assembly.state === 'wrong') {
            assembly.setState('next');
            refreshPanel();
          }
        }, 3000);
        break;
      case 'next':
        // Stand-in for `step_complete` from perception: green, fade, then the next ghost.
        if (advancing) break; // already advancing
        movedByWearer = true;
        advancing = true;
        autoGhost = false;
        window.clearTimeout(advanceTimer);
        assembly.setState('correct');
        chime();
        refreshPanel();
        advanceTimer = window.setTimeout(() => {
          advancing = false;
          const turned = assembly.isTurn; // the real object was just turned: snap the plate to it again
          if (!assembly.advance()) finish();
          else if (turned && !SIMULATE && placement.placed) {
            tellLive();
            beginSnap(true);
          } else refreshPanel();
        }, 650);
        break;
      case 'prep-ok':
        if (missingInfo || prepIndex >= TIPS.length) {
          missingInfo = false;
          prepDone = true;
        } else {
          prepIndex++;
        }
        refreshPanel();
        break;
      case 'prep-skip':
        missingInfo = false;
        prepDone = true;
        refreshPanel();
        break;
      case 'prep-missing':
        missingInfo = true;
        refreshPanel();
        break;
      case 'prep-back':
        missingInfo = false;
        refreshPanel();
        break;
      case 'restart':
        clearProgress();
        resetSpeech();
        goToStep(0);
        setMode(session ? 'guide' : 'preview');
        refreshPick();
        break;
    }
  }

  function placePanelInFront(): void {
    scene.add(panel);
    panelNeedsFront = true;
  }

  placement.onPlaced = (how) => {
    console.info('[xr] plate placed via', how);
    panelOnPlate();
    if (mode === 'scan') return; // the anchor came back while scanning: the scan goes on
    if (how === 'restored') setMode('guide');
    else if (SIMULATE) setMode('align');
    else beginSnap(false);
  };

  // ---------- select (pinch / trigger) ----------
  function onSelectStart(c: THREE.XRTargetRaySpace): void {
    const data = c.userData as ControllerData;
    const ray = rayOf(c);
    const id = panel.buttonAt(panelHit(ray));
    if (id) return onButton(id);
    const inWindow = guideWindow?.hit(ray);
    if (inWindow === 'close') {
      guideWindow!.visible = false;
      click();
      return;
    }
    if (inWindow === 'view') {
      guideWindow!.beginTurn(along(c));
      data.turning = true;
      return;
    }

    if (mode === 'place') {
      if (placement.requestPlace()) click();
    } else if ((mode === 'align' || mode === 'drag') && placement.hitsPlate(ray)) {
      data.dragging = placement.beginDrag(ray);
    }
  }

  function onSelectEnd(c: THREE.XRTargetRaySpace): void {
    const data = c.userData as ControllerData;
    if (data.turning) {
      data.turning = false;
      guideWindow?.endTurn();
    }
    if (data.dragging) {
      data.dragging = false;
      placement.endDrag();
    }
  }

  // ---------- desktop preview input ----------
  function enterPreview(refresh = true): void {
    mode = 'preview';
    preview.visible = true;
    placement.placeAt(new THREE.Vector3(0, PREVIEW_TABLE_Y, 0), 0);
    panelOnPlate();
    if (refresh) refreshPanel();
  }

  const ndc = new THREE.Vector2();
  renderer.domElement.addEventListener('pointerdown', (e) => {
    if (renderer.xr.isPresenting) return;
    audio ??= new AudioContext();
    ndc.set((e.clientX / window.innerWidth) * 2 - 1, -(e.clientY / window.innerHeight) * 2 + 1);
    raycaster.setFromCamera(ndc, camera);
    const id = panel.buttonAt(raycaster.intersectObject(panel.mesh, false)[0]);
    if (id) onButton(id);
    downAt = id ? null : { x: e.clientX, y: e.clientY };
  });

  // The two-corner snap in the preview: a click (not an orbit drag) on the table top is a touched corner.
  let downAt: { x: number; y: number } | null = null;
  const tableTop = new THREE.Plane(new THREE.Vector3(0, 1, 0), -PREVIEW_TABLE_Y);
  renderer.domElement.addEventListener('pointerup', (e) => {
    if (renderer.xr.isPresenting || !downAt || mode !== 'align' || !snap.active) return;
    const moved = Math.hypot(e.clientX - downAt.x, e.clientY - downAt.y);
    downAt = null;
    if (moved > 6) return;
    ndc.set((e.clientX / window.innerWidth) * 2 - 1, -(e.clientY / window.innerHeight) * 2 + 1);
    raycaster.setFromCamera(ndc, camera);
    const p = raycaster.ray.intersectPlane(tableTop, new THREE.Vector3());
    if (p) onSnap(snap.addPoint(p));
  });

  renderer.domElement.addEventListener('pointermove', (e) => {
    if (renderer.xr.isPresenting) return;
    ndc.set((e.clientX / window.innerWidth) * 2 - 1, -(e.clientY / window.innerHeight) * 2 + 1);
    raycaster.setFromCamera(ndc, camera);
    pointAt(raycaster.ray);
  });

  const KEY_STATES: Record<string, GhostState> = { '1': 'next', '2': 'correct', '3': 'wrong', '4': 'unsure', '5': 'good' };
  window.addEventListener('keydown', (e) => {
    if (KEY_STATES[e.key]) {
      assembly.setState(KEY_STATES[e.key]);
      refreshPanel();
    } else if (e.key === 'ArrowRight') {
      const primary = content().buttons.find((b) => b.primary);
      if (primary) onButton(primary.id);
    } else if (e.key === 'ArrowLeft' && prepDone) onButton('back');
  });

  window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
    if (!renderer.xr.isPresenting) framePreview();
  });

  // ---------- overlay with the Enter button ----------
  const overlay = document.createElement('div');
  overlay.className = 'xr-overlay';
  overlay.innerHTML = `
    <h1>Jarvis Assembler · XR</h1>
    <p class="xr-status" role="status">Checking WebXR…</p>
    <button class="xr-guide xr-camera" type="button" hidden>${CAMERA_LABEL}</button>
    <button class="xr-guide" type="button" hidden>${GUIDE_LABEL}</button>
    <button class="xr-enter" disabled>Enter AR</button>
    <p class="xr-hint">Preview: drag to orbit, click the panel buttons, keys 1–5 change the ghost state, arrows change step.</p>`;
  root.appendChild(overlay);
  const statusEl = overlay.querySelector<HTMLParagraphElement>('.xr-status')!;
  const enterBtn = overlay.querySelector<HTMLButtonElement>('.xr-enter')!;
  // «Οδηγίες» before the session: the guide page for the same job, at the step the headset is on
  const guideBtn = overlay.querySelector<HTMLButtonElement>('.xr-guide:not(.xr-camera)')!;
  // The headset's camera for live guidance, asked for here, before the session (a permission prompt inside an
  // immersive session can end it). Optional: without it guidance follows the steps and says it has no camera.
  const cameraBtn = overlay.querySelector<HTMLButtonElement>('.xr-camera')!;
  // shown for the scan too: on a Quest its picture comes from this camera (asked for before the session)
  cameraBtn.hidden = !navigator.mediaDevices?.getUserMedia;
  const media = new Media(/OculusBrowser|Quest/i.test(navigator.userAgent), import.meta.env.DEV && params.get('cam') === 'any', () => {
    const m = media.status;
    if (m.asking) return;
    const said: Record<string, string> = {
      headset: LANG === 'el' ? 'Κάμερα κράνους έτοιμη' : 'Headset camera ready',
      generic: LANG === 'el' ? 'Κάμερα έτοιμη' : 'Camera ready',
      'inward-only': LANG === 'el' ? 'Στο λουκέτο, τσέκαρε «Headset cameras»' : 'At the lock icon, tick “Headset cameras”',
      denied: LANG === 'el' ? 'Χωρίς κάμερα' : 'No camera',
      missing: LANG === 'el' ? 'Δεν βρέθηκε κάμερα' : 'No camera found',
      unavailable: LANG === 'el' ? 'Χωρίς κάμερα' : 'No camera',
    };
    if (m.asked) {
      const why = m.camera === 'missing' || m.camera === 'denied' ? ` (${m.cameraError || '?'} · ${m.videoInputs} ${LANG === 'el' ? 'κάμερες' : 'cameras'})` : '';
      statusEl.textContent = (said[m.camera] ?? statusEl.textContent) + why;
      reportCamera(m);
    }
    cameraBtn.disabled = m.camera === 'headset' || m.camera === 'generic';
    stepButtons();
  });
  // One camera report per try to the host (`jobs/_diag/`), so a failure on the headset can be read without a cable.
  let reportedCamera = '';
  function reportCamera(m: typeof media.status): void {
    const sig = `${m.camera}|${m.cameraError}|${m.videoInputs}`;
    if (sig === reportedCamera) return;
    reportedCamera = sig;
    const body = JSON.stringify({ kind: 'camera', camera: m.camera, error: m.cameraError, videoInputs: m.videoInputs, label: m.cameraLabel, cameras: m.cameras, secure: window.isSecureContext });
    void apiFetch('/api/diag', params.get('key'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body }).catch(() => {});
  }
  // Two steps in order, so the camera is never forgotten: ① Camera is the main button until it is granted (a prompt
  // inside the session can end it), then ② Enter AR is. Enter AR still works without the camera.
  const ENTER_LABEL = LANG === 'el' ? 'Μπες στο AR' : 'Enter AR';
  function stepButtons(): void {
    const ordered = !cameraBtn.hidden;
    const m = media.status;
    const ready = m.camera === 'headset' || m.camera === 'generic';
    const tried = ready || (m.asked && m.camera !== 'inward-only');
    cameraBtn.textContent = ordered ? `${ready ? '✓' : '①'} ${CAMERA_LABEL}` : CAMERA_LABEL;
    enterBtn.textContent = ordered ? `② ${ENTER_LABEL}` : ENTER_LABEL;
    cameraBtn.classList.toggle('is-primary', ordered && !tried);
    enterBtn.classList.toggle('is-secondary', ordered && !tried);
    if (ordered && !m.asked && !session && !enterBtn.disabled) statusEl.textContent = LANG === 'el' ? 'Πρώτα η κάμερα, μετά το AR' : 'Camera first, then AR';
  }
  cameraBtn.addEventListener('click', () => {
    audio ??= new AudioContext(); // the first tap also starts the sound
    void media.request();
  });
  // On a Quest, ask for the camera as soon as the page opens (no tap needed; the browser shows its own prompt once
  // and remembers the answer), so the only tap left is Enter AR. Elsewhere the ① Camera button stays.
  if (!cameraBtn.hidden && /OculusBrowser|Quest/i.test(navigator.userAgent) && params.get('cam') !== 'tap') void media.request();
  guideBtn.hidden = !JOB;
  guideBtn.addEventListener('click', () => openGuide());

  const supported = await arSupported();
  statusEl.textContent = supported
    ? 'Ready for AR'
    : window.isSecureContext
      ? 'Preview · drag to orbit, ← → steps'
      : 'AR needs HTTPS · preview only';
  enterBtn.disabled = !supported;
  stepButtons();

  enterBtn.addEventListener('click', async () => {
    try {
      audio ??= new AudioContext();
      enterBtn.disabled = true;
      // live guidance starts inside the tap (the voice may begin); with the camera of the bar's button, if any
      if (live) {
        const at = manifestPlace();
        live.start(at.step, at.action, media.cameraStream);
      }
      session = await startAR(renderer);
      features = grantedFeatures(session);
      console.info('[xr] session started; granted features:', features ?? '(not reported)');
      preview.visible = false;
      controls.enabled = false;
      planes.visible = true;
      await placement.onSessionStart(session);
      if (!placement.placed) {
        placePanelInFront();
        if (mode === 'scan') refreshPanel(); // a scan begun on the page goes on in the headset
        else if (SCAN && !scan.job) startScan();
        else setMode('place');
      }
      session.addEventListener('end', () => {
        session = null;
        live?.stop();
        snap.stop();
        scan.cancel();
        placement.onSessionEnd();
        planes.clearPlanes();
        controls.enabled = true;
        enterBtn.disabled = false;
        statusEl.textContent = 'AR session ended · preview';
        console.info('[xr] granted features:', features?.join(', ') ?? 'not reported');
        enterPreview();
      });
    } catch (err) {
      live?.stop();
      console.error('[xr] could not start immersive-ar', err);
      statusEl.textContent = `Could not start AR: ${(err as Error).message}`;
      enterBtn.disabled = false;
    }
  });

  // ---------- loop ----------
  enterPreview(!SCAN); // with ?scan=1 the first words are the scan's, not the step's
  if (SCAN) startScan();
  const targetBox = new THREE.Box3();
  const judgeBox = new THREE.Box3();
  let lastJudge = 0;
  // Dev only: a stand-in hand at a world point, to try the green and red ghost without a headset:
  // `__xrHand(x, y, z)`, `__xrHand()` takes it away; `__xrSpots()` lists the ghost's box and the other places.
  let fakeHand: THREE.Vector3 | null = null;
  if (import.meta.env.DEV)
    Object.assign(window, {
      __xrHand: (x?: number, y?: number, z?: number) => (fakeHand = x === undefined ? null : new THREE.Vector3(x, y, z)),
      __xrSpots: () => ({
        ghost: assembly.targetBox(new THREE.Box3()),
        others: assembly.otherSpots().map((o) => ({ name: o.what.name, id: o.what.id, c: o.box.getCenter(new THREE.Vector3()) })),
        verdict: judge.verdict,
        state: assembly.state,
      }),
    });
  let lastT = 0;
  let lastDiag = 0;

  renderer.setAnimationLoop((t: number, frame?: XRFrame) => {
    const dt = lastT ? Math.min((t - lastT) / 1000, 0.1) : 0;
    lastT = t;

    if (frame && session) {
      const ref = renderer.xr.getReferenceSpace()!;
      planes.update(frame, ref);
      const pointer = activePointer();
      placement.update(frame, ref, pointer);

      let closeHover = false;
      for (const c of controllers) {
        const data = c.userData as ControllerData;
        if (data.dragging) placement.dragTo(rayOf(c));
        if (data.turning) guideWindow?.turnTo(along(c));
        if (data.source && guideWindow?.hit(rayOf(c)) === 'close') closeHover = true;
      }
      guideWindow?.setCloseHover(closeHover);

      if (panelNeedsFront) {
        const pose = frame.getViewerPose(ref);
        if (pose) {
          const { position: p, orientation: o } = pose.transform;
          const head = new THREE.Vector3(p.x, p.y, p.z);
          const fwd = new THREE.Vector3(0, 0, -1).applyQuaternion(new THREE.Quaternion(o.x, o.y, o.z, o.w));
          fwd.y = 0;
          fwd.normalize();
          // 0.8 m away, about 12° below eye level, facing the user, then left alone.
          panel.position.copy(head).addScaledVector(fwd, 0.8);
          panel.position.y -= 0.8 * Math.tan(THREE.MathUtils.degToRad(12));
          panel.lookAt(head);
          panelNeedsFront = false;
        }
      }

      // Hover feedback on the panel buttons.
      let hovered: string | null = null;
      for (const c of controllers) {
        const data = c.userData as ControllerData;
        if (!data.source) continue;
        const hit = panelHit(rayOf(c));
        data.line.scale.z = hit ? hit.distance : 0.6;
        hovered ??= panel.buttonAt(hit);
      }
      panel.setHovered(hovered);
      const p = activePointer();
      pointAt(p && !panelHit(p.ray) ? p.ray : null);

      if (mode === 'align' && snap.active) onSnap(snap.update(dt, fingertips(), renderer.xr.getCamera(), nearPanel));

      handChecker.update(t, mode === 'guide' ? assembly.targetBox(targetBox) : null);

      // The real part's name and size by the hand that holds it, when the camera has named it.
      const held = heldText();
      const holder = held ? hands.map((h) => h.joints['index-finger-tip']).find((j) => j?.visible) : undefined;
      heldTag.sprite.visible = !!holder;
      if (holder) {
        heldTag.set(held!);
        holder.getWorldPosition(heldTag.sprite.position).y += 0.07;
      }

      // Hands-free confirm: hand held still at the target for DWELL_S → same as Next.
      dwellCooldown = Math.max(0, dwellCooldown - dt);
      const hs = handChecker.last;
      const holding = AUTO_CONFIRM && prepDone && mode === 'guide' && (assembly.state === 'next' || assembly.state === 'good') && dwellCooldown === 0 && !!hs?.hand_at_target && hs.still;
      const before = dwell;
      dwell = holding ? dwell + dt : 0;
      if (dwell >= DWELL_S) {
        dwell = 0;
        dwellCooldown = 1.5;
        onButton('next');
      } else if (Math.ceil((dwell / DWELL_S) * 5) !== Math.ceil((before / DWELL_S) * 5)) {
        refreshPanel();
      }

      if (t - lastDiag > 1000 || (mode === 'place' && t - lastDiag > 250)) {
        lastDiag = t;
        refreshPanel();
      }
    } else {
      controls.update();
      snap.update(dt, [], camera, () => true); // the marker's pulse; the corners come from clicks
    }
    updateCamView();

    // Green or red from where the hands are, ten times a second.
    if (JUDGE && t - lastJudge >= 100) {
      lastJudge = t;
      const probes: THREE.Vector3[][] = [];
      if (frame && session) {
        for (const hand of hands) {
          if (!hand.joints['wrist']?.visible) continue;
          probes.push(
            (['index-finger-tip', 'thumb-tip', 'wrist'] as const)
              .map((j) => hand.joints[j])
              .filter((j): j is THREE.XRJointSpace => !!j?.visible)
              .map((j) => j.getWorldPosition(new THREE.Vector3())),
          );
        }
      }
      if (fakeHand) probes.push([fakeHand]);
      const judging = prepDone && !advancing && (mode === 'guide' || (mode === 'preview' && !!fakeHand));
      const changed = judge.update(t, probes, judging && !assembly.isTurn ? assembly.targetBox(judgeBox) : null, judging ? assembly.otherSpots() : []);
      if (changed) resolveGhost();
      else if (wrongBy === 'hand' && judge.wrongSpot) assembly.markWrongAt(judge.wrongAt); // the ✕ follows the hand
    }

    assembly.update(dt);
    card.update(dt, renderer.xr.isPresenting ? renderer.xr.getCamera() : camera);
    guideWindow?.tick(dt);
    guideWindow?.render(renderer);
    renderer.render(scene, camera);
  });
}
