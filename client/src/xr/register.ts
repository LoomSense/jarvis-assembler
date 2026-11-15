import * as THREE from 'three';
import { AG_XR, reducedMotion } from './palette';

// Registration: snap the build plate to the real object by touching two of its corners.
//
// WebXR on Quest gives no camera intrinsics and no camera pose, so the camera picture cannot be used to track the
// real object in 6-DoF. What the headset does know well is where the fingertips are (hand joints). So: the wearer
// touches the real assembly's front-left bottom corner, then its front-right bottom corner, with the index
// fingertip; a corner counts when the tip stays within 1 cm for 0.8 s. From the two points the plate gets its
// position (on the table, height from the touched points) and its yaw, so that the current pose's front-bottom
// corners land on them. The plate is then anchored (placement.ts) like any other placement.

const HOLD_M = 0.01;
const HOLD_S = 0.8;
/** a hold shorter than this shows no ring, so a hand at rest for a moment does not flash one */
const RING_AFTER_S = 0.15;
/** the second corner must be at least this far from the first */
const MIN_APART_M = 0.08;
/** accepted ratio between the touched distance and the model's width */
const RATIO_OK: [number, number] = [0.6, 1.6];
const RING_SEGMENTS = 48;

export interface SnapResult {
  /** plate position, world */
  pos: THREE.Vector3;
  /** plate yaw about +Y, radians */
  yaw: number;
  /** distance between the touched points and between the model's corners, metres */
  measured: number;
  model: number;
}

/** angle of a horizontal vector, matching a three.js rotation about +Y (rotateY(a) turns (1,0,0) to angle a) */
const angleOf = (x: number, z: number): number => Math.atan2(-z, x);

/**
 * The plate pose that puts the plate-frame points `a` (front-left) and `b` (front-right) on the world points `p`
 * and `q`: yaw from the two directions, position from the midpoints, height so that the corners' height matches.
 */
export function solveSnap(a: THREE.Vector3, b: THREE.Vector3, p: THREE.Vector3, q: THREE.Vector3): SnapResult {
  const yaw = angleOf(q.x - p.x, q.z - p.z) - angleOf(b.x - a.x, b.z - a.z);
  const midL = a.clone().add(b).multiplyScalar(0.5);
  const midW = p.clone().add(q).multiplyScalar(0.5);
  const turned = new THREE.Vector3(midL.x, 0, midL.z).applyAxisAngle(new THREE.Vector3(0, 1, 0), yaw);
  const pos = new THREE.Vector3(midW.x - turned.x, midW.y - midL.y, midW.z - turned.z);
  return {
    pos,
    yaw,
    measured: Math.hypot(q.x - p.x, q.z - p.z),
    model: Math.hypot(b.x - a.x, b.z - a.z),
  };
}

interface Tracker {
  at: THREE.Vector3 | null;
  since: number;
}

export type SnapEvent = { kind: 'corner'; index: 0 | 1 } | { kind: 'done'; result: SnapResult } | { kind: 'off'; measured: number; model: number };

/**
 * The corner prompt and the hold detection. `marker` belongs in the plate's frame (it shows which corner of the
 * virtual plate to touch), `ring` in the world (it fills at the fingertip while it holds still).
 */
export class CornerSnap {
  readonly marker = new THREE.Group();
  readonly ring = new THREE.Group();
  active = false;
  /** 0: waiting for the front-left corner, 1: for the front-right one */
  corner: 0 | 1 = 0;
  /** the last result, for the footer */
  last: SnapResult | null = null;
  /** 0..1, how far the current hold is */
  hold = 0;

