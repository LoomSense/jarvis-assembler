import {
  AnimationClip,
  AnimationMixer,
  Box3,
  LoopOnce,
  Matrix4,
  Object3D,
  PropertyBinding,
  Quaternion,
  Vector3,
  type AnimationAction,
  type KeyframeTrack,
} from 'three'
import type { Manifest, ManifestAction, ManifestStep } from '../types'
import { localBox, type Model } from './model'

/** One thing the person does in a step, with its node resolved. */
export interface ActionInfo {
  index: number
  type: ManifestAction['type']
  raw: ManifestAction
  /** the part or screw this action is about; null for `turn` and `note` */
  node: Object3D | null
  /** this action brings `node` in from the box. False for a screw that is only tightened, or a part moved again. */
  fresh: boolean
  /** start and end inside the step clip, seconds */
  t0: number
  t1: number
  /** for `fasten`: which screw of this code in this step (1-based) and how many there are */
  screwIndex: number
  screwTotal: number
}

export interface StepTimeline {
  index: number
  /** null for the closing pseudo-step that turns the finished assembly the right way up */
  raw: ManifestStep | null
  clip: AnimationClip | null
  /** in the manifest's order; `ActionInfo.index` is the place in this list */
  actions: ActionInfo[]
  /**
   * The same actions in the order they are played. A manifest lists "put on both arms, then all four screws";
   * a person puts on one arm, screws it, then the other. So each screw follows the last part it holds.
   */
  sequence: ActionInfo[]
  /** `rank[i]` is where action `i` sits in `sequence`; null when that is the manifest's own order */
  rank: number[] | null
  /** time of the last key, seconds */
  duration: number
}

/** a screw holds a part when it ends this close to it, metres */
const TOUCH = 0.015
/** a screw that touches none of the step's parts goes with the nearest one, when that is this close */
const NEAR = 0.08

interface Pose {
  position?: Vector3
  quaternion?: Quaternion
}

/** an interpolant of a track: the values at a time */
interface Evaluator {
  evaluate(time: number): ArrayLike<number>
}

interface PlacedNode {
  name: string
  obj: Object3D
  /** index of the step in which it is put on */
  placed: number
  /** index of the action that puts it on, or -1 */
  action: number
}

const trackTarget = (t: KeyframeTrack) => PropertyBinding.parseTrackName(t.name)

/**
 * Poses the assembly for "step s, action a, time t" (PLAN §4.3).
 *
 * - Parts of earlier steps stand in their final pose, taken from the last key of the clip that placed them.
 * - The current step's clip `step_NN` runs through an AnimationMixer action with LoopOnce and clampWhenFinished.
 *   The caller owns the clock: it sets the time, so pausing after each action and stepping back are exact.
 * - Parts of later steps, and parts of this step whose action has not started, are hidden.
 */
export class StepPlayer {
  readonly steps: StepTimeline[] = []
  /** index of the closing pseudo-step, or -1 when the GLB has no walkthrough clip to take it from */
  readonly finishIndex: number

  private nodes: PlacedNode[] = []
  private rest = new Map<Object3D, { position: Vector3; quaternion: Quaternion }>()
  /** per step: where each node the clip moves ends up */
  private finalPoses: Map<Object3D, Pose>[] = []
  private mixer: AnimationMixer | null = null
  private action: AnimationAction | null = null
  private active = -1
  private markerNormal: Vector3 | null = null
  /** the walkthrough has no closing turn: the last step already leaves the assembly the right way up */
  private endsAsBuilt = false

