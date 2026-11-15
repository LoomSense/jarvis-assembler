import {
  AnimationClip,
  Box3,
  Material,
  Matrix4,
  Mesh,
  MeshStandardMaterial,
  Object3D,
  PropertyBinding,
  Vector3,
  type Group,
} from 'three'
import { GLTFLoader, type GLTF } from 'three/addons/loaders/GLTFLoader.js'
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js'
import type { Manifest } from '../types'

/** Where a screw's head and tip are in the screw node's own frame, read from its vertices. */
export interface FastenerShape {
  /** unit vector from the head to the tip (the direction it is driven) */
  axis: Vector3
  /** centre of the outer face of the head */
  head: Vector3
  tip: Vector3
  /** overall length in metres, head included */
  length: number
  headRadius: number
}

export interface Model {
  gltf: GLTF
  /** the glTF scene, added to the viewer scene */
  scene: Group
  /** the node that turns the whole assembly (`manifest.rootNode`) */
  root: Object3D
  /** the ring shown at the hole being screwed (`manifest.markerNode`), if the GLB has one */
  marker: Object3D | null
  clips: Map<string, AnimationClip>
  /** a node by the name the manifest uses */
  node(name: string | null | undefined): Object3D | undefined
  /** head, tip and axis of a screw node, cached */
  fastener(node: Object3D): FastenerShape
}

export const isMesh = (o: Object3D): o is Mesh => (o as Mesh).isMesh === true

export function loadGltf(url: string, onProgress?: (fraction: number | null) => void): Promise<GLTF> {
  const loader = new GLTFLoader()
  loader.setMeshoptDecoder(MeshoptDecoder) // the packed GLB is meshopt-compressed
  return new Promise((resolve, reject) => {
    loader.load(
      url,
      resolve,
      (e) => onProgress?.(e.lengthComputable && e.total > 0 ? e.loaded / e.total : null),
      (err) => reject(err instanceof Error ? err : new Error(`could not load ${url}`)),
    )
  })
}

/** Every vertex under `node`, in the node's own frame (independent of the node's scale and of any quantisation child). */
function localPoints(node: Object3D): Vector3[] {
  node.updateWorldMatrix(true, true)
  const inverse = new Matrix4().copy(node.matrixWorld).invert()
  const points: Vector3[] = []
  const m = new Matrix4()
  node.traverse((o) => {
    if (!isMesh(o)) return
    const pos = o.geometry.getAttribute('position')
    m.multiplyMatrices(inverse, o.matrixWorld)
    for (let i = 0; i < pos.count; i++) points.push(new Vector3().fromBufferAttribute(pos, i).applyMatrix4(m))
  })
  return points
}

/** Bounding box of a node in its own frame. */
export function localBox(node: Object3D): Box3 {
  return new Box3().setFromPoints(localPoints(node))
}

function analyseFastener(node: Object3D): FastenerShape {
  const pts = localPoints(node)
  const box = new Box3().setFromPoints(pts)
  const size = box.getSize(new Vector3())
  const centre = box.getCenter(new Vector3())
  const dims = size.toArray()
  const k = dims.indexOf(Math.max(...dims)) // the long axis
  const lo = box.min.getComponent(k)
  const hi = box.max.getComponent(k)
  const band = (hi - lo) * 0.15
  let rLo = 0
  let rHi = 0
  for (const p of pts) {
    const d = p.clone().sub(centre).setComponent(k, 0).length()
    const a = p.getComponent(k)
    if (a <= lo + band) rLo = Math.max(rLo, d)
    if (a >= hi - band) rHi = Math.max(rHi, d)
  }
  const headAtLo = rLo >= rHi // the wide end is the head
  const head = centre.clone().setComponent(k, headAtLo ? lo : hi)
  const tip = centre.clone().setComponent(k, headAtLo ? hi : lo)
  return { axis: tip.clone().sub(head).normalize(), head, tip, length: hi - lo, headRadius: Math.max(rLo, rHi) }
}

/**
 * Index the loaded GLB by the manifest's names and make it ready for step playback.
 * Throws when a node or clip the manifest names is missing, so the caller can fall back to the other GLB.
 */
export function prepareModel(gltf: GLTF, manifest: Manifest): Model {
  const byName = new Map<string, Object3D>()
  gltf.scene.traverse((o) => {
    if (o.name && !byName.has(o.name)) byName.set(o.name, o)
    const original = o.userData?.name as string | undefined // the loader keeps the unsanitised name here
    if (original && !byName.has(original)) byName.set(original, o)
  })
  const node = (name: string | null | undefined) =>
    name ? (byName.get(name) ?? byName.get(PropertyBinding.sanitizeNodeName(name))) : undefined

  const clips = new Map(gltf.animations.map((c) => [c.name, c]))
  const named = [
    manifest.rootNode ?? 'assembly',
    ...manifest.parts.flatMap((p) => p.nodes),
    ...manifest.hardware.flatMap((h) => h.nodes),
  ]
  const missingNodes = named.filter((n) => !node(n))
  const missingClips = manifest.steps.map((s) => s.clip).filter((c): c is string => !!c && !clips.has(c))
  if (missingNodes.length || missingClips.length) {
    throw new Error(`GLB does not match the manifest: missing ${[...missingNodes, ...missingClips].slice(0, 6).join(', ')}`)
  }

  // One material per mesh, so a single part can glow or fade without touching its look-alikes.
  gltf.scene.traverse((o) => {
    if (!isMesh(o)) return
    o.material = Array.isArray(o.material) ? o.material.map((m) => m.clone()) : (o.material as Material).clone()
    o.frustumCulled = true
  })

  // Parts that are not placed yet sit in the GLB at scale 0.0001 (glTF cannot hide a node, PLAN §4.3), and the
  // step clips carry no scale tracks. The viewer therefore gives every part and screw its real scale once
  // and shows or hides it with `visible`.
  for (const name of named.slice(1)) node(name)!.scale.set(1, 1, 1)

  const shapes = new Map<Object3D, FastenerShape>()
  return {
    gltf,
    scene: gltf.scene,
    root: node(manifest.rootNode ?? 'assembly')!,
    marker: node(manifest.markerNode ?? 'marker') ?? null,
    clips,
    node,
    fastener(n) {
      let s = shapes.get(n)
      if (!s) shapes.set(n, (s = analyseFastener(n)))
      return s
    },
  }
}

/** A stand-alone copy of a part or screw for a close-up: at the origin, real scale, its own materials. */
export function cloneForDisplay(node: Object3D): Object3D {
  const copy = node.clone(true)
  copy.position.set(0, 0, 0)
  copy.quaternion.identity()
  copy.scale.set(1, 1, 1)
  copy.visible = true
  copy.traverse((o) => {
    if (!isMesh(o)) return
    const m = (o.material as Material).clone()
    if (m instanceof MeshStandardMaterial) {
      m.emissive.setRGB(0, 0, 0) // the source may be glowing or faded at this moment
      m.emissiveIntensity = 1
    }
    m.opacity = 1
    m.transparent = false
    m.depthWrite = true
    o.material = m
  })
  return copy
}

/** Release the materials a display copy owns. Geometry is shared with the model and stays. */
export function disposeDisplayCopy(copy: Object3D): void {
  copy.traverse((o) => {
    if (isMesh(o)) (o.material as Material).dispose()
  })
}
