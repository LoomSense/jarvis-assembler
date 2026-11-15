// "The exploded drawing, alive": a design mockup of the assembly guide.
// One full-screen scene. The parts still in the box hang in a row at the top like a parts list; the piece that is
// called travels down a drawn guide line and seats. Real model and real step logic come from client/src.
import {
  Box3,
  BoxGeometry,
  Color,
  CylinderGeometry,
  DirectionalLight,
  DoubleSide,
  EdgesGeometry,
  Group,
  HemisphereLight,
  LineBasicMaterial,
  LineSegments,
  MathUtils,
  Matrix4,
  Mesh,
  MeshStandardMaterial,
  NeutralToneMapping,
  Object3D,
  PerspectiveCamera,
  PlaneGeometry,
  PMREMGenerator,
  Quaternion,
  Scene,
  ShaderMaterial,
  Spherical,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
  type BufferGeometry,
} from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js'
import { fetchManifest, fileUrl } from '/src/api.ts'
import { displayName } from '/src/i18n.ts'
import { cloneForDisplay, isMesh, loadGltf, localBox, prepareModel, type Model } from '/src/viewer/model.ts'
import { StepPlayer, type ActionInfo } from '/src/viewer/steps.ts'
import type { Manifest } from '/src/types.ts'

const query = new URLSearchParams(location.search)
const JOB = query.get('job') || 'local-fresh'
const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)')
const still = () => reducedQuery.matches || query.has('still')

// The only sentences on the page: what to do when the whole assembly turns. Keyed by the step number.
const TURN_WORDS: Record<number, string> = { 3: 'Γύρισέ το ανάποδα', 5: 'Στήσε το όρθιο', 7: 'Άλλη άκρη πάνω' }
const TURN_FALLBACK = 'Γύρισέ το'
const FINISH_TURN = 'Γύρισέ το κανονικά'
const FINISHED = 'Έτοιμο'

const FLASH = new Color(1.0, 0.92, 0.8) // the light a piece gives off as it seats
const LIST_DEPTH = 0.4 // metres in front of the eye where the parts list hangs
const FOV = 30
const X = new Vector3(1, 0, 0)
const Y = new Vector3(0, 1, 0)

const $ = <T extends Element>(id: string) => document.getElementById(id) as unknown as T
const stage = $<HTMLCanvasElement>('stage')
const listEl = $<HTMLUListElement>('list')
const againEl = $<HTMLButtonElement>('again')
const sayEl = $<HTMLParagraphElement>('say')
const ruleEl = $<HTMLDivElement>('rule')
const prevEl = $<HTMLButtonElement>('prev')
const nextEl = $<HTMLButtonElement>('next')
const ink = {
  leader: $<SVGPathElement>('leader'),
  soft: $<SVGPathElement>('leader-soft'),
  axis: $<SVGLineElement>('axis'),
  seat: $<SVGGElement>('seat'),
  pulse: $<SVGCircleElement>('pulse'),
  pulse2: $<SVGCircleElement>('pulse2'),
}

const easeOut = (t: number) => (t >= 1 ? 1 : 1 - Math.pow(2, -10 * t))
const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)
const clamp01 = (t: number) => Math.min(1, Math.max(0, t))

// ------------------------------------------------------------------ renderer, the two spaces

const renderer = new WebGLRenderer({ canvas: stage, antialias: true, alpha: true, powerPreference: 'high-performance' })
renderer.outputColorSpace = SRGBColorSpace
renderer.toneMapping = NeutralToneMapping
renderer.autoClear = false
renderer.setClearColor(0x000000, 0)

const pmrem = new PMREMGenerator(renderer)
const envMap = pmrem.fromScene(new RoomEnvironment(), 0.04).texture
pmrem.dispose()

/** The drafting space with the assembly in it. */
const scene = new Scene()
scene.environment = envMap
scene.environmentIntensity = 0.34
const camera = new PerspectiveCamera(FOV, 1, 0.05, 40)
camera.position.set(1.2, 1.0, 1.6)
{
  const key = new DirectionalLight(0xfff3e2, 1.4)
  key.position.set(1.4, 3.0, 2.2)
  const fill = new DirectionalLight(0xffe6cc, 0.55)
  fill.position.set(-2.4, 1.2, -1.6)
  scene.add(key, fill, new HemisphereLight(0xfff6ea, 0x7a6450, 0.45))
}

/** The parts list and the piece to pick up now. It hangs in front of the eye, so it never turns with the orbit. */
const listScene = new Scene()
listScene.environment = envMap
listScene.environmentIntensity = 0.4
const listCamera = new PerspectiveCamera(FOV, 1, 0.05, 4)
{
  const key = new DirectionalLight(0xfff3e2, 1.5)
  key.position.set(-0.7, 1.4, 1.6)
  const fill = new DirectionalLight(0xffe6cc, 0.5)
  fill.position.set(1.6, -0.2, 0.9)
  listScene.add(key, fill, new HemisphereLight(0xfff6ea, 0x7a6450, 0.35))
}

const controls = new OrbitControls(camera, stage)
controls.enableDamping = true
controls.dampingFactor = 0.09
controls.rotateSpeed = 0.85
controls.enablePan = false // the assembly stays in the centre
controls.minDistance = 0.7
controls.maxDistance = 7
controls.zoomSpeed = 0.8

let W = 1
let H = 1
let insetTop = 0
let insetBottom = 0

/** Screen place (CSS px) and distance from the eye of a point in the drafting space. */
const tmp = new Vector3()
function toScreen(world: Vector3, out: { x: number; y: number; z: number }): typeof out {
  tmp.copy(world).applyMatrix4(camera.matrixWorldInverse)
  out.z = -tmp.z
  tmp.applyMatrix4(camera.projectionMatrix)
  out.x = (tmp.x * 0.5 + 0.5) * W
  out.y = (0.5 - tmp.y * 0.5) * H
  return out
}
/** The point of the drafting space that shows at a screen place, `depth` metres in front of the eye. */
function fromScreen(x: number, y: number, depth: number, out: Vector3): Vector3 {
  out.set((x / W) * 2 - 1, 1 - (y / H) * 2, 0.5).applyMatrix4(camera.projectionMatrixInverse)
  out.multiplyScalar(depth / -out.z)
  return out.applyMatrix4(camera.matrixWorld)
}

