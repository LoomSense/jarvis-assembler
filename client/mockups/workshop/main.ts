// Workshop at night: the assembly on a disc of light, the pieces still in the box hovering above it,
// the piece to pick up between two buttons. One beat is one action of the guide.
import {
  Box3,
  BoxGeometry,
  Color,
  CylinderGeometry,
  Group,
  MathUtils,
  Matrix4,
  Mesh,
  MeshStandardMaterial,
  Object3D,
  Quaternion,
  Spherical,
  Vector3,
} from 'three'
import { fetchManifest, fileUrl } from '/src/api.ts'
import { cloneForDisplay, loadGltf, localBox, prepareModel, type Model } from '/src/viewer/model.ts'
import { StepPlayer, type ActionInfo } from '/src/viewer/steps.ts'
import { displayName } from '/src/i18n.ts'
import type { Lang, Manifest } from '/src/types.ts'
import { dress, type ScrewShape } from './materials'
import { FOV, Stage } from './stage'

// ------------------------------------------------------------------ words

const query = new URLSearchParams(location.search)
const lang: Lang = query.get('lang') === 'en' ? 'en' : 'el'
const JOB = query.get('job') || 'local-fresh'

const WORDS = {
  el: {
    prev: 'Προηγούμενο',
    next: 'Επόμενο',
    stock: 'Μένουν στο κουτί',
    progress: 'Πρόοδος',
    screw: 'Βίδα {code}, {i} από {n}',
    done: 'Έτοιμο',
    failed: 'Το μοντέλο δεν φορτώθηκε',
    turn: 'Γύρνα το',
    poses: { upside_down: 'Γύρνα το ανάποδα', on_end_a: 'Στήσε το όρθιο', on_end_b: 'Στην άλλη άκρη', upright: 'Γύρνα το κανονικά' },
  },
  en: {
    prev: 'Previous',
    next: 'Next',
    stock: 'Left in the box',
    progress: 'Progress',
    screw: 'Screw {code}, {i} of {n}',
    done: 'Done',
    failed: 'The model did not load',
    turn: 'Turn it',
    poses: { upside_down: 'Turn it over', on_end_a: 'Stand it on end', on_end_b: 'Onto the other end', upright: 'Set it upright' },
  },
} as const
const W = WORDS[lang]

// ------------------------------------------------------------------ page

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T
const canvas = $<HTMLCanvasElement>('view')
const stockEl = $<HTMLUListElement>('stock')
const nowEl = $<HTMLParagraphElement>('now')
const prevBtn = $<HTMLButtonElement>('prev')
const nextBtn = $<HTMLButtonElement>('next')
const progressEl = $<HTMLDivElement>('progress')
document.documentElement.lang = lang
prevBtn.setAttribute('aria-label', W.prev)
nextBtn.setAttribute('aria-label', W.next)
stockEl.setAttribute('aria-label', W.stock)
progressEl.setAttribute('aria-label', W.progress)

const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)')
const reduced = () => reducedQuery.matches

const stage = new Stage(canvas)

// ------------------------------------------------------------------ small maths

const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)
const easeOut = (t: number) => 1 - Math.pow(1 - t, 3)
const X = new Vector3(1, 0, 0)
const Y = new Vector3(0, 1, 0)
const Z = new Vector3(0, 0, 1)
const qa = new Quaternion()
const qb = new Quaternion()
const va = new Vector3()
const vb = new Vector3()

// ------------------------------------------------------------------ the kinds of piece in the box

interface Display {
  /** the copy shown in the tray; its local frame is the frame of the real node */
  piece: Object3D
  /** turns the piece so a board faces the viewer with its long side level, or a screw points down */
  base: Quaternion
  /** middle of the piece in its own frame */
  centre: Vector3
  /** tray metres per real metre */
  scale: number
}

interface Kind {
  key: string
  screw: boolean
  code: string | null
  name: string
  total: number
  left: number
  nodes: Set<Object3D>
  /** longest side, metres */
  length: number
  headRadius: number
  axis: Vector3
  /** how far a board leans back from facing the viewer: wide panels lie almost flat, narrow slats show their face */
  pitch: number
  phase: number
  /** where it hovers along the top row, CSS pixels: the kinds still in the box close ranks as others run out */
  x: number
  xTarget: number
  /** the one that hovers at the top */
  stock: Display & { holder: Group; slot: Vector3 }
  /** the one between the buttons */
  crisp: Display
  label: HTMLLIElement
  count: HTMLElement
}

