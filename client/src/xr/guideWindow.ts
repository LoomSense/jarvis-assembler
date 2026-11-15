import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { fetchManifest, fileUrl } from '../api';
import { displayName } from '../i18n';
import type { Lang, Manifest } from '../types';
import { isMesh, loadGltf, prepareModel, type Model } from '../viewer/model';
import { StepPlayer, type ActionInfo } from '../viewer/steps';
import { bakeQuantised, dress } from '../guide/materials';
import { turnWords } from '../guide/words';
import { FONT } from './palette';
import { makeBackPlate } from './panels';

// «Οδηγίες» inside the immersive session, where there is no page to open: the guide page's view of the current
// step in a floating window beside the panel. The job's own model (src/viewer/model.ts), posed by the page's
// StepPlayer, dressed in the page's materials (src/guide/materials.ts) and lit like the page's workshop at night,
// with the current piece moving in again and again. Under it the page's one line of words. A pinch and a drag on
// the picture turns the assembly; the round button in its corner closes the window.
//
// It is drawn by the session's own renderer into a texture (a render target), then shown on a plane: no second
// renderer, no second WebGL context.

const W = 0.425; // metres (0.34 x 0.255 before the +25 % of Sun 15 Nov)
const H = 0.319;
const WORDS_H = 0.045;
const RT_W = 1024;
const RT_H = Math.round((RT_W * (H - WORDS_H)) / W);
const HOLD_S = 0.8;
const INK = '#f6ecdb';

interface Beat {
  step: number;
  a: ActionInfo | null;
  t0: number;
  t1: number;
}

export class GuideWindow extends THREE.Group {
  private rt = new THREE.WebGLRenderTarget(RT_W, RT_H, { samples: 4 });
  private scene = new THREE.Scene();
  private camera = new THREE.PerspectiveCamera(30, RT_W / RT_H, 0.01, 20);
  private view: THREE.Mesh;
  private closeBtn: THREE.Mesh;
  private wordsCanvas = document.createElement('canvas');
  private wordsTex: THREE.CanvasTexture;
  private player: StepPlayer;
  private pose: (step: number, time: number, action: number) => void;
  private beat: Beat = { step: 0, a: null, t0: 0, t1: 0 };
  private centre = new THREE.Vector3();
  private radius = 0.3;
  private frames: (THREE.Sphere | null)[] = [];
  private time = 0;
  private yaw = 0;
  private drag: { from: number; yaw: number } | null = null;
  private raycaster = new THREE.Raycaster();
  private words = '';
  private closeHover = false;

  /** Load the job's model a second time, for the window alone. Rejects when it cannot. */
  static async load(job: string, key: string | null, lang: Lang, renderer: THREE.WebGLRenderer): Promise<GuideWindow> {
    const manifest = await fetchManifest(job, key);
    let lastError: unknown = null;
    for (const path of [manifest.glbPacked, manifest.glb].filter((p): p is string => !!p)) {
      try {
        const model = prepareModel(await loadGltf(fileUrl(job, path, key)), manifest);
        return new GuideWindow(model, manifest, lang, renderer);
      } catch (err) {
        lastError = err;
      }
    }
    throw lastError ?? new Error('no GLB');
  }

