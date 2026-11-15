package com.agj.android.ui

/**
 * The coach flow (client/src/xr/scan.ts, coach 1/4 – 4/4), as a pure state machine:
 *
 * LOOK (point at the box/label) → IDENTIFY (`/api/identify`) → BUILD (`/api/catalog/open`, poll `/api/jobs/{id}`
 * until ready; skipped when ready) → LOCATE (`/locate`: which step) → REGISTER (marker seen or two-corner touch) →
 * GUIDE (steps; tracking keeps running and may drop back to REGISTER when lost for long).
 *
 * A `job` launch parameter skips LOOK/IDENTIFY/BUILD. Pure Kotlin, unit-tested. Not thread-safe: drive it from the
 * main thread; observers get [Listener.onStage] synchronously.
 */
class CoachFlow(private val listener: Listener? = null) {
  enum class Stage {
    LOOK,
    IDENTIFY,
    BUILD,
    LOCATE,
    REGISTER,
    GUIDE,
  }

  fun interface Listener {
    fun onStage(from: Stage, to: Stage, flow: CoachFlow)
  }

  var stage: Stage = Stage.LOOK
    private set

  var jobId: String? = null
    private set

  var product: String? = null
    private set

  /** Manifest step number to start guiding at (from LOCATE); 1-based like the manifest's `n`. */
  var startStep: Int? = null
    private set

  private fun go(to: Stage) {
    val from = stage
    if (from == to) return
    stage = to
    listener?.onStage(from, to, this)
  }

  /** A job was given at launch: go straight to LOCATE. */
  fun startWithJob(id: String) {
    jobId = id
    go(Stage.LOCATE)
  }

  /** LOOK: a frame worth sending was captured. */
  fun frameCaptured() {
    if (stage == Stage.LOOK) go(Stage.IDENTIFY)
  }

  /** IDENTIFY answered. [jobId] null = not recognised → back to LOOK. */
  fun identified(jobId: String?, product: String?, ready: Boolean) {
    if (stage != Stage.IDENTIFY) return
    if (jobId == null) {
      go(Stage.LOOK)
      return
    }
    this.jobId = jobId
    this.product = product
    go(if (ready) Stage.LOCATE else Stage.BUILD)
  }

  /** BUILD finished (the server built the guide). */
  fun built() {
    if (stage == Stage.BUILD) go(Stage.LOCATE)
  }

  /** LOCATE answered with the next step (null = unknown → start at the first step). */
  fun located(nextStep: Int?) {
    if (stage != Stage.LOCATE) return
    startStep = nextStep
    go(Stage.REGISTER)
  }

  /** REGISTER: the assembly pose is known (marker or touch). */
  fun registered() {
    if (stage == Stage.REGISTER) go(Stage.GUIDE)
  }

  /** GUIDE: tracking lost for long → ask to register again. */
  fun trackingLost() {
    if (stage == Stage.GUIDE) go(Stage.REGISTER)
  }

  /** A failure in IDENTIFY/BUILD/LOCATE: back to the last stage that can retry by itself. */
  fun failed() {
    when (stage) {
      Stage.IDENTIFY, Stage.BUILD -> go(Stage.LOOK)
      Stage.LOCATE -> located(null)
      else -> {}
    }
  }
}
