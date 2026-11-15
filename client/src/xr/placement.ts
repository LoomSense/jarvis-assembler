import * as THREE from 'three';
import { AG_XR } from './palette';
import type { PlaneVisuals } from './session';

const STORE_KEY = 'ag.plateAnchor';
const UP = new THREE.Vector3(0, 1, 0);

/** The ray of the hand or controller the user is pointing with. */
export interface Pointer {
  source: XRInputSource | null;
  ray: THREE.Ray;
}

export type PlacedHow = 'hit-test' | 'plane' | 'fallback' | 'restored';
export type AnchorStatus = 'none' | 'pending' | 'anchored' | 'persisted' | 'unsupported';

function storeGet(): string | null {
  try {
    return localStorage.getItem(STORE_KEY);
  } catch {
    return null;
  }
}
function storeSet(v: string | null): void {
  try {
    if (v) localStorage.setItem(STORE_KEY, v);
    else localStorage.removeItem(STORE_KEY);
  } catch {
    /* storage blocked: anchors just won't survive a reload */
  }
}

/**
 * PLAN §6.0 steps 1–2 (XR-03): find the table, pinch to put the build plate on it,
 * drag and rotate until the virtual base sits on the real one, and keep it there with
 * a (persistent, where supported) anchor.
 *
 * Source of truth for the plate pose is `plateRoot`. While an anchor exists and the
 * user is not dragging, the plate follows the anchor every frame.
 */
export class Placement {
  readonly reticle: THREE.Mesh;
  readonly plateRoot = new THREE.Group();
  readonly plateHit: THREE.Mesh;

  placed = false;
  reticleValid = false;
  hitTestGranted = false;
  anchorStatus: AnchorStatus = 'none';
  onPlaced?: (how: PlacedHow) => void;

