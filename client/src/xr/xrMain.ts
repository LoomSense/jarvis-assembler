import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { XRHandModelFactory } from 'three/addons/webxr/XRHandModelFactory.js';
import { PRODUCTS, type DemoProduct } from './demoAssembly';
import { loadJob } from './jobAssembly';
import { AssemblyView, type GhostState } from './ghost';
import { calloutText, hardwareLabel } from './stepGuides';
import { LANG, t } from './i18n';
import { CARD_H, CARD_W, PartsCard, inventory, makeTag } from './partsCard';
import { prefetch, resetSpeech, say } from './speech';
import { displayName } from './stepGuides';
import { HandChecker } from './handChecks';
import { AG_XR } from './palette';
import { StepPanel, type PanelButton, type PanelContent } from './panels';
import { Placement, type Pointer } from './placement';
import { PlaneVisuals, arSupported, grantedFeatures, startAR } from './session';
import '../styles/xr.css';

/**
 * Quest 3 phase-2 experience (PLAN §6.0), opened with `?mode=xr`.
 * Outside a headset the same page is a desktop preview: orbit with the mouse, click the
 * panel buttons, keys 1–4 switch the ghost state, arrow keys change step.
 *
 * Flow in the headset: place (pinch on the table) → align (drag/rotate the plate onto
 * the real base) → guide (ghost of the next part, Back / Repeat / Next).
 */
type Mode = 'preview' | 'place' | 'align' | 'guide' | 'done';

const params = new URLSearchParams(location.search);
const SHOW_HANDS = params.get('hands') !== '0';
// `?sim=1`: no real furniture. Placed parts are drawn solid, the plate goes on any empty
// surface, and a "Show mistake" button plays the wrong-part warning (stand-in for perception).
// `?product=lack|knagglig` picks the demo product; simulation defaults to the LACK side table.
const SIMULATE = params.get('sim') === '1';
// `?job=<id>`: a job built by the 3D pipeline (manifest + GLB at /files/<id>/), loaded in startXR.
const JOB = params.get('job');
let PRODUCT: DemoProduct = PRODUCTS[(params.get('product') ?? (SIMULATE ? 'lack' : 'knagglig')) as keyof typeof PRODUCTS] ?? PRODUCTS.knagglig;
let FOOTPRINT = PRODUCT.footprint;
let PRODUCT_NAME = LANG === 'el' ? PRODUCT.nameEl : PRODUCT.name;
// Hands-free confirm: a hand held still on the target for DWELL_S counts as "done" (`?auto=0` turns it off).
const AUTO_CONFIRM = params.get('auto') !== '0';
const DWELL_S = 1.5;
// `?debug=1` shows tracking diagnostics in the panel footer instead of the user tips.
const DEBUG = params.get('debug') === '1';
const PREVIEW_TABLE_Y = 0.75;

// Panel beside the work area, in the plate's frame: to the right, above the table,
// tilted back and turned towards the user (BRAND.md §5).
const PANEL_ON_PLATE = {
  pos: new THREE.Vector3(0.49, 0.2, 0.06), // x is set from the product's footprint in startXR
  rot: new THREE.Euler(-0.26, -0.4, 0, 'YXZ'),
};

interface ControllerData {
  source: XRInputSource | null;
  dragging: boolean;
  line: THREE.Line;
}

