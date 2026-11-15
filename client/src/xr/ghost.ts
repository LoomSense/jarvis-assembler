import * as THREE from 'three';
import { AG_XR, reducedMotion, type Status } from './palette';
import { makeBadge } from './panels';
import type { DemoPart, DemoStep } from './demoAssembly';
import { StepGuides, actionLabel } from './stepGuides';
import type { Lang } from './i18n';

/**
 * `next` the part to add · `good` the wearer is doing the right thing (steady green, stays) · `correct` done
 * (green, fades) · `wrong` red, dashed · `unsure` amber.
 */
export type GhostState = 'next' | 'good' | 'correct' | 'wrong' | 'unsure';

const GHOST_STYLE = {
  next: AG_XR.ghostNext,
  good: AG_XR.ghostCorrect,
  correct: AG_XR.ghostCorrect,
  wrong: AG_XR.ghostWrong,
  unsure: AG_XR.ghostUnsure,
} as const;

const BADGE_STATUS: Partial<Record<GhostState, Status>> = { correct: 'success', wrong: 'error', unsure: 'unsure' };

const INSERT_LOOP_S = 1.5;
const CORRECT_FADE_S = 0.6;

interface PartView {
  part: DemoPart;
  group: THREE.Group;
  fill: THREE.Mesh;
  edges: THREE.LineSegments;
  dashed: THREE.LineSegments;
  /** Index of the action that adds this part within its step; -1 for a base part. */
  action: number;
}

interface ActionView {
  parts: DemoPart[];
  mover: THREE.Group;
  guides: StepGuides;
  /** A `turn` action: no part, the whole assembly changes pose. */
  turn?: boolean;
}

interface Pose {
  q: THREE.Quaternion;
  p: THREE.Vector3;
}

const TURN_LOOP_S = 2.6;
// Blender (Z up) to glTF (Y up): a -90° turn about X.
const B2G = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), -Math.PI / 2);

/**
 * PLAN §6.0 step 3 (XR-04). Each guide step is split into ordered actions, one part at a
 * time (§6 decision Sat 21:00: "this screw, here, then the next"). Parts of earlier actions
 * are placed, the current action's part is a glowing ghost with its arrow and insertion
 * loop, and later actions of the same step show as faint outlines so the user sees what is
 * coming. Ghost colours, opacity and pulse rates follow BRAND.md §5.
 *
 * Lives inside the plate's frame, so it moves with the plate.
 */
export class AssemblyView extends THREE.Group {
  stepIndex = 0;
  actionIndex = 0;
  state: GhostState = 'next';

  /** Holds every part, mover and guide; turned and lifted to the current step's pose. */
  readonly posed = new THREE.Group();
  private poses: Pose[] = [];
  private poseFrom: Pose | null = null; // previous pose while a turn action loops
  private views: PartView[][] = [];
  private steelByColor = new Map<string, THREE.MeshStandardMaterial>();
  private where = new Map<DemoPart, { s: number; action: number }>();
  private raycaster = new THREE.Raycaster();
  private actions: ActionView[][] = [];
  private badges = new Map<GhostState, THREE.Sprite>();
  /** ✕ at the place a hand is wrongly at (handJudge.ts) */
  private notHere = makeBadge('error');
  private stateSince = 0;
  private clock = 0;

