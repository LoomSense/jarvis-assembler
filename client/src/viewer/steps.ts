import {
  AnimationClip,
  AnimationMixer,
  Box3,
  LoopOnce,
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
  /** the part or screw that appears with this action; null for `turn` */
  node: Object3D | null
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
  actions: ActionInfo[]
  /** time of the last key, seconds */
  duration: number
}

interface Pose {
  position?: Vector3
  quaternion?: Quaternion
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

  constructor(private model: Model, manifest: Manifest) {
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
          t0: a.t0_s,
          t1: a.t1_s,
          screwIndex: a.type === 'fasten' ? seen.get(code)! : 0,
          screwTotal: a.type === 'fasten' ? totals.get(code)! : 0,
        }
      })
      const lastEnd = Math.max(0, ...actions.map((a) => a.t1))
      this.steps.push({ index, raw, clip, actions, duration: Math.max(clip?.duration ?? 0, lastEnd) })
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
    this.finishIndex = this.steps.length
    this.steps.push({ index: this.finishIndex, raw: null, clip: finishClip, actions: [], duration: finishClip?.duration ?? 0 })

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
    for (const n of this.nodes) {
      if (n.placed === stepIndex) n.obj.visible = n.action < 0 ? actionIndex >= 0 : n.action <= actionIndex
    }
    if (this.action && this.mixer) {
      this.action.time = Math.min(Math.max(time, 0), st.clip!.duration)
      this.mixer.update(0)
    }
    this.poseMarker(st, actionIndex)
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
    // The closing pseudo-step ends upright. Without a clip for it, the root's rest pose is that upright pose.
    const upright = stepIndex === this.finishIndex && !st.clip
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
