// Load a job's walkthrough.glb with the three.js loader and check it against manifest.json (CAD-06).
//
//   cd eval/glb_check && npm install
//   node check.mjs ../../jobs/knagglig [walkthrough.packed.glb]
//
// Checks: file size, triangle and mesh counts (PLAN §5.4 targets), one clip per step plus `walkthrough`,
// every node the manifest names exists, every animation track finds its node, and playing each step clip
// to its end moves at least one of the step's `moving` nodes and leaves no NaN, and every action happens at
// the time the manifest gives it (the piece is away from its target at t0 and on it at t1).
import { readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { MeshoptDecoder } from 'three/examples/jsm/libs/meshopt_decoder.module.js'

const TOL_M = 0.002
const LIMITS = { bytes: 5 * 1024 * 1024, triangles: 150_000, meshes: 100 }
const job = process.argv[2]
if (!job) { console.error('usage: node check.mjs <job_dir> [glb name]'); process.exit(2) }
const manifest = JSON.parse(readFileSync(join(job, 'manifest.json'), 'utf8'))
const glbPath = join(job, process.argv[3] ?? manifest.glb)
const buf = readFileSync(glbPath)
const loader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder)
const gltf = await loader.parseAsync(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '')

const problems = []
const fail = (m) => problems.push(m)
const byName = new Map()
let triangles = 0, meshes = 0
gltf.scene.traverse((o) => {
  byName.set(o.name, o)
  if (o.isMesh) {
    meshes += 1
    const g = o.geometry
    triangles += (g.index ? g.index.count : g.attributes.position.count) / 3
  }
})
// the loader replaces characters a track path cannot hold, so compare names the way it writes them
const has = (n) => byName.has(n) || byName.has(THREE.PropertyBinding.sanitizeNodeName(n))
const get = (n) => byName.get(n) ?? byName.get(THREE.PropertyBinding.sanitizeNodeName(n))

const bytes = statSync(glbPath).size
if (bytes > LIMITS.bytes) fail(`file is ${bytes} bytes, limit ${LIMITS.bytes}`)
if (triangles > LIMITS.triangles) fail(`${triangles} triangles, limit ${LIMITS.triangles}`)
if (meshes > LIMITS.meshes) fail(`${meshes} meshes (draw calls), limit ${LIMITS.meshes}`)

const named = [manifest.rootNode, manifest.markerNode,
  ...manifest.parts.flatMap((p) => p.nodes), ...manifest.hardware.flatMap((h) => h.nodes),
  ...manifest.steps.map((s) => s.camera?.node)].filter(Boolean)
const missing = named.filter((n) => !has(n))
if (missing.length) fail(`nodes named in the manifest but not in the GLB: ${missing.slice(0, 8).join(', ')}${missing.length > 8 ? ', …' : ''}`)

const clips = new Map(gltf.animations.map((c) => [c.name, c]))
const wanted = [manifest.walkthrough?.clip, ...manifest.steps.map((s) => s.clip)].filter(Boolean)
for (const name of wanted) if (!clips.has(name)) fail(`clip ${name} is missing`)
if (gltf.animations[0]?.name !== manifest.walkthrough?.clip) fail(`first clip is ${gltf.animations[0]?.name}, expected ${manifest.walkthrough?.clip}`)

let unbound = 0
for (const clip of gltf.animations)
  for (const t of clip.tracks)
    if (!THREE.PropertyBinding.findNode(gltf.scene, THREE.PropertyBinding.parseTrackName(t.name).nodeName)) unbound += 1
if (unbound) fail(`${unbound} animation tracks point to a node that does not exist`)

const mixer = new THREE.AnimationMixer(gltf.scene)
const snapshot = (nodes) => nodes.map((n) => { const o = get(n); o.updateWorldMatrix(true, false); return o.matrixWorld.clone() })
const steps = []
for (const s of manifest.steps) {
  const clip = clips.get(s.clip)
  if (!clip) continue
  const moving = s.moving.filter(has)
  mixer.stopAllAction()
  const action = mixer.clipAction(clip)
  action.setLoop(THREE.LoopOnce); action.clampWhenFinished = true
  action.play(); mixer.setTime(0)
  const before = snapshot(moving)
  mixer.setTime(Math.max(0, clip.duration - 1e-3))
  const after = snapshot(moving)
  const moved = after.filter((m, i) => !m.equals(before[i])).length
  const nan = after.some((m) => m.elements.some(Number.isNaN))
  if (moving.length && !moved) fail(`step ${s.n}: clip ${s.clip} moves none of its ${moving.length} moving nodes`)
  if (nan) fail(`step ${s.n}: NaN in a node matrix at the end of ${s.clip}`)
  // the clip may stop at its last key; the manifest's duration adds a short hold after it
  const lastEnd = Math.max(0, ...s.actions.map((a) => a.t1_s))
  if (clip.duration < lastEnd - 0.1 || clip.duration > s.duration_s + 0.1)
    fail(`step ${s.n}: clip lasts ${clip.duration.toFixed(2)} s, the manifest's actions end at ${lastEnd} s of ${s.duration_s} s`)
  // each action must happen when the manifest says: not at its target just before t0, at it at t1
  let late = 0, early = 0
  for (const a of s.actions) {
    if (!a.node || !a.target_m || !has(a.node)) continue
    const [x, y, z] = a.target_m
    const target = new THREE.Vector3(x, z, -y)                 // Blender is Z-up, the GLB is Y-up
    const o = get(a.node)
    mixer.setTime(Math.min(a.t1_s, clip.duration))
    if (o.position.distanceTo(target) > TOL_M) late += 1
    const moves = a.from_dir && (a.type === 'fasten' || s.moving.includes(a.node))
    if (moves && a.t1_s - a.t0_s > 0.5) {
      mixer.setTime(a.t0_s + 0.05)
      if (o.position.distanceTo(target) < TOL_M) early += 1
    }
  }
  if (late) fail(`step ${s.n}: ${late} pieces are not at their target when their action ends`)
  if (early) fail(`step ${s.n}: ${early} pieces are already at their target when their action starts`)
  steps.push({ n: s.n, clip: s.clip, duration_s: +clip.duration.toFixed(2), tracks: clip.tracks.length, moved: `${moved}/${moving.length}` })
}

console.log('RESULT ' + JSON.stringify({
  glb: glbPath, three: THREE.REVISION, bytes, triangles, meshes, nodes: byName.size,
  clips: gltf.animations.map((c) => c.name), steps, pass: problems.length === 0, problems }))
process.exit(problems.length ? 1 : 0)
