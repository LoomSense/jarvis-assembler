// What every surface is made of. The GLB carries flat colours only (no textures, no UVs) and names each material
// after the plan's `material` field (`pine`, later `oak`, `white laminate`, `steel`...) or `hw_<code>` for hardware.
// The page reads that name: wood gets a grain drawn in the shader, everything else keeps the GLB's own colour with
// a finish that suits it. The warmth of the picture comes from the lamp, never from the material.
import {
  Box3,
  BufferAttribute,
  Color,
  MathUtils,
  Matrix3,
  Matrix4,
  MeshStandardMaterial,
  Object3D,
  Vector3,
  type BufferGeometry,
  type Mesh,
} from 'three'
import { isMesh } from '../viewer/model'

export type Finish = 'wood' | 'coated' | 'metal' | 'plastic'

/** What the GLB says about a surface: the material's name and its three numbers. */
export interface Surface {
  name: string
  /** linear RGB */
  rgb: [number, number, number]
  roughness: number
  metalness: number
}

const WOODS = new Set(
  'pine oak birch beech ash walnut bamboo wood wooden plywood ply maple cherry teak acacia spruce fir cedar mahogany elm alder poplar larch eucalyptus veneer timber'.split(' '),
)
const METALS = new Set('steel metal metallic aluminium aluminum iron chrome brass zinc stainless copper nickel galvanised galvanized'.split(' '))
const PLASTICS = new Set('plastic abs nylon pvc polypropylene polyethylene polycarbonate acrylic rubber silicone'.split(' '))

/**
 * The finish a material name asks for. A name that means wood gets the grain. A plastic is soft. Metal is metal,
 * by name or because the GLB says so. Anything else is a painted or laminated board: smooth, with a slight sheen.
 */
export function finishOf(name: string, metalness: number): Finish {
  const tokens = name.toLowerCase().split(/[^a-z]+/).filter(Boolean)
  if (tokens.some((t) => WOODS.has(t) || t.endsWith('wood') || t.startsWith('wood'))) return 'wood'
  if (tokens.some((t) => PLASTICS.has(t))) return 'plastic'
  if (metalness >= 0.5 || tokens.some((t) => METALS.has(t))) return 'metal'
  return 'coated'
}

// Debug only: `?material=white` dresses the boards as another product would arrive, to check that the page does
// not lean on pine. The values are what a GLB would carry for that material.
const SAMPLES: Record<string, { name: string; hex: number; roughness: number; metalness: number }> = {
  white: { name: 'white laminate', hex: 0xf1efe9, roughness: 0.6, metalness: 0 },
  black: { name: 'black laminate', hex: 0x1b1b1d, roughness: 0.6, metalness: 0 },
  grey: { name: 'grey paint', hex: 0x8d9094, roughness: 0.6, metalness: 0 },
  oak: { name: 'oak', hex: 0xc9a06a, roughness: 0.65, metalness: 0 },
  walnut: { name: 'walnut', hex: 0x6b4a33, roughness: 0.65, metalness: 0 },
  steel: { name: 'steel', hex: 0xb8bcc2, roughness: 0.35, metalness: 1 },
  plastic: { name: 'plastic', hex: 0xd8d2c4, roughness: 0.6, metalness: 0 },
}

/** The surface `?material=<name>` stands for, or null when the name is not one of the samples. */
export function sampleSurface(name: string | null): Surface | null {
  const s = name ? SAMPLES[name.toLowerCase()] : undefined
  if (!s) return null
  const c = new Color(s.hex)
  return { name: s.name, rgb: [c.r, c.g, c.b], roughness: s.roughness, metalness: s.metalness }
}

function surfaceOf(material: MeshStandardMaterial): Surface {
  const kept = material.userData?.surface as Surface | undefined // a copy of a piece that was dressed before
  if (kept) return kept
  return {
    name: material.name ?? '',
    rgb: [material.color.r, material.color.g, material.color.b],
    roughness: material.roughness ?? 0.6,
    metalness: material.metalness ?? 0,
  }
}

// ------------------------------------------------------------------ wood

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
 * Panels come pre-assembled: one mesh holds several slats and the battens across them. Split the mesh into
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

let seedCounter = 1