let manifest: Manifest
let model: Model
let player: StepPlayer
const kinds: Kind[] = []
const kindOf = new Map<Object3D, Kind>()
const crispHolder = new Group()
const crispSlot = new Vector3()
stage.trayScene.add(crispHolder)
let crispKind: Kind | null = null
let crispIn = 1
let phone = false
let rowPitch = 148
let rowY = 80
let insetTop = 150
let insetBottom = 230

function makeDisplay(node: Object3D, screw: boolean, shape: ScrewShape | null, shapeOf: (code: string) => ScrewShape | null): Display {
  const piece = cloneForDisplay(node)
  dress(piece, shapeOf, false)
  const base = new Quaternion()
  const centre = new Vector3()
  if (screw && shape) {
    base.setFromUnitVectors(shape.axis, new Vector3(0, -1, 0))
    centre.copy(shape.head).addScaledVector(shape.axis, shape.length / 2)
  } else {
    const box = localBox(node)
    box.getCenter(centre)
    const size = box.getSize(new Vector3()).toArray()
    const order = [0, 1, 2].sort((a, b) => size[b] - size[a])
    const long = new Vector3().setComponent(order[0], 1)
    const thin = new Vector3().setComponent(order[2], 1)
    const wide = new Vector3().crossVectors(thin, long)
    base.setFromRotationMatrix(new Matrix4().makeBasis(long, wide, thin)).invert() // long -> X, thin -> Z (towards the viewer)
  }
  return { piece, base, centre, scale: 1 }
}

function buildKinds(): void {
  const shapes = new Map<string, ScrewShape>()
  for (const h of manifest.hardware) {
    const n = model.node(h.nodes[0])
    if (n) shapes.set(h.code, model.fastener(n))
  }
  const shapeOf = (code: string) => shapes.get(code) ?? null
  dress(model.scene, shapeOf, true)
  model.marker?.traverse((o) => {
    const m = (o as Mesh).material as MeshStandardMaterial | undefined
    if (!m?.emissive) return
    m.color.set(0x000000) // the hole ring is light only: the brand azure, the one cool thing on the wood
    m.emissive.set('#9ecfff')
    m.emissiveIntensity = 1.15
  })

  const add = (key: string, screw: boolean, name: string, total: number, names: string[]) => {
    const nodes = names.map((n) => model.node(n)).filter((n): n is Object3D => !!n)
    if (!nodes.length) return
    const shape = screw ? shapeOf(key) : null
    const size = localBox(nodes[0]).getSize(new Vector3())
    const stock = makeDisplay(nodes[0], screw, shape, shapeOf)
    const crisp = makeDisplay(nodes[0], screw, shape, shapeOf)
    const holder = new Group()
    holder.add(stock.piece)
    stage.trayScene.add(holder)
    crisp.piece.visible = false
    crispHolder.add(crisp.piece)

    const label = document.createElement('li')
    const count = document.createElement('b')
    label.append(count)
    if (screw) {
      const code = document.createElement('span')
      code.textContent = key
      label.append(code)
    }
    stockEl.append(label)

    const kind: Kind = {
      key,
      screw,
      code: screw ? key : null,
      name,
      total,
      left: total,
      nodes: new Set(nodes),
      length: shape ? shape.length : Math.max(size.x, size.y, size.z),
      headRadius: shape ? shape.headRadius : 0,
      axis: shape ? shape.axis.clone() : new Vector3(0, 1, 0),
      pitch: -MathUtils.clamp(0.25 + 3.2 * ([size.x, size.y, size.z].sort((a, b) => b - a)[1] / Math.max(size.x, size.y, size.z) - 0.2), 0.45, 0.95),
      phase: kinds.length * 1.7 + 0.4,
      x: Number.NaN,
      xTarget: 0,
      stock: { ...stock, holder, slot: new Vector3() },
      crisp,
      label,
      count,
    }
    kinds.push(kind)
    for (const n of nodes) kindOf.set(n, kind)
  }
  for (const p of manifest.parts) add(p.id, false, displayName(lang, p), p.count, p.nodes)
  for (const h of manifest.hardware) add(h.code, true, displayName(lang, h), h.count, h.nodes)

  // the one warm accent of the page is the plating of the brass-coloured screw, read from the manifest
  const brass = manifest.hardware.map((h) => h.color).filter((c): c is [number, number, number] => !!c).sort((a, b) => b[0] - b[2] - (a[0] - a[2]))[0]
  if (brass && brass[0] - brass[2] > 0.2) {
    document.documentElement.style.setProperty('--brass', `#${new Color().setRGB(brass[0], brass[1], brass[2]).getHexString()}`)
  }
}