  /**
   * `keepOrder`: play every step in the manifest's own order. The headset's guide window asks for it, because it
   * follows the headset's count of actions, which is the manifest's.
   */
  constructor(private model: Model, manifest: Manifest, keepOrder = false) {
    manifest.steps.forEach((raw, index) => {
      const clip = raw.clip ? (model.clips.get(raw.clip) ?? null) : null
      const totals = new Map<string, number>()
      for (const a of raw.actions ?? []) if (a.type === 'fasten') totals.set(a.code ?? '', (totals.get(a.code ?? '') ?? 0) + 1)
      const seen = new Map<string, number>()
      const actions = (raw.actions ?? []).map((a, i): ActionInfo => {
        const code = a.code ?? ''
        if (a.type === 'fasten') seen.set(code, (seen.get(code) ?? 0) + 1)
        return {
          index: i,
          type: a.type,
          raw: a,
          node: a.type === 'turn' ? null : (model.node(a.node) ?? null),
          fresh: false,
          t0: a.t0_s,
          t1: a.t1_s,
          screwIndex: a.type === 'fasten' ? seen.get(code)! : 0,
          screwTotal: a.type === 'fasten' ? totals.get(code)! : 0,
        }
      })
      const lastEnd = Math.max(0, ...actions.map((a) => a.t1))
      this.steps.push({ index, raw, clip, actions, sequence: actions, rank: null, duration: Math.max(clip?.duration ?? 0, lastEnd) })
    })

    // closing pseudo-step: the root's own keys from the end of the `walkthrough` clip ("turn it the right way up")
    const finish = manifest.walkthrough?.steps.find((s) => s.n === 'finish')
    const whole = manifest.walkthrough ? model.clips.get(manifest.walkthrough.clip) : undefined
    let finishClip: AnimationClip | null = null
    if (finish && whole) {
      const tracks = whole.tracks
        .filter((t) => model.node(trackTarget(t).nodeName) === model.root)
        .map((t) => t.clone().trim(finish.start_s, finish.end_s).shift(-finish.start_s))
      if (tracks.length) finishClip = new AnimationClip('finish', -1, tracks)
    }
    this.endsAsBuilt = !!manifest.walkthrough && !finish
    this.finishIndex = this.steps.length
    this.steps.push({ index: this.finishIndex, raw: null, clip: finishClip, actions: [], sequence: [], rank: null, duration: finishClip?.duration ?? 0 })

    // when each part and screw is put on
    const all = [...manifest.parts.flatMap((p) => p.nodes), ...manifest.hardware.flatMap((h) => h.nodes)]
    for (const name of all) {
      const obj = model.node(name)
      if (!obj) continue
      let placed = manifest.steps.findIndex((s) => s.visible.includes(name))
      let action = -1
      this.steps.forEach((st) => {
        const a = st.actions.find((x) => x.node === obj)
        if (a && action < 0) {
          action = a.index
          placed = st.index
        }
      })
      if (placed < 0) placed = Number.POSITIVE_INFINITY // named by no step: never shown
      this.nodes.push({ name, obj, placed, action })
    }

    for (const obj of [model.root, ...this.nodes.map((n) => n.obj)]) {
      this.rest.set(obj, { position: obj.position.clone(), quaternion: obj.quaternion.clone() })
    }

    // final pose of everything a clip moves: its last key
    for (const st of this.steps) {
      const poses = new Map<Object3D, Pose>()
      for (const track of st.clip?.tracks ?? []) {
        const { nodeName, propertyName } = trackTarget(track)
        const obj = model.node(nodeName)
        if (!obj || obj === model.marker) continue
        const size = track.getValueSize()
        const v = track.values
        const o = v.length - size
        const pose = poses.get(obj) ?? {}
        if (propertyName === 'position') pose.position = new Vector3(v[o], v[o + 1], v[o + 2])
        if (propertyName === 'quaternion') pose.quaternion = new Quaternion(v[o], v[o + 1], v[o + 2], v[o + 3])
        poses.set(obj, pose)
      }
      this.finalPoses.push(poses)
    }

    for (const st of this.steps) {
      for (const a of st.actions) a.fresh = this.nodes.some((n) => n.obj === a.node && n.placed === st.index && n.action === a.index)
      const sequence = keepOrder ? null : this.playOrder(st)
      if (!sequence) continue
      st.sequence = sequence
      st.rank = st.actions.map((a) => sequence.indexOf(a))
      const seen = new Map<string, number>() // "screw 2 of 4" counts in the order they are driven
      for (const a of sequence) {
        if (a.type !== 'fasten') continue
        const code = a.raw.code ?? ''
        seen.set(code, (seen.get(code) ?? 0) + 1)
        a.screwIndex = seen.get(code)!
      }
    }

    if (model.marker) {
      // which way the ring faces in its own frame: its thinnest side
      const size = localBox(model.marker).getSize(new Vector3()).toArray()
      const k = size.indexOf(Math.min(...size))
      this.markerNormal = new Vector3().setComponent(k, 1)
      model.marker.visible = false
    }
  }

  /** Number of real steps (the closing pseudo-step is not counted). */
  get count(): number {
    return this.finishIndex
  }