/** Wood with a drawn grain that runs along each slat. The colour is the GLB's: pine, oak and walnut differ only there. */
function woodMaterial(geometry: BufferGeometry, s: Surface): MeshStandardMaterial {
  addGrainAttribute(geometry)
  const seed = (seedCounter++ * 0.618) % 1
  const mat = new MeshStandardMaterial({
    color: new Color().setRGB(...s.rgb),
    roughness: MathUtils.clamp(s.roughness - 0.05, 0.45, 0.8),
    metalness: 0,
  })
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
  mat.customProgramCacheKey = () => 'ag-wood'
  return mat
}

// ------------------------------------------------------------------ boards that are not wood

/**
 * A painted or laminated board, a metal part, a plastic one: the GLB's colour with a finish that suits it.
 * A dark board in a dark room is drawn by its edges, so the darker the colour, the more its faces pick up of the
 * cool light behind as they turn away from the viewer. A pale board gets none of it.
 */
function plainMaterial(s: Surface, finish: Exclude<Finish, 'wood'>): MeshStandardMaterial {
  const color = new Color().setRGB(...s.rgb)
  const luminance = 0.2126 * s.rgb[0] + 0.7152 * s.rgb[1] + 0.0722 * s.rgb[2]
  const dark = MathUtils.clamp(1 - luminance / 0.12, 0, 1)
  const mat = new MeshStandardMaterial({ color })
  if (finish === 'metal') {
    // fully metallic boards would only mirror a dark room: leave a little of the colour to the lamp
    mat.metalness = Math.min(0.85, Math.max(0.6, s.metalness))
    mat.roughness = MathUtils.clamp(s.roughness, 0.28, 0.6)
    mat.envMapIntensity = 1.25
  } else if (finish === 'plastic') {
    mat.metalness = 0
    mat.roughness = MathUtils.clamp(s.roughness, 0.45, 0.7)
    mat.envMapIntensity = 0.7
  } else {
    mat.metalness = 0
    mat.roughness = MathUtils.clamp(s.roughness * 0.62, 0.28, 0.5) // smooth, with a slight sheen
    mat.envMapIntensity = 1 + dark
  }
  if (finish !== 'metal' && luminance < 0.04) color.addScalar(0.04 - luminance) // no paint is blacker than this under a lamp
  if (dark > 0.01) {
    const rim = new Vector3(0.22, 0.26, 0.31).multiplyScalar(dark)
    mat.onBeforeCompile = (shader) => {
      shader.uniforms.uRim = { value: rim }
      shader.fragmentShader = shader.fragmentShader
        .replace('#include <common>', '#include <common>\nuniform vec3 uRim;')
        .replace(
          '#include <emissivemap_fragment>',
          `#include <emissivemap_fragment>
           totalEmissiveRadiance += uRim * pow(1.0 - saturate(dot(normal, normalize(vViewPosition))), 2.6);`,
        )
    }
    mat.customProgramCacheKey = () => 'ag-rim'
  }
  return mat
}

// ------------------------------------------------------------------ hardware

/** Where the head and the tip of a screw are in its own frame (from `model.fastener`). */
export interface ScrewShape {
  axis: Vector3
  head: Vector3
  length: number
  headRadius: number
}

/**
 * Plated steel. With `threaded` a thread is drawn on the shank; the mesh itself is a plain cylinder with a real
 * cross recess. Only hardware the manifest calls a screw is threaded.
 */
function hardwareMaterial(s: Surface, shape: ScrewShape, display: boolean, threaded: boolean): MeshStandardMaterial {
  const mat = new MeshStandardMaterial({
    color: new Color().setRGB(...s.rgb),
    roughness: display ? 0.36 : 0.32,
    metalness: display ? 0.5 : 0.62,
  })
  if (display) mat.envMapIntensity = 1.5 // in the tray a screw is a few pixels wide: let it catch more of the room
  if (!threaded) return mat

  const axis = shape.axis.clone().normalize()
  const u = new Vector3(1, 0, 0)
  if (Math.abs(axis.dot(u)) > 0.9) u.set(0, 0, 1)
  u.addScaledVector(axis, -axis.dot(u)).normalize()
  const v = new Vector3().crossVectors(axis, u)
  const basis = new Matrix3().set(u.x, u.y, u.z, axis.x, axis.y, axis.z, v.x, v.y, v.z) // rows: u, axis, v
  const long = shape.length > 0.045
  const threadStart = long ? shape.length * 0.42 : shape.length * 0.17
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
  mat.customProgramCacheKey = () => 'ag-thread'
  return mat
}