// ------------------------------------------------------------------ layout (CSS pixels -> tray)

function layout(): void {
  const w = window.innerWidth
  const h = window.innerHeight
  phone = w < 700
  insetTop = phone ? 132 : 156
  insetBottom = phone ? 232 : 236
  stage.resize(w, h, insetTop, insetBottom)
  const u = stage.trayUnit

  rowPitch = Math.min(phone ? 76 : 148, (w - (phone ? 16 : 160)) / Math.max(1, kinds.length))
  rowY = phone ? 58 : 80
  const boardPx = Math.min(rowPitch * 0.78, 116)
  const screwPx = Math.min(rowPitch * 0.8, 76)
  const longestBoard = Math.max(0.001, ...kinds.filter((k) => !k.screw).map((k) => k.length))
  const longestScrew = Math.max(0.001, ...kinds.filter((k) => k.screw).map((k) => k.length))
  for (const k of kinds) {
    // boards share one scale; a short screw is drawn larger than its share, or it would be a speck
    const px = k.screw ? (screwPx * Math.sqrt(k.length / longestScrew)) / k.length : boardPx / longestBoard
    k.stock.scale = px * u
    k.label.style.top = `${rowY + (phone ? 30 : 42)}px`

    // between the buttons: boards share one scale; a screw is as large as fits, so its cross can be read
    const crispPx = k.screw
      ? Math.min((phone ? 44 : 50) / (2 * k.headRadius), (phone ? 170 : 200) / (0.74 * k.length))
      : (phone ? 150 : 176) / longestBoard
    k.crisp.scale = crispPx * u
    k.x = Number.NaN // settle on the new row at once
  }
  flowRow()
  stage.trayPoint(w / 2, h - (phone ? 150 : 156), crispSlot)
  crispHolder.position.copy(crispSlot)
  stage.aimCrisp(crispSlot, 128 * u)
}

/** Centre the kinds that are still in the box along the top row. */
function flowRow(): void {
  const present = kinds.filter((k) => k.left > 0)
  present.forEach((k, i) => (k.xTarget = stage.width / 2 + (i - (present.length - 1) / 2) * rowPitch))
}

// ------------------------------------------------------------------ the tray, every frame

const CRISP_AXIS = new Vector3(0.64, -0.36, -0.68).normalize()
const DOWN = new Vector3(0, -1, 0)

function poseDisplay(d: Display, q: Quaternion, scale: number): void {
  d.piece.quaternion.copy(q)
  d.piece.scale.setScalar(scale)
  d.piece.position.copy(d.centre).applyQuaternion(q).multiplyScalar(-scale) // its middle on the holder
}

