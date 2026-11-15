// Materials for the workshop mockup. The GLB carries flat colours only (no textures, no UVs), so the grain of
// the pine and the thread of the screws are drawn in the fragment shader from each mesh's own coordinates.
import { Box3, BufferAttribute, Color, Matrix3, Mesh, MeshStandardMaterial, Object3D, Vector3, type BufferGeometry } from 'three'

const isMesh = (o: Object3D): o is Mesh => (o as Mesh).isMesh === true

const NOISE = /* glsl */ `
float wsHash(vec3 p) {
  p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
  p *= 17.0;
  return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float wsNoise(vec3 x) {
  vec3 i = floor(x);
  vec3 f = fract(x);
  f = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(mix(wsHash(i), wsHash(i + vec3(1, 0, 0)), f.x), mix(wsHash(i + vec3(0, 1, 0)), wsHash(i + vec3(1, 1, 0)), f.x), f.y),
    mix(mix(wsHash(i + vec3(0, 0, 1)), wsHash(i + vec3(1, 0, 1)), f.x), mix(wsHash(i + vec3(0, 1, 1)), wsHash(i + vec3(1, 1, 1)), f.x), f.y),
    f.z);
}
`

const GRAIN = /* glsl */ `
// p.x runs along the board, p.y and p.z across it (metres)
float wsGrain(vec3 p, float seed) {
  vec3 s = vec3(seed * 7.31, seed * 3.17, seed * 5.71);
  float warp = wsNoise(vec3(p.x * 2.2, p.y * 10.0, p.z * 10.0) + s);
  float warp2 = wsNoise(vec3(p.x * 9.0, p.y * 36.0, p.z * 36.0) + s.zxy);
  float rings = p.y * 110.0 + p.z * 70.0 + warp * 6.0 + warp2 * 1.2 + seed * 9.0;
  float late = smoothstep(0.5, 0.99, 0.5 + 0.5 * sin(rings * 6.2831853));    // the dark late-wood line of each ring
  late = mix(late, 0.25, smoothstep(0.2, 0.8, fwidth(rings)));               // fade before it aliases
  float fy = p.y * 1300.0;
  float fibre = wsNoise(vec3(p.x * 55.0, fy, p.z * 1300.0) + s);
  fibre = mix(fibre, 0.5, smoothstep(0.35, 1.2, fwidth(fy)));
  return clamp(1.0 - late * 0.62 - (1.0 - fibre) * 0.2, 0.0, 1.0);
}
`

/**
 * The panels come pre-assembled: one mesh holds several slats and the battens across them. Split the mesh into
 * its solids (triangles joined through shared edges) and give every vertex the long axis of its own solid and a
 * number of its own, so each slat and each batten has its grain along its length and no two look the same.
 */
function addGrainAttribute(geometry: BufferGeometry): void {
  if (geometry.getAttribute('aWood')) return
  const pos = geometry.getAttribute('position')
  const index = geometry.getIndex()
  const n = pos.count
  const ids = new Int32Array(n)
  const byPlace = new Map<string, number>()
  for (let i = 0; i < n; i++) {
    const key = `${Math.round(pos.getX(i) * 1e5)},${Math.round(pos.getY(i) * 1e5)},${Math.round(pos.getZ(i) * 1e5)}`
    let id = byPlace.get(key)
    if (id === undefined) byPlace.set(key, (id = byPlace.size))
    ids[i] = id
  }
  const places = byPlace.size
  const tris = Math.floor((index ? index.count : n) / 3)
  const vi = (t: number, c: number) => (index ? index.getX(t * 3 + c) : t * 3 + c)
  const parent = new Int32Array(tris)
  for (let t = 0; t < tris; t++) parent[t] = t
  const find = (a: number) => {
    while (parent[a] !== a) a = parent[a] = parent[parent[a]]
    return a
  }
  const edges = new Map<number, number>()
  for (let t = 0; t < tris; t++) {
    for (let c = 0; c < 3; c++) {
      const a = ids[vi(t, c)]
      const b = ids[vi(t, (c + 1) % 3)]
      const key = Math.min(a, b) * places + Math.max(a, b)
      const other = edges.get(key)
      if (other === undefined) edges.set(key, t)
      else parent[find(t)] = find(other)
    }
  }
  const boxes = new Map<number, Box3>()
  const v = new Vector3()
  for (let t = 0; t < tris; t++) {
    const root = find(t)
    let box = boxes.get(root)
    if (!box) boxes.set(root, (box = new Box3()))
    for (let c = 0; c < 3; c++) box.expandByPoint(v.fromBufferAttribute(pos, vi(t, c)))
  }
  const solid = new Map<number, [number, number]>()
  for (const [root, box] of boxes) {
    const size = box.getSize(v).toArray()
    solid.set(root, [size.indexOf(Math.max(...size)), (solid.size * 0.37) % 1])
  }
  const data = new Float32Array(n * 2)
  for (let t = 0; t < tris; t++) {
    const [axis, seed] = solid.get(find(t))!
    for (let c = 0; c < 3; c++) {
      data[vi(t, c) * 2] = axis
      data[vi(t, c) * 2 + 1] = seed
    }
  }
  geometry.setAttribute('aWood', new BufferAttribute(data, 2))
}