  constructor(
    private model: Model,
    private manifest: Manifest,
    private lang: Lang,
    renderer: THREE.WebGLRenderer,
  ) {
    super();
    // the model, as the guide page prepares it
    const named = new Set<THREE.Object3D>([model.root]);
    const kinds = new Map<THREE.Object3D, { item: Manifest['parts'][number] | Manifest['hardware'][number]; hardware: boolean; screw: boolean }>();
    for (const p of manifest.parts) for (const n of p.nodes) { const o = model.node(n); if (o) { named.add(o); kinds.set(o, { item: p, hardware: false, screw: false }); } }
    for (const h of manifest.hardware) for (const n of h.nodes) { const o = model.node(n); if (o) { named.add(o); kinds.set(o, { item: h, hardware: true, screw: h.kind === 'screw' }); } }
    if (model.marker) named.add(model.marker);
    bakeQuantised(model.scene, named);
    for (const [node, k] of kinds) {
      dress(node, { display: false, hardware: k.hardware ? { shape: model.fastener(node), screw: k.screw } : null });
    }
    model.scene.traverse((o) => {
      if ((o as unknown as { isLight?: boolean }).isLight) o.visible = false;
    });
    model.marker?.traverse((o) => {
      if (!isMesh(o)) return;
      const m = o.material as THREE.MeshStandardMaterial;
      if (!m.emissive) return;
      m.color.set(0x000000);
      m.emissive.set('#9ecfff'); // the hole ring is light only, as on the page
      m.emissiveIntensity = 1.15;
    });
    this.player = new StepPlayer(model, manifest);
    this.pose = exactPose(this.player, model);

    // how big it ever gets, to frame it
    const reach = new THREE.Box3();
    for (const st of this.player.steps) {
      this.pose(st.index, st.duration, st.actions.length);
      const box = this.player.bounds();
      reach.union(box);
      // each step framed on its own, as the page frames it: the finished step, a little room for the piece coming in
      this.frames.push(box.isEmpty() ? null : box.getBoundingSphere(new THREE.Sphere()));
    }
    const sphere = reach.getBoundingSphere(new THREE.Sphere());
    this.centre.copy(sphere.center);
    this.radius = Math.max(0.05, sphere.radius);

    // the workshop at night: black, one warm lamp up on the left, a cool rim behind, a low dark disc
    this.scene.background = new THREE.Color('#000000');
    const pmrem = new THREE.PMREMGenerator(renderer);
    this.scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
    this.scene.environmentIntensity = 0.35;
    pmrem.dispose();
    const lamp = new THREE.SpotLight('#ffd9ae', 40, 0, 0.45, 0.9, 2);
    lamp.position.set(this.centre.x - this.radius * 2.2, this.centre.y + this.radius * 4, this.centre.z + this.radius * 2);
    lamp.target.position.copy(this.centre);
    const rim = new THREE.DirectionalLight('#9ecfff', 0.9);
    rim.position.set(1.5, 1, -2);
    this.scene.add(lamp, lamp.target, rim, new THREE.HemisphereLight('#ffe7cb', '#1b1209', 0.35));
    const disc = new THREE.Mesh(
      new THREE.CylinderGeometry(this.radius * 1.15, this.radius * 1.15, 0.012, 64),
      new THREE.MeshStandardMaterial({ color: '#3b2a1c', roughness: 0.85 }),
    );
    disc.position.set(this.centre.x, reach.min.y - 0.007, this.centre.z);
    this.scene.add(disc, model.scene);

    // the window: the picture, the words under it, the close button in the corner
    this.rt.texture.colorSpace = THREE.SRGBColorSpace;
    this.view = new THREE.Mesh(
      new THREE.PlaneGeometry(W, H - WORDS_H),
      new THREE.MeshBasicMaterial({ map: this.rt.texture, toneMapped: false }),
    );
    this.view.position.y = WORDS_H / 2;
    this.view.renderOrder = 10;
    this.wordsCanvas.width = RT_W;
    this.wordsCanvas.height = Math.round((RT_W * WORDS_H) / W);
    this.wordsTex = new THREE.CanvasTexture(this.wordsCanvas);
    this.wordsTex.colorSpace = THREE.SRGBColorSpace;
    const wordsPlane = new THREE.Mesh(new THREE.PlaneGeometry(W, WORDS_H), new THREE.MeshBasicMaterial({ map: this.wordsTex, toneMapped: false }));
    wordsPlane.position.y = -(H - WORDS_H) / 2;
    wordsPlane.renderOrder = 10;
    this.closeBtn = new THREE.Mesh(new THREE.CircleGeometry(0.018, 40), new THREE.MeshBasicMaterial({ transparent: true, toneMapped: false, depthWrite: false }));
    this.closeBtn.position.set(W / 2 - 0.028, H / 2 - 0.028, 0.002);
    this.closeBtn.renderOrder = 11;
    this.drawClose();
    this.add(this.view, wordsPlane, this.closeBtn, makeBackPlate(W, H, 20));
    this.visible = false;
  }

