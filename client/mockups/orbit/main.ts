// Planet and orbit: the assembly is a small planet, the pieces still in the box hang in its sky,
// and each one falls from the sky and locks on when its turn comes.
//
// Two scenes share one canvas. `space` holds the planet (the real model, posed by StepPlayer), its orbit
// lines, the stars and the falling piece. `hud` is drawn over it in CSS pixels: the pieces in the sky and
// the crisp piece between the two buttons. The loop never stops and the orbit controls are never disabled.

import {
  AdditiveBlending,
  BoxGeometry,
  BufferAttribute,
  BufferGeometry,
  CanvasTexture,
  CircleGeometry,
  Color,
  CylinderGeometry,
  DirectionalLight,
  DoubleSide,
  Euler,
  Fog,
  Group,
  HemisphereLight,
  MathUtils,
  Matrix4,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  NeutralToneMapping,
  Object3D,
  OrthographicCamera,
  PerspectiveCamera,
  PlaneGeometry,
  PMREMGenerator,
  Points,
  Quaternion,
  Scene,
  ShaderMaterial,
  SphereGeometry,
  Spherical,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
} from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js'
import { Line2 } from 'three/addons/lines/Line2.js'
import { LineGeometry } from 'three/addons/lines/LineGeometry.js'
import { LineMaterial } from 'three/addons/lines/LineMaterial.js'
import { fetchManifest, fileUrl } from '/src/api.ts'
import { displayName } from '/src/i18n.ts'
import { cloneForDisplay, isMesh, loadGltf, localBox, prepareModel, type FastenerShape } from '/src/viewer/model.ts'
import { StepPlayer, type ActionInfo } from '/src/viewer/steps.ts'

const JOB = 'local-fresh'
const lang: 'el' | 'en' = new URLSearchParams(location.search).get('lang') === 'en' ? 'en' : 'el'
const AZURE = 0x9ecfff
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
const still = () => reduced.matches

// The only sentences on screen. A turn of the whole assembly is said in two or three words.
const WORDS = {
  el: {
    upside_down: 'Γύρισέ το ανάποδα',
    on_end_a: 'Στήσε το όρθιο',
    on_end_b: 'Στην άλλη άκρη',
    upright: 'Γύρισέ το κανονικά',
    turn: 'Γύρισέ το',
    finish: 'Γύρισέ το κανονικά',
    done: 'Έτοιμο',
    failed: 'Το μοντέλο δεν φορτώθηκε',
    prev: 'Προηγούμενο',
    next: 'Επόμενο',
    left: 'μένουν',
  },
  en: {
    upside_down: 'Turn it over',
    on_end_a: 'Stand it on end',
    on_end_b: 'Onto the other end',
    upright: 'Turn it upright',
    turn: 'Turn it',
    finish: 'Turn it upright',
    done: 'Done',
    failed: 'The model did not load',
    prev: 'Previous',
    next: 'Next',
    left: 'left',
  },
}[lang]

const el = <T extends Element = HTMLElement>(id: string) => document.getElementById(id) as unknown as T
const sayEl = el('say')
const pieceEl = el('piece')
const prevEl = el<HTMLButtonElement>('prev')
const nextEl = el<HTMLButtonElement>('next')
const turnGlyph = el<SVGElement>('turn')
const doneGlyph = el<SVGElement>('done')
const skyEl = el('sky')
document.documentElement.lang = lang
prevEl.setAttribute('aria-label', WORDS.prev)
nextEl.setAttribute('aria-label', WORDS.next)

const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)
const easeOut = (t: number) => 1 - Math.pow(1 - t, 3)

// ---------------------------------------------------------------- renderer, cameras, controls

const canvas = el<HTMLCanvasElement>('space')
const renderer = new WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' })
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 3)) // full device resolution: the crisp piece must be crisp
renderer.outputColorSpace = SRGBColorSpace
renderer.toneMapping = NeutralToneMapping
renderer.autoClear = false
renderer.setClearColor(0x03050a, 1)

const space = new Scene()
const hud = new Scene()
const camera = new PerspectiveCamera(30, 1, 0.05, 140)
const hudCam = new OrthographicCamera(-1, 1, 1, -1, 1, 4000)
hudCam.position.z = 2000
space.add(camera) // the key and rim lights ride on the camera

const pmrem = new PMREMGenerator(renderer)
const envMap = pmrem.fromScene(new RoomEnvironment(), 0.04).texture
pmrem.dispose()
space.environment = envMap
space.environmentIntensity = 0.3
hud.environment = envMap
hud.environmentIntensity = 1.0

const controls = new OrbitControls(camera, canvas)
controls.enableDamping = true
controls.dampingFactor = 0.08
controls.rotateSpeed = 0.9
controls.enablePan = false
controls.minDistance = 0.4
controls.maxDistance = 7
controls.minPolarAngle = 0.05
controls.maxPolarAngle = Math.PI - 0.05
controls.autoRotateSpeed = 0.7

let W = 1
let H = 1
let phone = false

// ---------------------------------------------------------------- space: background, stars, lights

const dpr = () => renderer.getPixelRatio()

// One fullscreen triangle: black with a little blue in it, and a wide cold glow behind the planet.
const backdrop = new Mesh(
  new BufferGeometry().setAttribute('position', new BufferAttribute(new Float32Array([-1, -1, 0, 3, -1, 0, -1, 3, 0]), 3)),
  new ShaderMaterial({
    depthTest: false,
    depthWrite: false,
    toneMapped: false,
    uniforms: { uC: { value: [0, 0] }, uR: { value: 300 }, uDpr: { value: 1 }, uPulse: { value: 0 } },
    vertexShader: 'void main(){ gl_Position = vec4(position.xy, 1.0, 1.0); }',
    fragmentShader: `
      uniform vec2 uC; uniform float uR; uniform float uDpr; uniform float uPulse;
      void main(){
        vec2 p = gl_FragCoord.xy / uDpr;
        float d = length(p - uC) / uR;
        vec3 col = vec3(0.012, 0.018, 0.034);
        col += vec3(0.040, 0.085, 0.160) * exp(-d * d * 0.42) * (1.0 + uPulse);
        float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
        col += (n - 0.5) / 160.0;
        gl_FragColor = vec4(col, 1.0);
      }`,
  }),
)
backdrop.frustumCulled = false
backdrop.renderOrder = -100
space.add(backdrop)

