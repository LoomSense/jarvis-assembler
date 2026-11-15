// Workshop at night: the assembly on a disc of light, the pieces still in the box hovering above it,
// the piece to pick up between two buttons. One beat is one action of the guide.
//
// The loop never stops and the orbit controls are never disabled: a press always takes the camera.
import { Box3, Color, Group, MathUtils, Matrix4, Object3D, PropertyBinding, Quaternion, Spherical, Vector3, type Mesh, type MeshStandardMaterial } from 'three'
import { displayName, localise } from '../i18n'
import type { Lang, Manifest, ManifestHardware, ManifestPart } from '../types'
import { cloneForDisplay, isMesh, localBox, type Model } from '../viewer/model'
import { StepPlayer, type ActionInfo } from '../viewer/steps'
import { bakeQuantised, dress, sampleSurface, type Finish, type ScrewShape } from './materials'
import { FLIGHT_LAYER, FOV, type Stage } from './stage'
import { buildScrewdriver, TOOL_LENGTH, TOOL_RADIUS } from './tool'
import { firstSentence, turnWords, WORDS } from './words'

export interface GuidePage {
  stock: HTMLUListElement
  now: HTMLParagraphElement
  prev: HTMLButtonElement
  next: HTMLButtonElement
  progress: HTMLElement
  /** the group inside the seat mark's svg */
  seat: SVGGElement
}

export interface GuideOptions {
  stage: Stage
  page: GuidePage
  manifest: Manifest
  model: Model
  lang: Lang
  /** open with this beat finished instead of playing the first one (0-based) */
  openAt: number | null
  /** debug: `?material=white` and the like, see materials.ts */
  material: string | null
}

/** Where the walk is, in the manifest's own numbers: the step's `n` and the 0-based index into its `actions`. */
export interface Place {
  step: number
  /** null for a step the manifest lists no actions for */
  action: number | null
}

export interface Guide {
  setLang(lang: Lang): void
  /** where the walk is now */
  place(): Place
  /** `fn` is called after every change of place */
  onPlace(fn: (place: Place) => void): void
  /** The part id or hardware code of the piece between the buttons, or null during a turn. */
  wanted(): string | null
  /** How the page writes a part id (its short name) or a hardware code (the code); null when the guide has no such thing. */
  label(key: string): string | null
  /** The few words for the turn a step begins with (live guidance: "the assembly stands the wrong way"). */
  turnOf(step: number): string
  /**
   * Live guidance: what the hand holds, as the server sees it. `wrong` marks that kind in the row and lifts the
   * lamp over the piece between the buttons (this one); `right` lifts the lamp once. null clears both.
   */
  setHeld(held: { verdict: 'right' | 'wrong'; key: string | null } | null): void
  /** Live guidance: the step looks finished. A second ring on Next, nothing more: the person decides. */
  cueNext(on: boolean): void
  /** for the console and the check scripts */
  debug: Record<string, unknown>
}

// the brand's shape for "wrong": an octagon with a cross (BRAND.md §3), drawn small beside a count in the row
const NOT_THIS = '<svg class="not" viewBox="0 0 24 24" aria-hidden="true"><path d="M8.3 3h7.4L21 8.3v7.4L15.7 21H8.3L3 15.7V8.3z"/><path d="M9.2 9.2l5.6 5.6M14.8 9.2l-5.6 5.6"/></svg>'

const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)
const easeOut = (t: number) => 1 - Math.pow(1 - t, 3)
const X = new Vector3(1, 0, 0)
const Y = new Vector3(0, 1, 0)
const Z = new Vector3(0, 0, 1)
const DOWN = new Vector3(0, -1, 0)
const CRISP_AXIS = new Vector3(0.64, -0.36, -0.68).normalize()

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

/** One kind of piece in the box: a part id or a hardware code. */
interface Kind {
  key: string
  item: ManifestPart | ManifestHardware
  /** hardware hangs point down like a fastener; a part lies like a board */
  hardware: boolean
  /** the manifest calls it a screw: it gets a thread and the screwdriver */
  driven: boolean
  code: string | null
  name: string
  total: number
  left: number
  nodes: Set<Object3D>
  /** longest, middle and shortest side, metres */
  dims: [number, number, number]
  length: number
  headRadius: number
  /** the piece's box and its middle, in its own frame */
  box: Box3
  centre: Vector3
  /** how far a board leans back from facing the viewer: wide panels lie almost flat, narrow slats show their face */
  pitch: number
  phase: number
  /** where it hovers along the top row, CSS pixels: the kinds still in the box close ranks as others run out */
  x: number
  xTarget: number
  /** its copy in the row stays hidden while the last piece of the kind is on its way back up */
  held: boolean
  /** the one that hovers at the top */
  stock: Display & { holder: Group; slot: Vector3 }
  /** the one between the buttons */
  crisp: Display
  label: HTMLLIElement
  count: HTMLElement
}

interface Beat {
  step: number
  k: number
  /** null for the closing turn, and for a step the manifest lists no actions for (the whole clip is one beat) */
  a: ActionInfo | null
  t0: number
  t1: number
}

type Phase = 'idle' | 'fly' | 'clip' | 'rise' | 'rewind'