  /**
   * Follow the guide: manifest step `step` (0-based) and the panel's action `action`, counted the way the headset
   * splits a step (the turn first, then one action per part).
   */
  follow(step: number, action: number): void {
    const st = this.player.steps[step];
    if (!st) return;
    const turn = st.actions.find((a) => a.type === 'turn') ?? null;
    const others = st.actions.filter((a) => a.type !== 'turn');
    const a = turn ? (action === 0 ? turn : (others[action - 1] ?? null)) : (others[action] ?? null);
    const next: Beat = a ? { step, a, t0: a.t0, t1: a.t1 } : { step, a: null, t0: 0, t1: st.duration };
    if (next.step !== this.beat.step || next.a !== this.beat.a) this.time = 0;
    this.beat = next;
    this.setWords(this.wordsFor(next));
  }

  private wordsFor(b: Beat): string {
    const a = b.a;
    const raw = this.manifest.steps[b.step];
    if (a && a.type === 'fasten') return `${a.raw.code ?? ''} ${a.screwIndex}/${a.screwTotal}`.trim();
    if (a && a.node) {
      const part = this.manifest.parts.find((p) => p.nodes.some((n) => this.model.node(n) === a.node));
      return part ? displayName(this.lang, part) : '';
    }
    return turnWords(this.lang, raw?.pose, a?.raw.text);
  }

  private setWords(text: string): void {
    if (text === this.words) return;
    this.words = text;
    const c = this.wordsCanvas.getContext('2d')!;
    c.fillStyle = '#000000';
    c.fillRect(0, 0, this.wordsCanvas.width, this.wordsCanvas.height);
    c.font = `500 64px ${FONT}`;
    c.fillStyle = INK;
    c.textAlign = 'center';
    c.textBaseline = 'middle';
    c.fillText(text, this.wordsCanvas.width / 2, this.wordsCanvas.height / 2 - 8);
    this.wordsTex.needsUpdate = true;
  }

  private drawClose(): void {
    const cv = document.createElement('canvas');
    cv.width = cv.height = 128;
    const c = cv.getContext('2d')!;
    c.beginPath();
    c.arc(64, 64, 60, 0, Math.PI * 2);
    c.fillStyle = this.closeHover ? 'rgba(246,236,219,0.22)' : 'rgba(0,0,0,0.5)';
    c.fill();
    c.lineWidth = 4;
    c.strokeStyle = this.closeHover ? INK : 'rgba(246,236,219,0.5)';
    c.stroke();
    c.lineWidth = 6;
    c.lineCap = 'round';
    c.strokeStyle = INK;
    c.beginPath();
    c.moveTo(46, 46);
    c.lineTo(82, 82);
    c.moveTo(82, 46);
    c.lineTo(46, 82);
    c.stroke();
    const mat = this.closeBtn.material as THREE.MeshBasicMaterial;
    mat.map?.dispose();
    mat.map = new THREE.CanvasTexture(cv);
    mat.map.colorSpace = THREE.SRGBColorSpace;
    mat.needsUpdate = true;
  }

  /** What a ray points at: the close button, the picture, or nothing. */
  hit(ray: THREE.Ray): 'close' | 'view' | null {
    if (!this.visible) return null;
    this.raycaster.ray.copy(ray);
    if (this.raycaster.intersectObject(this.closeBtn, false).length) return 'close';
    if (this.raycaster.intersectObject(this.view, false).length) return 'view';
    return null;
  }

  setCloseHover(on: boolean): void {
    if (on === this.closeHover) return;
    this.closeHover = on;
    this.drawClose();
  }

  /** Turning by a drag: `along` is how far the hand is along the wearer's right, metres. */
  beginTurn(along: number): void {
    this.drag = { from: along, yaw: this.yaw };
  }
  turnTo(along: number): void {
    if (this.drag) this.yaw = this.drag.yaw + (along - this.drag.from) * 9;
  }
  endTurn(): void {
    this.drag = null;
  }
  get turning(): boolean {
    return !!this.drag;
  }
  get turned(): number {
    return this.yaw;
  }