/** Pine with a drawn grain that runs along each slat. */
export function woodMaterial(geometry: BufferGeometry, base: Color, seed: number): MeshStandardMaterial {
  addGrainAttribute(geometry)
  const mat = new MeshStandardMaterial({ color: base.clone(), roughness: 0.6, metalness: 0 })
  mat.name = 'pine'
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uWoodSeed = { value: seed }
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nattribute vec2 aWood;\nuniform float uWoodSeed;\nvarying vec3 vWood;\nvarying float vWoodSeed;')
      .replace(
        '#include <begin_vertex>',
        '#include <begin_vertex>\nvWood = aWood.x < 0.5 ? position.xyz : (aWood.x < 1.5 ? position.yzx : position.zxy);\nvWoodSeed = aWood.y + uWoodSeed;',
      )
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', `#include <common>\nvarying vec3 vWood;\nvarying float vWoodSeed;\n${NOISE}${GRAIN}`)
      .replace(
        '#include <color_fragment>',
        `#include <color_fragment>
         float wsG = wsGrain(vWood, vWoodSeed);
         diffuseColor.rgb *= mix(vec3(0.74, 0.62, 0.50), vec3(1.05, 1.03, 1.0), wsG);`,
      )
      .replace(
        '#include <roughnessmap_fragment>',
        `#include <roughnessmap_fragment>
         roughnessFactor = clamp(roughnessFactor * mix(1.14, 0.9, wsG), 0.05, 1.0);`,
      )
  }
  mat.customProgramCacheKey = () => 'ws-wood'
  return mat
}

/** Where the head and the tip of a screw are in its own frame (from `model.fastener`). */
export interface ScrewShape {
  axis: Vector3
  head: Vector3
  length: number
  headRadius: number
}

/** Plated steel with a thread drawn on the shank. The mesh itself is a plain cylinder with a real cross recess. */
export function screwMaterial(base: Color, shape: ScrewShape, name: string, display = false): MeshStandardMaterial {
  const axis = shape.axis.clone().normalize()
  const u = new Vector3(1, 0, 0)
  if (Math.abs(axis.dot(u)) > 0.9) u.set(0, 0, 1)
  u.addScaledVector(axis, -axis.dot(u)).normalize()
  const v = new Vector3().crossVectors(axis, u)
  const basis = new Matrix3().set(u.x, u.y, u.z, axis.x, axis.y, axis.z, v.x, v.y, v.z) // rows: u, axis, v
  const long = shape.length > 0.045
  const threadStart = long ? shape.length * 0.42 : shape.length * 0.17

  const mat = new MeshStandardMaterial({ color: base.clone(), roughness: display ? 0.36 : 0.32, metalness: display ? 0.5 : 0.62 })
  mat.name = name
  if (display) mat.envMapIntensity = 1.5 // in the tray a screw is a few pixels wide: let it catch more of the room
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uScrewBasis = { value: basis }
    shader.uniforms.uScrewHead = { value: shape.head.clone() }
    shader.uniforms.uScrewAxis = { value: axis }
    shader.uniforms.uThread = { value: new Vector3(threadStart, long ? 0.0019 : 0.0016, shape.headRadius * 0.72) }
    shader.vertexShader = shader.vertexShader
      .replace(
        '#include <common>',
        '#include <common>\nuniform mat3 uScrewBasis;\nuniform vec3 uScrewHead;\nuniform vec3 uScrewAxis;\nvarying vec3 vScrew;\nvarying vec3 vScrewAxisView;',
      )
      .replace(
        '#include <begin_vertex>',
        '#include <begin_vertex>\nvScrew = uScrewBasis * (position - uScrewHead);\nvScrewAxisView = normalize(normalMatrix * uScrewAxis);',
      )
    shader.fragmentShader = shader.fragmentShader
      .replace(
        '#include <common>',
        `#include <common>
         varying vec3 vScrew;
         varying vec3 vScrewAxisView;
         uniform vec3 uThread; // start along the axis, pitch, largest radius that carries thread
         float wsThreadMask() {
           float r = length(vScrew.xz);
           return smoothstep(uThread.x, uThread.x + 0.0012, vScrew.y) * (1.0 - smoothstep(uThread.z, uThread.z * 1.25, r));
         }
         float wsThreadPhase() { return vScrew.y / uThread.y * 6.2831853 + atan(vScrew.z, vScrew.x); }`,
      )
      .replace(
        '#include <color_fragment>',
        `#include <color_fragment>
         float wsTm = wsThreadMask();
         float wsPh = wsThreadPhase();
         float wsFade = 1.0 - smoothstep(0.6, 2.2, fwidth(vScrew.y / uThread.y * 6.2831853));
         wsTm *= wsFade;
         diffuseColor.rgb *= mix(1.0, mix(0.38, 1.0, smoothstep(-0.7, 0.6, sin(wsPh))), wsTm);`,
      )
      .replace(
        '#include <normal_fragment_maps>',
        `#include <normal_fragment_maps>
         normal = normalize(normal + wsTm * 0.7 * cos(wsPh) * vScrewAxisView);`,
      )
  }
  mat.customProgramCacheKey = () => 'ws-screw'
  return mat
}

let seedCounter = 1

/**
 * Give every mesh under `root` the workshop material that matches the GLB material it came with
 * (`pine` for the boards, `hw_<code>` for the screws). The hole ring keeps its own material.
 * `shapeOf` returns the head and axis of a screw node; it is asked once per screw mesh.
 */
export function dress(root: Object3D, shapeOf: (name: string) => ScrewShape | null, shadows: boolean): void {
  root.traverse((o) => {
    if (!isMesh(o)) return
    const old = o.material as MeshStandardMaterial
    const name = old.name ?? ''
    if (name === 'pine') {
      o.material = woodMaterial(o.geometry, old.color, (seedCounter++ * 0.618) % 1)
    } else if (name.startsWith('hw_')) {
      const shape = shapeOf(name.slice(3))
      if (shape) o.material = screwMaterial(old.color, shape, name, !shadows)
    } else {
      o.castShadow = false
      o.receiveShadow = false
      return
    }
    old.dispose()
    o.castShadow = shadows
    o.receiveShadow = shadows
  })
}
