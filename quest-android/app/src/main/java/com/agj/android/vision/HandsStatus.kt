package com.agj.android.vision

/**
 * Decides when the AGQ `hands_status` event is logged and with which `state` (pure, unit-tested). Fed about ten
 * times a second with what the hand sources gave.
 *
 * | state | meaning | logged |
 * |---|---|---|
 * | `joints_live` | a hand with wrist, thumb tip and index tip | at once, each time it is reached |
 * | `wrist_only` | a hand, but no finger joints | after 1 s (10 s when joints were live before) |
 * | `no_hands_tracked` | the API answers, yet no hand was tracked since the start | after 12 s, then every 60 s |
 * | `hands_out_of_view` | no hand for 12 s, after hands had been tracked | once per time |
 * | `controllers_active` | a controller is the active input | after 3 s, then every 60 s |
 * | `api_failed` | no hand source answers | after 2 s, then every 60 s |
 *
 * `no_hands_tracked` is the "hand tracking is off in the headset settings" case as far as an app can tell: the
 * SDK has no call that reads the setting, so the same state is logged when the hands simply stayed out of view
 * for the first 12 s. A later `joints_live` or `wrist_only` settles it.
 */
class HandsStatusTracker(
    private val noHandsMs: Long = 12_000,
    private val controllersMs: Long = 3_000,
    private val repeatMs: Long = 60_000,
) {
  /** The state last logged; null before the first. */
  var reported: String? = null
    private set

  private var candidate: String? = null
  private var since = 0L
  private var lastLogMs = 0L
  private var everTracked = false

  /**
   * @param fingers a tracked hand has finger joints; [wrist] a tracked hand without them
   * @param controllers a controller is the active input of a hand; [works] a hand source answered
   * @return the state to log now, or null
   */
  fun update(nowMs: Long, fingers: Boolean, wrist: Boolean, controllers: Boolean, works: Boolean): String? {
    if (fingers || wrist) everTracked = true
    val cand =
        when {
          fingers -> JOINTS_LIVE
          wrist -> WRIST_ONLY
          !works -> API_FAILED
          controllers -> CONTROLLERS_ACTIVE
          everTracked -> HANDS_OUT_OF_VIEW
          else -> NO_HANDS_TRACKED
        }
    if (cand != candidate) {
      candidate = cand
      since = nowMs
    }
    val dwell =
        when (cand) {
          JOINTS_LIVE -> 0L
          WRIST_ONLY -> if (reported == JOINTS_LIVE) 10_000L else 1_000L
          API_FAILED -> 2_000L
          CONTROLLERS_ACTIVE -> controllersMs
          else -> noHandsMs
        }
    if (nowMs - since < dwell) return null
    if (cand == reported) {
      if (cand in REPEATED && nowMs - lastLogMs >= repeatMs) {
        lastLogMs = nowMs
        return cand
      }
      return null
    }
    reported = cand
    lastLogMs = nowMs
    return cand
  }

  companion object {
    const val JOINTS_LIVE = "joints_live"
    const val WRIST_ONLY = "wrist_only"
    const val NO_HANDS_TRACKED = "no_hands_tracked"
    const val HANDS_OUT_OF_VIEW = "hands_out_of_view"
    const val CONTROLLERS_ACTIVE = "controllers_active"
    const val API_FAILED = "api_failed"
    private val REPEATED = setOf(NO_HANDS_TRACKED, CONTROLLERS_ACTIVE, API_FAILED)

    /** One sentence for the log line (and for whoever reads it to the wearer). */
    fun message(state: String): String =
        when (state) {
          JOINTS_LIVE -> "hand joints are live (wrist, palm, fingertips)"
          WRIST_ONLY -> "hands are tracked at the wrist only: no finger joints from the body-tracking skeleton"
          NO_HANDS_TRACKED ->
              "no hand tracked since the start although the hand API answers: hand tracking is probably off in " +
                  "the headset settings (Settings > Movement tracking > Hand and body tracking), or no hand was " +
                  "held in view yet"
          HANDS_OUT_OF_VIEW -> "no hand in view for a while (hands were tracked earlier)"
          CONTROLLERS_ACTIVE ->
              "a controller is the active input: hands are not tracked while it is held; put the controllers down"
          else -> "no hand source answers (see the `error` lines with where: hands)"
        }
  }
}