// ------------------------------------------------------------------ the datum plane

function makeDatum(centre: Vector3, y: number): Mesh {
  const material = new ShaderMaterial({
    transparent: true,
    depthWrite: false,
    side: DoubleSide,
    uniforms: { uColor: { value: new Vector3(0.62, 0.81, 1.0) }, uCentre: { value: centre }, uRadius: { value: 1.7 } },
    vertexShader: /* glsl */ `
      varying vec3 vWorld;
      void main() {
        vec4 w = modelMatrix * vec4(position, 1.0);
        vWorld = w.xyz;
        gl_Position = projectionMatrix * viewMatrix * w;
      }`,
    fragmentShader: /* glsl */ `
      uniform vec3 uColor;
      uniform vec3 uCentre;
      uniform float uRadius;
      varying vec3 vWorld;
      // one-pixel lines every "cell" metres, fading out before they crowd into moire
      float grid(vec2 p, float cell) {
        vec2 q = p / cell;
        vec2 w = max(fwidth(q), vec2(1e-6));
        vec2 g = abs(fract(q - 0.5) - 0.5) / w;
        float crowd = max(w.x, w.y);
        return (1.0 - min(min(g.x, g.y), 1.0)) * (1.0 - smoothstep(0.1, 0.4, crowd));
      }
      float ticks(float along, float across, float cell, float reach) {
        float q = along / cell;
        float w = max(fwidth(q), 1e-6);
        float l = 1.0 - min(abs(fract(q - 0.5) - 0.5) / w, 1.0);
        return l * (1.0 - smoothstep(0.1, 0.4, w)) * step(abs(across), reach);
      }
      void main() {
        vec2 p = vWorld.xz - uCentre.xz;
        float d = clamp(length(p) / uRadius, 0.0, 1.0);
        float fade = pow(1.0 - d, 1.7);
        float fine = grid(p, 0.05) * 0.13;
        float coarse = grid(p, 0.25) * 0.3;
        vec2 w = max(fwidth(p), vec2(1e-6));
        float datum = max(1.0 - min(abs(p.x) / w.x, 1.0), 1.0 - min(abs(p.y) / w.y, 1.0)) * 0.55;
        // the measured scale along the two datum lines: 10 mm ticks, longer every 50 mm
        float scale = max(
          max(ticks(p.x, p.y, 0.01, 0.004), ticks(p.y, p.x, 0.01, 0.004)),
          max(ticks(p.x, p.y, 0.05, 0.009), ticks(p.y, p.x, 0.05, 0.009))
        ) * 0.5;
        float a = max(max(fine, coarse), max(datum, scale)) * fade;
        if (a < 0.003) discard;
        gl_FragColor = vec4(uColor, a);
      }`,
  })
  const plane = new Mesh(new PlaneGeometry(4, 4), material)
  plane.rotation.x = -Math.PI / 2
  plane.position.set(centre.x, y, centre.z)
  plane.renderOrder = -1
  return plane
}

// ------------------------------------------------------------------ ink edges on every piece

const edgeCache = new Map<BufferGeometry, EdgesGeometry>()
const edgeMaterial = new LineBasicMaterial({ color: 0x2a1c10, transparent: true, opacity: 0.6 })
const edgeMaterialMetal = new LineBasicMaterial({ color: 0x0c0d10, transparent: true, opacity: 0.55 })
function addEdges(node: Object3D, metal: boolean): void {
  node.traverse((o) => {
    if (!isMesh(o)) return
    let edges = edgeCache.get(o.geometry)
    if (!edges) edgeCache.set(o.geometry, (edges = new EdgesGeometry(o.geometry, 28)))
    const lines = new LineSegments(edges, metal ? edgeMaterialMetal : edgeMaterial)
    lines.name = 'ink'
    o.add(lines)
    const m = o.material as MeshStandardMaterial
    m.polygonOffset = true // the faces sit a hair behind their own outlines
    m.polygonOffsetFactor = 1
    m.polygonOffsetUnits = 1
  })
}

// ------------------------------------------------------------------ a small cross-head screwdriver
// Shape taken from client/src/features/screwdriver.ts; tip at the origin, pointing down -Y.

function buildScrewdriver(): Group {
  const steel = new MeshStandardMaterial({ color: 0xd7dade, metalness: 1, roughness: 0.28 })
  const grip = new MeshStandardMaterial({ color: 0xdfe6ee, metalness: 0, roughness: 0.5, flatShading: true })
  const band = new MeshStandardMaterial({ color: 0x9ecfff, metalness: 0, roughness: 0.45 })
  const collar = new MeshStandardMaterial({ color: 0x5c6875, metalness: 0.2, roughness: 0.6 })
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
  addEdges(tool, true)
  return tool
}

// ------------------------------------------------------------------ boot

interface Kind {
  key: string
  screw: boolean
  total: number
  left: number
  /** one of its nodes: the source of every copy and measure */
  node: Object3D
  centre: Vector3
  size: Vector3
  /** how it hangs in the list, and how it lies on the plate */
  quat: Quaternion
  plateQuat: Quaternion
  spinAxis: Vector3
  list: Group
  plate: Group
  scale: number
  plateScale: number
  /** slot centre, CSS px */
  x: number
  y: number
  /** where its slot should be: the list closes ranks when a kind is used up */
  tx: number
  weight: number
  li: HTMLLIElement
  nEl: HTMLElement
}

interface Beat {
  step: number
  k: number
  a: ActionInfo | null
  kind: Kind | null
  /** beats that share a key share one framing */
  key: string
}

let manifest: Manifest
let model: Model
let player: StepPlayer
const kinds: Kind[] = []
const beats: Beat[] = []
/** What a framing must show: the assembly (kept between the list and the plate) and where pieces and the tool come in from (kept on screen). */
interface Framing {
  box: Box3
  around: Vector3[]
  dir: Vector3
}
const frames = new Map<string, Framing>()
const tool = buildScrewdriver()
let beatIndex = -1
let frameCount = 0