function tickTray(time: number, dt: number): void {
  const still = reduced()
  const t = still ? 0 : time
  const u = stage.trayUnit
  for (const k of kinds) {
    const s = k.stock
    s.holder.visible = k.left > 0
    if (k.left > 0 || Number.isNaN(k.x)) {
      const before = k.x
      k.x = still || Number.isNaN(k.x) ? k.xTarget : k.x + (k.xTarget - k.x) * (1 - Math.exp(-dt * 5))
      if (!(Math.abs(k.x - before) < 0.05)) k.label.style.left = `${k.x.toFixed(1)}px`
    }
    stage.trayPoint(k.x, rowY, s.slot)
    s.holder.position.copy(s.slot)
    s.holder.position.y += Math.sin(t * 0.55 + k.phase) * 3.2 * u
    s.holder.position.x += Math.sin(t * 0.31 + k.phase * 2.1) * 1.6 * u
    if (k.screw) {
      qa.setFromAxisAngle(Y, t * 0.35 + k.phase) // about its own axis (the base turned that onto Y)
      qb.setFromAxisAngle(X, 0.5 + 0.1 * Math.sin(t * 0.4 + k.phase))
      qb.multiply(qa).multiply(s.base)
      qa.setFromAxisAngle(Z, 0.3 + 0.12 * Math.sin(t * 0.27 + k.phase))
      qa.multiply(qb)
    } else {
      qb.setFromAxisAngle(X, k.pitch + 0.1 * Math.sin(t * 0.33 + k.phase))
      qb.multiply(s.base)
      qa.setFromAxisAngle(Y, -0.42 + 0.5 * Math.sin(t * 0.21 + k.phase))
      qa.multiply(qb)
      qb.setFromAxisAngle(Z, 0.07 * Math.sin(t * 0.26 + k.phase * 1.3))
      qa.premultiply(qb)
    }
    poseDisplay(s, qa, s.scale)
  }

  crispIn = Math.min(1, crispIn + dt / 0.3)
  const grow = still ? 1 : 0.86 + 0.14 * easeOut(crispIn)
  for (const k of kinds) {
    const c = k.crisp
    c.piece.visible = k === crispKind
    if (k !== crispKind) continue
    if (k.screw) {
      qa.setFromAxisAngle(Y, t * 0.22 + 0.6)
      qb.setFromUnitVectors(DOWN, CRISP_AXIS)
      qb.multiply(qa).multiply(c.base)
      qa.setFromAxisAngle(Y, 0.16 * Math.sin(t * 0.5))
      qa.multiply(qb)
    } else {
      qb.setFromAxisAngle(X, k.pitch)
      qb.multiply(c.base)
      qa.setFromAxisAngle(Y, -0.38 + 0.2 * Math.sin(t * 0.4))
      qa.multiply(qb)
    }
    poseDisplay(c, qa, c.scale * grow)
  }
}

// ------------------------------------------------------------------ the screwdriver

/** A cabinetmaker's screwdriver, about 176 mm: beech handle, brass ferrule. Tip at the origin, handle up +Y. */
function buildScrewdriver(): Group {
  const steel = new MeshStandardMaterial({ color: 0xc9ccd1, metalness: 0.95, roughness: 0.26 })
  const beech = new MeshStandardMaterial({ color: 0x8a4f26, metalness: 0, roughness: 0.42 })
  const ferrule = new MeshStandardMaterial({ color: 0xd9a441, metalness: 0.95, roughness: 0.3 })
  const tool = new Group()
  const add = (mesh: Mesh, y: number) => {
    mesh.position.y = y
    mesh.castShadow = true
    tool.add(mesh)
  }
  add(new Mesh(new BoxGeometry(0.0052, 0.005, 0.0011), steel), 0.0025)
  add(new Mesh(new BoxGeometry(0.0011, 0.005, 0.0052), steel), 0.0025)
  add(new Mesh(new CylinderGeometry(0.0021, 0.0021, 0.07, 20), steel), 0.039)
  add(new Mesh(new CylinderGeometry(0.0072, 0.0052, 0.012, 28), ferrule), 0.08)
  add(new Mesh(new CylinderGeometry(0.0128, 0.0098, 0.05, 28), beech), 0.111)
  add(new Mesh(new CylinderGeometry(0.0118, 0.0128, 0.034, 28), beech), 0.153)
  add(new Mesh(new CylinderGeometry(0.0062, 0.0118, 0.007, 28), beech), 0.1735)
  // one flat on the handle, so its turning shows
  add(new Mesh(new BoxGeometry(0.0262, 0.03, 0.006), beech), 0.13)
  tool.visible = false
  return tool
}
const tool = buildScrewdriver()
stage.scene.add(tool)
const toolAlign = new Quaternion()