export function startGuide(o: GuideOptions): Guide {
  const { stage, page, manifest, model } = o
  let lang = o.lang
  const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)')
  const reduced = () => reducedQuery.matches

  const qa = new Quaternion()
  const qb = new Quaternion()
  const va = new Vector3()
  const vb = new Vector3()
  const vc = new Vector3()

  // ------------------------------------------------------------------ the model, dressed

  const override = sampleSurface(o.material)
  const named = new Set<Object3D>([model.root])
  for (const name of [...manifest.parts.flatMap((p) => p.nodes), ...manifest.hardware.flatMap((h) => h.nodes)]) {
    const n = model.node(name)
    if (n) named.add(n)
  }
  if (model.marker) named.add(model.marker)
  bakeQuantised(model.scene, named)

  const player = new StepPlayer(model, manifest)
  let posedStep = -1
  /**
   * `player.apply`, exact from the first call on a step. When the player moves to another step it zeroes the
   * positions its clip drives only after the mixer has taken them as its starting values, and a mixer does not
   * write a value equal to its starting one. So at the first moment of a step that begins with a turn, the whole
   * assembly would sit at the origin until the turn had moved it. Those values are written here, once per step.
   */
  function pose(step: number, time: number, action: number): void {
    player.apply(step, time, action)
    if (step === posedStep) return
    posedStep = step
    const clip = player.steps[step]?.clip
    if (!clip) return
    const t = MathUtils.clamp(time, 0, clip.duration)
    for (const track of clip.tracks) {
      const { nodeName, propertyName } = PropertyBinding.parseTrackName(track.name)
      if (propertyName !== 'position' && propertyName !== 'scale') continue
      const obj = model.node(nodeName)
      if (!obj) continue
      // the track's own interpolant, the one the mixer uses (three sets it on every track; its types leave it out)
      const v = (track as unknown as { createInterpolant(): { evaluate(t: number): ArrayLike<number> } }).createInterpolant().evaluate(t)
      obj[propertyName].set(v[0], v[1], v[2])
    }
  }
  const kinds: Kind[] = []
  /** live guidance: what the hand holds, and how far the lamp over the piece to pick up is lifted for it */
  let held: { verdict: 'right' | 'wrong'; key: string | null } | null = null
  let heldLift = 0
  let heldPulse = 0
  const kindOf = new Map<Object3D, Kind>()
  const finishes = new Set<Finish>()
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

  function makeDisplay(node: Object3D, shape: ScrewShape | null, driven: boolean): Display {
    const piece = cloneForDisplay(node)
    dress(piece, { display: true, hardware: shape ? { shape, screw: driven } : null, override })
    const base = new Quaternion()
    const centre = new Vector3()
    if (shape) {
      base.setFromUnitVectors(shape.axis, DOWN)
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

  function addKind(key: string, item: ManifestPart | ManifestHardware, hw: ManifestHardware | null): void {
    const nodes = item.nodes.map((n) => model.node(n)).filter((n): n is Object3D => !!n)
    if (!nodes.length) return
    const shape = hw ? model.fastener(nodes[0]) : null
    const driven = hw?.kind === 'screw'
    for (const n of nodes) for (const f of dress(n, { display: false, hardware: shape ? { shape, screw: driven } : null, override })) finishes.add(f)

    const box = localBox(nodes[0])
    const dims = box.getSize(new Vector3()).toArray().sort((a, b) => b - a) as [number, number, number]
    const stock = makeDisplay(nodes[0], shape, driven)
    const crisp = makeDisplay(nodes[0], shape, driven)
    const holder = new Group()
    holder.add(stock.piece)
    stage.trayScene.add(holder)
    crisp.piece.visible = false
    crispHolder.add(crisp.piece)

    const label = document.createElement('li')
    const count = document.createElement('b')
    label.append(count)
    label.insertAdjacentHTML('beforeend', NOT_THIS) // shown only while live guidance sees this kind in the wrong hand
    if (hw) {
      const code = document.createElement('span')
      code.textContent = key
      label.append(code)
    }
    page.stock.append(label)

    const kind: Kind = {
      key,
      item,
      hardware: !!hw,
      driven,
      code: hw ? key : null,
      name: displayName(lang, item),
      total: item.count,
      left: item.count,
      nodes: new Set(nodes),
      dims,
      length: shape ? shape.length : dims[0],
      headRadius: shape ? shape.headRadius : 0,
      box,
      centre: box.getCenter(new Vector3()),
      pitch: -MathUtils.clamp(0.25 + 3.2 * (dims[1] / Math.max(1e-6, dims[0]) - 0.2), 0.45, 0.95),
      phase: kinds.length * 1.7 + 0.4,
      x: Number.NaN,
      xTarget: 0,
      held: false,
      stock: { ...stock, holder, slot: new Vector3() },
      crisp,
      label,
      count,
    }
    kinds.push(kind)
    for (const n of nodes) kindOf.set(n, kind)
  }
  for (const p of manifest.parts) addKind(p.id, p, null)
  for (const h of manifest.hardware) addKind(h.code, h, h)

  model.scene.traverse((o3) => {
    if ((o3 as unknown as { isLight?: boolean }).isLight) o3.visible = false // the room has its own lamp
  })
  model.marker?.traverse((o3) => {
    if (!isMesh(o3)) return
    const m = o3.material as MeshStandardMaterial
    o3.castShadow = false
    o3.receiveShadow = false
    if (!m.emissive) return
    m.color.set(0x000000) // the hole ring is light only: the brand azure, the one cool thing on the piece
    m.emissive.set('#9ecfff')
    m.emissiveIntensity = 1.15
  })

  // The one warm accent of the page is the plating of the product's own hardware, read from the manifest.
  // Without a warm plating the stylesheet's neutral warm stays.
  {
    const warmest = manifest.hardware
      .map((h) => h.color)
      .filter((c): c is [number, number, number] => Array.isArray(c) && c.length === 3)
      .sort((a, b) => b[0] - b[2] - (a[0] - a[2]))[0]
    if (warmest && warmest[0] - warmest[2] > 0.12) {
      const c = new Color().setRGB(warmest[0], warmest[1], warmest[2])
      const hsl = c.getHSL({ h: 0, s: 0, l: 0 })
      if (hsl.l < 0.62) c.setHSL(hsl.h, hsl.s, 0.62) // a hairline has to read on black
      document.documentElement.style.setProperty('--warm', `#${c.getHexString()}`)
    }
  }

  // ------------------------------------------------------------------ layout (CSS pixels -> tray)

  function layout(): void {
    const w = window.innerWidth
    const h = window.innerHeight
    phone = w < 700
    const buttonTop = page.next.getBoundingClientRect().top || h - 84
    const crispY = buttonTop - (phone ? 66 : 72)
    insetTop = phone ? 144 : 170 // the counts end about 16 px above this
    insetBottom = h - crispY + (phone ? 82 : 80)
    stage.resize(w, h, insetTop, insetBottom)
    const u = stage.trayUnit

    rowPitch = Math.min(phone ? 76 : 148, (w - (phone ? 16 : 160)) / Math.max(1, kinds.length))
    page.stock.classList.toggle('tight', rowPitch < 52)
    rowY = phone ? 62 : 80
    const boardPx = Math.min(rowPitch * 0.78, 116)
    const screwPx = Math.min(rowPitch * 0.8, 76)
    const longestBoard = Math.max(0.001, ...kinds.filter((k) => !k.hardware).map((k) => k.length))
    const longestScrew = Math.max(0.001, ...kinds.filter((k) => k.hardware).map((k) => k.length))
    for (const k of kinds) {
      // Boards keep a hint of their relative size, so a short slat is still a thing to see next to a long panel.
      // A short screw is drawn larger than its share, or it would be a speck.
      const share = k.hardware ? Math.sqrt(k.length / longestScrew) : Math.pow(k.length / longestBoard, 0.6)
      k.stock.scale = (((k.hardware ? screwPx : boardPx) * share) / k.length) * u
      k.label.style.top = `${rowY + (phone ? 30 : 42)}px`

      // between the buttons: a board as wide as the space allows and no taller than it;
      // a screw as large as fits, so the cross in its head can be read
      let crispPx: number
      if (k.hardware) {
        crispPx = Math.min((phone ? 44 : 50) / (2 * Math.max(1e-4, k.headRadius)), (phone ? 170 : 200) / (0.74 * k.length))
      } else {
        const tall = k.dims[1] * Math.sin(-k.pitch) + k.dims[2] * Math.cos(k.pitch)
        crispPx = Math.min(((phone ? 150 : 176) * share) / k.length, (phone ? 92 : 104) / Math.max(1e-4, tall))
      }
      k.crisp.scale = crispPx * u
      k.x = Number.NaN // settle on the new row at once
    }
    flowRow()
    stage.trayPoint(w / 2, crispY, crispSlot)
    crispHolder.position.copy(crispSlot)
    stage.aimCrisp(crispSlot, 128 * u)
  }

  /** Centre the kinds that are still in the box along the top row. */
  function flowRow(): void {
    const present = kinds.filter((k) => k.left > 0)
    present.forEach((k, i) => (k.xTarget = stage.width / 2 + (i - (present.length - 1) / 2) * rowPitch))
  }

  // ------------------------------------------------------------------ the tray, every frame

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
      s.holder.visible = k.left > 0 && !k.held
      if (k.left > 0 || Number.isNaN(k.x)) {
        const before = k.x
        k.x = still || Number.isNaN(k.x) ? k.xTarget : k.x + (k.xTarget - k.x) * (1 - Math.exp(-dt * 5))
        if (!(Math.abs(k.x - before) < 0.05)) k.label.style.left = `${k.x.toFixed(1)}px`
      }
      stage.trayPoint(k.x, rowY, s.slot)
      s.holder.position.copy(s.slot)
      s.holder.position.y += Math.sin(t * 0.55 + k.phase) * 3.2 * u
      s.holder.position.x += Math.sin(t * 0.31 + k.phase * 2.1) * 1.6 * u
      if (k.hardware) {
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
    // live guidance: the lamp over the piece to pick up comes up while the hand holds something else, and once
    // for a moment when it holds the right thing. The piece comes a little forward with it.
    heldPulse *= Math.exp(-dt * 1.7)
    const lift = (held?.verdict === 'wrong' ? 0.6 : 0) + 0.9 * heldPulse
    heldLift = still ? lift : heldLift + (lift - heldLift) * (1 - Math.exp(-dt * 7))
    stage.crispLevel = 1 + heldLift
    const grow = still ? 1 : (0.86 + 0.14 * easeOut(crispIn)) * (1 + 0.05 * Math.min(1, heldLift))
    for (const k of kinds) {
      const c = k.crisp
      c.piece.visible = k === crispKind
      if (k !== crispKind) continue
      if (k.hardware) {
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

  const beats: Beat[] = []
  for (const st of player.steps) {
    if (st.index === player.finishIndex) beats.push({ step: st.index, k: 0, a: null, t0: 0, t1: st.duration }) // the closing turn, then "done"
    else if (!st.actions.length) beats.push({ step: st.index, k: 0, a: null, t0: 0, t1: st.duration })
    else for (const a of st.actions) beats.push({ step: st.index, k: a.index, a, t0: a.t0, t1: a.t1 })
  }
  const closing = (b: Beat) => b.step === player.finishIndex
  /** for a step without actions: the kinds its clip brings in, one entry per piece */
  const broughtIn = new Map<number, Kind[]>()
  {
    const seen = new Set<string>()
    manifest.steps.forEach((raw, i) => {
      const fresh = raw.visible.filter((name) => !seen.has(name))
      for (const name of raw.visible) seen.add(name)
      if (raw.actions?.length) return
      broughtIn.set(i, fresh.map((name) => model.node(name)).map((n) => (n ? kindOf.get(n) : undefined)).filter((k): k is Kind => !!k))
    })
  }

  let cur = -1
  let phase: Phase = 'idle'
  let phaseTime = 0
  let clipTime = 0
  const startPos = new Vector3()
  const startQuat = new Quaternion()
  const settle = { t: 1, node: null as Object3D | null, amp: 0 }
  /** the beat being taken back: its piece rises to the row, or the turn runs backwards */
  let undo: {
    beat: Beat
    node: Object3D | null
    kind: Kind | null
    /** the pose the mixer last wrote for the piece */
    truthPos: Vector3
    truthQuat: Quaternion
    /** where the piece was when Previous was pressed */
    fromPos: Vector3
    fromQuat: Quaternion
    fromScale: number
    /** how much of the tray's light was on it at that moment */
    fromFlight: number
  } | null = null
  const seat = { on: false, at: new Vector3(), shown: false }
  /** the last piece of a kind is leaving the row: the others close ranks once it is clear of them */
  let flowDue = false
  let frames = 0

  const stateAtEnd = (b: Beat) => pose(b.step, b.t1, b.a ? b.k + 0.5 : 0) // the half hides the hole ring

  function setFlightLayer(node: Object3D, on: boolean): void {
    node.traverse((o3) => (on ? o3.layers.enable(FLIGHT_LAYER) : o3.layers.disable(FLIGHT_LAYER)))
  }

  function setGlow(node: Object3D | null, amount: number): void {
    node?.traverse((o3) => {
      const m = (o3 as Mesh).material as MeshStandardMaterial | undefined
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

  /** Where the hovering copy of `kind` is, in the frame of `parent`: position in `va`, turn in `qa`, size returned. */
  function slotPose(kind: Kind, parent: Object3D): number {
    const from = kind.stock.piece
    from.updateWorldMatrix(true, false)
    from.matrixWorld.decompose(va, qa, vb) // tray space is the space of the camera
    stage.camera.updateMatrixWorld()
    parent.updateWorldMatrix(true, false)
    va.applyMatrix4(stage.camera.matrixWorld)
    parent.worldToLocal(va)
    qa.premultiply(stage.camera.quaternion)
    qa.premultiply(parent.getWorldQuaternion(qb).invert())
    return vb.x / Math.max(1e-6, parent.getWorldScale(vc).x)
  }

  /** Put the real piece `e` of the way from its hovering copy (0) to a pose in the assembly (1). */
  function carry(node: Object3D, kind: Kind, pos: Vector3, quat: Quaternion, scale: number, e: number): void {
    const parent = node.parent
    if (!parent) return
    const small = slotPose(kind, parent)
    node.position.lerpVectors(va, pos, e)
    node.quaternion.slerpQuaternions(qa, quat, e)
    node.scale.setScalar(MathUtils.lerp(small, scale, e))
    stage.flight = 1 - MathUtils.smoothstep(e, 0.05, 0.75) // the tray's light lets go as the lamp takes over
  }

  function endUndo(): void {
    if (!undo) return
    if (undo.node) {
      undo.node.position.copy(undo.truthPos)
      undo.node.quaternion.copy(undo.truthQuat)
      undo.node.scale.set(1, 1, 1)
      setFlightLayer(undo.node, false)
    }
    if (undo.kind) {
      undo.kind.held = false
      undo.kind.stock.holder.visible = undo.kind.left > 0 // in this same frame: the piece itself is put away below
    }
    undo = null
    stage.flight = 0
    phase = 'idle'
    stateAtEnd(beats[cur])
  }

  /** Bring whatever is moving to its end at once. */
  function finishNow(): void {
    if (cur < 0) return
    const b = beats[cur]
    if (phase === 'rise' || phase === 'rewind') {
      endUndo()
    } else if (phase !== 'idle') {
      if (phase === 'fly' && b.a?.node) {
        b.a.node.position.copy(startPos)
        b.a.node.quaternion.copy(startQuat)
        b.a.node.scale.set(1, 1, 1)
        setFlightLayer(b.a.node, false)
      }
      stage.flight = 0
      stateAtEnd(b)
      phase = 'idle'
      if (closing(b)) {
        describe()
        aimFinished(true)
      }
    }
    if (flowDue) {
      flowDue = false
      flowRow()
    }
    tool.visible = false
    seat.on = false
    endSettle()
  }

  // ------------------------------------------------------------------ what the page says

  /** The words for the beat on screen: a code and a count, a short name, or two or three words for a turn. */
  function describe(): void {
    const b = beats[cur]
    page.now.replaceChildren()
    if (!b) return
    const a = b.a
    let length = 0
    const say = (text: string) => {
      const sr = document.createElement('span')
      sr.className = 'sr'
      sr.textContent = text
      page.now.append(sr)
    }
    const show = (text: string, cls?: string) => {
      const el = document.createElement('span')
      el.textContent = text
      el.setAttribute('aria-hidden', 'true')
      if (cls) el.className = cls
      page.now.append(el)
      length += text.length
    }
    const kind = a?.node ? kindOf.get(a.node) : undefined
    if (a && a.type === 'fasten') {
      const code = a.raw.code ?? kind?.code ?? ''
      show(code || (kind?.name ?? ''))
      show(`${a.screwIndex}/${a.screwTotal}`, 'of')
      say(
        WORDS[lang].hardware
          .replace('{name}', kind?.name ?? '')
          .replace('{code}', code)
          .replace('{i}', String(a.screwIndex))
          .replace('{n}', String(a.screwTotal))
          .trim(),
      )
    } else if (a && a.node) {
      const name = kind?.name ?? ''
      show(name)
      say(name)
    } else if (!a && !closing(b)) {
      // a step without actions: the manifest's own name for it is all there is to say
      const title = firstSentence(lang, { [lang]: localise(lang, manifest.steps[b.step]?.title) })
      show(title)
      say(title)
    } else {
      const ended = !a && phase === 'idle'
      const text = a ? a.raw.text : manifest.walkthrough?.steps.find((s) => s.n === 'finish')?.text
      const pose = a ? manifest.steps[b.step]?.pose : 'upright'
      const words = ended ? WORDS[lang].done : turnWords(lang, pose, text)
      show(words)
      say(ended ? words : firstSentence(lang, text) || words)
    }
    // words that would run under the buttons are set smaller and may take two lines
    page.now.classList.remove('long')
    if (length > 24 || page.now.scrollWidth > page.now.clientWidth + 1) page.now.classList.add('long')
  }

  function labelKind(k: Kind): void {
    k.label.setAttribute('aria-label', `${k.name}${k.code ? ` ${k.code}` : ''}: ${k.left}`)
  }

  function syncPage(flow = true): void {
    const b = beats[cur]
    const used = new Map<Kind, number>()
    for (let i = 0; i <= cur; i++) {
      const node = beats[i].a?.node
      const k = node ? kindOf.get(node) : undefined
      if (k) used.set(k, (used.get(k) ?? 0) + 1)
      if (!beats[i].a) for (const each of broughtIn.get(beats[i].step) ?? []) used.set(each, (used.get(each) ?? 0) + 1)
    }
    for (const k of kinds) {
      k.left = k.total - (used.get(k) ?? 0)
      k.count.textContent = String(k.left)
      k.label.dataset.left = String(k.left)
      labelKind(k)
    }
    flowDue = !flow
    if (flow) flowRow()
    const kind = b?.a?.node ? (kindOf.get(b.a.node) ?? null) : null
    if (kind !== crispKind) {
      crispKind = kind
      crispIn = 0
    }
    describe()
    page.prev.setAttribute('aria-disabled', String(cur <= 0))
    page.next.setAttribute('aria-disabled', String(cur >= beats.length - 1))
    page.progress.style.setProperty('--p', String((cur + 1) / beats.length))
    page.progress.setAttribute('aria-valuenow', String(cur + 1))
    const at = place()
    if (at.step !== told.step || at.action !== told.action) {
      told = at
      window.dispatchEvent(new CustomEvent('agj:step', { detail: at })) // for "see it on your table" (guide/ar.ts)
      for (const fn of placeListeners) fn(at)
    }
  }

  /** The manifest's numbers for the beat on screen. The closing turn belongs to no step: it keeps the last one. */
  function place(): Place {
    let i = Math.max(0, cur)
    while (i > 0 && closing(beats[i])) i--
    const b = beats[i]
    const raw = manifest.steps[b.step]
    return { step: raw?.n ?? b.step + 1, action: b.a ? b.k : null }
  }
  const placeListeners: ((place: Place) => void)[] = []
  let told: Place = { step: Number.NaN, action: null }

  function setWords(): void {
    page.prev.setAttribute('aria-label', WORDS[lang].prev)
    page.next.setAttribute('aria-label', WORDS[lang].next)
    page.stock.setAttribute('aria-label', WORDS[lang].stock)
    page.progress.setAttribute('aria-label', WORDS[lang].progress)
    for (const k of kinds) {
      k.name = displayName(lang, k.item)
      labelKind(k)
    }
    if (cur >= 0) describe()
  }

  // ------------------------------------------------------------------ forward

  function begin(i: number): void {
    finishNow()
    const before = cur < 0 ? -1 : beats[cur].step
    cur = MathUtils.clamp(i, 0, beats.length - 1)
    const b = beats[cur]
    stage.controls.autoRotate = false
    if (b.step !== before || !b.a || b.a.type === 'turn') aimCamera(b, before < 0)

    const node = b.a?.node ?? null
    const kind = node ? (kindOf.get(node) ?? null) : null
    // a board gets a small mark where it will sit; a screw has the model's own ring at its hole
    seat.on = false
    if (node && kind && (b.a!.type !== 'fasten' || !model.marker)) {
      pose(b.step, b.t1, b.k)
      node.updateWorldMatrix(true, false)
      seat.at.copy(kind.hardware ? model.fastener(node).head : kind.centre).applyMatrix4(node.matrixWorld)
      seat.on = true
    }
    pose(b.step, b.t0, b.a ? b.k : 0)
    clipTime = b.t0
    phaseTime = 0
    phase = 'clip'
    if (node && kind && !reduced()) {
      // the real piece leaves from its hovering copy
      startPos.copy(node.position)
      startQuat.copy(node.quaternion)
      phase = 'fly'
      setFlightLayer(node, true)
    }
    syncPage(!(phase === 'fly' && kind!.left === 1))
    if (phase === 'fly') carry(node!, kind!, startPos, startQuat, 1, 0)
  }

  function tickBeat(dt: number): void {
    if (cur < 0) return
    const b = beats[cur]
    if (phase === 'fly') {
      const node = b.a!.node!
      const kind = kindOf.get(node)!
      phaseTime += dt
      const k = Math.min(1, phaseTime / (kind.hardware ? 0.62 : 0.85))
      carry(node, kind, startPos, startQuat, 1, easeInOut(k))
      if (flowDue && k > 0.42) {
        flowDue = false
        flowRow()
      }
      if (k >= 1) {
        node.position.copy(startPos)
        node.quaternion.copy(startQuat)
        node.scale.set(1, 1, 1)
        setFlightLayer(node, false)
        stage.flight = 0
        phase = 'clip'
        phaseTime = 0
      }
    } else if (phase === 'clip') {
      phaseTime += dt
      clipTime = Math.min(b.t1, clipTime + dt)
      pose(b.step, clipTime, b.a ? b.k : 0)
      const kind = b.a?.node ? kindOf.get(b.a.node) : undefined
      if (b.a?.type === 'fasten' && b.a.node && kind?.driven) rideScrew(b.a.node, easeOut(Math.min(1, phaseTime / 0.16)))
      if (clipTime >= b.t1) {
        stateAtEnd(b)
        phase = 'idle'
        tool.visible = false
        seat.on = false
        if (closing(b)) {
          describe() // the one word at the end
          aimFinished(false)
          if (!reduced()) stage.controls.autoRotate = true // the finished piece turns once the work is done
        } else if (b.a?.node && !reduced()) {
          settle.t = 0
          settle.node = b.a.node
          settle.amp = b.a.type === 'fasten' ? 0.0005 : 0.0016
        }
      }
    } else if (phase === 'rise' && undo?.node && undo.kind) {
      phaseTime += dt
      const k = Math.min(1, phaseTime / 0.34)
      const e = 1 - easeInOut(k)
      carry(undo.node, undo.kind, undo.fromPos, undo.fromQuat, undo.fromScale, e)
      stage.flight = Math.max(stage.flight, undo.fromFlight * e)
      if (k >= 1) endUndo()
    } else if (phase === 'rewind' && undo) {
      clipTime = Math.max(undo.beat.t0, clipTime - dt * 3.2)
      pose(undo.beat.step, clipTime, undo.beat.a ? undo.beat.k : 0)
      if (clipTime <= undo.beat.t0) endUndo()
    }
    if (settle.t < 1) {
      settle.t = Math.min(1, settle.t + dt / 0.36)
      const x = settle.t
      model.scene.position.y = -settle.amp * Math.sin(Math.PI * x) * (1 - x) * 1.7
      setGlow(settle.node, 0.2 * (1 - x) * (1 - x))
      if (x >= 1) endSettle()
    }
  }

  // ------------------------------------------------------------------ back

  /** Take the beat on screen back: its piece rises to its place in the row, a turn runs backwards. */
  function back(): void {
    if (phase === 'rise' || phase === 'rewind') finishNow()
    if (cur <= 0) return
    const b = beats[cur]
    const node = b.a?.node ?? null
    const kind = node ? (kindOf.get(node) ?? null) : null
    // where the piece is right now, if it was still on its way
    const moving = phase === 'fly' || phase === 'clip'
    const fromPos = node && moving ? node.position.clone() : null
    const fromQuat = node && moving ? node.quaternion.clone() : null
    const fromScale = node && moving ? node.scale.x : 1
    const fromFlight = stage.flight
    const time = phase === 'clip' ? clipTime : phase === 'fly' ? b.t0 : b.t1
    finishNow()
    stage.controls.autoRotate = false

    cur -= 1
    const pb = beats[cur]
    const wasGone = !!kind && kind.left === 0
    if (pb.step !== b.step) aimCamera(pb, false)
    if (reduced()) {
      stateAtEnd(pb)
      syncPage()
      return
    }
    if (node && kind) {
      pose(b.step, b.t1, b.k + 0.5)
      undo = {
        beat: b,
        node,
        kind,
        truthPos: node.position.clone(),
        truthQuat: node.quaternion.clone(),
        fromPos: fromPos ?? node.position.clone(),
        fromQuat: fromQuat ?? node.quaternion.clone(),
        fromScale,
        fromFlight,
      }
      kind.held = wasGone // its copy in the row comes back when the piece has arrived
      setFlightLayer(node, true)
      phase = 'rise'
      phaseTime = 0
      carry(node, kind, undo.fromPos, undo.fromQuat, undo.fromScale, 1)
      stage.flight = fromFlight
    } else {
      pose(b.step, time, b.a ? b.k : 0)
      undo = { beat: b, node: null, kind: null, truthPos: va.clone(), truthQuat: qa.clone(), fromPos: va.clone(), fromQuat: qa.clone(), fromScale: 1, fromFlight: 0 }
      phase = 'rewind'
      clipTime = time
    }
    syncPage()
  }

  const next = () => {
    if (cur < beats.length - 1) begin(cur + 1)
  }

  // ------------------------------------------------------------------ camera

  const tween = { k: 1, fromTarget: new Vector3(), toTarget: new Vector3(), from: new Spherical(), to: new Spherical() }
  let holding = false
  stage.controls.addEventListener('start', () => {
    holding = true
    tween.k = 1 // a press takes the camera
    stage.controls.autoRotate = false
  })
  stage.controls.addEventListener('end', () => (holding = false))

  function viewDir(step: number): Vector3 | null {
    const cam = manifest.steps[step]?.camera
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
    return null
  }

  /** What a step needs in view, and the side to see it from. Measured once per step, before anything is on screen. */
  interface Framing {
    /** corners of everything that must stay inside the free area: the finished step, each piece where its
     *  insertion starts, the screwdriver on each screw, the assembly on its way through a turn */
    points: Vector3[]
    /** corners of the finished step alone */
    final: Vector3[]
    /** null: keep the side the person is looking from */
    dir: Vector3 | null
  }
  const framings: Framing[] = []
  const reachBox = new Box3()
  /** where along a turn the assembly is measured, so its highest corner on the way is inside the frame too */
  const TURN_SAMPLES = [0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875]

  function measure(): void {
    const box = new Box3()
    const corners = (b: Box3, m: Matrix4 | null, into: Vector3[]) => {
      if (b.isEmpty()) return
      for (let i = 0; i < 8; i++) {
        const c = new Vector3(i & 1 ? b.max.x : b.min.x, i & 2 ? b.max.y : b.min.y, i & 4 ? b.max.z : b.min.z)
        into.push(m ? c.applyMatrix4(m) : c)
      }
    }
    /** Each board's own box where it stands now. Tighter than one box around them all, which is mostly air. */
    const boards = (into: Vector3[]) => {
      model.scene.updateMatrixWorld(true)
      for (const [node, kind] of kindOf) if (node.visible && !kind.hardware) corners(kind.box, node.matrixWorld, into)
    }
    for (const st of player.steps) {
      const points: Vector3[] = []
      const final: Vector3[] = []
      pose(st.index, st.duration, st.actions.length)
      reachBox.union(player.bounds())
      boards(final)
      points.push(...final)
      let dir: Vector3 | null = null
      if (st.index === player.finishIndex) {
        for (const j of TURN_SAMPLES) {
          pose(st.index, st.duration * j, 0)
          boards(points)
        }
      } else {
        const out = new Vector3()
        if (!st.actions.length) {
          for (const j of TURN_SAMPLES) {
            pose(st.index, st.duration * j, 0)
            boards(points)
          }
        }
        for (const a of st.actions) {
          if (a.type === 'turn') {
            for (const j of TURN_SAMPLES) {
              pose(st.index, MathUtils.lerp(a.t0, a.t1, j), a.index)
              boards(points)
            }
            continue
          }
          const kind = a.node ? kindOf.get(a.node) : undefined
          if (!a.node || !kind) continue
          pose(st.index, a.t0, a.index)
          model.scene.updateMatrixWorld(true)
          corners(kind.box, a.node.matrixWorld, points)
          if (a.type !== 'fasten') continue
          const shape = model.fastener(a.node)
          const axis = va.copy(shape.axis).transformDirection(a.node.matrixWorld)
          out.sub(axis)
          if (kind.driven) {
            const end = vb.copy(shape.head).applyMatrix4(a.node.matrixWorld).addScaledVector(axis, -TOOL_LENGTH)
            corners(box.setFromCenterAndSize(end, vc.setScalar(TOOL_RADIUS * 2)), null, points)
          }
        }
        dir = viewDir(st.index)
        // The guide's view can have the screw holes on the far side. Then look from outside the face the screws
        // enter, leaning to the side the guide looks from.
        if (out.lengthSq() > 1e-6) {
          out.normalize()
          const toCamera = (dir ?? new Vector3(-0.5, -0.5, -0.7)).clone().negate()
          if (toCamera.dot(out) < 0.4) {
            const side = toCamera.clone().addScaledVector(out, -toCamera.dot(out))
            if (side.lengthSq() < 1e-3) side.set(0, 1, 0).addScaledVector(out, -out.y)
            if (side.lengthSq() < 1e-3) side.set(1, 0, 0)
            side.normalize()
            dir = out.clone().multiplyScalar(0.72).addScaledVector(side, 0.69).normalize().negate()
          }
        }
      }
      framings.push({ points, final, dir })
    }
  }

  /**
   * Fit `points` inside the sides of the screen and between `top` and `bottom` (CSS pixels from the top of the
   * screen; by default the two bands of the tray), looking along `dir`.
   */
  function frameCamera(points: Vector3[], dir: Vector3, immediate: boolean, top = insetTop, bottom = stage.height - insetBottom): void {
    if (!points.length) return
    const { width, height } = stage
    const tanFull = Math.tan(MathUtils.degToRad(FOV) / 2)
    const axisY = height / 2 + stage.shiftY // where the camera's axis meets the screen
    const tanUp = (tanFull * Math.max(height * 0.08, axisY - top)) / (height / 2)
    const tanDown = (tanFull * Math.max(height * 0.08, bottom - axisY)) / (height / 2)
    const tanH = tanFull * (width / height) * (phone ? (width - 28) / width : 0.7)

    const off = dir.clone().normalize().negate() // from the assembly to the camera
    const elevation = MathUtils.clamp(Math.asin(MathUtils.clamp(off.y, -1, 1)), 0.3, 0.72)
    const flat = Math.hypot(off.x, off.z) || 1
    off.set((off.x / flat) * Math.cos(elevation), Math.sin(elevation), (off.z / flat) * Math.cos(elevation))
    const fwd = off.clone().negate()
    const right = new Vector3().crossVectors(fwd, Y).normalize()
    const up = new Vector3().crossVectors(right, fwd)

    // start from the middle of the points as the camera sees them, then centre the picture and close in
    const lo = new Vector3(Infinity, Infinity, Infinity)
    const hi = new Vector3(-Infinity, -Infinity, -Infinity)
    for (const p of points) {
      va.set(p.dot(right), p.dot(up), p.dot(fwd))
      lo.min(va)
      hi.max(va)
    }
    const mid = lo.add(hi).multiplyScalar(0.5)
    const centre = new Vector3().addScaledVector(right, mid.x).addScaledVector(up, mid.y).addScaledVector(fwd, mid.z)
    const fit = () => {
      let d = 0
      for (const p of points) {
        va.copy(p).sub(centre)
        const depth = va.dot(fwd)
        const y = va.dot(up)
        d = Math.max(d, Math.abs(va.dot(right)) / tanH - depth, (y > 0 ? y / tanUp : -y / tanDown) - depth)
      }
      return d
    }
    let dist = fit()
    for (let pass = 0; pass < 4; pass++) {
      // perspective makes the near things wider: move the centre until the picture sits evenly in the frame
      let x0 = Infinity
      let x1 = -Infinity
      let y0 = Infinity
      let y1 = -Infinity
      for (const p of points) {
        va.copy(p).sub(centre)
        const d = Math.max(1e-4, dist + va.dot(fwd))
        x0 = Math.min(x0, va.dot(right) / d)
        x1 = Math.max(x1, va.dot(right) / d)
        y0 = Math.min(y0, va.dot(up) / d)
        y1 = Math.max(y1, va.dot(up) / d)
      }
      const shiftX = (x0 + x1) / 2
      const shiftY = (y1 * tanDown + y0 * tanUp) / (tanUp + tanDown)
      centre.addScaledVector(right, shiftX * dist).addScaledVector(up, shiftY * dist)
      dist = fit()
    }
    dist = MathUtils.clamp(dist * 1.04, stage.controls.minDistance, stage.controls.maxDistance)

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

  /**
   * The camera at `k` (0..1) of its move. It swings round at an even pace, but when the new view is further
   * away it steps back in the first four tenths of the move: a turning assembly needs the room at once.
   */
  function placeCamera(k: number): void {
    const swing = easeInOut(k)
    const back = tween.to.radius > tween.from.radius ? easeInOut(Math.min(1, k / 0.4)) : swing
    const s = new Spherical(
      MathUtils.lerp(tween.from.radius, tween.to.radius, back),
      MathUtils.lerp(tween.from.phi, tween.to.phi, swing),
      MathUtils.lerp(tween.from.theta, tween.to.theta, swing),
    ).makeSafe()
    stage.controls.target.lerpVectors(tween.fromTarget, tween.toTarget, back)
    stage.camera.position.setFromSpherical(s).add(stage.controls.target)
  }

  function tickCamera(dt: number): void {
    if (tween.k >= 1) return
    tween.k = Math.min(1, tween.k + dt / 1.1)
    placeCamera(tween.k)
  }

  /** The whole of the beat's step in view, from the side the guide draws it. */
  function aimCamera(b: Beat, immediate: boolean): void {
    const f = framings[b.step]
    let dir = f.dir
    if (!dir) {
      dir = stage.controls.target.clone().sub(stage.camera.position).normalize()
      dir.y = -0.45
      dir.normalize()
    }
    frameCamera(f.points, dir, immediate)
  }

  /** The work is done: nothing is left in the row or between the buttons, so the finished piece takes their room. */
  function aimFinished(immediate: boolean): void {
    const dir = stage.controls.target.clone().sub(stage.camera.position).normalize()
    dir.y = -0.45
    dir.normalize()
    const [top, bottom] = heroBand()
    frameCamera(sweep(framings[player.finishIndex].final), dir, immediate, top, bottom)
  }

  /** Top and bottom of the room the finished piece gets, CSS pixels: from under the corner to above the buttons. */
  function heroBand(): [number, number] {
    const buttonTop = page.next.getBoundingClientRect().top || stage.height - 84
    return [phone ? 84 : 92, buttonTop - (phone ? 48 : 44)]
  }

  /** The cylinder that `points` sweep when they turn about the vertical through their middle, as a ring of points. */
  function sweep(points: Vector3[]): Vector3[] {
    const box = new Box3().setFromPoints(points)
    const mid = box.getCenter(new Vector3())
    let r = 0
    for (const p of points) r = Math.max(r, Math.hypot(p.x - mid.x, p.z - mid.z))
    const ring: Vector3[] = []
    for (let i = 0; i < 24; i++) {
      const a = (i / 24) * Math.PI * 2
      for (const y of [box.min.y, box.max.y]) ring.push(new Vector3(mid.x + Math.cos(a) * r, y, mid.z + Math.sin(a) * r))
    }
    return ring
  }

  const finished = () => cur === beats.length - 1 && phase === 'idle'

  // ------------------------------------------------------------------ the mark at the seat

  const screen = { x: 0, y: 0, z: 0 }
  const screenPiece = { x: 0, y: 0, z: 0 }
  function tickSeat(): void {
    let show = false
    if (seat.on && cur >= 0 && (phase === 'fly' || phase === 'clip')) {
      const node = beats[cur].a?.node
      const kind = node ? kindOf.get(node) : undefined
      stage.toScreen(seat.at, screen)
      show = screen.z < 1
      if (show && node && kind) {
        // gone once the piece is nearly there: the mark says where to look, the piece then says the rest
        node.updateWorldMatrix(true, false)
        stage.toScreen(va.copy(kind.hardware ? model.fastener(node).head : kind.centre).applyMatrix4(node.matrixWorld), screenPiece)
        if (Math.hypot(screenPiece.x - screen.x, screenPiece.y - screen.y) < 14) show = false
      }
      if (show) page.seat.setAttribute('transform', `translate(${screen.x.toFixed(1)} ${screen.y.toFixed(1)})`)
    }
    if (show !== seat.shown) {
      seat.shown = show
      page.seat.classList.toggle('on', show)
    }
  }

  // ------------------------------------------------------------------ start

  measure()
  {
    const centre = reachBox.getCenter(new Vector3())
    const size = reachBox.getSize(new Vector3())
    // the disc sits under everything the assembly ever covers
    stage.setFocus(new Vector3(centre.x, reachBox.min.y, centre.z), Math.hypot(size.x, size.z) * 0.5 * 1.12)
  }
  stage.scene.add(model.scene)
  page.progress.setAttribute('aria-valuemax', String(beats.length))
  setWords()
  layout()
  tickTray(0, 0)
  stage.warm()

  let last = performance.now()
  let time = 0
  function frame(now: number): void {
    requestAnimationFrame(frame)
    const raw = (now - last) / 1000
    const dt = Math.min(0.05, Math.max(0, raw))
    last = now
    time += dt
    tickCamera(dt)
    stage.controls.update(dt)
    tickTray(time, dt)
    tickBeat(dt)
    tickSeat()
    stage.draw(time, !reduced())
    stage.watchPace(raw)
    frames++
  }

  window.addEventListener('resize', () => {
    layout()
    if (holding) return
    if (finished()) aimFinished(true)
    else if (cur >= 0) aimCamera(beats[cur], true)
  })
  page.next.addEventListener('click', next)
  page.prev.addEventListener('click', back)
  window.addEventListener('keydown', (e) => {
    if (e.altKey || e.ctrlKey || e.metaKey) return
    if (e.key === 'ArrowRight') {
      e.preventDefault()
      next()
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault()
      back()
    }
  })

  if (o.openAt !== null) {
    begin(o.openAt)
    finishNow()
    syncPage()
  } else {
    // the one moment that plays by itself: the row is seen whole, then the first piece comes down
    aimCamera(beats[0], true)
    pose(0, 0, -1)
    syncPage()
    page.next.setAttribute('aria-disabled', 'false')
    window.setTimeout(() => cur < 0 && begin(0), reduced() ? 0 : 700)
  }
  requestAnimationFrame(frame)

  return {
    setLang(l) {
      lang = l
      setWords()
    },
    place,
    onPlace(fn) {
      placeListeners.push(fn)
    },
    wanted: () => crispKind?.key ?? null,
    label(key) {
      const k = kinds.find((each) => each.key === key)
      return k ? (k.code ?? k.name) : null
    },
    turnOf(step) {
      const raw = manifest.steps.find((s) => s.n === step)
      return turnWords(lang, raw?.pose, raw?.actions?.find((a) => a.type === 'turn')?.text)
    },
    setHeld(next) {
      if (next?.verdict === 'right' && held?.verdict !== 'right') heldPulse = 1
      held = next
      for (const k of kinds) k.label.toggleAttribute('data-not', next?.verdict === 'wrong' && next.key === k.key)
    },
    cueNext(on) {
      page.next.classList.toggle('cue', on)
    },
    debug: {
      stage,
      player,
      next,
      prev: back,
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
      get finishes() {
        return [...finishes]
      },
      get seatShown() {
        return seat.shown
      },
      /** CSS pixels covered right now by every board on the disc, every piece in motion and the screwdriver */
      extent() {
        const e = { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity }
        const see = (p: Vector3) => {
          stage.toScreen(p, screen)
          e.left = Math.min(e.left, screen.x)
          e.right = Math.max(e.right, screen.x)
          e.top = Math.min(e.top, screen.y)
          e.bottom = Math.max(e.bottom, screen.y)
        }
        model.scene.updateMatrixWorld(true)
        const flying = phase === 'fly' || phase === 'rise' ? (undo?.node ?? beats[cur]?.a?.node ?? null) : null
        for (const [node, kind] of kindOf) {
          if (!node.visible || node === flying) continue // a piece between the row and the disc crosses the bands by design
          if (kind.hardware && node !== beats[cur]?.a?.node) continue
          for (let i = 0; i < 8; i++) {
            see(va.set(i & 1 ? kind.box.max.x : kind.box.min.x, i & 2 ? kind.box.max.y : kind.box.min.y, i & 4 ? kind.box.max.z : kind.box.min.z).applyMatrix4(node.matrixWorld))
          }
        }
        if (tool.visible) {
          tool.updateWorldMatrix(true, false)
          see(va.set(0, 0, 0).applyMatrix4(tool.matrixWorld))
          see(va.set(0, TOOL_LENGTH, 0).applyMatrix4(tool.matrixWorld))
        }
        const [bandTop, bandBottom] = finished() ? heroBand() : [insetTop, stage.height - insetBottom]
        return { ...e, bandTop, bandBottom, width: stage.width, height: stage.height }
      },
      get tweening() {
        return tween.k < 1
      },
    },
  }
}