/** Long side along the page, broad face up, then the three-quarter turn of a parts drawing. */
function boardQuat(size: Vector3, yawDeg: number, pitchDeg: number): Quaternion {
  const d = size.toArray()
  const [a, b, c] = [0, 1, 2].sort((i, j) => d[j] - d[i]) // longest, middle, thinnest
  const cols = [new Vector3(), new Vector3(), new Vector3()]
  cols[a].set(1, 0, 0)
  cols[c].set(0, 1, 0)
  cols[b].set(0, 0, 1)
  const m = new Matrix4().makeBasis(cols[0], cols[1], cols[2])
  if (m.determinant() < 0) {
    cols[b].set(0, 0, -1)
    m.makeBasis(cols[0], cols[1], cols[2])
  }
  const q = new Quaternion().setFromRotationMatrix(m)
  const yaw = new Quaternion().setFromAxisAngle(Y, MathUtils.degToRad(yawDeg))
  const pitch = new Quaternion().setFromAxisAngle(X, MathUtils.degToRad(pitchDeg))
  return pitch.multiply(yaw).multiply(q)
}

/** Width and height on the page of a box of `size` turned by `q`. */
function extent(size: Vector3, q: Quaternion): { x: number; y: number } {
  let x = 0
  let y = 0
  const v = new Vector3()
  for (let i = 0; i < 8; i++) {
    v.set(i & 1 ? 0.5 : -0.5, i & 2 ? 0.5 : -0.5, i & 4 ? 0.5 : -0.5).multiply(size).applyQuaternion(q)
    x = Math.max(x, Math.abs(v.x))
    y = Math.max(y, Math.abs(v.y))
  }
  return { x: 2 * x, y: 2 * y }
}

function makeKind(key: string, screw: boolean, total: number, nodeName: string): Kind | null {
  const node = model.node(nodeName)
  if (!node) return null
  const box = localBox(node)
  const centre = box.getCenter(new Vector3())
  const size = box.getSize(new Vector3())
  let quat: Quaternion
  let plateQuat: Quaternion
  const spinAxis = new Vector3(0, 1, 0)
  if (screw) {
    const shape = model.fastener(node)
    spinAxis.copy(shape.axis)
    // in the list: hanging tip down, leaning a little, the head turned towards the eye
    quat = new Quaternion().setFromUnitVectors(shape.axis, new Vector3(0.2, -1, -0.34).normalize())
    // on the plate: lying across, head up left, the way a catalogue shows a screw
    plateQuat = new Quaternion().setFromUnitVectors(shape.axis, new Vector3(0.86, -0.4, -0.3).normalize())
  } else {
    quat = boardQuat(size, -28, 34)
    plateQuat = boardQuat(size, -32, 30)
  }
  const hang = (q: Quaternion) => {
    const group = new Group()
    const copy = cloneForDisplay(node)
    copy.position.copy(centre).negate() // the group's origin is the middle of the piece
    group.add(copy)
    group.quaternion.copy(q)
    listScene.add(group)
    return group
  }
  const li = document.createElement('li')
  const nEl = document.createElement('span')
  nEl.className = 'n'
  li.append(nEl)
  if (screw) {
    const code = document.createElement('span')
    code.className = 'code'
    code.textContent = key
    li.append(code)
  }
  listEl.append(li)
  const plate = hang(plateQuat)
  plate.visible = false
  return { key, screw, total, left: total, node, centre, size, quat, plateQuat, spinAxis, list: hang(quat), plate, scale: 1, plateScale: 1, x: 0, y: 0, tx: 0, weight: 1, li, nEl }
}

async function boot(): Promise<void> {
  manifest = await fetchManifest(JOB, null)
  // the full GLB: its meshes are not quantised, so edges and silhouettes stay exact
  const gltf = await loadGltf(fileUrl(JOB, manifest.glb, null))
  model = prepareModel(gltf, manifest)
  player = new StepPlayer(model, manifest)

  for (const p of manifest.parts) for (const n of p.nodes) addEdges(model.node(n)!, false)
  for (const h of manifest.hardware) for (const n of h.nodes) addEdges(model.node(n)!, true)

  for (const p of manifest.parts) {
    const k = makeKind(p.id, false, p.count, p.nodes[0])
    if (k) kinds.push(k)
  }
  for (const h of manifest.hardware) {
    const k = makeKind(h.code, true, h.count, h.nodes[0])
    if (k) kinds.push(k)
  }
  const kindOf = (a: ActionInfo): Kind | null => {
    const key = a.type === 'fasten' ? a.raw.code : a.raw.partId
    return kinds.find((k) => k.key === key) ?? null
  }

  // every action is one beat; the closing turn is the last
  player.steps.forEach((st, s) => {
    if (s === player.finishIndex) return
    for (const a of st.actions) beats.push({ step: s, k: a.index, a, kind: kindOf(a), key: a.type === 'turn' ? `${s}:turn` : `${s}` })
  })
  beats.push({ step: player.finishIndex, k: 0, a: null, kind: null, key: 'finish' })

  measureFramings()
  const whole = frames.get('finish')!.box
  const centre = whole.getCenter(new Vector3())
  scene.add(model.scene, makeDatum(centre, Math.min(0, whole.min.y) - 0.0015), tool)
  tool.visible = false

  buildRule()
  layout()
  addEventListener('resize', () => {
    layout()
    reframe(true)
  })

  const open = Number(query.get('beat'))
  if (query.has('beat') && Number.isInteger(open) && open >= 0 && open < beats.length) show(open, false, true)
  else show(0, true, true)

  document.body.classList.remove('loading')
  requestAnimationFrame(frame)
}