function makeStars(): Points {
  const n = 2600
  const pos = new Float32Array(n * 3)
  const size = new Float32Array(n)
  const alpha = new Float32Array(n)
  let seed = 7
  const rnd = () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646
  for (let i = 0; i < n; i++) {
    const u = rnd() * 2 - 1
    const a = rnd() * Math.PI * 2
    const r = Math.sqrt(1 - u * u)
    pos.set([r * Math.cos(a) * 60, u * 60, r * Math.sin(a) * 60], i * 3)
    const big = rnd()
    size[i] = 1 + big * big * big * 2.2
    alpha[i] = 0.18 + rnd() * 0.5
  }
  const g = new BufferGeometry()
  g.setAttribute('position', new BufferAttribute(pos, 3))
  g.setAttribute('aSize', new BufferAttribute(size, 1))
  g.setAttribute('aAlpha', new BufferAttribute(alpha, 1))
  const m = new ShaderMaterial({
    transparent: true,
    depthWrite: false,
    blending: AdditiveBlending,
    toneMapped: false,
    uniforms: { uDpr: { value: 1 } },
    vertexShader: `
      attribute float aSize; attribute float aAlpha; uniform float uDpr; varying float vA;
      void main(){ vA = aAlpha; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); gl_PointSize = aSize * uDpr; }`,
    fragmentShader: `
      varying float vA;
      void main(){ float d = length(gl_PointCoord - 0.5); gl_FragColor = vec4(vec3(0.80, 0.89, 1.0) * smoothstep(0.5, 0.12, d) * vA, 1.0); }`,
  })
  const p = new Points(g, m)
  p.frustumCulled = false
  p.renderOrder = -90
  return p
}
const stars = makeStars()
space.add(stars)

// Light comes with the viewer, so every side reads when the planet is turned round.
const key = new DirectionalLight(0xfff3e4, 2.3)
key.position.set(-1.6, 2.2, 1.4)
key.target.position.set(0, 0, -3)
const rim = new DirectionalLight(AZURE, 2.2)
rim.position.set(2.4, 1.2, -7)
rim.target.position.set(0, 0, -3)
camera.add(key, key.target, rim, rim.target)
const under = new DirectionalLight(AZURE, 0.5) // the glow under the planet lights its underside a little
under.position.set(0, -1, 0)
space.add(under, new HemisphereLight(0xdfeaff, 0x0a0f18, 0.35))

// The soft light under the planet.
function radialTexture(stops: [number, string][]): CanvasTexture {
  const c = document.createElement('canvas')
  c.width = c.height = 256
  const g = c.getContext('2d')!
  const grad = g.createRadialGradient(128, 128, 0, 128, 128, 128)
  for (const [at, colour] of stops) grad.addColorStop(at, colour)
  g.fillStyle = grad
  g.fillRect(0, 0, 256, 256)
  const t = new CanvasTexture(c)
  t.colorSpace = SRGBColorSpace
  return t
}
const ground = new Mesh(
  new CircleGeometry(1, 72),
  new MeshBasicMaterial({
    map: radialTexture([
      [0, 'rgba(255,255,255,0.9)'],
      [0.3, 'rgba(255,255,255,0.42)'],
      [0.62, 'rgba(255,255,255,0.1)'],
      [1, 'rgba(255,255,255,0)'],
    ]),
    color: AZURE,
    transparent: true,
    opacity: 0.32,
    blending: AdditiveBlending,
    depthWrite: false,
    toneMapped: false,
    side: DoubleSide,
    fog: false,
  }),
)
ground.rotation.x = -Math.PI / 2
ground.renderOrder = -10
space.add(ground)

// ---------------------------------------------------------------- space: orbit lines

space.fog = new Fog(0x000000, 1, 10) // only the orbit lines take fog: their far side sinks into the dark

const UNIT_CIRCLE = (() => {
  const pts: number[] = []
  for (let i = 0; i <= 160; i++) {
    const a = (i / 160) * Math.PI * 2
    pts.push(Math.cos(a), 0, Math.sin(a))
  }
  return pts
})()

/**
 * A hairline circle. `behind` draws it before the planet and without a depth test, so the planet always covers it;
 * the other kind is depth-tested and is used faintly for the arc that passes in front of the planet.
 */
function orbitLine(opacity: number, width: number, behind = false): Line2 {
  const g = new LineGeometry()
  g.setPositions(UNIT_CIRCLE)
  const m = new LineMaterial({
    color: AZURE,
    linewidth: width,
    transparent: !behind,
    opacity,
    blending: AdditiveBlending,
    depthWrite: false,
    depthTest: !behind,
    fog: true,
  })
  const line = new Line2(g, m)
  line.frustumCulled = false
  line.renderOrder = behind ? -50 : 0
  return line
}

const orbits = new Group()
space.add(orbits)
const ORBITS = [
  { r: 1.3, tilt: new Euler(0.2, 0, 0.07), opacity: 0.3 },
  { r: 1.75, tilt: new Euler(-0.34, 0.9, 0.16), opacity: 0.2 },
].map((o) => {
  const pivot = new Group()
  pivot.rotation.copy(o.tilt)
  const line = orbitLine(o.opacity, 1, true)
  const front = orbitLine(o.opacity * 0.28, 1)
  pivot.add(line, front)
  orbits.add(pivot)
  return { ...o, pivot, line, front }
})
// The inner orbit is also the hairline of progress: the arc already travelled is brighter, with a bead at its head.
const travelled = orbitLine(0.62, 1.25, true)
travelled.renderOrder = -49
const bead = new Mesh(new SphereGeometry(1, 16, 12), new MeshBasicMaterial({ color: 0xd9ecff, toneMapped: false, fog: false }))
ORBITS[0].pivot.add(travelled, bead)
let progressNow = 0
let progressTo = 0

// ---------------------------------------------------------------- space: the fall's trail and the landing ring

const TRAIL_N = 26
const trailPts: Vector3[] = Array.from({ length: TRAIL_N }, () => new Vector3())
const trailGeo = new LineGeometry()
trailGeo.setPositions(new Array(TRAIL_N * 3).fill(0))
trailGeo.setColors(
  trailPts.flatMap((_, i) => {
    const k = Math.pow(i / (TRAIL_N - 1), 2.2)
    return [0.62 * k, 0.81 * k, 1.0 * k]
  }),
)
const trail = new Line2(
  trailGeo,
  new LineMaterial({ vertexColors: true, linewidth: 1.5, transparent: true, blending: AdditiveBlending, depthWrite: false }),
)
trail.frustumCulled = false
trail.visible = false
space.add(trail)
let trailLife = 0

function writeTrail(): void {
  const data = (trailGeo.attributes.instanceStart as any).data
  const arr = data.array as Float32Array
  for (let i = 0; i < TRAIL_N - 1; i++) {
    const a = trailPts[i]
    const b = trailPts[i + 1]
    arr.set([a.x, a.y, a.z, b.x, b.y, b.z], i * 6)
  }
  data.needsUpdate = true
}
function trailReset(p: Vector3): void {
  for (const q of trailPts) q.copy(p)
  writeTrail()
}
function trailPush(p: Vector3): void {
  for (let i = 0; i < TRAIL_N - 1; i++) trailPts[i].copy(trailPts[i + 1])
  trailPts[TRAIL_N - 1].copy(p)
  writeTrail()
}

