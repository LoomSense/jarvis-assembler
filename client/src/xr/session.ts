import * as THREE from 'three';
import { AG_XR } from './palette';

// PLAN §6.0. depth-sensing is left out on purpose: requesting it without a
// `depthSensing` init dictionary can make Quest Browser reject the whole session.
export const SESSION_INIT: XRSessionInit = {
  requiredFeatures: ['local-floor'],
  optionalFeatures: ['hand-tracking', 'plane-detection', 'anchors', 'hit-test'],
};

export async function arSupported(): Promise<boolean> {
  if (!navigator.xr) return false;
  try {
    return await navigator.xr.isSessionSupported('immersive-ar');
  } catch {
    return false;
  }
}

export async function startAR(renderer: THREE.WebGLRenderer): Promise<XRSession> {
  const session = await navigator.xr!.requestSession('immersive-ar', SESSION_INIT);
  renderer.xr.setReferenceSpaceType('local-floor');
  await renderer.xr.setSession(session);
  return session;
}

/** Features the browser actually granted (Quest Browser reports this; others may not). */
export function grantedFeatures(session: XRSession): string[] | null {
  return session.enabledFeatures ? [...session.enabledFeatures] : null;
}

interface PlaneEntry {
  group: THREE.Group;
  outline: THREE.LineLoop;
  hit: THREE.Mesh;
  changed: number;
}

/**
 * Faint outlines of detected horizontal planes (tables, floor), plus invisible meshes
 * that controller rays can hit when the hit-test feature is not granted.
 * Planes appear 2–3 s after the session starts.
 */
export class PlaneVisuals extends THREE.Group {
  private entries = new Map<XRPlane, PlaneEntry>();
  private outlineMat = new THREE.LineBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.3 });
  private hitMat = new THREE.MeshBasicMaterial({ visible: false, side: THREE.DoubleSide });
  readonly hitMeshes: THREE.Mesh[] = [];

  get count(): number {
    return this.entries.size;
  }

  update(frame: XRFrame, ref: XRReferenceSpace): void {
    const planes = frame.detectedPlanes;
    if (!planes) return;

    for (const [plane, entry] of this.entries) {
      if (!planes.has(plane)) this.drop(plane, entry);
    }

    for (const plane of planes) {
      if (plane.orientation !== 'horizontal') continue;
      let entry = this.entries.get(plane);
      if (entry && entry.changed !== plane.lastChangedTime) {
        this.drop(plane, entry);
        entry = undefined;
      }
      if (!entry) entry = this.build(plane);

      const pose = frame.getPose(plane.planeSpace, ref);
      entry.group.visible = !!pose;
      if (pose) {
        entry.group.matrix.fromArray(pose.transform.matrix);
        // Hide the floor outline (it is clutter), but keep it hittable.
        entry.outline.visible = pose.transform.position.y > 0.3;
      }
    }
  }

  private build(plane: XRPlane): PlaneEntry {
    const group = new THREE.Group();
    group.matrixAutoUpdate = false;

    const pts = plane.polygon.map((p) => new THREE.Vector3(p.x, 0.002, p.z));
    const outline = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(pts), this.outlineMat);

    // ShapeGeometry lies in XY; rotating -90° about X maps (x, -z) back to (x, 0, z).
    const shape = new THREE.Shape(plane.polygon.map((p) => new THREE.Vector2(p.x, -p.z)));
    const hit = new THREE.Mesh(new THREE.ShapeGeometry(shape), this.hitMat);
    hit.rotation.x = -Math.PI / 2;

    group.add(outline, hit);
    this.add(group);
    this.hitMeshes.push(hit);

    const entry = { group, outline, hit, changed: plane.lastChangedTime };
    this.entries.set(plane, entry);
    return entry;
  }

  private drop(plane: XRPlane, entry: PlaneEntry): void {
    entry.outline.geometry.dispose();
    entry.hit.geometry.dispose();
    this.remove(entry.group);
    this.hitMeshes.splice(this.hitMeshes.indexOf(entry.hit), 1);
    this.entries.delete(plane);
  }

  clearPlanes(): void {
    for (const [plane, entry] of this.entries) this.drop(plane, entry);
  }
}