  private placedFill = new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.05, depthWrite: false });
  private placedEdge = new THREE.LineBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.35 });
  private upcomingFill = new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.03, depthWrite: false });
  private upcomingEdge = new THREE.LineBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.18 });
  // `?sim=1`: no real furniture, so placed parts are drawn solid (wood boards, steel screws).
  private woodFill = new THREE.MeshStandardMaterial({ color: '#d8b88a', roughness: 0.85, metalness: 0 });
  private steelFill = new THREE.MeshStandardMaterial({ color: '#cfd4dc', roughness: 0.4, metalness: 0.3 });
  private woodEdge = new THREE.LineBasicMaterial({ color: '#6b5235', transparent: true, opacity: 0.55 });
  private ghostFill = new THREE.MeshStandardMaterial({ transparent: true, depthWrite: false, roughness: 0.6, metalness: 0 });
  private ghostEdge = new THREE.LineBasicMaterial({ color: AG_XR.ghostEdge, transparent: true, opacity: 0.6 });
  private ghostDashed = new THREE.LineDashedMaterial({ color: AG_XR.ghostEdge, transparent: true, opacity: 0.6, dashSize: 0.012, gapSize: 0.008 });
  private moverFill = new THREE.MeshBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.16, depthWrite: false });
  private moverEdge = new THREE.LineBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.35 });

  constructor(
    readonly steps: DemoStep[],
    private readonly opts: { solidPlaced?: boolean; finish?: string; lang?: Lang } = {},
  ) {
    super();
    this.add(this.posed);
    if (opts.finish) this.woodFill.color.set(opts.finish);
    steps.forEach((step, s) => {
      const row: PartView[] = [];
      const acts: ActionView[] = [];
      if (step.turn) acts.push({ parts: [], mover: new THREE.Group(), guides: null!, turn: true });
      for (const part of step.parts) {
        const action = part.base ? -1 : acts.length;
        row.push(this.buildPart(part, action));
        this.where.set(part, { s, action });
        if (part.base) continue;
        const mover = new THREE.Group();
        mover.add(this.buildMover(part));
        this.posed.add(mover);
        acts.push({ parts: [part], mover, guides: null! });
      }
      // Guides need every action's label ("Leg 2 of 4"), so build them after the split.
      acts.forEach((a, i) => {
        const p = a.parts[0];
        a.guides = new StepGuides(a.parts, s + 1, this.labelFor(step, acts, i), p?.kind === 'screw' ? p.name : undefined);
        this.posed.add(a.guides);
      });
      this.views.push(row);
      this.actions.push(acts);
    });
    for (const s of ['correct', 'wrong', 'unsure'] as const) {
      const b = makeBadge(BADGE_STATUS[s]!);
      b.visible = false;
      this.badges.set(s, b);
      this.add(b);
    }
    this.notHere.visible = false;
    this.add(this.notHere);
    this.poses = this.computePoses();
    this.setStep(0);
    this.snapPose();
  }

  private labelFor(step: DemoStep, acts: ActionView[], i: number): string {
    if (acts[i]?.turn) return this.opts.lang === 'el' ? 'Γύρισμα' : 'Turn it';
    return actionLabel(step, i - (acts[0]?.turn ? 1 : 0), this.opts.lang);
  }

  /**
   * Pose per step from the manifest's `assemblyRotationDeg` (Blender XYZ degrees), then lifted
   * and centred so the turned assembly stands on the plate. Hand-made products have no pose.
   */
  private computePoses(): Pose[] {
    const id = { q: new THREE.Quaternion(), p: new THREE.Vector3() };
    if (!this.steps.some((st) => st.pose)) return this.steps.map(() => id);
    const all = new THREE.Box3();
    for (const row of this.views)
      for (const v of row) {
        const c = new THREE.Vector3(...v.part.pos);
        const h = partHalfExtents(v.part);
        all.union(new THREE.Box3(c.clone().sub(h), c.add(h)));
      }
    let deg: [number, number, number] = [0, 0, 0];
    return this.steps.map((st) => {
      deg = st.pose ?? deg;
      const qb = new THREE.Quaternion().setFromEuler(new THREE.Euler(...deg.map(THREE.MathUtils.degToRad) as [number, number, number], 'XYZ'));
      const q = B2G.clone().multiply(qb).multiply(B2G.clone().invert());
      const turned = all.clone().applyMatrix4(new THREE.Matrix4().makeRotationFromQuaternion(q));
      const c = turned.getCenter(new THREE.Vector3());
      return { q, p: new THREE.Vector3(-c.x, -turned.min.y, -c.z) };
    });
  }

  private get pose(): Pose {
    return this.poses[this.stepIndex] ?? { q: new THREE.Quaternion(), p: new THREE.Vector3() };
  }

  private poseMatrix(): THREE.Matrix4 {
    return new THREE.Matrix4().compose(this.pose.p, this.pose.q, new THREE.Vector3(1, 1, 1));
  }

  private snapPose(): void {
    this.posed.quaternion.copy(this.pose.q);
    this.posed.position.copy(this.pose.p);
  }

  private buildPart(part: DemoPart, action: number): PartView {
    const geo = partGeometry(part);
    const edgeGeo = edgesOf(part, geo);
    const group = new THREE.Group();
    group.position.set(...part.pos);
    const fill = new THREE.Mesh(geo, this.placedFill);
    fill.renderOrder = 2;
    const edges = new THREE.LineSegments(edgeGeo, this.placedEdge);
    const dashed = new THREE.LineSegments(edgeGeo, this.ghostDashed);
    dashed.computeLineDistances();
    dashed.visible = false;
    group.add(fill, edges, dashed);
    group.name = `part_${part.id}`;
    this.posed.add(group);
    return { part, group, fill, edges, dashed, action };
  }

  private buildMover(part: DemoPart): THREE.Group {
    const geo = partGeometry(part);
    const g = new THREE.Group();
    g.add(new THREE.Mesh(geo, this.moverFill), new THREE.LineSegments(edgesOf(part, geo), this.moverEdge));
    g.userData.part = part;
    return g;
  }

  get stepCount(): number {
    return this.steps.length;
  }

  get step(): DemoStep {
    return this.steps[this.stepIndex];
  }

  /** Number of actions in the current step (at least 1). */
  get actionCount(): number {
    return Math.max(1, this.actions[this.stepIndex].length);
  }

  /** Parts the user handles in the current action. */
  get currentParts(): DemoPart[] {
    return this.actions[this.stepIndex][this.actionIndex]?.parts ?? [];
  }

  /** "Leg 2 of 4", or the part's name when the step adds only one of it ("Turn it" for a turn). */
  get currentLabel(): string {
    return this.labelFor(this.step, this.actions[this.stepIndex], this.actionIndex);
  }

  /** Label of any action, for prefetching what the voice will say. */
  labelOf(s: number, a: number): string {
    return this.labelFor(this.steps[s], this.actions[s], a);
  }

  /** The action's own instruction when the data has one (pipeline jobs), else undefined. */
  textOf(s: number, a: number): string | undefined {
    const act = this.actions[s]?.[a];
    const step = this.steps[s];
    const lang = this.opts.lang === 'el' ? 'el' : 'en';
    if (act?.turn) return step.turn?.[lang];
    return act?.parts[0]?.text?.[lang];
  }

  get currentText(): string | undefined {
    return this.textOf(this.stepIndex, this.actionIndex);
  }

  /** Actions per step, for prefetching. */
  actionCountOf(s: number): number {
    return Math.max(1, this.actions[s]?.length ?? 0);
  }

  get isTurn(): boolean {
    return !!this.actions[this.stepIndex][this.actionIndex]?.turn;
  }

  /** A point just above a part, in this view's frame (for name tags). */
  partTop(part: DemoPart, lift = 0.035): THREE.Vector3 {
    const c = new THREE.Vector3(...part.pos);
    const h = partHalfExtents(part);
    const box = new THREE.Box3(c.clone().sub(h), c.add(h)).applyMatrix4(this.posed.matrix);
    return new THREE.Vector3((box.min.x + box.max.x) / 2, box.max.y + lift, (box.min.z + box.max.z) / 2);
  }

  get isLast(): boolean {
    return this.stepIndex === this.steps.length - 1 && this.actionIndex >= this.actionCount - 1;
  }

  setStep(i: number, action = 0): void {
    const before = this.pose;
    this.stepIndex = THREE.MathUtils.clamp(i, 0, this.steps.length - 1);
    this.actionIndex = THREE.MathUtils.clamp(action, 0, this.actionCount - 1);
    const solid = this.opts.solidPlaced;
    this.views.forEach((row, s) => {
      for (const v of row) {
        const cur = s === this.stepIndex;
        const placed = s < this.stepIndex || v.part.base || (cur && v.action < this.actionIndex);
        const ghost = cur && v.action === this.actionIndex;
        const upcoming = cur && v.action > this.actionIndex;
        v.group.visible = s <= this.stepIndex;
        v.edges.visible = true;
        v.dashed.visible = false;
        if (placed) {
          v.fill.material = this.placedMaterial(v.part);
          v.edges.material = solid ? this.woodEdge : this.placedEdge;
        } else if (ghost) {
          v.fill.material = this.ghostFill;
          v.edges.material = this.ghostEdge;
        } else if (upcoming) {
          v.fill.material = this.upcomingFill;
          v.edges.material = this.upcomingEdge;
        }
      }
    });
    this.actions.forEach((acts, s) =>
      acts.forEach((a, i) => {
        const on = s === this.stepIndex && i === this.actionIndex;
        a.mover.visible = on;
        a.guides.visible = on;
      }),
    );
    this.notHere.visible = false;
    this.poseFrom = this.isTurn ? (before.q.equals(this.pose.q) ? this.poses[Math.max(0, this.stepIndex - 1)] : before) : null;
    this.setState('next');
  }

  /** Where a part is in the build right now. */
  statusOf(part: DemoPart): 'placed' | 'current' | 'todo' {
    const w = this.where.get(part);
    if (!w) return 'todo';
    if (w.s < this.stepIndex) return 'placed';
    if (w.s > this.stepIndex) return 'todo';
    if (w.action === -1 || w.action < this.actionIndex) return 'placed';
    return w.action === this.actionIndex ? 'current' : 'todo';
  }

  /** The visible part a ray points at (pointer, hand ray or mouse), if any. */
  partAt(ray: THREE.Ray): DemoPart | null {
    this.raycaster.ray.copy(ray);
    const fills: THREE.Object3D[] = [];
    for (const row of this.views) for (const v of row) if (v.group.visible) fills.push(v.fill);
    const hit = this.raycaster.intersectObjects(fills, false)[0];
    if (!hit) return null;
    for (const row of this.views) for (const v of row) if (v.fill === hit.object) return v.part;
    return null;
  }

  /** Next action, or the first action of the next step. Returns false at the very end. */
  advance(): boolean {
    if (this.actionIndex + 1 < this.actionCount) {
      this.setStep(this.stepIndex, this.actionIndex + 1);
      return true;
    }
    if (this.stepIndex + 1 < this.steps.length) {
      this.setStep(this.stepIndex + 1, 0);
      return true;
    }
    return false;
  }

  /** Finished: every part placed, no ghost or guides, a ✓ above the whole product. */
  complete(): void {
    this.stepIndex = this.steps.length - 1;
    this.actionIndex = this.actionCount; // past the last action
    for (const row of this.views)
      for (const v of row) {
        v.group.visible = true;
        v.edges.visible = true;
        v.dashed.visible = false;
        v.fill.material = this.placedMaterial(v.part);
        v.edges.material = this.opts.solidPlaced ? this.woodEdge : this.placedEdge;
      }
    for (const acts of this.actions) for (const a of acts) a.mover.visible = a.guides.visible = false;
    this.state = 'correct';
    this.stateSince = this.clock;
    const all = new THREE.Box3();
    for (const row of this.views)
      for (const v of row) {
        const c = new THREE.Vector3(...v.part.pos);
        const h = partHalfExtents(v.part);
        all.union(new THREE.Box3(c.clone().sub(h), c.add(h)));
      }
    all.applyMatrix4(this.poseMatrix());
    this.poseFrom = null;
    for (const [s, badge] of this.badges) {
      badge.visible = s === 'correct';
      badge.position.set((all.min.x + all.max.x) / 2, all.max.y + 0.06, (all.min.z + all.max.z) / 2);
    }
  }

  /** Previous action, or the last action of the previous step. */
  retreat(): void {
    if (this.actionIndex > 0) this.setStep(this.stepIndex, this.actionIndex - 1);
    else if (this.stepIndex > 0) this.setStep(this.stepIndex - 1, this.actions[this.stepIndex - 1].length - 1);
  }

  private placedMaterial(part: DemoPart): THREE.Material {
    if (!this.opts.solidPlaced) return this.placedFill;
    if (part.kind !== 'screw') return this.woodFill;
    if (!part.color) return this.steelFill;
    let m = this.steelByColor.get(part.color);
    if (!m) this.steelByColor.set(part.color, (m = new THREE.MeshStandardMaterial({ color: part.color, roughness: 0.4, metalness: 0.3 })));
    return m;
  }

  private get currentAction(): ActionView | undefined {
    return this.actions[this.stepIndex][this.actionIndex];
  }

  setState(state: GhostState): void {
    this.state = state;
    this.stateSince = this.clock;
    const style = GHOST_STYLE[state];
    this.ghostFill.color.set(style.color);
    this.ghostFill.emissive.set(style.emissive);
    this.ghostFill.emissiveIntensity = style.emissiveIntensity;
    this.ghostFill.opacity = style.opacity;

    // Wrong = dashed outline (BRAND: colour is never the only cue).
    for (const v of this.views[this.stepIndex]) {
      if (v.action !== this.actionIndex) continue;
      v.edges.visible = state !== 'wrong';
      v.dashed.visible = state === 'wrong';
    }
    const act = this.currentAction;
    const box = this.targetBox(new THREE.Box3(), false);
    if (act) {
      act.mover.visible = state === 'next' || state === 'unsure';
      // Arrows and the callout guide the hand; hide them once the result is known (badge shows).
      act.guides.visible = state === 'next' || state === 'unsure' || state === 'good';
      const toPosed = this.poseMatrix().invert();
      act.guides.placeCallout(box, toPosed, this.pose.q.clone().invert());
    }
    for (const [s, badge] of this.badges) {
      badge.visible = s === state && !box.isEmpty();
      if (badge.visible) badge.position.set((box.min.x + box.max.x) / 2, box.max.y + 0.05, (box.min.z + box.max.z) / 2);
    }
  }

  /**
   * Where the parts that are not due now go, in world space: later actions and later steps, except pieces of the
   * same kind in the same step (three identical slats or eight identical screws may go in in any order).
   * A hand that stays at one of these is at the wrong place (handJudge.ts).
   */
  otherSpots(): { box: THREE.Box3; what: DemoPart }[] {
    const cur = this.currentParts;
    if (!cur.length) return [];
    const same = new Set(cur.map(identity));
    const pose = this.poseMatrix();
    this.updateWorldMatrix(true, false);
    const out: { box: THREE.Box3; what: DemoPart }[] = [];
    for (const [part, w] of this.where) {
      if (part.base || this.statusOf(part) !== 'todo') continue;
      if (w.s === this.stepIndex && same.has(identity(part))) continue;
      const c = new THREE.Vector3(...part.pos);
      const h = partHalfExtents(part);
      out.push({ box: new THREE.Box3(c.clone().sub(h), c.add(h)).applyMatrix4(pose).applyMatrix4(this.matrixWorld), what: part });
    }
    return out;
  }

  /** Put the ✕ just above a point in world space (where a hand is at the wrong place), or take it away. */
  markWrongAt(point: THREE.Vector3 | null): void {
    this.notHere.visible = !!point;
    if (!point) return;
    this.notHere.position.copy(this.worldToLocal(point.clone()));
    this.notHere.position.y += 0.05;
  }

  /** Restart the insertion loop from the beginning (the "Repeat" button). */
  replay(): void {
    this.stateSince = this.clock;
  }

  /** Bounding box of the current action's parts, in world space unless `world` is false. */
  targetBox(out: THREE.Box3, world = true): THREE.Box3 {
    out.makeEmpty();
    const tmp = new THREE.Box3();
    let parts = this.currentParts;
    // A turn has no part: aim at everything placed so far.
    if (!parts.length) parts = [...this.where.keys()].filter((p) => this.statusOf(p) === 'placed');
    for (const part of parts) {
      const half = partHalfExtents(part);
      const c = new THREE.Vector3(...part.pos);
      tmp.set(c.clone().sub(half), c.add(half));
      out.union(tmp);
    }
    if (!out.isEmpty()) out.applyMatrix4(this.poseMatrix());
    if (world && !out.isEmpty()) {
      this.updateWorldMatrix(true, false);
      out.applyMatrix4(this.matrixWorld);
    }
    return out;
  }

  update(dt: number): void {
    this.clock += dt;
    const t = this.clock - this.stateSince;
    const style = GHOST_STYLE[this.state];
    const still = reducedMotion();

    if (this.state === 'correct') {
      this.ghostFill.opacity = style.opacity * Math.max(0, 1 - t / CORRECT_FADE_S);
    } else if (style.pulseHz > 0 && !still) {
      this.ghostFill.opacity = style.opacity + 0.1 * Math.sin(2 * Math.PI * style.pulseHz * t);
    } else {
      this.ghostFill.opacity = style.opacity;
    }

    // Pose: a turn action loops "previous pose → new pose" so the user sees which way to turn;
    // otherwise ease towards the step's pose.
    if (this.poseFrom && this.state !== 'correct' && !still) {
      const k = THREE.MathUtils.clamp(((t % TURN_LOOP_S) - 0.4) / 1.4, 0, 1);
      const e = k * k * (3 - 2 * k);
      this.posed.quaternion.slerpQuaternions(this.poseFrom.q, this.pose.q, e);
      this.posed.position.lerpVectors(this.poseFrom.p, this.pose.p, e);
    } else {
      const k = 1 - Math.exp(-dt * 7);
      this.posed.quaternion.slerp(this.pose.q, k);
      this.posed.position.lerp(this.pose.p, k);
    }

    const act = this.currentAction;
    if (!act) return;
    act.guides.update(t);

    const mover = act.mover;
    if (still) {
      mover.visible = false;
      return;
    }
    if (!mover.visible) return;
    // 1.5 s loop: ease in along the path for 1.1 s, rest at the target, then restart.
    const k = Math.min(1, (t % INSERT_LOOP_S) / 1.1);
    const e = 1 - Math.pow(1 - k, 3);
    for (const child of mover.children) {
      const part = child.userData.part as DemoPart;
      child.position.set(
        part.pos[0] + part.insertFrom[0] * (1 - e),
        part.pos[1] + part.insertFrom[1] * (1 - e),
        part.pos[2] + part.insertFrom[2] * (1 - e),
      );
    }
    this.moverFill.opacity = 0.16 * (1 - k * 0.5);
  }
}