  private corners: [THREE.Vector3, THREE.Vector3] = [new THREE.Vector3(), new THREE.Vector3()];
  private first: THREE.Vector3 | null = null;
  private trackers: Tracker[] = [];
  private clock = 0;
  private fill: THREE.Mesh;
  private dotMat = new THREE.MeshBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.9, depthTest: false, depthWrite: false });
  private haloMat = new THREE.MeshBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.5, side: THREE.DoubleSide, depthTest: false, depthWrite: false });

  constructor() {
    // marker: a dot on the corner and a flat halo around it, drawn over everything (the real corner hides it otherwise)
    const dot = new THREE.Mesh(new THREE.SphereGeometry(0.008, 16, 12), this.dotMat);
    const halo = new THREE.Mesh(new THREE.RingGeometry(0.016, 0.022, 40).rotateX(-Math.PI / 2), this.haloMat);
    dot.renderOrder = halo.renderOrder = 20;
    this.marker.add(dot, halo);
    this.marker.visible = false;

    // ring: a faint track and a fill that grows clockwise with the hold
    const track = new THREE.Mesh(
      new THREE.RingGeometry(0.014, 0.018, RING_SEGMENTS),
      new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.25, side: THREE.DoubleSide, depthTest: false, depthWrite: false }),
    );
    const fillGeo = new THREE.RingGeometry(0.014, 0.018, RING_SEGMENTS, 1, Math.PI / 2, -Math.PI * 2);
    this.fill = new THREE.Mesh(
      fillGeo,
      new THREE.MeshBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.95, side: THREE.DoubleSide, depthTest: false, depthWrite: false }),
    );
    track.renderOrder = 21;
    this.fill.renderOrder = 22;
    this.ring.add(track, this.fill);
    this.ring.visible = false;
  }

  /** Start (again) with the front-bottom corners of what stands on the plate, in the plate's frame. */
  start(built: THREE.Box3): void {
    this.active = true;
    this.corner = 0;
    this.first = null;
    this.hold = 0;
    this.trackers = [];
    this.corners = [new THREE.Vector3(built.min.x, built.min.y, built.max.z), new THREE.Vector3(built.max.x, built.min.y, built.max.z)];
    this.showMarker();
  }

  stop(): void {
    this.active = false;
    this.marker.visible = false;
    this.ring.visible = false;
    this.hold = 0;
  }

  /** The model's width between the two corners, metres. */
  get modelWidth(): number {
    return this.corners[0].distanceTo(this.corners[1]);
  }

  private showMarker(): void {
    this.marker.position.copy(this.corners[this.corner]);
    this.marker.position.y += 0.002;
    this.marker.visible = this.active;
  }

  /**
   * A touched corner, world coordinates (a fingertip hold, or a click on the table in the desktop preview).
   * Returns what happened, or null when the point was ignored (too close to the first corner).
   */
  addPoint(p: THREE.Vector3): SnapEvent | null {
    if (!this.active) return null;
    if (this.corner === 0) {
      this.first = p.clone();
      this.corner = 1;
      this.trackers = [];
      this.showMarker();
      return { kind: 'corner', index: 0 };
    }
    if (!this.first || p.distanceTo(this.first) < MIN_APART_M) return null;
    const r = solveSnap(this.corners[0], this.corners[1], this.first, p);
    const ratio = r.model > 0 ? r.measured / r.model : 1;
    if (ratio < RATIO_OK[0] || ratio > RATIO_OK[1]) {
      // the wrong corners, or a model whose size is far off: start over rather than snap somewhere odd
      this.corner = 0;
      this.first = null;
      this.trackers = [];
      this.showMarker();
      return { kind: 'off', measured: r.measured, model: r.model };
    }
    this.last = r;
    this.stop();
    return { kind: 'done', result: r };
  }

  /**
   * Per frame in a session: the index fingertips (world, with the joint radius when known). `ignore` drops tips
   * that are pressing the panel. Returns an event when a corner was taken.
   */
  update(dt: number, tips: ({ p: THREE.Vector3; r: number } | null)[], camera: THREE.Camera, ignore: (p: THREE.Vector3) => boolean): SnapEvent | null {
    this.clock += dt;
    if (!this.active) return null;
    // marker pulse at 0.5 Hz (BRAND §5: at most 1 Hz)
    const k = reducedMotion() ? 0.5 : 0.5 + 0.5 * Math.sin(this.clock * Math.PI);
    this.haloMat.opacity = 0.25 + 0.45 * k;
    this.marker.scale.setScalar(0.85 + 0.3 * k);

    let best: { i: number; held: number; p: THREE.Vector3 } | null = null;
    tips.forEach((tip, i) => {
      const tr = (this.trackers[i] ??= { at: null, since: this.clock });
      if (!tip || ignore(tip.p) || (this.first && this.corner === 1 && tip.p.distanceTo(this.first) < MIN_APART_M)) {
        tr.at = null;
        return;
      }
      if (!tr.at || tr.at.distanceTo(tip.p) > HOLD_M) {
        tr.at = tip.p.clone();
        tr.since = this.clock;
      }
      const held = this.clock - tr.since;
      if (!best || held > best.held) {
        // the touched point: the fingertip's centre, lowered by its radius (it rests on the corner, not in it)
        const at = tr.at.clone();
        at.y -= tip.r;
        best = { i, held, p: at };
      }
    });

    const b = best as { i: number; held: number; p: THREE.Vector3 } | null;
    this.hold = b ? Math.min(1, b.held / HOLD_S) : 0;
    this.ring.visible = !!b && b.held >= RING_AFTER_S;
    if (b && this.ring.visible) {
      this.ring.position.copy(this.trackers[b.i].at!);
      this.ring.lookAt(camera.getWorldPosition(new THREE.Vector3()));
      const n = Math.round(RING_SEGMENTS * this.hold);
      (this.fill.geometry as THREE.BufferGeometry).setDrawRange(0, n * 6);
    }
    if (b && b.held >= HOLD_S) {
      this.trackers = [];
      this.ring.visible = false;
      this.hold = 0;
      return this.addPoint(b.p);
    }
    return null;
  }
}
