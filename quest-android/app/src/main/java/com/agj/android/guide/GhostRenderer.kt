package com.agj.android.guide

import com.agj.android.core.Agq
import com.agj.android.core.Pose3

/**
 * Draws the guidance on the real object: the ghost of the current part at its final place, insertion arrows
 * (`from_dir`), turning arrows for `turn`/`tighten`, hole rings, and labels with part/hardware codes.
 * Reference implementation of every visual: client/src/xr/ghost.ts and stepGuides.ts.
 *
 * Frames: [setWorldFromAssembly] gives WORLD ← ASSEMBLY (metres); everything else is placed in ASSEMBLY coordinates
 * (glTF Y-up; convert manifest vectors with [ManifestAction.targetGltf] / [ManifestAction.fromDirGltf]).
 *
 * Threading: main thread only (it creates and edits Spatial SDK entities). [setWorldFromAssembly] is called every
 * render frame while tracking and must be cheap (update one root Transform).
 */
interface GhostRenderer {
  /** Load the GLB as entities under one hidden root. Call once per job, before [show]. */
  fun load(assembly: Assembly)

  /** Show step [step] (manifest `n`), action [action] (0-based index into the step's `actions`). */
  fun show(step: Int, action: Int)

  /** Where the assembly is in the world; null = not tracked (hide, or fade the ghost). */
  fun setWorldFromAssembly(pose: Pose3?)

  /** Remove everything this renderer created. */
  fun clear()
}

/** Phase 1 stub: logs calls, draws nothing. */
class StubGhostRenderer : GhostRenderer {
  private var assembly: Assembly? = null

  override fun load(assembly: Assembly) {
    this.assembly = assembly
  }

  override fun show(step: Int, action: Int) {
    Agq.log.event("ghost_show", mapOf("step" to step, "action" to action, "stub" to true))
  }

  override fun setWorldFromAssembly(pose: Pose3?) {}

  override fun clear() {
    assembly = null
  }
}