/** For every framing key: what the camera must hold and the side the guide looks from. */
function measureFramings(): void {
  const dirOf = (s: number): Vector3 => {
    const cam = manifest.steps[s]?.camera
    const node = model.node(cam?.node)
    if (node) {
      node.updateWorldMatrix(true, false)
      return new Vector3(0, 0, -1).transformDirection(node.matrixWorld)
    }
    return new Vector3(-0.55, -0.45, -0.7).normalize()
  }
  const corners = (box: Box3): Vector3[] =>
    [0, 1, 2, 3, 4, 5, 6, 7].map((i) => new Vector3(i & 1 ? box.max.x : box.min.x, i & 2 ? box.max.y : box.min.y, i & 4 ? box.max.z : box.min.z))
  player.steps.forEach((st, s) => {
    if (s === player.finishIndex) {
      const swing = new Box3()
      for (const f of [0, 0.25, 0.5, 0.75, 1]) swing.union(player.boundsAt(s, st.duration * f, 0))
      frames.set('finish:turn', { box: swing, around: [], dir: dirOf(s - 1) })
      frames.set('finish', { box: player.boundsAt(s, st.duration, 0).clone(), around: [], dir: new Vector3(-0.62, -0.4, -0.68).normalize() })
      return
    }
    const dir = dirOf(s)
    const around: Vector3[] = []
    for (const a of st.actions) {
      if (a.type === 'turn') {
        const swing = new Box3()
        for (const f of [0, 0.25, 0.5, 0.75, 1]) swing.union(player.boundsAt(s, a.t0 + (a.t1 - a.t0) * f, a.index))
        frames.set(`${s}:turn`, { box: swing, around: [], dir })
      } else if (a.node) {
        // where the piece waits before it goes in; for a screw, the far end of the screwdriver too
        player.apply(s, a.t0, a.index)
        model.scene.updateMatrixWorld(true)
        if (a.type === 'fasten') {
          const shape = model.fastener(a.node)
          const head = shape.head.clone().applyMatrix4(a.node.matrixWorld)
          const out = shape.axis.clone().transformDirection(a.node.matrixWorld).negate()
          around.push(head.clone().addScaledVector(out, 0.185))
        } else around.push(...corners(new Box3().setFromObject(a.node)))
      }
    }
    frames.set(`${s}`, { box: player.boundsAt(s, st.duration, st.actions.length).clone(), around, dir })
  })
}

// ------------------------------------------------------------------ layout: the list on top, the plate at the bottom

function layout(): void {
  W = Math.max(1, window.innerWidth)
  H = Math.max(1, window.innerHeight)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 3))
  renderer.setSize(W, H, false)
  const phone = W < 640
  const tanV = Math.tan(MathUtils.degToRad(FOV) / 2)
  const tanH = (tanV * W) / H
  const mPerPx = (2 * LIST_DEPTH * tanV) / H
  listCamera.aspect = W / H
  listCamera.updateProjectionMatrix()

  // the list: boards share one scale, so do the screws, and each slot is as wide as its piece needs
  const boards = kinds.filter((k) => !k.screw)
  const screws = kinds.filter((k) => k.screw)
  const ext = new Map(kinds.map((k) => [k, extent(k.size, k.quat)]))
  const widest = Math.max(...boards.map((k) => ext.get(k)!.x))
  for (const k of boards) k.weight = Math.max(0.62, ext.get(k)!.x / widest)
  for (const k of screws) k.weight = phone ? 0.62 : 0.5
  const gap = phone ? 6 : 30
  const sum = kinds.reduce((n, k) => n + k.weight, 0)
  const unit = Math.min(phone ? 76 : 148, (W - (phone ? 20 : 96) - gap * (kinds.length - 1)) / sum)
  const slotH = phone ? 54 : 82
  const top = phone ? 20 : 30
  slot.unit = unit
  slot.gap = gap
  slot.tanH = tanH
  slot.tanV = tanV
  for (const k of kinds) k.y = top + slotH / 2
  flow(true)
  const boardScale = Math.min(...boards.map((k) => Math.min((unit * k.weight) / ext.get(k)!.x, slotH / ext.get(k)!.y)))
  const screwScale = Math.min(...screws.map((k) => Math.min((unit * k.weight) / ext.get(k)!.x, slotH / ext.get(k)!.y)))
  for (const k of kinds) {
    k.scale = (k.screw ? screwScale : boardScale) * mPerPx
    k.list.scale.setScalar(k.scale)
    k.li.style.top = `${top + slotH + (phone ? 6 : 10)}px`
  }
  insetTop = top + slotH + (phone ? 44 : 56)

  // the plate: each piece as large as the space between the buttons allows
  const r = againEl.getBoundingClientRect()
  for (const k of kinds) {
    const e = extent(k.size, k.plateQuat)
    k.plateScale = Math.min((r.width * (k.screw ? 0.8 : 0.9)) / e.x, (r.height * 0.9) / e.y) * mPerPx
    k.plate.scale.setScalar(k.plateScale)
    const cx = r.left + r.width / 2
    const cy = r.top + r.height / 2
    k.plate.position.set(((cx / W) * 2 - 1) * LIST_DEPTH * tanH, (1 - (cy / H) * 2) * LIST_DEPTH * tanV, -LIST_DEPTH)
  }
  insetBottom = H - r.top + (phone ? 10 : 18)

  // the assembly's centre shows in the middle of the band left free between the list and the plate
  camera.aspect = W / H
  camera.setViewOffset(W, H, 0, -(insetTop - insetBottom) / 2, W, H)
  camera.updateProjectionMatrix()
}

const slot = { unit: 1, gap: 0, tanH: 1, tanV: 1 }

/** Slots of the kinds that still have pieces, centred as one row. `snap` puts them there at once. */
function flow(snap: boolean): void {
  const shown = kinds.filter((k) => k.left > 0)
  const width = shown.reduce((n, k) => n + slot.unit * k.weight, 0) + slot.gap * Math.max(0, shown.length - 1)
  let x = (W - width) / 2
  for (const k of shown) {
    const w = slot.unit * k.weight
    k.tx = x + w / 2
    x += w + slot.gap
    if (snap || !k.list.visible) k.x = k.tx
  }
  if (snap) for (const k of kinds) if (k.left <= 0 && !k.x) k.x = W / 2
}

/** Slide each slot towards its place and hang its piece and its number there. */
function placeList(dt: number): void {
  const pull = still() ? 1 : 1 - Math.exp(-dt * 7)
  for (const k of kinds) {
    if (k.left > 0 && Math.abs(k.tx - k.x) > 0.05) k.x += (k.tx - k.x) * pull
    k.list.position.set(((k.x / W) * 2 - 1) * LIST_DEPTH * slot.tanH, (1 - (k.y / H) * 2) * LIST_DEPTH * slot.tanV, -LIST_DEPTH)
    const left = `${k.x.toFixed(1)}px`
    if (k.li.style.left !== left) k.li.style.left = left
  }
}