  /**
   * Pose everything for step `stepIndex` at clip time `time`.
   * `actionIndex` is the action in progress: parts of later actions stay hidden. Pass -1 for "before the first
   * action" and `actions.length` for "the step is finished" (which also hides the ring).
   */
  apply(stepIndex: number, time: number, actionIndex: number): void {
    const st = this.steps[stepIndex]
    if (!st) return
    if (this.active !== stepIndex) this.activate(stepIndex)
    const at = this.rankOf(st, actionIndex)
    for (const n of this.nodes) {
      if (n.placed === stepIndex) n.obj.visible = n.action < 0 ? actionIndex >= 0 : this.rankOf(st, n.action) <= at
    }
    if (this.action && this.mixer) {
      this.action.time = Math.min(Math.max(time, 0), st.clip!.duration)
      this.mixer.update(0)
    }
    if (st.rank && st.clip) {
      // Played out of the clip's own order, the clock no longer says where a piece is: at the time the second
      // arm comes down, the clip still holds the first arm's screws in the air. So each piece of this step is
      // put where its place in the played order says: at its last key when it is done, on its own keys when
      // it is the one moving. (The mixer is not asked: it only writes a value that changed since its last update.)
      const t = Math.min(Math.max(time, 0), st.clip.duration)
      const now = Math.floor(at)
      for (const n of this.nodes) {
        if (n.placed !== stepIndex || n.action < 0) continue
        const r = st.rank[n.action]
        if (r < now) this.setPose(n.obj, this.finalPoses[stepIndex].get(n.obj) ?? {})
        else if (r === now) this.sample(st, n.obj, t)
      }
    }
    this.poseMarker(st, actionIndex)
  }

  /** `actionIndex` as `apply` takes it (-1, an index, an index and a half, the count), as a place in the played order. */
  private rankOf(st: StepTimeline, actionIndex: number): number {
    if (!st.rank || actionIndex < 0 || actionIndex >= st.actions.length) return actionIndex
    const k = Math.floor(actionIndex)
    return st.rank[k] + (actionIndex - k)
  }

  /** Write `obj`'s own position and turn from the step's clip at `time`. */
  private sample(st: StepTimeline, obj: Object3D, time: number): void {
    let tracks = this.tracksOf.get(st)
    if (!tracks) {
      tracks = new Map()
      for (const track of st.clip?.tracks ?? []) {
        const { nodeName, propertyName } = trackTarget(track)
        const target = this.model.node(nodeName)
        if (!target || (propertyName !== 'position' && propertyName !== 'quaternion')) continue
        const entry = tracks.get(target) ?? {}
        // the track's own interpolant, the one the mixer uses (three sets it on every track; its types leave it out)
        entry[propertyName] = (track as unknown as { createInterpolant(): Evaluator }).createInterpolant()
        tracks.set(target, entry)
      }
      this.tracksOf.set(st, tracks)
    }
    const mine = tracks.get(obj)
    if (mine?.position) {
      const v = mine.position.evaluate(time)
      obj.position.set(v[0], v[1], v[2])
    }
    if (mine?.quaternion) {
      const v = mine.quaternion.evaluate(time)
      obj.quaternion.set(v[0], v[1], v[2], v[3])
    }
  }
  private tracksOf = new Map<StepTimeline, Map<Object3D, { position?: Evaluator; quaternion?: Evaluator }>>()

  /**
   * The order to play a step in, or null for the manifest's own. Only one pattern is touched: two or more parts
   * put on one after the other, then the screws. Each screw then follows the last of those parts it ends in
   * (a screw through two of them waits for both); one that touches none goes with the nearest, or stays last.
   */
  private playOrder(st: StepTimeline): ActionInfo[] | null {
    const acts = st.actions
    const out: ActionInfo[] = []
    let i = 0
    while (i < acts.length) {
      let p = i
      while (p < acts.length && acts[p].type === 'place' && acts[p].fresh) p++
      let f = p
      while (f < acts.length && acts[f].type === 'fasten' && acts[f].fresh) f++
      if (p - i < 2 || f === p) {
        out.push(acts[i++])
        continue
      }
      const parts = acts.slice(i, p)
      const screws = acts.slice(p, f)
      const holds = screws.map((s) => this.lastHeld(st.index, s.node!, parts.map((a) => a.node!)))
      if (holds.includes(-1)) out.push(...parts, ...screws)
      else parts.forEach((part, j) => out.push(part, ...screws.filter((_, k) => holds[k] === j)))
      i = f
    }
    return out.every((a, k) => a === acts[k]) ? null : out
  }

