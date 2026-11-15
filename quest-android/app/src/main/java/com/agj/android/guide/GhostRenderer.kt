package com.agj.android.guide

import com.agj.android.core.Agq
import com.agj.android.core.Pose3

/** Ghost states (XR_NATIVE_SPEC §4.1, 4.7–4.9, 4.12). */
enum class GhostState(val wire: String) {
  /** The part to add now: azure, breathing at 0.42 Hz. */
  NEXT("next"),
  /** The wearer is doing the right thing: steady green. */
  GOOD("good"),
  /** Done: green, fades out over 600 ms. */
  CORRECT("correct"),
  /** Wrong part or place: red, 0.83 Hz for 3 cycles then steady. */
  WRONG("wrong"),
  /** Not sure: amber rim, static. */
  UNSURE("unsure"),
}

/**
 * Draws the guidance on the real object: placed parts (subtle), the ghost of the current action's part at its final
 * place (fresnel material), the insertion path along `from_dir` with a travelling dash and an arrowhead, a turning
 * arrow for fasten/tighten, the turn preview for `turn` actions, and the part label (pill + leader line).
 * Reference behaviour: client/src/xr/ghost.ts and stepGuides.ts; look: docs/design/XR_NATIVE_SPEC.md §4.
 *
 * Frames: everything lives under one root entity. Either the tracker measured the assembly itself
 * ([setAssemblyPose], WORLD ← ASSEMBLY, e.g. the marker on the drawer front) or the pose of the table spot is known
 * ([setTablePose], WORLD ← TABLE, e.g. two-corner touch or a fixed demo spot); in the second case the step's pose
 * (the turned drawer, [GuideStep.tableFromAssembly]) is applied by the renderer.
 *
 * Threading: main thread only. [tick] once per render frame.
 */
interface GhostRenderer {
  /** Build every entity (hidden) for the job. Call once per job, before [setStep]. */
  fun load(assembly: Assembly)

  /** Show step [step] (manifest `n`), action [action] (index into the manifest step's `actions`). State → NEXT. */
  fun setStep(step: Int, action: Int)

  fun setState(state: GhostState)

  /** WORLD ← ASSEMBLY measured on the real object; null = lost this frame (ghost fades to 30 % and freezes). */
  fun setAssemblyPose(worldFromAssembly: Pose3?)

  /** WORLD ← TABLE (assembly stands on the table spot in the step's pose); null = hide. */
  fun setTablePose(worldFromTable: Pose3?)

  /** Per-frame animation; [dtS] seconds since the last call. */
  fun tick(dtS: Float)

  /** World position of the current action's label anchor (top of the ghost), for panels and the HUD. */
  fun currentAnchorWorld(): com.agj.android.core.Vec3?

  /** World-space centre of everything placed so far (the step window follows this). */
  fun assemblyCenterWorld(): com.agj.android.core.Vec3?

  fun clear()

  /** Reduced motion (spec §7): no breathing, no mover, static path and arrow, turn preview shows the end pose. */
  var reducedMotion: Boolean

  /** True while the pose is lost (> hold time): the ghost is at 30 % and frozen; the window shows a status pill. */
  val lost: Boolean

  // phase 1 names, kept so older call sites still compile
  fun show(step: Int, action: Int) = setStep(step, action)

  fun setWorldFromAssembly(pose: Pose3?) = setAssemblyPose(pose)
}

/** No scene (tests, or before the scene is ready): logs calls, draws nothing. */
class StubGhostRenderer : GhostRenderer {
  private var assembly: Assembly? = null

  override fun load(assembly: Assembly) {
    this.assembly = assembly
  }

  override fun setStep(step: Int, action: Int) {
    Agq.log.event("ghost_show", mapOf("step" to step, "action" to action, "stub" to true))
  }

  override fun setState(state: GhostState) {}

  override fun setAssemblyPose(worldFromAssembly: Pose3?) {}

  override fun setTablePose(worldFromTable: Pose3?) {}

  override fun tick(dtS: Float) {}

  override fun currentAnchorWorld() = null

  override fun assemblyCenterWorld() = null

  override var reducedMotion = false

  override val lost = false

  override fun clear() {
    assembly = null
  }
}