  tick(dt: number): void {
    if (!this.visible) return;
    this.time += dt;
    const b = this.beat;
    const run = Math.max(b.a && b.a.type !== 'turn' ? 0.6 : 1.6, b.t1 - b.t0);
    const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const u = reduced ? run : this.time % (run + HOLD_S);
    this.pose(b.step, b.t0 + (b.t1 - b.t0) * Math.min(1, u / run), b.a ? b.a.index : 0);

    // the side the guide draws this step from, turned by the wearer's drag
    const dir = viewDir(this.model, this.manifest, b.step) ?? new THREE.Vector3(-0.5, -0.5, -0.7).normalize();
    const off = dir.clone().negate();
    const elevation = THREE.MathUtils.clamp(Math.asin(THREE.MathUtils.clamp(off.y, -1, 1)), 0.3, 0.72);
    const flat = Math.hypot(off.x, off.z) || 1;
    off.set((off.x / flat) * Math.cos(elevation), Math.sin(elevation), (off.z / flat) * Math.cos(elevation));
    off.applyAxisAngle(new THREE.Vector3(0, 1, 0), this.yaw);
    const f = this.frames[b.step] ?? { center: this.centre, radius: this.radius };
    const dist = (f.radius / Math.sin(THREE.MathUtils.degToRad(this.camera.fov) / 2)) * 1.08;
    this.camera.position.copy(f.center).addScaledVector(off, dist);
    this.camera.lookAt(f.center);
  }

  /**
   * Draw the picture into the window's texture with the session's renderer. Call once a frame, before the main
   * render. While XR is presenting the renderer would draw with the headset's cameras, so XR is off for this one call
   * and the session's own target is put back after it.
   */
  render(renderer: THREE.WebGLRenderer): void {
    if (!this.visible) return;
    const target = renderer.getRenderTarget();
    const xr = renderer.xr.enabled;
    renderer.xr.enabled = false;
    renderer.setRenderTarget(this.rt);
    renderer.render(this.scene, this.camera);
    renderer.setRenderTarget(target);
    renderer.xr.enabled = xr;
  }
}

/** `player.apply`, exact from the first call on a step (the workaround of `pose()` in src/guide/guide.ts). */
function exactPose(player: StepPlayer, model: Model): (step: number, time: number, action: number) => void {
  let posed = -1;
  return (step, time, action) => {
    player.apply(step, time, action);
    if (step === posed) return;
    posed = step;
    const clip = player.steps[step]?.clip;
    if (!clip) return;
    const t = THREE.MathUtils.clamp(time, 0, clip.duration);
    for (const track of clip.tracks) {
      const { nodeName, propertyName } = THREE.PropertyBinding.parseTrackName(track.name);
      if (propertyName !== 'position' && propertyName !== 'scale') continue;
      const obj = model.node(nodeName);
      if (!obj) continue;
      const v = (track as unknown as { createInterpolant(): { evaluate(t: number): ArrayLike<number> } }).createInterpolant().evaluate(t);
      (obj[propertyName] as THREE.Vector3).set(v[0], v[1], v[2]);
    }
  };
}

/** Where the guide draws a step from (from the camera to the assembly), as on the page. */
function viewDir(model: Model, manifest: Manifest, step: number): THREE.Vector3 | null {
  const cam = manifest.steps[step]?.camera;
  const node = model.node(cam?.node);
  if (node) {
    node.updateWorldMatrix(true, false);
    return new THREE.Vector3(0, 0, -1).transformDirection(node.matrixWorld);
  }
  if (cam?.pos?.length === 3 && cam.target?.length === 3) {
    const d = new THREE.Vector3(cam.target[0] - cam.pos[0], cam.target[2] - cam.pos[2], -(cam.target[1] - cam.pos[1]));
    if (d.lengthSq() > 1e-6) return d.normalize();
  }
  return null;
}

export const GUIDE_WINDOW_W = W;
export const GUIDE_WINDOW_H = H;