// ------------------------------------------------------------------ the camera: a short move to each framing

const rig = {
  fromTarget: new Vector3(),
  toTarget: new Vector3(),
  from: new Spherical(),
  to: new Spherical(),
  p: 1,
  duration: 1.0,
  key: '',
}
controls.addEventListener('start', () => (rig.p = 1)) // a press takes the camera at once

function frameView(f: Framing, immediate: boolean): void {
  if (f.box.isEmpty()) return
  const phone = W < 640
  const tanFull = Math.tan(MathUtils.degToRad(FOV) / 2)
  const aspect = W / H
  const fwd = f.dir.clone().normalize()
  const right = new Vector3().crossVectors(fwd, Y)
  if (right.lengthSq() < 1e-6) right.set(1, 0, 0)
  right.normalize()
  const up = new Vector3().crossVectors(right, fwd)
  const origin = f.box.getCenter(new Vector3())
  const seen = (p: Vector3) => {
    const d = p.clone().sub(origin)
    return { x: d.dot(right), y: d.dot(up), z: d.dot(fwd) }
  }
  const body: { x: number; y: number; z: number }[] = []
  for (let i = 0; i < 8; i++) {
    body.push(seen(new Vector3(i & 1 ? f.box.max.x : f.box.min.x, i & 2 ? f.box.max.y : f.box.min.y, i & 4 ? f.box.max.z : f.box.min.z)))
  }
  const around = f.around.map(seen)
  // the picture is centred on the assembly from top to bottom, and on everything from side to side
  const xs = [...body, ...around].map((p) => p.x)
  const ys = body.map((p) => p.y)
  const cx = (Math.min(...xs) + Math.max(...xs)) / 2
  const cy = (Math.min(...ys) + Math.max(...ys)) / 2
  const band = Math.max(0.3, (H - insetTop - insetBottom) / H)
  const mid = insetTop + (H - insetTop - insetBottom) / 2 // where the centre of the picture shows on the screen
  const tanBand = tanFull * band
  const tanSide = tanFull * aspect * (phone ? 0.92 : 0.8)
  const tanEdge = tanFull * aspect * 0.94
  const tanUp = ((2 * mid) / H) * tanFull * 0.96
  const tanDown = ((2 * (H - mid)) / H) * tanFull * 0.96
  let dist = 0
  for (const p of body) dist = Math.max(dist, Math.abs(p.x - cx) / tanSide - p.z, Math.abs(p.y - cy) / tanBand - p.z)
  dist *= 0.95 // the corners of the box are rarely filled, so the assembly may stand a little larger
  for (const p of around) dist = Math.max(dist, Math.abs(p.x - cx) / tanEdge - p.z, (p.y - cy) / tanUp - p.z, (cy - p.y) / tanDown - p.z)
  dist = MathUtils.clamp(dist, controls.minDistance, controls.maxDistance)

  rig.fromTarget.copy(controls.target)
  rig.from.setFromVector3(camera.position.clone().sub(controls.target))
  rig.toTarget.copy(origin).addScaledVector(right, cx).addScaledVector(up, cy)
  rig.to.setFromVector3(fwd.multiplyScalar(-dist))
  const dTheta = rig.to.theta - rig.from.theta // the short way round
  if (dTheta > Math.PI) rig.to.theta -= 2 * Math.PI
  if (dTheta < -Math.PI) rig.to.theta += 2 * Math.PI
  rig.p = immediate || still() ? 1 : 0
  if (rig.p === 1) placeCamera(1)
}

function placeCamera(k: number): void {
  const s = new Spherical(
    MathUtils.lerp(rig.from.radius, rig.to.radius, k),
    MathUtils.lerp(rig.from.phi, rig.to.phi, k),
    MathUtils.lerp(rig.from.theta, rig.to.theta, k),
  ).makeSafe()
  controls.target.lerpVectors(rig.fromTarget, rig.toTarget, k)
  camera.position.setFromSpherical(s).add(controls.target)
  camera.lookAt(controls.target)
  camera.updateMatrixWorld()
}

/** Move to the framing of the current beat when it differs from the last one. Returns true when the camera moves. */
function reframe(force = false, immediate = force): boolean {
  const b = beats[beatIndex]
  if (!b) return false
  const key = b.key === 'finish' && flight ? 'finish:turn' : b.key
  if (!force && key === rig.key) return false
  rig.key = key
  const f = frames.get(key) ?? frames.get(`${b.step}`)
  if (!f) return false
  frameView(f, immediate)
  return true
}

// ------------------------------------------------------------------ a piece in flight

interface Flight {
  beat: Beat
  node: Object3D | null
  phase: 'wait' | 'travel' | 'clip'
  wait: number
  /** 0..1 along the guide line */
  u: number
  /** seconds since the line began to draw */
  drawn: number
  /** clip time */
  t: number
  t1: number
  travelFor: number
  startPos: Vector3
  startQuat: Quaternion
  startWorldQuat: Quaternion
  startCentre: Vector3
  /** the point of the piece that leads: its middle, or the tip of a screw (node frame) */
  lead: Vector3
  /** where that point comes to rest (world) */
  seat: Vector3
  /** the guide line of this frame, CSS px */
  curve: number[]
}

let flight: Flight | null = null
const glows = new Map<Object3D, number>()
const pulses: { at: Vector3; t: number; reach: number }[] = []

function setGlow(node: Object3D, v: number): void {
  node.traverse((o) => {
    if (!isMesh(o)) return
    const m = o.material as MeshStandardMaterial
    if (m.emissive) m.emissive.copy(FLASH).multiplyScalar(v)
  })
}

/** Pose the assembly. The GLB's own ring at the hole stays hidden: the seat is marked in ink, like every other line. */
function pose(step: number, time: number, action: number): void {
  player.apply(step, time, action)
  if (model.marker) model.marker.visible = false
}