interface Pulse {
  holder: Group
  material: LineMaterial
  t: number
  r: number
}
const pulses: Pulse[] = [0, 1, 2].map(() => {
  const line = orbitLine(0, 1.25)
  const material = line.material as LineMaterial
  material.fog = false
  material.depthTest = false
  line.rotation.x = Math.PI / 2 // the unit circle lies flat; stand it up to face the camera
  const holder = new Group()
  holder.add(line)
  holder.visible = false
  space.add(holder)
  return { holder, material, t: 1, r: 0.1 }
})
let pulseNext = 0
let glow = 0 // the planet's light swells for a moment when a piece locks on

function ringPulse(at: Vector3, radius: number): void {
  if (still()) return
  const p = pulses[pulseNext++ % pulses.length]
  p.holder.position.copy(at)
  p.t = 0
  p.r = radius
  p.holder.visible = true
}

// A cross-head screwdriver, tip at the origin, handle up +Y. Copied from src/features/screwdriver.ts.
function buildScrewdriver(): Group {
  const steel = new MeshStandardMaterial({ color: 0xd7dade, metalness: 1, roughness: 0.28, fog: false })
  const grip = new MeshStandardMaterial({ color: 0x23466b, metalness: 0, roughness: 0.5, flatShading: true, fog: false })
  const band = new MeshStandardMaterial({ color: AZURE, metalness: 0, roughness: 0.45, fog: false })
  const collar = new MeshStandardMaterial({ color: 0x1b1d20, metalness: 0.2, roughness: 0.6, fog: false })
  const tool = new Group()
  const add = (mesh: Mesh, y: number) => {
    mesh.position.y = y
    tool.add(mesh)
  }
  add(new Mesh(new BoxGeometry(0.0052, 0.005, 0.0011), steel), 0.0025)
  add(new Mesh(new BoxGeometry(0.0011, 0.005, 0.0052), steel), 0.0025)
  add(new Mesh(new CylinderGeometry(0.0021, 0.0021, 0.07, 12), steel), 0.039)
  add(new Mesh(new CylinderGeometry(0.0075, 0.005, 0.012, 16), collar), 0.08)
  add(new Mesh(new CylinderGeometry(0.0125, 0.0105, 0.082, 6), grip), 0.127)
  add(new Mesh(new CylinderGeometry(0.008, 0.0125, 0.008, 6), grip), 0.172)
  add(new Mesh(new BoxGeometry(0.0236, 0.05, 0.0036), band), 0.127)
  tool.visible = false
  return tool
}
const driver = buildScrewdriver()
space.add(driver)

// ---------------------------------------------------------------- hud: lights and the two soft fades

const hudKey = new DirectionalLight(0xfff6ea, 2.1)
hudKey.position.set(-0.7, 1, 1.3)
const hudRim = new DirectionalLight(AZURE, 1.8)
hudRim.position.set(1, 0.1, -0.7)
hud.add(hudKey, hudRim, new HemisphereLight(0xe6efff, 0x0c1018, 0.3))

// Space gets darker behind the sky and behind the dock, so the planet never fights the pieces drawn there.
function fade(flip: boolean, strength: number): Mesh {
  const m = new Mesh(
    new PlaneGeometry(1, 1),
    new ShaderMaterial({
      transparent: true,
      depthWrite: false, // depth test stays on: the pieces of the hud are in front of it
      toneMapped: false,
      uniforms: { uFlip: { value: flip ? 1 : 0 }, uK: { value: strength } },
      vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
      fragmentShader: `
        varying vec2 vUv; uniform float uFlip; uniform float uK;
        void main(){ float y = mix(1.0 - vUv.y, vUv.y, uFlip); gl_FragColor = vec4(0.012, 0.018, 0.034, smoothstep(0.0, 1.0, y) * uK); }`,
    }),
  )
  m.renderOrder = -10
  m.position.z = -1500
  hud.add(m)
  return m
}
const fadeTop = fade(true, 0.7)
const fadeBottom = fade(false, 0.92)

// ---------------------------------------------------------------- the pieces: one kind per part id and per screw code

interface Kind {
  key: string
  screw: boolean
  code: string | null
  name: string
  total: number
  left: number
  /** centre and size of the piece in its own frame */
  centre: Vector3
  size: Vector3
  /** how it is turned before it sways, in view space: in the sky and in the dock */
  skyRest: Quaternion
  crispRest: Quaternion
  sky: Group
  crisp: Group
  label: HTMLElement
  /** its place in the sky, CSS pixels from the middle of the screen: where it is and where it is going */
  x: number
  toX: number
  /** how far under its middle the number sits */
  drop: number
  labelX: number
  phase: number
}

interface Flat {
  s: number
  k: number
  a: ActionInfo
  kind: Kind | null
}

let kinds: Kind[] = []
let flat: Flat[] = []
let player: StepPlayer
let model: ReturnType<typeof prepareModel>
let manifest: Awaited<ReturnType<typeof fetchManifest>>
let N = 0 // number of actions; cursor N is the closing turn, N + 1 is "done"
let cursor = -1
let ready = false
let frames = 0

const UP = new Vector3(0, 1, 0)
const SKY_SWAY = { base: 0.35, board: 0.3, screw: 0.45 }
const CRISP_SWAY = { base: 0.3, board: 0.3 }

/** Turn a board so its long side lies across the screen and its broad face looks at the viewer. */
function slabQuaternion(size: Vector3): Quaternion {
  const d = size.toArray()
  const order = [0, 1, 2].sort((a, b) => d[b] - d[a])
  const axis = (i: number) => new Vector3().setComponent(i, 1)
  const basis = new Matrix4().makeBasis(axis(order[0]), axis(order[1]), axis(order[2]))
  if (basis.determinant() < 0) basis.makeBasis(axis(order[0]), axis(order[1]), axis(order[2]).negate())
  return new Quaternion().setFromRotationMatrix(basis.transpose())
}

function holderFor(node: Object3D, centre: Vector3): Group {
  const copy = cloneForDisplay(node)
  copy.position.copy(centre).negate() // turn about its middle, not about its origin
  const g = new Group()
  g.add(copy)
  return g
}

/**
 * The model's screws are smooth pins. In the dock, where the person tells one screw from another, the copy gets
 * a thread: a helix pressed into the shading of the lower shank and the point. Geometry and silhouette stay the model's.
 */