function rideScrew(node: Object3D, grow: number): void {
  const shape = model.fastener(node)
  toolAlign.setFromUnitVectors(Y, va.copy(shape.axis).negate())
  node.updateWorldMatrix(true, false)
  node.matrixWorld.decompose(va, qa, vb)
  tool.position.copy(shape.head).applyMatrix4(node.matrixWorld)
  tool.quaternion.copy(qa).multiply(toolAlign)
  tool.scale.setScalar(Math.max(0.001, grow))
  tool.visible = true
}

// ------------------------------------------------------------------ beats

interface Beat {
  step: number
  k: number
  /** null for the closing turn */
  a: ActionInfo | null
  t0: number
  t1: number
}
const beats: Beat[] = []
let cur = -1
type Phase = 'idle' | 'fly' | 'clip'
let phase: Phase = 'idle'
let phaseTime = 0
let clipTime = 0
const startPos = new Vector3()
const startQuat = new Quaternion()
const settle = { t: 1, node: null as Object3D | null, amp: 0 }
let frames = 0

function setGlow(node: Object3D | null, amount: number): void {
  node?.traverse((o) => {
    const m = (o as Mesh).material as MeshStandardMaterial | undefined
    if (!m || !m.emissive) return
    m.emissive.setRGB(1, 0.62, 0.25)
    m.emissiveIntensity = amount
  })
}

function endSettle(): void {
  if (settle.node) setGlow(settle.node, 0)
  settle.node = null
  settle.t = 1
  model.scene.position.y = 0
}

/** Bring the beat on screen to its end at once. */
function finishNow(): void {
  if (cur < 0) return
  const b = beats[cur]
  if (phase === 'fly' && b.a?.node) {
    b.a.node.position.copy(startPos)
    b.a.node.quaternion.copy(startQuat)
    b.a.node.scale.set(1, 1, 1)
  }
  if (phase !== 'idle') player.apply(b.step, b.t1, b.a ? b.k + 0.5 : 0)
  phase = 'idle'
  tool.visible = false
  endSettle()
}

function describe(b: Beat, ended: boolean): void {
  const a = b.a
  nowEl.replaceChildren()
  const say = (text: string) => {
    const sr = document.createElement('span')
    sr.className = 'sr'
    sr.textContent = text
    nowEl.append(sr)
  }
  const show = (text: string, cls?: string) => {
    const el = document.createElement('span')
    el.textContent = text
    el.setAttribute('aria-hidden', 'true')
    if (cls) el.className = cls
    nowEl.append(el)
  }
  if (a && a.type === 'fasten') {
    const code = a.raw.code ?? ''
    show(code)
    show(`${a.screwIndex}/${a.screwTotal}`, 'of')
    say(W.screw.replace('{code}', code).replace('{i}', String(a.screwIndex)).replace('{n}', String(a.screwTotal)))
  } else if (a && a.node) {
    const name = kindOf.get(a.node)?.name ?? ''
    show(name)
    say(name)
  } else {
    const pose = (a ? manifest.steps[b.step]?.pose : 'upright') as keyof typeof W.poses | undefined
    const text = !a && ended ? W.done : ((pose && W.poses[pose]) ?? W.turn)
    show(text)
    say(text)
  }
}

function syncPage(): void {
  const b = beats[cur]
  const used = new Map<Kind, number>()
  for (let i = 0; i <= cur; i++) {
    const k = beats[i].a?.node ? kindOf.get(beats[i].a!.node!) : undefined
    if (k) used.set(k, (used.get(k) ?? 0) + 1)
  }
  for (const k of kinds) {
    k.left = k.total - (used.get(k) ?? 0)
    k.count.textContent = String(k.left)
    k.label.dataset.left = String(k.left)
    k.label.setAttribute('aria-label', `${k.name}${k.code ? ` ${k.code}` : ''}: ${k.left}`)
  }
  flowRow()
  const kind = b.a?.node ? (kindOf.get(b.a.node) ?? null) : null
  if (kind !== crispKind) {
    crispKind = kind
    crispIn = 0
  }
  describe(b, false)
  prevBtn.setAttribute('aria-disabled', String(cur <= 0))
  nextBtn.setAttribute('aria-disabled', String(cur >= beats.length - 1))
  progressEl.style.setProperty('--p', String((cur + 1) / beats.length))
  progressEl.setAttribute('aria-valuenow', String(cur + 1))
}