/** What a part is, whichever copy of it: the job's part id or hardware code, else its name. */
function identity(part: DemoPart): string {
  return part.ref ?? `${part.kind}:${part.name}`;
}

/** Half extents of a part's box in the plate frame (screws: diameter x length along `axis`). */
export function partHalfExtents(part: DemoPart): THREE.Vector3 {
  if (part.half) return new THREE.Vector3(...part.half);
  if (part.kind === 'board') return new THREE.Vector3(...part.size).multiplyScalar(0.5);
  const [d, len] = part.size;
  const r = d / 2;
  const h = len / 2;
  return new THREE.Vector3(part.axis === 'x' ? h : r, part.axis === 'z' ? r : part.axis === 'x' ? r : h, part.axis === 'z' ? h : r);
}

/**
 * Box for boards. Screws are a lathe profile so they read as screws, not rods: a wood screw
 * has a head, a thread and a point; a double screw (LACK 115980, as drawn on guide page 1)
 * has a point at both ends and a collar in the middle. The head faces `insertFrom`, the
 * side the screw is driven from.
 */
export function partGeometry(part: DemoPart): THREE.BufferGeometry {
  if (part.geometry) return part.geometry;
  if (part.kind === 'board') return new THREE.BoxGeometry(...part.size);
  const [d, len] = part.size;
  const g = screwGeometry(d / 2, len, part.screwType === 'double');
  const u = new THREE.Vector3(...part.insertFrom);
  if (u.lengthSq() < 1e-8) u.set(part.axis === 'x' ? 1 : 0, part.axis === 'x' || part.axis === 'z' ? 0 : 1, part.axis === 'z' ? 1 : 0);
  g.applyQuaternion(new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 1, 0), u.normalize()));
  return g;
}