function cutThread(copy: Object3D, shape: FastenerShape): void {
  if (!isMesh(copy)) return
  const pos = copy.geometry.getAttribute('position')
  const rows: [number, number][] = []
  const p = new Vector3()
  for (let i = 0; i < pos.count; i++) {
    p.fromBufferAttribute(pos, i).sub(shape.head)
    const along = p.dot(shape.axis)
    rows.push([along, p.addScaledVector(shape.axis, -along).length()])
  }
  // the shank is the longest stretch along the axis without a ring of vertices: from under the head to where the point begins
  rows.sort((a, b) => a[0] - b[0])
  let from = 0
  let to = 0
  for (let i = 1; i < rows.length; i++) {
    if (rows[i][0] - rows[i - 1][0] > to - from) {
      from = rows[i - 1][0]
      to = rows[i][0]
    }
  }
  if (to - from < shape.length * 0.3) return
  const shank = Math.max(...rows.filter(([a]) => Math.abs(a - to) < 1e-5).map(([, r]) => r))
  const e1 = new Vector3(1, 0, 0)
  if (Math.abs(shape.axis.x) > 0.9) e1.set(0, 1, 0)
  e1.addScaledVector(shape.axis, -e1.dot(shape.axis)).normalize()
  const uniforms = {
    uHead: { value: shape.head },
    uAxis: { value: shape.axis },
    uE1: { value: e1 },
    uE2: { value: new Vector3().crossVectors(shape.axis, e1) },
    uFrom: { value: from + (to - from) * 0.3 },
    uTo: { value: shape.length },
    uPitch: { value: Math.max(0.0013, shank * 0.8) },
  }
  const m = copy.material as MeshStandardMaterial
  m.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms)
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vObj; varying vec3 vAxisView; uniform vec3 uAxis;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvObj = position; vAxisView = normalize(normalMatrix * uAxis);')
    shader.fragmentShader = shader.fragmentShader
      .replace(
        '#include <common>',
        '#include <common>\nvarying vec3 vObj; varying vec3 vAxisView; uniform vec3 uHead; uniform vec3 uAxis; uniform vec3 uE1; uniform vec3 uE2; uniform float uFrom; uniform float uTo; uniform float uPitch;',
      )
      .replace(
        '#include <normal_fragment_maps>',
        `#include <normal_fragment_maps>
        {
          vec3 rel = vObj - uHead;
          float along = dot(rel, uAxis);
          vec3 perp = rel - uAxis * along;
          float turn = atan(dot(perp, uE1), dot(perp, uE2)) / 6.2831853;
          float on = smoothstep(uFrom, uFrom + uPitch, along) * (1.0 - smoothstep(uTo - uPitch * 0.4, uTo, along));
          float wave = sin((along / uPitch + turn) * 6.2831853);
          normal = normalize(normal + vAxisView * wave * 0.8 * on);
          diffuseColor.rgb *= 1.0 - 0.3 * on * smoothstep(0.3, -0.9, wave);
        }`,
      )
  }
  m.customProgramCacheKey = () => `thread-${copy.uuid}`
}

function buildKinds(): void {
  const defs = [
    ...manifest.parts.map((p) => ({ key: p.id, screw: false, code: null as string | null, item: p })),
    ...manifest.hardware.map((h) => ({ key: h.code, screw: true, code: h.code as string | null, item: h })),
  ]
  kinds = defs.map((d, i) => {
    const node = model.node(d.item.nodes[0])!
    const box = localBox(node)
    const centre = box.getCenter(new Vector3())
    const size = box.getSize(new Vector3())
    const shape = d.screw ? model.fastener(node) : null
    const slab = slabQuaternion(size)
    const label = document.createElement('p')
    label.className = 'sat'
    label.innerHTML = d.code ? `<b></b><i>${d.code}</i>` : '<b></b>'
    skyEl.append(label)
    const kind: Kind = {
      key: d.key,
      screw: d.screw,
      code: d.code,
      name: displayName(lang, d.item),
      total: d.item.count,
      left: d.item.count,
      centre,
      size,
      // in the sky a screw hangs point down like a needle and a board is a slab seen from a little above
      skyRest: shape
        ? new Quaternion().setFromUnitVectors(shape.axis, new Vector3(0.34, -0.9, -0.28).normalize())
        : new Quaternion().setFromEuler(new Euler(-0.95, 0, 0)).multiply(slab),
      // in the dock a screw lies across, head to the left and leaning to the viewer so the head reads as round
      crispRest: shape
        ? new Quaternion().setFromUnitVectors(shape.axis, new Vector3(0.95, -0.1, -0.3).normalize())
        : new Quaternion().setFromEuler(new Euler(-0.72, 0, 0)).multiply(slab),
      sky: holderFor(node, centre),
      crisp: holderFor(node, centre),
      label,
      x: 0,
      toX: 0,
      drop: 40,
      labelX: NaN,
      phase: i * 1.7,
    }
    if (shape) cutThread(kind.crisp.children[0], shape)
    kind.crisp.visible = false
    hud.add(kind.sky, kind.crisp)
    return kind
  })
}

/** Width and height a kind covers on screen, in metres, over the turns given (its rest pose swayed about the vertical). */
function extent(kind: Kind, rest: Quaternion, sways: number[]): { w: number; h: number } {
  const half = kind.size.clone().multiplyScalar(0.5)
  const q = new Quaternion()
  const c = new Vector3()
  let w = 0
  let h = 0
  for (const sway of sways) {
    q.setFromAxisAngle(UP, sway).multiply(rest)
    for (let i = 0; i < 8; i++) {
      c.set(i & 1 ? half.x : -half.x, i & 2 ? half.y : -half.y, i & 4 ? half.z : -half.z).applyQuaternion(q)
      w = Math.max(w, Math.abs(c.x) * 2)
      h = Math.max(h, Math.abs(c.y) * 2)
    }
  }
  return { w, h }
}

// ---------------------------------------------------------------- layout (CSS pixels)

let skyBand = 150
let dockBand = 190
const crispBox = { x: 0, y: 0, w: 300, h: 100 }
const sky = { pitch: 170, top: 80, sag: 28, half: 400 }

/** Height of the sky's arc at x: highest in the middle. */
const skyTop = (x: number) => sky.top + sky.sag * (x / sky.half) ** 2

/** The kinds that still have pieces close ranks around the middle. The others keep their place, unseen. */
function arrange(jump: boolean): void {
  const here = kinds.filter((k) => k.left > 0)
  here.forEach((kind, i) => {
    kind.toX = (i - (here.length - 1) / 2) * sky.pitch
    if (jump) kind.x = kind.toX
  })
}