function poseFinished(b: Beat): void {
  if (b.a) pose(b.step, b.a.t1, b.k + 0.5)
  else pose(b.step, player.steps[b.step].duration, 0)
}

/** End whatever is playing at once: the piece stands seated. */
function settle(): void {
  if (!flight) return
  const f = flight
  flight = null
  if (f.node) {
    f.node.position.copy(f.startPos)
    f.node.quaternion.copy(f.startQuat)
    f.node.scale.set(1, 1, 1)
    glows.delete(f.node)
    setGlow(f.node, 0)
  }
  poseFinished(f.beat)
  tool.visible = false
  hideInk()
}

/** Go to a beat. `animate` plays its move; otherwise the beat is shown finished. */
function show(index: number, animate: boolean, first = false): void {
  if (index < 0 || index >= beats.length) return
  settle()
  beatIndex = index
  const b = beats[index]
  for (const k of kinds) k.left = k.total
  for (let i = 0; i <= index; i++) if (beats[i].kind) beats[i].kind!.left--
  updatePage(animate)

  if (!animate) {
    poseFinished(b)
    reframe(first)
    return
  }

  const a = b.a
  const node = a?.node ?? null
  const f: Flight = {
    beat: b,
    node,
    phase: 'clip',
    wait: 0,
    u: 0,
    drawn: 0,
    t: a ? a.t0 : 0,
    t1: a ? a.t1 : player.steps[b.step].duration,
    travelFor: b.kind?.screw ? 0.8 : 1.0,
    startPos: new Vector3(),
    startQuat: new Quaternion(),
    startWorldQuat: new Quaternion(),
    startCentre: new Vector3(),
    lead: new Vector3(),
    seat: new Vector3(),
    curve: [],
  }
  if (a && node && b.kind) {
    // where it ends, then where the clip starts it
    pose(b.step, a.t1, b.k)
    model.scene.updateMatrixWorld(true)
    if (b.kind.screw) {
      const shape = model.fastener(node)
      f.lead.copy(shape.tip)
      f.seat.copy(shape.head).applyMatrix4(node.matrixWorld) // the tip is led to the mouth of the hole
    } else {
      f.lead.copy(b.kind.centre)
      f.seat.copy(b.kind.centre).applyMatrix4(node.matrixWorld)
    }
    pose(b.step, a.t0, b.k)
    model.scene.updateMatrixWorld(true)
    f.startPos.copy(node.position)
    f.startQuat.copy(node.quaternion)
    node.getWorldQuaternion(f.startWorldQuat)
    f.startCentre.copy(b.kind.centre).applyMatrix4(node.matrixWorld)
    if (!still()) f.phase = 'wait' // with reduced motion the piece simply appears where the insertion starts
  } else {
    pose(b.step, f.t, b.k)
  }
  flight = f
  const moved = reframe(first)
  f.wait = moved && !first && !still() ? 0.55 : first ? 0.5 : 0
  if (f.phase === 'wait') poseOnLine(f, 0)
}

const bez = (a: number, b: number, c: number, d: number, t: number) => {
  const s = 1 - t
  return s * s * s * a + 3 * s * s * t * b + 3 * s * t * t * c + t * t * t * d
}
const scr = { x: 0, y: 0, z: 0 }
const scr2 = { x: 0, y: 0, z: 0 }
const mat = new Matrix4()
const inv = new Matrix4()
const shift = new Matrix4()
const qWorld = new Quaternion()
const pWorld = new Vector3()
const sWorld = new Vector3()

/**
 * Put the travelling piece at `e` (0..1) of its guide line. The line is laid out on the screen every frame, from the
 * piece's slot in the list to where its insertion starts, so it stays true while the camera turns.
 */
function poseOnLine(f: Flight, e: number): void {
  const k = f.beat.kind!
  const node = f.node!
  toScreen(f.startCentre, scr)
  toScreen(f.seat, scr2)
  const x0 = k.x
  const y0 = k.y
  const x3 = scr.x
  const y3 = scr.y
  let ax = scr2.x - x3
  let ay = scr2.y - y3
  const al = Math.hypot(ax, ay)
  if (al > 3) {
    ax /= al
    ay /= al
  } else {
    ax = 0
    ay = 1
  }
  // it leaves the list straight down and comes in from above, leaning into the axis of the insertion
  const drop = Math.max(60, y3 - y0)
  let ix = ax * 0.55
  let iy = 1 + ay * 0.55
  const il = Math.hypot(ix, iy) || 1
  ix /= il
  iy /= il
  const reach = Math.min(190, drop * 0.46)
  f.curve = [x0, y0, x0, y0 + drop * 0.5, x3 - ix * reach, y3 - iy * reach, x3, y3]
  const c = f.curve
  const x = bez(c[0], c[2], c[4], c[6], e)
  const y = bez(c[1], c[3], c[5], c[7], e)
  const depth = MathUtils.lerp(LIST_DEPTH, scr.z, e)
  // it stays list-sized while it is still among the numbers, then grows into its real size as it nears its place
  const size = MathUtils.lerp(k.scale / LIST_DEPTH, 1 / scr.z, Math.pow(e, 1.7)) * depth
  fromScreen(x, y, depth, pWorld)
  qWorld.copy(camera.quaternion).multiply(k.list.quaternion).slerp(f.startWorldQuat, easeInOut(e))
  mat.compose(pWorld, qWorld, sWorld.setScalar(size)).multiply(shift.makeTranslation(-k.centre.x, -k.centre.y, -k.centre.z))
  node.parent!.updateWorldMatrix(true, false)
  mat.premultiply(inv.copy(node.parent!.matrixWorld).invert())
  mat.decompose(node.position, node.quaternion, node.scale)
}

/** Review scripts can stop the clock of a move at a chosen moment; the loop and the orbit keep running. */
const hold: { phase: string; at: number } = { phase: '', at: 0 }
function held(): boolean {
  const f = flight
  if (!f || f.phase !== hold.phase) return false
  return f.phase === 'travel' ? f.u >= hold.at : f.t - (f.beat.a ? f.beat.a.t0 : 0) >= hold.at
}