function begin(i: number): void {
  finishNow()
  const before = cur < 0 ? -1 : beats[cur].step
  cur = MathUtils.clamp(i, 0, beats.length - 1)
  const b = beats[cur]
  stage.controls.autoRotate = false

  // camera first: measuring the step moves the pieces, so the start pose is applied after it
  if (b.step !== before || !b.a || b.a.type === 'turn') aimCamera(b, before < 0)
  player.apply(b.step, b.t0, b.a ? b.k : 0)
  clipTime = b.t0
  phaseTime = 0
  syncPage()

  const node = b.a?.node ?? null
  if (node && !reduced()) {
    startPos.copy(node.position)
    startQuat.copy(node.quaternion)
    phase = 'fly'
    flyStep(0)
  } else {
    phase = 'clip'
  }
}

/** Carry the real piece from the hovering one to where its clip starts. */
function flyStep(k: number): void {
  const b = beats[cur]
  const node = b.a!.node!
  const kind = kindOf.get(node)
  const parent = node.parent
  if (!kind || !parent) return
  const from = kind.stock.piece
  from.updateWorldMatrix(true, false)
  from.matrixWorld.decompose(va, qa, vb) // tray space is the space of the camera
  stage.camera.updateMatrixWorld()
  parent.updateWorldMatrix(true, false)
  va.applyMatrix4(stage.camera.matrixWorld)
  parent.worldToLocal(va)
  qa.premultiply(stage.camera.quaternion)
  qa.premultiply(parent.getWorldQuaternion(qb).invert())
  const e = easeInOut(k)
  node.position.lerpVectors(va, startPos, e)
  node.quaternion.slerpQuaternions(qa, startQuat, e)
  node.scale.setScalar(MathUtils.lerp(vb.x, 1, e))
}

function tickBeat(dt: number): void {
  if (cur < 0) return
  const b = beats[cur]
  if (phase === 'fly') {
    phaseTime += dt
    const k = Math.min(1, phaseTime / (b.a!.type === 'fasten' ? 0.62 : 0.85))
    flyStep(k)
    if (k >= 1) {
      const node = b.a!.node!
      node.position.copy(startPos)
      node.quaternion.copy(startQuat)
      node.scale.set(1, 1, 1)
      phase = 'clip'
      phaseTime = 0
    }
  } else if (phase === 'clip') {
    phaseTime += dt
    clipTime = Math.min(b.t1, clipTime + dt)
    player.apply(b.step, clipTime, b.a ? b.k : 0)
    if (b.a?.type === 'fasten' && b.a.node) rideScrew(b.a.node, easeOut(Math.min(1, phaseTime / 0.16)))
    if (clipTime >= b.t1) {
      player.apply(b.step, b.t1, b.a ? b.k + 0.5 : 0)
      phase = 'idle'
      tool.visible = false
      if (!b.a) {
        describe(b, true)
        if (!reduced()) stage.controls.autoRotate = true // the finished box turns once the work is done
      } else if (b.a.node && !reduced()) {
        settle.t = 0
        settle.node = b.a.node
        settle.amp = b.a.type === 'fasten' ? 0.0005 : 0.0016
      }
    }
  }
  if (settle.t < 1) {
    settle.t = Math.min(1, settle.t + dt / 0.36)
    const x = settle.t
    model.scene.position.y = -settle.amp * Math.sin(Math.PI * x) * (1 - x) * 1.7
    setGlow(settle.node, 0.2 * (1 - x) * (1 - x))
    if (x >= 1) endSettle()
  }
}

const next = () => cur < beats.length - 1 && begin(cur + 1)
const prev = () => cur > 0 && begin(cur - 1)

// ------------------------------------------------------------------ camera

const tween = { k: 1, fromTarget: new Vector3(), toTarget: new Vector3(), from: new Spherical(), to: new Spherical() }
let holding = false
stage.controls.addEventListener('start', () => {
  holding = true
  tween.k = 1 // a press takes the camera
  stage.controls.autoRotate = false
})
stage.controls.addEventListener('end', () => (holding = false))