function layout(): void {
  W = window.innerWidth
  H = window.innerHeight
  phone = W <= 600
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 3))
  renderer.setSize(W, H, false)
  hudCam.left = -W / 2
  hudCam.right = W / 2
  hudCam.top = H / 2
  hudCam.bottom = -H / 2
  hudCam.updateProjectionMatrix()
  ;(backdrop.material as ShaderMaterial).uniforms.uDpr.value = dpr()
  ;(stars.material as ShaderMaterial).uniforms.uDpr.value = dpr()

  // the sky: one place per kind, on a shallow arc
  const S = phone ? 54 : 104
  const n = Math.max(1, kinds.length)
  sky.pitch = Math.min(phone ? 66 : 176, (W - 16) / n)
  sky.top = phone ? 62 : 78
  sky.sag = phone ? 10 : 26
  sky.half = Math.max(1, (sky.pitch * (n - 1)) / 2)
  const screwMax = Math.max(1e-6, ...kinds.filter((k) => k.screw).map((k) => k.size.length()))
  const boardMax = Math.max(1e-6, ...kinds.filter((k) => !k.screw).map((k) => k.size.length()))
  for (const kind of kinds) {
    const d = kind.size.length()
    // boards keep a hint of their relative size; screws keep their true relative length
    const scale = (kind.screw ? S * 0.72 * (d / screwMax) : S * Math.pow(d / boardMax, 0.45)) / d
    kind.sky.scale.setScalar(scale)
    const amp = kind.screw ? SKY_SWAY.screw : SKY_SWAY.board
    kind.drop = (extent(kind, kind.skyRest, [SKY_SWAY.base - amp, SKY_SWAY.base, SKY_SWAY.base + amp]).h * scale) / 2 + (phone ? 7 : 12)
    kind.labelX = NaN
  }
  arrange(true)
  skyBand = sky.top + sky.sag + S * 0.4 + 52

  // the dock: the crisp piece fills the rectangle of #piece
  const r = pieceEl.getBoundingClientRect()
  crispBox.x = r.left + r.width / 2 - W / 2
  crispBox.y = H / 2 - (r.top + r.height / 2)
  crispBox.w = Math.min(r.width, 330)
  crispBox.h = r.height
  dockBand = H - r.top + 26
  // every screw at one scale, so a long one is seen to be long
  const screwScale = (crispBox.w * 0.94) / Math.max(1e-6, ...kinds.filter((k) => k.screw).map((k) => extent(k, k.crispRest, [0]).w))
  for (const kind of kinds) {
    kind.crisp.position.set(crispBox.x, crispBox.y, 0)
    if (kind.screw) {
      kind.crisp.scale.setScalar(screwScale)
    } else {
      const e = extent(kind, kind.crispRest, [CRISP_SWAY.base - CRISP_SWAY.board, CRISP_SWAY.base, CRISP_SWAY.base + CRISP_SWAY.board])
      kind.crisp.scale.setScalar(Math.min((crispBox.w * 0.9) / e.w, (crispBox.h * 0.84) / e.h))
    }
  }

  fadeTop.scale.set(W, skyBand + 40, 1)
  fadeTop.position.set(0, H / 2 - (skyBand + 40) / 2, -1500)
  fadeBottom.scale.set(W, dockBand + 70, 1)
  fadeBottom.position.set(0, -H / 2 + (dockBand + 70) / 2, -1500)

  // the planet sits in the middle of the room left between the sky and the dock
  camera.aspect = W / H
  camera.setViewOffset(W, H, 0, (dockBand - skyBand) / 2, W, H)
  camera.updateProjectionMatrix()
}

// ---------------------------------------------------------------- the camera: where the guide wants to look from

const tween = { on: false, t: 0, fromT: new Vector3(), toT: new Vector3(), fromS: new Spherical(), toS: new Spherical() }
const stepBox: { centre: Vector3; floor: number; radius: number }[] = []
const wantDir: Vector3[] = []
let planetR = 0.32
let lastDir: Vector3 | null = null
let lastStep = -1
let floorY = 0

controls.addEventListener('start', () => {
  tween.on = false // a press always wins over a camera move
  controls.autoRotate = false
})

function fitDistance(): number {
  const tanV = Math.tan(MathUtils.degToRad(camera.fov) / 2)
  const halfV = Math.atan((tanV * (H - skyBand - dockBand)) / H)
  const halfH = Math.atan((tanV * W) / H)
  return (planetR / Math.sin(Math.max(0.02, Math.min(halfV, halfH)))) * (phone ? 1.04 : 0.92)
}

function stepDir(s: number): Vector3 {
  const cam = manifest.steps[s]?.camera
  const node = model.node(cam?.node)
  if (node) {
    node.updateWorldMatrix(true, false)
    return new Vector3(0, 0, -1).transformDirection(node.matrixWorld)
  }
  if (cam?.pos?.length === 3 && cam.target?.length === 3) {
    // manifest numbers are Z up, the GLB is Y up
    const d = new Vector3(cam.target[0] - cam.pos[0], cam.target[2] - cam.pos[2], -(cam.target[1] - cam.pos[1]))
    if (d.lengthSq() > 1e-6) return d.normalize()
  }
  return new Vector3(-0.62, -0.42, -0.66).normalize()
}

/** Bounds and view directions for every state, measured once before anything moves. */
function survey(): void {
  for (let s = 0; s < player.steps.length; s++) {
    const st = player.steps[s]
    const box = player.boundsAt(s, st.duration, st.actions.length)
    stepBox.push({ centre: box.getCenter(new Vector3()), floor: box.min.y, radius: box.getSize(new Vector3()).length() / 2 })
  }
  planetR = Math.max(...stepBox.map((b) => b.radius))
  for (const f of flat) {
    const dir = stepDir(f.s)
    if (f.a.type === 'fasten' && f.a.node) {
      // look from outside the face the screw goes into, leaning to the side the guide's own drawing is seen from
      player.apply(f.s, f.a.t1, f.k)
      f.a.node.updateWorldMatrix(true, false)
      const out = model.fastener(f.a.node).axis.clone().transformDirection(f.a.node.matrixWorld).negate()
      const toCamera = dir.clone().negate()
      const side = toCamera.clone().addScaledVector(out, -toCamera.dot(out))
      if (side.lengthSq() < 1e-3) side.set(0, 1, 0).addScaledVector(out, -out.y)
      if (side.lengthSq() < 1e-3) side.set(1, 0, 0)
      side.normalize()
      const from = out.multiplyScalar(0.6).addScaledVector(side, 0.8)
      if (from.y < 0.24) from.y = 0.24
      wantDir.push(from.normalize().negate())
    } else {
      if (dir.y > -0.24) dir.setY(-0.24).normalize()
      wantDir.push(dir)
    }
  }
  const closing = new Vector3(-0.66, -0.4, -0.64).normalize()
  wantDir.push(closing, closing)
}

function stepOf(i: number): number {
  return i < 0 ? 0 : i >= N ? player.finishIndex : flat[i].s
}