/** Lathe profile along +Y, centred on the origin. Thread = ridges every `pitch`. */
function screwGeometry(r: number, len: number, double: boolean): THREE.BufferGeometry {
  const pitch = Math.max(0.0016, r * 0.55);
  const inner = r * 0.7;
  const tip = r * 2.4;
  const pts: THREE.Vector2[] = [];
  const thread = (from: number, to: number) => {
    let ridge = true;
    for (let y = from; y < to; y += pitch / 2) {
      pts.push(new THREE.Vector2(ridge ? r : inner, y));
      ridge = !ridge;
    }
  };
  const bottom = -len / 2;
  const top = len / 2;
  pts.push(new THREE.Vector2(0.0001, bottom), new THREE.Vector2(inner * 0.9, bottom + tip));
  if (double) {
    const c = r * 0.9; // collar half-height
    thread(bottom + tip + pitch / 2, -c);
    pts.push(new THREE.Vector2(inner, -c), new THREE.Vector2(r * 1.75, -c), new THREE.Vector2(r * 1.75, c), new THREE.Vector2(inner, c));
    thread(c + pitch / 2, top - tip);
    pts.push(new THREE.Vector2(inner * 0.9, top - tip), new THREE.Vector2(0.0001, top));
  } else {
    const head = r * 1.1;
    thread(bottom + tip + pitch / 2, top - head - r * 2);
    pts.push(
      new THREE.Vector2(inner, top - head - r * 1.2),
      new THREE.Vector2(r * 2, top - head),
      new THREE.Vector2(r * 2, top - head * 0.25),
      new THREE.Vector2(r * 1.6, top),
      new THREE.Vector2(0.0001, top),
    );
  }
  const g = new THREE.LatheGeometry(pts, 18);
  g.computeVertexNormals();
  return g;
}

/** Outline edges. Screws are round: a 40° threshold keeps only the end circles, not every facet. */
function edgesOf(part: DemoPart, geo: THREE.BufferGeometry): THREE.EdgesGeometry {
  return new THREE.EdgesGeometry(geo, part.kind === 'screw' ? 70 : part.geometry ? 25 : 1);
}