export async function startXR(root: HTMLElement): Promise<void> {
  if (JOB) {
    try {
      PRODUCT = await loadJob(JOB, params.get('key') ?? undefined);
      FOOTPRINT = PRODUCT.footprint;
      PRODUCT_NAME = LANG === 'el' ? PRODUCT.nameEl : PRODUCT.name;
    } catch (err) {
      console.error('[xr] could not load job, using the built-in demo', err);
    }
  }
  PANEL_ON_PLATE.pos.x = FOOTPRINT.x / 2 + 0.26;

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
  const assembly = new AssemblyView(PRODUCT.steps, { solidPlaced: SIMULATE, finish: PRODUCT.finish, lang: LANG });
  const panel = new StepPanel();

  // ---------- before-you-start screens and saved progress ----------
  // Tips the guide draws before step 1, then a parts check, then the steps.
  const TIPS = PRODUCT.tips ?? [];
  const PARTS = inventory(PRODUCT.steps);
  let prepIndex = 0; // 0..TIPS.length-1 = tip screens, TIPS.length = parts check
  let prepDone = false;
  let missingInfo = false;
  let resumed = false;
  const SAVE_KEY = `ag-xr-progress:${PRODUCT.id}`;
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
  {
    const lines: string[] = TIPS.map((tip) => {
      const x = LANG === 'el' ? tip.el : tip.en;
      return `${x.title}. ${x.body}`;
    });
    PRODUCT.steps.forEach((_, si) => {
      for (let a = 0; a < assembly.actionCountOf(si); a++) lines.push(speechFor(si, a));
    });
    void prefetch(lines);
  }
  try {
    const saved = JSON.parse(localStorage.getItem(SAVE_KEY) ?? 'null') as { step: number; action: number; prepDone: boolean } | null;
    if (saved) {
      prepDone = saved.prepDone;
      if (saved.step > 0 || saved.action > 0) {
        assembly.setStep(saved.step, saved.action);
        resumed = true;
      }
    }
  } catch {
    /* no saved progress */
  }
  // Parts card to the right of the step panel, top-aligned, same tilt (it is the panel's child).
  const card = new PartsCard(PRODUCT.steps, PRODUCT.finish);
  card.position.set(StepPanel.WIDTH / 2 + 0.014 + CARD_W / 2, StepPanel.HEIGHT / 2 - CARD_H / 2, 0);
  panel.add(card);
  // "What is this?" tag shown above whichever part the pointer or mouse is on.
  const tag = makeTag();
  assembly.add(tag.sprite);
  function pointAt(ray: THREE.Ray | null): void {
    const part = ray ? assembly.partAt(ray) : null;
    tag.sprite.visible = !!part;
    if (!part) return;
    tag.set(part.kind === 'screw' ? `${displayName(part)} · ${part.name}` : displayName(part));
    tag.sprite.position.copy(assembly.partTop(part));
  }
  placement.plateRoot.add(assembly);
  scene.add(planes, placement.reticle, placement.plateRoot);

  function panelOnPlate(): void {
    placement.plateRoot.add(panel);
    panel.position.copy(PANEL_ON_PLATE.pos);
    panel.rotation.copy(PANEL_ON_PLATE.rot);
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
  handChecker.onSummary = (s) => {
    // XR-05: send over the perception WebSocket once LIV-01 exists.
    if (s.seq % 50 === 0) console.debug('[xr] hands', s);
  };

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

  function prepContent(): PanelContent {
    const meta = t('beforeStart');
    if (missingInfo) {
      return {
        status: 'unsure',
        meta,
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
        meta: `${meta} · ${t('step', { n: prepIndex + 1, total: TIPS.length, page: tip.page }).split(' · ').pop()}`,
        title: text.title,
        body: text.body,
        image: tip.crop,
        buttons: [{ id: 'prep-ok', label: t('gotIt'), primary: true }],
      };
    }
    return {
      status: 'info',
      meta,
      title: t('checkTitle'),
      body: t('checkBody'),
      chips: PARTS.map((p) => `${p.total}× ${p.name}${p.code ? ` · ${p.code}` : ''}`),
      buttons: [
        { id: 'prep-missing', label: t('missing') },
        { id: 'prep-ok', label: t('haveAll'), primary: true },
      ],
    };
  }

  /** What the voice reads for a panel: the action and its instruction, never diagnostics. */
  function speechOf(c: PanelContent): string {
    if (mode === 'place' || mode === 'align') return '';
    if ((mode === 'guide' || mode === 'preview') && prepDone && assembly.state !== 'wrong') return speechFor(assembly.stepIndex, assembly.actionIndex);
    const lead = c.focus ?? c.title;
    const list = c.chips && !prepDone ? ` ${c.chips.map((x) => x.split(' · ')[0]).join(', ')}.` : '';
    return `${lead}. ${c.body ?? ''}${list}`;
  }

  function content(): PanelContent {
    if ((mode === 'guide' || mode === 'preview') && !prepDone) return prepContent();
    const n = assembly.stepIndex + 1;
    const total = assembly.stepCount;
    switch (mode) {
      case 'place':
        return {
          status: 'info',
          meta: t('setup1'),
          title: placement.reticleValid ? t('pinchPlace') : t('lookTable'),
          body: placement.reticleValid ? t('pinchPlaceBody') : t('lookTableBody'),
          foot: footer(t('pinchHint')),
          buttons: [{ id: 'place-front', label: t('placeFront') }],
        };
      case 'align':
        return {
          status: 'info',
          meta: t('setup2'),
          title: SIMULATE ? t('chooseSpot') : t('lineUp'),
          body: SIMULATE ? t('chooseSpotBody', { product: PRODUCT_NAME }) : t('lineUpBody'),
          foot: footer(t('pinchHint')),
          buttons: [
            { id: 'rot-l', label: '⟲ 5°' },
            { id: 'rot-r', label: '⟳ 5°' },
            { id: 'replace', label: t('replace') },
            { id: 'done-align', label: t('done'), primary: true },
          ],
        };
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
        const step = assembly.step;
        const text = LANG === 'el' && step.el ? step.el : step;
        // Pipeline jobs carry Greek per action but not per step: in Greek, the action is the title.
        const labelTitle = LANG === 'el' && !step.el;
        const state = assembly.state;
        const status = state === 'correct' ? 'success' : state === 'wrong' ? 'error' : state === 'unsure' ? 'unsure' : 'info';
        const buttons: PanelButton[] = [
          { id: 'back', label: t('back') },
          SIMULATE ? { id: 'mistake', label: t('mistake') } : { id: 'repeat', label: t('repeat') },
          { id: 'next', label: assembly.isLast ? t('finish') : t('next'), primary: true },
        ];
        if (mode === 'guide') buttons.unshift({ id: 'move', label: t('move') });
        const dwellTip =
          mode === 'guide' && AUTO_CONFIRM
            ? dwell > 0
              ? `${t('holdHint')} ${'●'.repeat(Math.ceil((dwell / DWELL_S) * 5)).padEnd(5, '○')}`
              : t('holdHint')
            : undefined;
        return {
          status,
          meta: t('step', { n, total, page: step.page }),
          progress: { index: n - 1, total },
          title: state === 'wrong' ? t('notThat') : labelTitle ? assembly.currentLabel : text.title,
          image: step.crop || undefined,
          body: state === 'wrong' ? t('needs', { what: calloutText(step) }) : (assembly.currentText ?? text.instruction),
          focus: !labelTitle && assembly.actionCount > 1 ? assembly.currentLabel : undefined,
          chips: step.hardware ? [hardwareLabel(step.hardware.code, step.hardware.qty)] : undefined,
          foot: footer(resumed ? t('resumed') : mode === 'preview' ? undefined : dwellTip),
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
    refreshPick();
    void say(speechOf(c), LANG);
    if ((mode === 'guide' || mode === 'preview') && prepDone) saveProgress();
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
        placement.reset();
        placePanelInFront();
        setMode('place');
        break;
      case 'done-align':
        setMode('guide');
        break;
      case 'move':
        setMode('align');
        break;
      case 'back':
        resumed = false;
        window.clearTimeout(advanceTimer);
        assembly.retreat();
        refreshPanel();
        break;
      case 'repeat':
        assembly.replay();
        resetSpeech();
        void say(speechOf(content()), LANG);
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
        if (assembly.state === 'correct') break; // already advancing
        resumed = false;
        window.clearTimeout(advanceTimer);
        assembly.setState('correct');
        chime();
        refreshPanel();
        advanceTimer = window.setTimeout(() => {
          if (assembly.advance()) refreshPanel();
          else finish();
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
        resumed = false;
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
    setMode(how === 'restored' ? 'guide' : 'align');
  };

  // ---------- select (pinch / trigger) ----------
  function onSelectStart(c: THREE.XRTargetRaySpace): void {
    const data = c.userData as ControllerData;
    const ray = rayOf(c);
    const id = panel.buttonAt(panelHit(ray));
    if (id) return onButton(id);

    if (mode === 'place') {
      if (placement.requestPlace()) click();
    } else if (mode === 'align' && placement.hitsPlate(ray)) {
      data.dragging = placement.beginDrag(ray);
    }
  }

  function onSelectEnd(c: THREE.XRTargetRaySpace): void {
    const data = c.userData as ControllerData;
    if (data.dragging) {
      data.dragging = false;
      placement.endDrag();
    }
  }

  // ---------- desktop preview input ----------
  function enterPreview(): void {
    mode = 'preview';
    preview.visible = true;
    placement.placeAt(new THREE.Vector3(0, PREVIEW_TABLE_Y, 0), 0);
    panelOnPlate();
    refreshPanel();
  }

  const ndc = new THREE.Vector2();
  renderer.domElement.addEventListener('pointerdown', (e) => {
    if (renderer.xr.isPresenting) return;
    audio ??= new AudioContext();
    ndc.set((e.clientX / window.innerWidth) * 2 - 1, -(e.clientY / window.innerHeight) * 2 + 1);
    raycaster.setFromCamera(ndc, camera);
    const id = panel.buttonAt(raycaster.intersectObject(panel.mesh, false)[0]);
    if (id) onButton(id);
  });

  renderer.domElement.addEventListener('pointermove', (e) => {
    if (renderer.xr.isPresenting) return;
    ndc.set((e.clientX / window.innerWidth) * 2 - 1, -(e.clientY / window.innerHeight) * 2 + 1);
    raycaster.setFromCamera(ndc, camera);
    pointAt(raycaster.ray);
  });

  const KEY_STATES: Record<string, GhostState> = { '1': 'next', '2': 'correct', '3': 'wrong', '4': 'unsure' };
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
    <h1>Assembly Guide · XR</h1>
    <p class="xr-status" role="status">Checking WebXR…</p>
    <button class="xr-enter" disabled>Enter AR</button>
    <p class="xr-hint">Preview: drag to orbit, click the panel buttons, keys 1–4 change the ghost state, arrows change step.</p>`;
  root.appendChild(overlay);
  const statusEl = overlay.querySelector<HTMLParagraphElement>('.xr-status')!;
  const enterBtn = overlay.querySelector<HTMLButtonElement>('.xr-enter')!;

  const supported = await arSupported();
  statusEl.textContent = supported
    ? 'Ready for AR'
    : window.isSecureContext
      ? 'Preview · drag to orbit, ← → steps'
      : 'AR needs HTTPS · preview only';
  enterBtn.disabled = !supported;

  enterBtn.addEventListener('click', async () => {
    try {
      audio ??= new AudioContext();
      enterBtn.disabled = true;
      session = await startAR(renderer);
      features = grantedFeatures(session);
      console.info('[xr] session started; granted features:', features ?? '(not reported)');
      preview.visible = false;
      controls.enabled = false;
      planes.visible = true;
      await placement.onSessionStart(session);
      if (!placement.placed) {
        placePanelInFront();
        setMode('place');
      }
      session.addEventListener('end', () => {
        session = null;
        placement.onSessionEnd();
        planes.clearPlanes();
        controls.enabled = true;
        enterBtn.disabled = false;
        statusEl.textContent = 'AR session ended · preview';
        console.info('[xr] granted features:', features?.join(', ') ?? 'not reported');
        enterPreview();
      });
    } catch (err) {
      console.error('[xr] could not start immersive-ar', err);
      statusEl.textContent = `Could not start AR: ${(err as Error).message}`;
      enterBtn.disabled = false;
    }
  });

  // ---------- loop ----------
  enterPreview();
  const targetBox = new THREE.Box3();
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

      for (const c of controllers) {
        const data = c.userData as ControllerData;
        if (data.dragging) placement.dragTo(rayOf(c));
      }

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

      handChecker.update(t, mode === 'guide' ? assembly.targetBox(targetBox) : null);

      // Hands-free confirm: hand held still at the target for DWELL_S → same as Next.
      dwellCooldown = Math.max(0, dwellCooldown - dt);
      const hs = handChecker.last;
      const holding = AUTO_CONFIRM && prepDone && mode === 'guide' && assembly.state === 'next' && dwellCooldown === 0 && !!hs?.hand_at_target && hs.still;
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
    }

    assembly.update(dt);
    card.update(dt, renderer.xr.isPresenting ? renderer.xr.getCamera() : camera);
    renderer.render(scene, camera);
  });
}