/** Move the camera for state `i`, but only when the guide's wish changed: a view the person chose is left alone. */
function focus(i: number, immediate = false): void {
  const s = stepOf(i)
  const dir = wantDir[Math.max(0, Math.min(i, wantDir.length - 1))]
  const changed = !lastDir || s !== lastStep || lastDir.angleTo(dir) > 0.3
  if (!changed && !immediate) return
  lastDir = dir
  lastStep = s
  floorY = stepBox[s].floor
  const offset = camera.position.clone().sub(controls.target)
  tween.fromT.copy(controls.target)
  tween.fromS.setFromVector3(offset)
  tween.toT.copy(stepBox[s].centre)
  tween.toS.setFromVector3(dir.clone().multiplyScalar(-(immediate ? fitDistance() : tween.fromS.radius)))
  const dTheta = tween.toS.theta - tween.fromS.theta
  if (dTheta > Math.PI) tween.toS.theta -= 2 * Math.PI
  if (dTheta < -Math.PI) tween.toS.theta += 2 * Math.PI
  if (immediate || still()) {
    tween.on = false
    placeCamera(1)
  } else {
    tween.on = true
    tween.t = 0
  }
}

function placeCamera(k: number): void {
  const s = new Spherical(
    MathUtils.lerp(tween.fromS.radius, tween.toS.radius, k),
    MathUtils.lerp(tween.fromS.phi, tween.toS.phi, k),
    MathUtils.lerp(tween.fromS.theta, tween.toS.theta, k),
  ).makeSafe()
  controls.target.lerpVectors(tween.fromT, tween.toT, k)
  camera.position.setFromSpherical(s).add(controls.target)
}

// ---------------------------------------------------------------- the steps

interface Anim {
  i: number
  phase: 'fall' | 'clip'
  t: number
  step: number
  k: number
  t0: number
  t1: number
  node: Object3D | null
  kind: Kind | null
  type: string
  // the pose the clip starts from, in the node's parent frame
  p0: Vector3
  q0: Quaternion
  // the fall, in world space: from the sky (S) by way of M to the clip's start (C1)
  S: Vector3
  M: Vector3
  C1: Vector3
  qS: Quaternion
  qE: Quaternion
  sS: number
  sE: number
  dur: number
}
let anim: Anim | null = null
const flashes: { mats: MeshStandardMaterial[]; t: number }[] = []

const v1 = new Vector3()
const v2 = new Vector3()
const q1 = new Quaternion()
const m1 = new Matrix4()

/** Pose the planet as it stands once state `i` is done. */
function pose(i: number): void {
  if (i < 0) return player.apply(0, 0, -1)
  if (i >= N) return player.apply(player.finishIndex, player.steps[player.finishIndex].duration, 0)
  const f = flat[i]
  player.apply(f.s, f.a.t1, f.k) // the ring stays on the hole while the person drives the screw
}

function worldCentre(node: Object3D, kind: Kind, out: Vector3): Vector3 {
  node.updateWorldMatrix(true, false)
  return out.copy(kind.centre).applyMatrix4(node.matrixWorld)
}

function play(i: number): void {
  cursor = i
  refresh()
  focus(i)
  if (i > N) {
    pose(i)
    glow = 1
    if (!still()) controls.autoRotate = true
    return
  }
  const fin = player.finishIndex
  const base = { i, t: 0, p0: new Vector3(), q0: new Quaternion(), S: new Vector3(), M: new Vector3(), C1: new Vector3(), qS: new Quaternion(), qE: new Quaternion(), sS: 1, sE: 1, dur: 0 }
  if (i === N) {
    player.apply(fin, 0, 0)
    anim = { ...base, phase: 'clip', step: fin, k: 0, t0: 0, t1: player.steps[fin].duration, node: null, kind: null, type: 'turn' }
    if (still()) finishNow()
    return
  }
  const f = flat[i]
  player.apply(f.s, f.a.t0, f.k) // the piece stands at the clip's start, the ring marks its hole
  anim = { ...base, phase: 'clip', step: f.s, k: f.k, t0: f.a.t0, t1: f.a.t1, node: f.a.node, kind: f.kind, type: f.a.type }
  const node = f.a.node
  const kind = f.kind
  if (!node || !kind || still()) return

  // ---- the fall from the sky
  model.scene.updateMatrixWorld(true)
  anim.phase = 'fall'
  anim.p0.copy(node.position)
  anim.q0.copy(node.quaternion)
  node.matrixWorld.decompose(v1, anim.qE, v2)
  anim.sE = v2.x
  anim.C1.copy(kind.centre).applyMatrix4(node.matrixWorld)

  // the place in space that lies under the piece in the sky, part of the way to the planet
  camera.updateMatrixWorld(true)
  const forward = camera.getWorldDirection(new Vector3())
  const depth = camera.position.distanceTo(controls.target) * 0.5
  const ray = new Vector3(kind.sky.position.x / (W / 2), kind.sky.position.y / (H / 2), 0.5).unproject(camera).sub(camera.position).normalize()
  anim.S.copy(camera.position).addScaledVector(ray, depth / ray.dot(forward))
  anim.sS = (kind.sky.scale.x * 2 * Math.tan(MathUtils.degToRad(camera.fov) / 2) * depth) / H
  anim.qS.copy(camera.quaternion).multiply(kind.sky.quaternion)

  // it does not drop straight: it swings out to the side and comes in on a curve, as from an orbit
  const right = new Vector3().setFromMatrixColumn(camera.matrixWorld, 0)
  const span = anim.S.distanceTo(anim.C1)
  const sign = kind.sky.position.x >= 0 ? 1 : -1
  anim.M.lerpVectors(anim.S, anim.C1, 0.45).addScaledVector(right, sign * span * 0.42).addScaledVector(forward, span * 0.12)
  anim.dur = kind.screw ? 0.62 : 0.86
  trailReset(anim.S)
  trail.visible = true
  trailLife = 1
  placeFalling(0)
}

/** Put the falling node at parameter u of its way down, working in world space and writing its parent's frame. */
function placeFalling(u: number): void {
  const a = anim!
  const node = a.node!
  const e = easeInOut(u)
  const centre = v1.copy(a.S).multiplyScalar((1 - e) * (1 - e)).addScaledVector(a.M, 2 * e * (1 - e)).addScaledVector(a.C1, e * e)
  const q = q1.copy(a.qS).slerp(a.qE, easeInOut(Math.min(1, u * 1.15)))
  const s = MathUtils.lerp(a.sS, a.sE, e)
  trailPush(centre)
  // the node's origin is not its middle: step back from the middle by the turned and scaled offset
  const origin = v2.copy(a.kind!.centre).multiplyScalar(s).applyQuaternion(q).negate().add(centre)
  const parent = node.parent!
  parent.updateWorldMatrix(true, false)
  node.position.copy(origin).applyMatrix4(m1.copy(parent.matrixWorld).invert())
  const pq = parent.getWorldQuaternion(new Quaternion())
  const ps = parent.getWorldScale(new Vector3())
  node.quaternion.copy(pq.invert()).multiply(q)
  node.scale.setScalar(s / ps.x)
}