  /** Which of `parts` (by index) the screw must wait for, from where each ends up in this step. -1: cannot tell. */
  private lastHeld(stepIndex: number, screw: Object3D, parts: Object3D[]): number {
    const matrix = (obj: Object3D) => {
      const pose = this.finalPoses[stepIndex].get(obj) ?? {}
      const rest = this.rest.get(obj)!
      return new Matrix4().compose(pose.position ?? rest.position, pose.quaternion ?? rest.quaternion, new Vector3(1, 1, 1))
    }
    if (parts.some((p) => p.parent !== screw.parent)) return -1
    const shape = this.model.fastener(screw)
    const along = [0, 0.25, 0.5, 0.75, 1].map((k) => new Vector3().lerpVectors(shape.head, shape.tip, k).applyMatrix4(matrix(screw)))
    const gaps = parts.map((part) => {
      const box = localBox(part)
      const inverse = matrix(part).invert()
      return Math.min(...along.map((p) => box.distanceToPoint(p.clone().applyMatrix4(inverse))))
    })
    let last = -1
    gaps.forEach((gap, j) => {
      if (gap <= TOUCH) last = j
    })
    if (last >= 0) return last
    const nearest = gaps.indexOf(Math.min(...gaps))
    return gaps[nearest] <= NEAR ? nearest : parts.length - 1
  }

  /** World-space box around every part and screw that is visible right now. */
  bounds(): Box3 {
    this.model.scene.updateMatrixWorld(true)
    const box = new Box3()
    for (const n of this.nodes) if (n.obj.visible) box.expandByObject(n.obj)
    return box
  }

  /** World-space box at another moment of a step. The caller must `apply` its own state again afterwards. */
  boundsAt(stepIndex: number, time: number, actionIndex: number): Box3 {
    this.apply(stepIndex, time, actionIndex)
    return this.bounds()
  }

  private poseBefore(obj: Object3D, stepIndex: number): Pose {
    for (let i = Math.min(stepIndex, this.finalPoses.length) - 1; i >= 0; i--) {
      const p = this.finalPoses[i].get(obj)
      if (p) return p
    }
    return {}
  }

  private setPose(obj: Object3D, pose: Pose): void {
    const rest = this.rest.get(obj)!
    obj.position.copy(pose.position ?? rest.position)
    obj.quaternion.copy(pose.quaternion ?? rest.quaternion)
  }

  private activate(stepIndex: number): void {
    this.active = stepIndex
    const st = this.steps[stepIndex]

    // 1. Everything stands where the earlier steps left it.
    for (const n of this.nodes) {
      n.obj.visible = n.placed < stepIndex
      this.setPose(n.obj, n.placed < stepIndex ? this.poseBefore(n.obj, stepIndex) : {})
    }
    // The closing pseudo-step ends upright. A guide with no walkthrough at all has no clip for it, and there the
    // root's rest pose is that upright pose. A walkthrough that lists no closing turn needs none: the last step
    // left the assembly as it should stand, and the rest pose is only where the first step began (a chair built
    // from its base up rests upside down).
    const upright = stepIndex === this.finishIndex && !st.clip && !this.endsAsBuilt
    this.setPose(this.model.root, upright ? {} : this.poseBefore(this.model.root, stepIndex))

    // 2. A new mixer for this step's clip. A mixer only writes a property when the value differs from the one it
    //    computed on its previous update, so a reused mixer could leave a node where step 1 above just put it.
    //    A new mixer starts from zeroed buffers: it writes every value except an exact zero vector, and the loop
    //    below puts exactly that in place for the properties the clip drives.
    this.mixer = new AnimationMixer(this.model.scene)
    this.action = null
    if (!st.clip) return
    const action = this.mixer.clipAction(st.clip)
    action.setLoop(LoopOnce, 1)
    action.clampWhenFinished = true
    action.play()
    action.paused = true // the caller sets the time; nothing advances on its own
    this.action = action
    for (const track of st.clip.tracks) {
      const { nodeName, propertyName } = trackTarget(track)
      const obj = this.model.node(nodeName)
      if (!obj) continue
      if (propertyName === 'position') obj.position.set(0, 0, 0)
      if (propertyName === 'scale') obj.scale.set(0, 0, 0)
    }
  }

  private poseMarker(st: StepTimeline, actionIndex: number): void {
    const marker = this.model.marker
    if (!marker) return
    const a = st.actions[actionIndex]
    const on = !!a && a.type === 'fasten' && !!a.node
    marker.visible = on
    if (!on || !this.markerNormal) return
    // The step clips move and scale the ring but do not turn it, so it would lie edge-on at holes that face
    // another way than its rest pose. Face it along the screw being driven. Both hang under the same parent.
    const turns = st.clip?.tracks.some((t) => {
      const target = trackTarget(t)
      return target.propertyName === 'quaternion' && this.model.node(target.nodeName) === marker
    })
    if (turns || a.node!.parent !== marker.parent) return
    const axis = this.model.fastener(a.node!).axis.clone().applyQuaternion(a.node!.quaternion)
    marker.quaternion.setFromUnitVectors(this.markerNormal, axis)
  }
}