function viewDir(step: number): Vector3 {
  const cam = manifest.steps[step]?.camera
  const node = model.node(cam?.node)
  if (node) {
    node.updateWorldMatrix(true, false)
    return new Vector3(0, 0, -1).transformDirection(node.matrixWorld)
  }
  if (cam?.pos?.length === 3 && cam.target?.length === 3) {
    const d = new Vector3(cam.target[0] - cam.pos[0], cam.target[2] - cam.pos[2], -(cam.target[1] - cam.pos[1]))
    if (d.lengthSq() > 1e-6) return d.normalize()
  }
  return stage.controls.target.clone().sub(stage.camera.position).normalize()
}

/** Fit `box` between the two bands of the tray, looking along `dir`. */
function frameCamera(box: Box3, dir: Vector3, immediate: boolean): void {
  if (box.isEmpty()) return
  const { width, height } = stage
  const tanFull = Math.tan(MathUtils.degToRad(FOV) / 2)
  const tanV = tanFull * ((height - insetTop - insetBottom) / height)
  const tanH = tanFull * (width / height) * (phone ? 0.9 : 0.7)

  const off = dir.clone().normalize().negate() // from the box to the camera
  const elevation = MathUtils.clamp(Math.asin(off.y), 0.3, 0.72)
  const flat = Math.hypot(off.x, off.z) || 1
  off.set((off.x / flat) * Math.cos(elevation), Math.sin(elevation), (off.z / flat) * Math.cos(elevation))
  const fwd = off.clone().negate()
  const right = new Vector3().crossVectors(fwd, Y).normalize()
  const up = new Vector3().crossVectors(right, fwd)

  const centre = box.getCenter(new Vector3())
  let dist = 0
  const c = new Vector3()
  for (let i = 0; i < 8; i++) {
    c.set(i & 1 ? box.max.x : box.min.x, i & 2 ? box.max.y : box.min.y, i & 4 ? box.max.z : box.min.z).sub(centre)
    const depth = c.dot(fwd)
    dist = Math.max(dist, Math.abs(c.dot(right)) / tanH - depth, Math.abs(c.dot(up)) / tanV - depth)
  }
  dist = MathUtils.clamp(dist * 1.06, stage.controls.minDistance, stage.controls.maxDistance)

  const controls = stage.controls
  tween.fromTarget.copy(controls.target)
  tween.from.setFromVector3(va.copy(stage.camera.position).sub(controls.target))
  tween.toTarget.copy(centre)
  tween.to.setFromVector3(off.multiplyScalar(dist))
  const d = tween.to.theta - tween.from.theta
  if (d > Math.PI) tween.to.theta -= 2 * Math.PI
  if (d < -Math.PI) tween.to.theta += 2 * Math.PI
  if (holding && !immediate) return // the person is turning it: leave the camera in their hand
  tween.k = immediate || reduced() ? 1 : 0
  if (tween.k === 1) placeCamera(1)
}

function placeCamera(k: number): void {
  const s = new Spherical(
    MathUtils.lerp(tween.from.radius, tween.to.radius, k),
    MathUtils.lerp(tween.from.phi, tween.to.phi, k),
    MathUtils.lerp(tween.from.theta, tween.to.theta, k),
  ).makeSafe()
  stage.controls.target.lerpVectors(tween.fromTarget, tween.toTarget, k)
  stage.camera.position.setFromSpherical(s).add(stage.controls.target)
}

function tickCamera(dt: number): void {
  if (tween.k >= 1) return
  tween.k = Math.min(1, tween.k + dt / 1.1)
  placeCamera(easeInOut(tween.k))
}