function restoreNode(a: Anim): void {
  if (!a.node || a.dur === 0) return
  a.node.position.copy(a.p0)
  a.node.quaternion.copy(a.q0)
  a.node.scale.set(1, 1, 1)
}

function land(a: Anim): void {
  driver.visible = false
  if (a.type === 'turn' || !a.node || !a.kind) return
  const at = worldCentre(a.node, a.kind, new Vector3())
  ringPulse(at, a.kind.screw ? 0.022 : a.kind.size.length() * 0.3)
  glow = Math.max(glow, a.kind.screw ? 0.35 : 1)
  const mats: MeshStandardMaterial[] = []
  a.node.traverse((o) => {
    if (isMesh(o)) mats.push(o.material as MeshStandardMaterial)
  })
  if (!still()) flashes.push({ mats, t: 0 })
}

/** End whatever is playing at once, in its finished pose. */
function finishNow(): void {
  if (!anim) return
  const a = anim
  anim = null
  if (a.phase === 'fall') restoreNode(a)
  pose(a.i)
  driver.visible = false
  trailLife = Math.min(trailLife, 0.25)
}

function stepAnim(dt: number): void {
  const a = anim
  if (!a) return
  a.t += dt
  if (a.phase === 'fall') {
    const u = Math.min(1, a.t / a.dur)
    placeFalling(u)
    if (u < 1) return
    restoreNode(a) // exactly the clip's start pose; the mixer does not rewrite values it thinks it already wrote
    a.phase = 'clip'
    a.t = 0
  }
  const time = Math.min(a.t1, a.t0 + a.t)
  player.apply(a.step, time, a.k)
  if (a.type === 'fasten' && a.node) rideScrew(a.node)
  if (time >= a.t1) {
    anim = null
    land(a)
  }
}

const HANDLE_UP = new Vector3(0, 1, 0)
function rideScrew(screw: Object3D): void {
  const shape = model.fastener(screw)
  screw.updateWorldMatrix(true, false)
  driver.position.copy(shape.head).applyMatrix4(screw.matrixWorld)
  driver.quaternion.copy(screw.getWorldQuaternion(q1)).multiply(new Quaternion().setFromUnitVectors(HANDLE_UP, shape.axis.clone().negate()))
  driver.visible = true
}

function next(): void {
  if (!ready) return
  finishNow()
  if (cursor <= N) play(cursor + 1)
}

function prev(): void {
  if (!ready) return
  finishNow()
  if (cursor <= 0) return
  controls.autoRotate = false
  cursor -= 1
  pose(cursor)
  focus(cursor)
  refresh()
}

/** Everything the page says about the state: the numbers in the sky, the crisp piece, the words, the two buttons. */
function refresh(): void {
  for (const kind of kinds) kind.left = kind.total
  for (let j = 0; j <= Math.min(cursor, N - 1); j++) {
    const kind = flat[j].kind
    if (kind) kind.left -= 1
  }
  const f = cursor >= 0 && cursor < N ? flat[cursor] : null
  for (const kind of kinds) {
    kind.label.firstElementChild!.textContent = String(kind.left)
    kind.label.classList.toggle('gone', kind.left === 0)
    kind.label.setAttribute('aria-label', `${kind.name}${kind.code ? ` ${kind.code}` : ''}, ${WORDS.left} ${kind.left}`)
    kind.sky.visible = kind.left > 0
    kind.crisp.visible = !!f && f.kind === kind
  }
  arrange(still())
  let words = ''
  let count = ''
  if (f?.a.type === 'fasten') {
    words = f.a.raw.code ?? f.kind?.code ?? ''
    count = `${f.a.screwIndex}/${f.a.screwTotal}`
  } else if (f?.a.type === 'place') {
    words = f.kind?.name ?? ''
  } else if (f) {
    const poseName = (manifest.steps[f.s].pose ?? 'turn') as keyof typeof WORDS
    words = WORDS[poseName] ?? WORDS.turn
  } else if (cursor === N) {
    words = WORDS.finish
  } else if (cursor > N) {
    words = WORDS.done
  }
  sayEl.replaceChildren(words)
  if (count) {
    const of = document.createElement('span')
    of.className = 'of'
    of.textContent = count
    sayEl.append(of)
  }
  const turning = (!!f && f.a.type === 'turn') || cursor === N
  turnGlyph.toggleAttribute('hidden', !turning)
  doneGlyph.toggleAttribute('hidden', cursor <= N)
  prevEl.disabled = cursor <= 0
  nextEl.disabled = cursor > N
  progressTo = Math.max(0, cursor + 1) / (N + 2)
}

// ---------------------------------------------------------------- the loop: it never stops

let last = performance.now()
const proj = new Vector3()

function frame(now: number): void {
  requestAnimationFrame(frame)
  const dt = Math.min(0.05, Math.max(0, (now - last) / 1000))
  last = now
  frames++
  if (ready) {
    stepAnim(dt)
    if (tween.on) {
      tween.t = Math.min(1, tween.t + dt / 1.0)
      placeCamera(easeInOut(tween.t))
      if (tween.t >= 1) tween.on = false
    }
  }
  controls.update(dt)
  ambient(dt, now)
  renderer.clear()
  renderer.render(space, camera)
  renderer.clearDepth()
  renderer.render(hud, hudCam)
}