function tickFlight(dt: number): void {
  const f = flight
  if (!f) return
  const b = f.beat
  if (f.phase === 'wait') {
    f.wait -= dt
    poseOnLine(f, 0)
    if (f.wait <= 0) f.phase = 'travel'
    return
  }
  if (f.phase === 'travel') {
    f.u += dt / f.travelFor
    f.drawn += dt
    if (f.u < 1) {
      poseOnLine(f, easeInOut(f.u))
      return
    }
    f.node!.position.copy(f.startPos)
    f.node!.quaternion.copy(f.startQuat)
    f.node!.scale.set(1, 1, 1)
    f.phase = 'clip'
    return
  }
  if (f.wait > 0) {
    f.wait -= dt // a turn waits for the camera too
    return
  }
  f.t += dt
  if (f.t < f.t1) {
    pose(b.step, f.t, b.k)
    return
  }
  // seated
  flight = null
  poseFinished(b)
  tool.visible = false
  hideInk()
  if (f.node) {
    glows.set(f.node, still() ? 0 : 0.5)
    if (!still()) pulses.push({ at: f.seat.clone(), t: 0, reach: b.kind?.screw ? 30 : 54 })
  }
  if (!b.a) {
    sayEl.textContent = FINISHED
    againEl.classList.remove('turn')
    rig.key = ''
    reframe()
  }
}

function tickGlows(dt: number): void {
  for (const [node, v] of glows) {
    const next = v - dt * 0.9
    if (next <= 0) {
      setGlow(node, 0)
      glows.delete(node)
    } else {
      setGlow(node, next * next * 2)
      glows.set(node, next)
    }
  }
}

// the screwdriver rides the screw while the clip drives it
const HANDLE_UP = new Vector3(0, 1, 0)
const align = new Quaternion()
const toolQuat = new Quaternion()
const toolPos = new Vector3()
const toolScale = new Vector3()
function poseTool(): void {
  const f = flight
  const node = f && f.phase === 'clip' && f.beat.kind?.screw ? f.node : null
  if (!f || !node || !node.visible) {
    tool.visible = false
    return
  }
  const shape = model.fastener(node)
  align.setFromUnitVectors(HANDLE_UP, shape.axis.clone().negate())
  node.updateWorldMatrix(true, false)
  node.matrixWorld.decompose(toolPos, toolQuat, toolScale)
  tool.position.copy(shape.head).applyMatrix4(node.matrixWorld)
  tool.quaternion.copy(toolQuat).multiply(align)
  tool.scale.setScalar(still() ? 1 : easeOut(clamp01((f.t - f.beat.a!.t0) / 0.22)))
  tool.visible = true
}

// ------------------------------------------------------------------ the drawn layer

function hideInk(): void {
  for (const el of [ink.leader, ink.soft, ink.axis, ink.seat]) el.classList.remove('on')
}

const lead = new Vector3()
function drawInk(dt: number): void {
  const f = flight
  if (f && f.node && f.beat.kind && (f.phase === 'travel' || f.phase === 'clip')) {
    toScreen(f.seat, scr2)
    let axisFrom: { x: number; y: number } | null = null
    let axisPart = 1
    if (f.phase === 'travel') {
      const c = f.curve
      const head = easeOut(clamp01(f.drawn / 0.42)) // the line runs ahead of the piece
      const tail = easeInOut(clamp01(f.u))
      if (head > tail + 0.004 && c.length) {
        let d = ''
        const n = 26
        for (let i = 0; i <= n; i++) {
          const t = tail + ((head - tail) * i) / n
          d += `${i ? 'L' : 'M'}${bez(c[0], c[2], c[4], c[6], t).toFixed(1)} ${bez(c[1], c[3], c[5], c[7], t).toFixed(1)}`
        }
        ink.leader.setAttribute('d', d)
        ink.soft.setAttribute('d', d)
        ink.leader.classList.add('on')
        ink.soft.classList.add('on')
      } else {
        ink.leader.classList.remove('on')
        ink.soft.classList.remove('on')
      }
      axisPart = easeOut(clamp01((f.drawn - 0.3) / 0.22))
      if (axisPart > 0 && c.length) axisFrom = { x: c[6], y: c[7] }
    } else {
      ink.leader.classList.remove('on')
      ink.soft.classList.remove('on')
      f.node.updateWorldMatrix(true, false)
      toScreen(lead.copy(f.lead).applyMatrix4(f.node.matrixWorld), scr)
      // a screw's tip passes the mouth of the hole: from then on there is nothing left to lead
      const ahead = f.beat.kind.screw ? lead.sub(f.seat).dot(tmp.copy(f.beat.kind.spinAxis).transformDirection(f.node.matrixWorld)) < 0 : true
      if (ahead && Math.hypot(scr2.x - scr.x, scr2.y - scr.y) > 2.5) axisFrom = { x: scr.x, y: scr.y }
    }
    if (axisFrom) {
      ink.axis.setAttribute('x1', axisFrom.x.toFixed(1))
      ink.axis.setAttribute('y1', axisFrom.y.toFixed(1))
      ink.axis.setAttribute('x2', (axisFrom.x + (scr2.x - axisFrom.x) * axisPart).toFixed(1))
      ink.axis.setAttribute('y2', (axisFrom.y + (scr2.y - axisFrom.y) * axisPart).toFixed(1))
      ink.axis.classList.add('on')
    } else ink.axis.classList.remove('on')
    const marked = f.phase === 'clip' || axisPart >= 0.98
    ink.seat.classList.toggle('on', marked)
    if (marked) ink.seat.setAttribute('transform', `translate(${scr2.x.toFixed(1)} ${scr2.y.toFixed(1)})`)
  }

  // the seating: two rings open from the seat and thin out
  const rings = [ink.pulse, ink.pulse2]
  rings.forEach((el) => el.classList.remove('on'))
  for (let i = pulses.length - 1; i >= 0; i--) {
    const p = pulses[i]
    p.t += dt
    if (p.t > 0.7) {
      pulses.splice(i, 1)
      continue
    }
    if (i !== pulses.length - 1) continue // only the latest is drawn
    toScreen(p.at, scr)
    rings.forEach((el, j) => {
      const t = clamp01((p.t - j * 0.09) / 0.6)
      if (t <= 0) return
      el.setAttribute('cx', scr.x.toFixed(1))
      el.setAttribute('cy', scr.y.toFixed(1))
      el.setAttribute('r', (6.5 + (p.reach * (j ? 0.62 : 1) - 6.5) * easeOut(t)).toFixed(1))
      el.setAttribute('stroke-width', (1.6 * (1 - t) + 0.3).toFixed(2))
      el.setAttribute('opacity', (1 - t).toFixed(3))
      el.classList.add('on')
    })
  }
}