  private session: XRSession | null = null;
  private viewerSource: XRHitTestSource | null = null;
  private inputSources = new Map<XRInputSource, XRHitTestSource>();
  private anchor: XRAnchor | null = null;
  private wantAnchor = false;
  private anchorBusy = false;
  private pending = false;
  private pendingFallback = false;
  private reticleHow: PlacedHow = 'hit-test';
  private dragOffset: THREE.Vector3 | null = null;
  private raycaster = new THREE.Raycaster();
  private v1 = new THREE.Vector3();
  private v2 = new THREE.Vector3();
  private outline = new THREE.LineLoop(
    new THREE.BufferGeometry(),
    new THREE.LineBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.8 }),
  );
  private front = new THREE.Mesh(
    new THREE.CircleGeometry(0.02, 3).rotateX(-Math.PI / 2).rotateY(-Math.PI / 2),
    new THREE.MeshBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.8, depthWrite: false }),
  );

  constructor(
    private camera: THREE.Camera,
    private planes: PlaneVisuals,
    footprint: { x: number; z: number },
  ) {
    this.reticle = new THREE.Mesh(
      new THREE.RingGeometry(0.035, 0.045, 40).rotateX(-Math.PI / 2),
      new THREE.MeshBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.9, depthWrite: false }),
    );
    this.reticle.visible = false;

    this.plateHit = new THREE.Mesh(
      new THREE.BufferGeometry(),
      new THREE.MeshBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.06, depthWrite: false }),
    );
    this.plateRoot.add(this.outline, this.front, this.plateHit);
    this.setFootprint(footprint);
    this.plateRoot.visible = false;
  }

  /** Plate outline, front marker and drag handle for a product's footprint (again when another job is loaded). */
  setFootprint(footprint: { x: number; z: number }): void {
    const m = 0.03;
    const hx = footprint.x / 2 + m;
    const hz = footprint.z / 2 + m;
    this.outline.geometry.dispose();
    this.outline.geometry = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(-hx, 0.001, -hz),
      new THREE.Vector3(hx, 0.001, -hz),
      new THREE.Vector3(hx, 0.001, hz),
      new THREE.Vector3(-hx, 0.001, hz),
    ]);
    this.front.position.set(0, 0.001, hz + 0.03);
    this.plateHit.geometry.dispose();
    this.plateHit.geometry = new THREE.PlaneGeometry(hx * 2, hz * 2).rotateX(-Math.PI / 2);
  }

  async onSessionStart(session: XRSession): Promise<void> {
    this.session = session;
    this.placed = false;
    this.plateRoot.visible = false;
    this.anchorStatus = 'none';

    if (session.requestHitTestSource) {
      try {
        const viewer = await session.requestReferenceSpace('viewer');
        this.viewerSource = (await session.requestHitTestSource({ space: viewer })) ?? null;
        this.hitTestGranted = !!this.viewerSource;
      } catch {
        this.hitTestGranted = false;
      }
    }
    session.addEventListener('inputsourceschange', this.onInputs);
    for (const s of session.inputSources) this.addSource(s);

    await this.restore(session);
  }

  onSessionEnd(): void {
    this.session?.removeEventListener('inputsourceschange', this.onInputs);
    this.viewerSource?.cancel();
    this.viewerSource = null;
    for (const h of this.inputSources.values()) h.cancel();
    this.inputSources.clear();
    this.anchor = null; // anchors die with the session
    this.session = null;
    this.reticle.visible = false;
  }

  private onInputs = (e: XRInputSourcesChangeEvent): void => {
    for (const s of e.removed) {
      this.inputSources.get(s)?.cancel();
      this.inputSources.delete(s);
    }
    for (const s of e.added) this.addSource(s);
  };

  private addSource(s: XRInputSource): void {
    if (!this.session?.requestHitTestSource || !this.hitTestGranted || s.targetRayMode !== 'tracked-pointer') return;
    this.session
      .requestHitTestSource({ space: s.targetRaySpace })
      ?.then((h) => this.inputSources.set(s, h))
      .catch(() => {});
  }

  private async restore(session: XRSession): Promise<void> {
    const uuid = storeGet();
    if (!uuid || !session.restorePersistentAnchor) return;
    try {
      this.anchor = await session.restorePersistentAnchor(uuid);
      this.placed = true;
      this.anchorStatus = 'persisted';
      this.onPlaced?.('restored');
    } catch {
      storeSet(null);
    }
  }

  update(frame: XRFrame, ref: XRReferenceSpace, pointer: Pointer | null): void {
    if (!this.placed) this.updateReticle(frame, ref, pointer);
    else this.reticle.visible = false;

    if (this.pending || this.pendingFallback) this.doPlace();

    if (this.anchor && !this.dragOffset) {
      const pose = frame.getPose(this.anchor.anchorSpace, ref);
      if (pose) {
        const { position: p, orientation: o } = pose.transform;
        this.plateRoot.position.set(p.x, p.y, p.z);
        this.plateRoot.quaternion.set(o.x, o.y, o.z, o.w);
        this.plateRoot.visible = true;
      }
    }

    if (this.wantAnchor && !this.anchorBusy && !this.dragOffset) this.createAnchor(frame, ref);
  }

  private updateReticle(frame: XRFrame, ref: XRReferenceSpace, pointer: Pointer | null): void {
    let found = false;

    const source = (pointer?.source && this.inputSources.get(pointer.source)) || this.viewerSource;
    if (source) {
      const results = frame.getHitTestResults(source);
      for (const r of results) {
        const pose = r.getPose(ref);
        if (!pose) continue;
        const { position: p, orientation: o } = pose.transform;
        // The hit pose's +y is the surface normal: keep only flat surfaces.
        const normal = this.v1.set(0, 1, 0).applyQuaternion(new THREE.Quaternion(o.x, o.y, o.z, o.w));
        if (normal.y < 0.85) continue;
        this.reticle.position.set(p.x, p.y, p.z);
        this.reticleHow = 'hit-test';
        found = true;
        break;
      }
    }

    if (!found && pointer && this.planes.hitMeshes.length) {
      this.raycaster.ray.copy(pointer.ray);
      const hit = this.raycaster.intersectObjects(this.planes.hitMeshes, false)[0];
      if (hit) {
        this.reticle.position.copy(hit.point);
        this.reticleHow = 'plane';
        found = true;
      }
    }

    this.reticleValid = found;
    this.reticle.visible = found;
  }

  /** Pinch while the reticle is on a surface. Returns false when there is no surface yet. */
  requestPlace(): boolean {
    if (!this.reticleValid) return false;
    this.pending = true;
    return true;
  }

  /** No surface found: put the plate 0.6 m ahead and about table height below the eyes. */
  placeFallback(): void {
    this.pendingFallback = true;
  }

  private doPlace(): void {
    const cam = this.camera.getWorldPosition(this.v1);
    let pos: THREE.Vector3;
    let how: PlacedHow;
    if (this.pending) {
      pos = this.reticle.position.clone();
      how = this.reticleHow;
    } else {
      const fwd = this.camera.getWorldDirection(this.v2).setY(0).normalize();
      pos = cam.clone().addScaledVector(fwd, 0.6);
      pos.y = cam.y - 0.55;
      how = 'fallback';
    }
    this.pending = this.pendingFallback = false;

    // +z of the plate (the box front) faces the user.
    const yaw = Math.atan2(cam.x - pos.x, cam.z - pos.z);
    this.plateRoot.position.copy(pos);
    this.plateRoot.quaternion.setFromAxisAngle(UP, yaw);
    this.plateRoot.visible = true;
    this.placed = true;
    this.dropAnchor();
    this.wantAnchor = true;
    this.onPlaced?.(how);
  }

  private createAnchor(frame: XRFrame, ref: XRReferenceSpace): void {
    this.wantAnchor = false;
    if (!frame.createAnchor) {
      this.anchorStatus = 'unsupported';
      return;
    }
    const p = this.plateRoot.position;
    const q = this.plateRoot.quaternion;
    const t = new XRRigidTransform({ x: p.x, y: p.y, z: p.z }, { x: q.x, y: q.y, z: q.z, w: q.w });
    this.anchorBusy = true;
    this.anchorStatus = 'pending';
    frame
      .createAnchor(t, ref)
      .then(async (a) => {
        // The user moved the plate while we waited: this anchor is stale.
        if (this.wantAnchor || this.dragOffset) {
          a.delete();
          return;
        }
        this.dropAnchor();
        this.anchor = a;
        this.anchorStatus = 'anchored';
        await this.persist(a);
      })
      .catch(() => {
        this.anchorStatus = 'unsupported';
      })
      .finally(() => {
        this.anchorBusy = false;
      });
  }

  private async persist(a: XRAnchor): Promise<void> {
    if (!a.requestPersistentHandle) return;
    try {
      const old = storeGet();
      if (old && this.session?.deletePersistentAnchor) await this.session.deletePersistentAnchor(old).catch(() => {});
      storeSet(await a.requestPersistentHandle());
      this.anchorStatus = 'persisted';
    } catch {
      /* persistence is optional; the session anchor still holds */
    }
  }

  private dropAnchor(): void {
    this.anchor?.delete();
    this.anchor = null;
    if (this.anchorStatus !== 'unsupported') this.anchorStatus = 'none';
  }

  /** True when the ray points at the plate (used to start a drag). */
  hitsPlate(ray: THREE.Ray): boolean {
    if (!this.plateRoot.visible) return false;
    this.raycaster.ray.copy(ray);
    return this.raycaster.intersectObject(this.plateHit, false).length > 0;
  }

  private onPlateLevel(ray: THREE.Ray, target: THREE.Vector3): THREE.Vector3 | null {
    const plane = new THREE.Plane(UP, -this.plateRoot.position.y);
    return ray.intersectPlane(plane, target);
  }

  beginDrag(ray: THREE.Ray): boolean {
    const p = this.onPlateLevel(ray, this.v1);
    if (!p) return false;
    this.dragOffset = this.plateRoot.position.clone().sub(p);
    this.dropAnchor();
    return true;
  }

  get dragging(): boolean {
    return this.dragOffset !== null;
  }

  dragTo(ray: THREE.Ray): void {
    if (!this.dragOffset) return;
    const p = this.onPlateLevel(ray, this.v1);
    if (!p) return;
    this.plateRoot.position.x = p.x + this.dragOffset.x;
    this.plateRoot.position.z = p.z + this.dragOffset.z;
  }

  endDrag(): void {
    if (!this.dragOffset) return;
    this.dragOffset = null;
    this.wantAnchor = true;
  }

  rotate(degrees: number): void {
    this.plateRoot.rotateY(THREE.MathUtils.degToRad(degrees));
    this.dropAnchor();
    this.wantAnchor = true;
  }

  /**
   * Put the plate at a computed pose (the two-corner snap, register.ts) and anchor it there.
   * In a session a new anchor is made on the next frame; in the preview it is just moved.
   */
  setPose(pos: THREE.Vector3, yaw: number): void {
    this.dragOffset = null;
    this.plateRoot.position.copy(pos);
    this.plateRoot.quaternion.setFromAxisAngle(UP, yaw);
    this.plateRoot.visible = true;
    this.placed = true;
    this.dropAnchor();
    if (this.session) this.wantAnchor = true;
  }

  /** Desktop preview only: put the plate somewhere without a session. */
  placeAt(pos: THREE.Vector3, yaw = 0): void {
    this.plateRoot.position.copy(pos);
    this.plateRoot.quaternion.setFromAxisAngle(UP, yaw);
    this.plateRoot.visible = true;
    this.placed = true;
  }

  /** Forget the plate (and its saved anchor) and go back to placing. */
  reset(): void {
    this.dropAnchor();
    this.dragOffset = null;
    this.placed = false;
    this.plateRoot.visible = false;
    const old = storeGet();
    if (old && this.session?.deletePersistentAnchor) this.session.deletePersistentAnchor(old).catch(() => {});
    storeSet(null);
  }
}