/** Everything that lives around the planet: the sky, the orbit lines, the glow, the trail and the landing ring. */
function ambient(dt: number, now: number): void {
  const calm = still()
  const dist = camera.position.distanceTo(controls.target)

  // the sky and the crisp piece
  for (const kind of kinds) {
    kind.x = calm || Math.abs(kind.toX - kind.x) < 0.2 ? kind.toX : kind.x + (kind.toX - kind.x) * Math.min(1, dt * 5)
    const top = skyTop(kind.x)
    const bob = calm ? 0 : Math.sin(now * 0.0008 + kind.phase * 2.3) * 3
    const sway = SKY_SWAY.base + (calm ? 0 : Math.sin(now * 0.0005 + kind.phase) * (kind.screw ? SKY_SWAY.screw : SKY_SWAY.board))
    kind.sky.position.set(kind.x, H / 2 - top + bob, 0)
    kind.sky.quaternion.setFromAxisAngle(UP, sway).multiply(kind.skyRest)
    if (kind.labelX !== kind.x) {
      kind.labelX = kind.x
      kind.label.style.left = `${W / 2 + kind.x}px`
      kind.label.style.top = `${top + kind.drop}px`
    }
    if (kind.crisp.visible) {
      const turn = kind.screw ? 0 : CRISP_SWAY.base + (calm ? 0 : Math.sin(now * 0.0007) * CRISP_SWAY.board)
      kind.crisp.quaternion.setFromAxisAngle(UP, turn).multiply(kind.crispRest)
    }
  }

  // orbit lines and the glow follow the middle of the planet
  orbits.position.copy(controls.target)
  ORBITS.forEach((o, i) => {
    o.pivot.scale.setScalar(planetR * o.r)
    if (!calm) o.line.rotation.y += dt * (0.05 - i * 0.08) // a slow drift along its own path, no wobble
    o.front.rotation.y = o.line.rotation.y
  })
  travelled.rotation.y = ORBITS[0].line.rotation.y
  progressNow += (progressTo - progressNow) * (calm ? 1 : Math.min(1, dt * 5))
  travelled.geometry.instanceCount = Math.round(progressNow * 160)
  travelled.visible = travelled.geometry.instanceCount > 0
  const ang = progressNow * Math.PI * 2 - travelled.rotation.y
  bead.position.set(Math.cos(ang), 0, Math.sin(ang))
  bead.scale.setScalar((0.0021 * dist) / (planetR * ORBITS[0].r))
  const fog = space.fog as Fog
  fog.near = dist - planetR * 1.2
  fog.far = dist + planetR * 3.4

  glow = Math.max(0, glow - dt * 1.6)
  const swell = easeOut(glow)
  ground.position.set(controls.target.x, floorY - 0.003, controls.target.z)
  ground.scale.setScalar(planetR * (2.1 + swell * 0.25))
  ;(ground.material as MeshBasicMaterial).opacity = 0.34 + swell * 0.2
  proj.copy(controls.target).project(camera)
  const u = (backdrop.material as ShaderMaterial).uniforms
  u.uC.value = [((proj.x + 1) / 2) * W, ((proj.y + 1) / 2) * H]
  u.uR.value = ((planetR * 1.5) / (dist * Math.tan(MathUtils.degToRad(camera.fov) / 2))) * (H / 2)
  u.uPulse.value = swell * 0.5
  if (!calm) stars.rotation.y += dt * 0.0035

  // the trail closes up on the piece after it has arrived
  if (trail.visible && anim?.phase !== 'fall') {
    trailPush(trailPts[TRAIL_N - 1])
    trailLife -= dt * 3
    if (trailLife <= 0) trail.visible = false
  }

  for (const p of pulses) {
    if (!p.holder.visible) continue
    p.t += dt / 0.6
    p.holder.quaternion.copy(camera.quaternion)
    p.holder.scale.setScalar(p.r * (1 + easeOut(Math.min(1, p.t)) * 1.5))
    p.material.opacity = 0.9 * (1 - Math.min(1, p.t)) ** 2
    if (p.t >= 1) p.holder.visible = false
  }

  for (let i = flashes.length - 1; i >= 0; i--) {
    const f = flashes[i]
    f.t += dt / 0.8
    const k = Math.max(0, 1 - f.t) ** 2 * 0.22
    for (const m of f.mats) m.emissive.setRGB(0.62 * k, 0.81 * k, 1.0 * k)
    if (f.t >= 1) flashes.splice(i, 1)
  }
}

// ---------------------------------------------------------------- start

/** A cold light on the limb: faces that turn away from the viewer pick up the azure of the sky. */
function limbLight(material: MeshStandardMaterial): void {
  material.fog = false
  material.onBeforeCompile = (shader) => {
    shader.fragmentShader = shader.fragmentShader.replace(
      '#include <emissivemap_fragment>',
      `#include <emissivemap_fragment>
       totalEmissiveRadiance += vec3(0.30, 0.52, 0.80) * pow(1.0 - saturate(dot(normal, normalize(vViewPosition))), 2.4) * 0.9;`,
    )
  }
  material.customProgramCacheKey = () => 'limb'
}

async function start(): Promise<void> {
  manifest = await fetchManifest(JOB, null)
  try {
    model = prepareModel(await loadGltf(fileUrl(JOB, manifest.glb, null)), manifest)
  } catch {
    model = prepareModel(await loadGltf(fileUrl(JOB, manifest.glbPacked ?? manifest.glb, null)), manifest)
  }
  player = new StepPlayer(model, manifest)

  const nodeKind = new Map<Object3D, Kind>()
  buildKinds()
  for (const kind of kinds) {
    const item = kind.screw ? manifest.hardware.find((h) => h.code === kind.key)! : manifest.parts.find((p) => p.id === kind.key)!
    for (const name of item.nodes) {
      const n = model.node(name)
      if (n) nodeKind.set(n, kind)
    }
  }
  player.steps.forEach((st, s) => {
    if (s === player.finishIndex) return
    st.actions.forEach((a, k) => flat.push({ s, k, a, kind: a.node ? (nodeKind.get(a.node) ?? null) : null }))
  })
  N = flat.length

  model.scene.traverse((o) => {
    if ((o as any).isLight) o.visible = false
    if (!isMesh(o)) return
    const m = o.material as MeshStandardMaterial
    if (o === model.marker || o.parent === model.marker) {
      m.fog = false
      m.emissive = new Color(AZURE)
      m.emissiveIntensity = 0.9
    } else {
      limbLight(m)
    }
  })
  space.add(model.scene)

  layout()
  survey()
  pose(-1)
  focus(0, true)
  refresh()
  ready = true
  nextEl.disabled = false

  // the one moment that plays by itself: the first piece comes down
  const open = Number(new URLSearchParams(location.search).get('at'))
  if (Number.isInteger(open) && open > 0) {
    cursor = Math.min(open, N + 1) - 1
    pose(cursor)
    focus(cursor, true)
    refresh()
  } else {
    setTimeout(() => cursor < 0 && play(0), still() ? 0 : 700)
  }
}

window.addEventListener('resize', () => {
  layout()
  if (!ready) return
  // keep the planet the same share of the new window
  const radius = fitDistance()
  camera.position.sub(controls.target).setLength(radius).add(controls.target)
  tween.toS.radius = radius
  tween.fromS.radius = radius
})
nextEl.addEventListener('click', next)
prevEl.addEventListener('click', prev)
window.addEventListener('keydown', (e) => {
  if (e.key === 'ArrowRight') {
    e.preventDefault()
    next()
  } else if (e.key === 'ArrowLeft') {
    e.preventDefault()
    prev()
  }
})

layout()
requestAnimationFrame(frame)
start().catch((err) => {
  console.error(err)
  sayEl.textContent = WORDS.failed
})

// for the check scripts
;(window as any).__orbit = {
  next,
  prev,
  get cursor() {
    return cursor
  },
  get total() {
    return N
  },
  get frames() {
    return frames
  },
  get busy() {
    return anim?.phase ?? null
  },
  get ready() {
    return ready
  },
  camera,
  controls,
  goTo(i: number) {
    finishNow()
    cursor = Math.max(0, Math.min(i, N + 1))
    pose(cursor)
    focus(cursor, true)
    refresh()
  },
}