// ------------------------------------------------------------------ the page: numbers, one line of words, the scale

function buildRule(): void {
  ruleEl.setAttribute('aria-valuemax', String(beats.length))
  beats.forEach((b, i) => {
    const tick = document.createElement('i')
    if (i === 0 || beats[i - 1].step !== b.step) tick.className = 's'
    ruleEl.append(tick)
  })
}

function updatePage(animate: boolean): void {
  const b = beats[beatIndex]
  // the list
  flow(false)
  for (const k of kinds) {
    k.list.visible = k.left > 0
    k.li.classList.toggle('gone', k.left <= 0)
    const text = `${k.left}`
    if (k.nEl.dataset.n !== text) {
      k.nEl.dataset.n = text
      k.nEl.innerHTML = `${text}<i>×</i>`
    }
    k.plate.visible = k === b.kind
    if (k === b.kind) plateBorn = animate ? performance.now() : 0
  }
  // the one thing to read
  againEl.classList.toggle('turn', !b.kind && !(beatIndex === beats.length - 1 && !animate))
  if (b.a?.type === 'fasten') {
    sayEl.innerHTML = `<span class="code">${b.a.raw.code}</span><span class="of">${b.a.screwIndex}/${b.a.screwTotal}</span>`
  } else if (b.a?.type === 'turn') {
    sayEl.textContent = TURN_WORDS[manifest.steps[b.step]?.n] ?? TURN_FALLBACK
  } else if (b.a) {
    const part = manifest.parts.find((p) => p.id === b.a!.raw.partId)
    sayEl.textContent = part ? displayName('el', part) : ''
  } else {
    sayEl.textContent = animate ? FINISH_TURN : FINISHED
  }
  // the scale
  const ticks = ruleEl.children
  for (let i = 0; i < ticks.length; i++) {
    ticks[i].classList.toggle('d', i < beatIndex)
    ticks[i].classList.toggle('c', i === beatIndex)
  }
  ruleEl.setAttribute('aria-valuenow', String(beatIndex + 1))
  prevEl.disabled = beatIndex <= 0
  nextEl.disabled = beatIndex >= beats.length - 1
}

const next = () => beatIndex < beats.length - 1 && show(beatIndex + 1, true)
const prev = () => beatIndex > 0 && show(beatIndex - 1, false)
const again = () => show(beatIndex, true)
nextEl.addEventListener('click', next)
prevEl.addEventListener('click', prev)
againEl.addEventListener('click', again)
addEventListener('keydown', (e) => {
  if (e.metaKey || e.ctrlKey || e.altKey) return
  if (e.key === 'ArrowRight') next()
  else if (e.key === 'ArrowLeft') prev()
  else return
  e.preventDefault()
})

// ------------------------------------------------------------------ the loop: it never stops, so the orbit never waits

let last = performance.now()
let plateBorn = 0
const sway = new Quaternion()

function drift(now: number): void {
  const calm = still()
  const t = now / 1000
  kinds.forEach((k, i) => {
    // weightless: each piece in the list breathes a little on its own beat
    const a = calm ? 0 : Math.sin(t * 0.55 + i * 1.7) * 0.07
    const b = calm ? 0 : Math.sin(t * 0.4 + i * 2.3) * 0.035
    k.list.quaternion.copy(sway.setFromAxisAngle(Y, a)).multiply(k.quat)
    k.list.quaternion.premultiply(sway.setFromAxisAngle(X, b))
    if (!k.plate.visible) return
    if (k.screw) {
      // the screw on the plate turns slowly on its own axis, as it will in the wood
      k.plate.quaternion.copy(k.plateQuat).multiply(sway.setFromAxisAngle(k.spinAxis, calm ? 0 : t * 0.5))
    } else {
      k.plate.quaternion.copy(sway.setFromAxisAngle(Y, calm ? 0 : Math.sin(t * 0.5) * 0.12)).multiply(k.plateQuat)
    }
    const born = calm || !plateBorn ? 1 : easeOut(clamp01((now - plateBorn) / 450))
    k.plate.scale.setScalar(k.plateScale * (0.9 + 0.1 * born))
  })
}

function frame(now: number): void {
  requestAnimationFrame(frame)
  const dt = Math.min(0.05, Math.max(0, (now - last) / 1000))
  last = now
  frameCount++

  if (rig.p < 1) {
    rig.p = Math.min(1, rig.p + dt / rig.duration)
    placeCamera(easeInOut(rig.p))
  }
  controls.update()
  camera.updateMatrixWorld()

  placeList(dt)
  drift(now)
  tickFlight(held() ? 0 : dt)
  tickGlows(dt)
  poseTool()
  drawInk(dt)

  renderer.clear()
  renderer.render(scene, camera)
  renderer.clearDepth() // the list is drawn over the space, never inside it
  renderer.render(listScene, listCamera)
}

boot().catch((err) => {
  console.error(err)
  document.body.classList.remove('loading')
  sayEl.textContent = 'Το μοντέλο δεν φορτώθηκε'
})

// for the design review scripts
Object.assign(window, {
  __drawing: {
    get beat() {
      return beatIndex
    },
    get beats() {
      return beats.length
    },
    get phase() {
      return flight ? flight.phase : 'rest'
    },
    get frames() {
      return frameCount
    },
    get left() {
      return Object.fromEntries(kinds.map((k) => [k.key, k.left]))
    },
    eye: () => camera.position.toArray(),
    /** stop the next move when it reaches `at`: a share of the way while travelling, seconds into the clip otherwise */
    holdAt(phase: string, at: number) {
      hold.phase = phase
      hold.at = at
    },
    get held() {
      return held()
    },
    show,
    next,
    prev,
    again,
  },
})
