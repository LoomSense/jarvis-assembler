import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import type { DemoPart, DemoProduct, DemoStep, Vec3 } from './demoAssembly';

// Loads a job made by the 3D pipeline (pipeline/README.md, PLAN §4.3): manifest.json plus
// walkthrough(.packed).glb, served at /files/<job>/ (the server's route; the dev server
// mirrors it). Each `place` / `fasten` action becomes one part the headset guides, with the
// part's real CadQuery shape; each `turn` becomes a turn action with the step's pose.
//
// In the GLB the parts rest hidden (scale 1e-4); their final places exist only at the end
// of the `walkthrough` clip, so the clip is run to its end before the nodes are read.
// Manifest points and directions (`target_m`, `from_dir`, `assemblyRotationDeg`) are in
// Blender's Z-up frame; the GLB is Y-up: (x, y, z)_blender = (x, -z, y)_gltf.

interface ManifestAction {
  type: 'place' | 'fasten' | 'turn';
  text: { en: string; el: string };
  node?: string;
  partId?: string;
  code?: string;
  from_dir?: Vec3;
  rotation_deg?: Vec3;
}

interface Manifest {
  title: string;
  glb: string;
  glbPacked?: string;
  rootNode: string;
  overall_mm: { L: number; W: number; H: number };
  walkthrough: { clip: string };
  parts: { id: string; short: string; name: string }[];
  hardware: { code: string; short: string; color: [number, number, number] }[];
  steps: {
    n: number;
    title: string;
    assemblyRotationDeg: Vec3;
    hardware: { code: string; qty: number }[];
    cite: { page: number };
    crop?: string;
    actions: ManifestAction[];
  }[];
}

const PUSH_M = 0.12; // how far a board's insertion loop starts from its place
const SCREW_M = 0.09;

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const toGltf = ([x, y, z]: Vec3): THREE.Vector3 => new THREE.Vector3(x, z, -y);
const hex = ([r, g, b]: [number, number, number]) => `#${new THREE.Color(r, g, b).getHexString()}`;

export async function loadJob(id: string, key?: string): Promise<DemoProduct> {
  const base = `/files/${encodeURIComponent(id)}/`;
  const q = key ? `?key=${encodeURIComponent(key)}` : '';
  const res = await fetch(`${base}manifest.json${q}`);
  if (!res.ok) throw new Error(`No manifest for job "${id}" (${res.status})`);
  const m = (await res.json()) as Manifest;

  const loader = new GLTFLoader();
  loader.setMeshoptDecoder(MeshoptDecoder);
  const gltf = await loader.loadAsync(`${base}${m.glbPacked ?? m.glb}${q}`);
  const root = gltf.scene.getObjectByName(m.rootNode);
  if (!root) throw new Error(`GLB has no "${m.rootNode}" node`);

  // Run the whole walkthrough to its last frame: every node is now where it ends up.
  const clip = gltf.animations.find((a) => a.name === m.walkthrough.clip);
  if (clip) {
    const mixer = new THREE.AnimationMixer(gltf.scene);
    const action = mixer.clipAction(clip);
    action.clampWhenFinished = true;
    action.setLoop(THREE.LoopOnce, 1);
    action.play();
    mixer.setTime(clip.duration);
  }
  gltf.scene.updateMatrixWorld(true);
  // Parts are read in the assembly's own frame (its turns are applied per step, not here).
  const toRoot = root.matrixWorld.clone().invert();

  const partNames = new Map(m.parts.map((p) => [p.id, cap(p.short)]));
  const hwColor = new Map(m.hardware.map((h) => [h.code, hex(h.color)]));

  function partFrom(a: ManifestAction): DemoPart | null {
    const node = a.node ? root!.getObjectByName(a.node) : undefined;
    if (!node) return null;
    const geos: THREE.BufferGeometry[] = [];
    node.traverse((o) => {
      const mesh = o as THREE.Mesh;
      if (!mesh.isMesh) return;
      const g = mesh.geometry.clone();
      for (const k of Object.keys(g.attributes)) if (k !== 'position' && k !== 'normal') g.deleteAttribute(k);
      g.applyMatrix4(new THREE.Matrix4().multiplyMatrices(toRoot, mesh.matrixWorld));
      geos.push(g.index ? g.toNonIndexed() : g);
    });
    if (!geos.length) return null;
    const geometry = geos.length === 1 ? geos[0] : (mergeGeometries(geos) ?? geos[0]);
    geometry.computeBoundingBox();
    const box = geometry.boundingBox!;
    const c = box.getCenter(new THREE.Vector3());
    const size = box.getSize(new THREE.Vector3());
    geometry.translate(-c.x, -c.y, -c.z);
    geometry.computeVertexNormals();

    const screw = a.type === 'fasten';
    const u = a.from_dir ? toGltf(a.from_dir).normalize() : new THREE.Vector3(0, 1, 0);
    const along = Math.abs(u.x) * size.x + Math.abs(u.y) * size.y + Math.abs(u.z) * size.z;
    const axis: 'x' | 'y' | 'z' = Math.abs(u.x) > 0.7 ? 'x' : Math.abs(u.z) > 0.7 ? 'z' : 'y';
    return {
      id: a.node!,
      name: screw ? (a.code ?? a.partId ?? 'screw') : (partNames.get(a.partId ?? '') ?? a.partId ?? a.node!),
      kind: screw ? 'screw' : 'board',
      // Screws: [diameter, length along the drive axis]; boards: the box size.
      size: screw ? [Math.max(...[size.x, size.y, size.z].filter((_, i) => Math.abs(u.getComponent(i)) < 0.7)), along, 0] : [size.x, size.y, size.z],
      axis: screw ? axis : undefined,
      pos: [c.x, c.y, c.z],
      insertFrom: u.clone().multiplyScalar(screw ? SCREW_M : PUSH_M).toArray() as Vec3,
      motion: screw ? 'twist' : 'push',
      geometry,
      half: [size.x / 2, size.y / 2, size.z / 2],
      text: a.text,
      color: screw && a.code ? hwColor.get(a.code) : undefined,
    };
  }

  const steps: DemoStep[] = m.steps.map((s) => {
    const turn = s.actions.find((a) => a.type === 'turn');
    const parts = s.actions.filter((a) => a.type !== 'turn').map(partFrom).filter((p): p is DemoPart => !!p);
    const hw = s.hardware[0];
    return {
      title: s.title,
      instruction: s.actions.find((a) => a.type !== 'turn')?.text.en ?? turn?.text.en ?? '',
      page: s.cite.page,
      crop: s.crop ? `${base}${s.crop}${q}` : '',
      hardware: hw ? { code: hw.code, qty: hw.qty } : undefined,
      parts,
      pose: s.assemblyRotationDeg,
      turn: turn?.text,
    };
  });

  return {
    id,
    name: m.title,
    nameEl: m.title,
    steps,
    footprint: { x: m.overall_mm.L / 1000, z: m.overall_mm.W / 1000 },
    finish: '#d8b88a',
  };
}