/** The whole of the step in view, from the side the guide draws it. */
function aimCamera(b: Beat, immediate: boolean): void {
  const st = player.steps[b.step]
  const box = player.boundsAt(b.step, st.duration, st.actions.length).clone()
  // Each piece starts its clip a little away from its place. Keep most of that in view too, without letting
  // one far start pose shrink the assembly.
  const wide = box.clone()
  for (const a of st.actions) if (a.type !== 'fasten') wide.union(player.boundsAt(b.step, a.t0, a.index))
  if (b.a?.type === 'turn') wide.union(player.boundsAt(b.step, b.t0 + (b.t1 - b.t0) * 0.5, b.k))
  box.min.lerp(wide.min, 0.5)
  box.max.lerp(wide.max, 0.5)
  let dir: Vector3
  if (b.a) {
    dir = viewDir(b.step)
    // The guide's view can have the screw holes on the far side. Then look from outside the face the screws
    // enter, leaning to the side the guide looks from.
    const out = new Vector3()
    player.apply(b.step, st.duration, st.actions.length)
    for (const a of st.actions) {
      if (a.type !== 'fasten' || !a.node) continue
      a.node.updateWorldMatrix(true, false)
      out.add(va.copy(model.fastener(a.node).axis).transformDirection(a.node.matrixWorld).negate())
    }
    if (out.lengthSq() > 1e-6) {
      out.normalize()
      const toCamera = dir.clone().negate()
      if (toCamera.dot(out) < 0.4) {
        const side = toCamera.clone().addScaledVector(out, -toCamera.dot(out))
        if (side.lengthSq() < 1e-3) side.set(0, 1, 0).addScaledVector(out, -out.y)
        if (side.lengthSq() < 1e-3) side.set(1, 0, 0)
        side.normalize()
        dir = out.multiplyScalar(0.72).addScaledVector(side, 0.69).normalize().negate()
      }
    }
  } else {
    dir = stage.controls.target.clone().sub(stage.camera.position).normalize()
    dir.y = -0.45
    dir.normalize()
  }
  frameCamera(box, dir, immediate)
}

// ------------------------------------------------------------------ start

async function start(): Promise<void> {
  manifest = await fetchManifest(JOB, query.get('key'))
  const gltf = await loadGltf(fileUrl(JOB, manifest.glb, query.get('key'))) // the full GLB: unquantised normals shade cleaner
  model = prepareModel(gltf, manifest)
  player = new StepPlayer(model, manifest)
  buildKinds()
  stage.scene.add(model.scene)

  for (const st of player.steps) {
    if (st.index === player.finishIndex) {
      if (st.clip) beats.push({ step: st.index, k: 0, a: null, t0: 0, t1: st.duration })
    } else for (const a of st.actions) beats.push({ step: st.index, k: a.index, a, t0: a.t0, t1: a.t1 })
  }
  progressEl.setAttribute('aria-valuemax', String(beats.length))

  // the disc sits under everything the assembly ever covers
  const reachBox = new Box3()
  for (const st of player.steps) reachBox.union(player.boundsAt(st.index, st.duration, st.actions.length))
  const centre = reachBox.getCenter(new Vector3())
  const size = reachBox.getSize(new Vector3())
  stage.setFocus(new Vector3(centre.x, 0, centre.z), Math.hypot(size.x, size.z) * 0.5 * 1.12)

  layout()
  const at = Number(query.get('at'))
  begin(Number.isInteger(at) && at > 0 ? at : 0)
  if (query.has('at')) finishNow()
  document.documentElement.dataset.ready = 'true'
}

let last = performance.now()
let time = 0
function frame(now: number): void {
  requestAnimationFrame(frame)
  const dt = Math.min(0.05, Math.max(0, (now - last) / 1000))
  last = now
  time += dt
  if (model) {
    tickBeat(dt)
    tickCamera(dt)
    tickTray(time, dt)
  }
  stage.controls.update(dt)
  stage.draw(time, !reduced())
  frames++
}

window.addEventListener('resize', layout)
nextBtn.addEventListener('click', next)
prevBtn.addEventListener('click', prev)
window.addEventListener('keydown', (e) => {
  if (e.altKey || e.ctrlKey || e.metaKey) return
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
  nowEl.setAttribute('role', 'alert')
  nowEl.textContent = W.failed
})

// for the check scripts
Object.assign(window, {
  __ws: {
    stage,
    next,
    prev,
    begin,
    finishNow,
    get cur() {
      return cur
    },
    get phase() {
      return phase
    },
    get beats() {
      return beats
    },
    get frames() {
      return frames
    },
    get kinds() {
      return kinds
    },
    get player() {
      return player
    },
  },
})