// ------------------------------------------------------------------ dressing a piece

export interface DressOptions {
  /** true for the copies in the tray: nothing casts a shadow there and metal is a few pixels wide */
  display: boolean
  /** for a piece of hardware: its head and axis, and whether the manifest's `kind` is `screw` */
  hardware?: { shape: ScrewShape; screw: boolean } | null
  /** debug: dress the boards as this surface instead of the one in the GLB */
  override?: Surface | null
}

/**
 * Give every mesh under `root` the material its GLB surface asks for, and return the finishes that were used.
 * The surface is kept on the new material, so a copy of the piece can be dressed again later.
 */
export function dress(root: Object3D, opts: DressOptions): Set<Finish> {
  const used = new Set<Finish>()
  root.traverse((o) => {
    if (!isMesh(o)) return
    const old = (Array.isArray(o.material) ? o.material[0] : o.material) as MeshStandardMaterial
    if (!old?.color) return // not a lit surface: leave it alone
    const surface = (!opts.hardware && opts.override) || surfaceOf(old)
    const finish = finishOf(surface.name, surface.metalness)
    let mat: MeshStandardMaterial
    if (finish === 'wood') mat = woodMaterial(o.geometry, surface)
    else if (opts.hardware && finish === 'metal') mat = hardwareMaterial(surface, opts.hardware.shape, opts.display, opts.hardware.screw)
    else mat = plainMaterial(surface, finish)
    mat.name = surface.name
    mat.userData.surface = surface
    used.add(finish)
    old.dispose()
    o.material = mat
    o.castShadow = !opts.display
    o.receiveShadow = !opts.display
  })
  return used
}

// ------------------------------------------------------------------ the packed GLB

/**
 * The packed GLB stores positions as 16-bit integers and normals as bytes, with the scale and offset that turn
 * them back into metres on a child node of every piece. The grain and the thread are drawn from the mesh's own
 * coordinates, so those have to be metres: write the transform into the vertices once and clear it from the node.
 * Meshes in `keep` (nodes the manifest names, which the clips move) are left alone. Returns how many meshes changed.
 */
export function bakeQuantised(root: Object3D, keep: Set<Object3D>): number {
  const users = new Map<BufferGeometry, Mesh[]>()
  root.traverse((o) => {
    if (!isMesh(o) || keep.has(o)) return
    if (o.geometry.getAttribute('position').array instanceof Float32Array) return
    const list = users.get(o.geometry)
    if (list) list.push(o)
    else users.set(o.geometry, [o])
  })
  let changed = 0
  for (const [geometry, meshes] of users) {
    for (const m of meshes) m.updateMatrix()
    const same = meshes.every((m) => m.matrix.equals(meshes[0].matrix))
    if (same) bake(geometry, meshes[0].matrix)
    for (const m of meshes) {
      if (!same) {
        m.geometry = geometry.clone()
        bake(m.geometry, m.matrix)
      }
      m.position.set(0, 0, 0)
      m.quaternion.identity()
      m.scale.set(1, 1, 1)
      m.updateMatrix()
      changed++
    }
  }
  return changed
}

function bake(geometry: BufferGeometry, matrix: Matrix4): void {
  const v = new Vector3()
  const pos = geometry.getAttribute('position')
  const p = new Float32Array(pos.count * 3)
  for (let i = 0; i < pos.count; i++) v.fromBufferAttribute(pos, i).applyMatrix4(matrix).toArray(p, i * 3)
  geometry.setAttribute('position', new BufferAttribute(p, 3))
  const nor = geometry.getAttribute('normal')
  if (nor) {
    const normalMatrix = new Matrix3().getNormalMatrix(matrix)
    const n = new Float32Array(nor.count * 3)
    for (let i = 0; i < nor.count; i++) v.fromBufferAttribute(nor, i).applyMatrix3(normalMatrix).normalize().toArray(n, i * 3)
    geometry.setAttribute('normal', new BufferAttribute(n, 3))
  }
  geometry.computeBoundingBox()
  geometry.computeBoundingSphere()
}
