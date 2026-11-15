package com.agj.android.vision

import com.agj.android.core.Vec3
import com.agj.android.net.HandSummary

/** One tracked hand in WORLD (metres). [indexTip] null when only the wrist/palm is known. */
data class HandSample(val wrist: Vec3, val indexTip: Vec3?, val tipRadiusM: Float = 0.008f)

/** Both hands at one time; null = that hand is not tracked. */
data class HandsFrame(val tNs: Long, val head: Vec3?, val left: HandSample?, val right: HandSample?) {
  val hands: List<HandSample>
    get() = listOfNotNull(left, right)
}

/** Axis-aligned box in WORLD, e.g. the current ghost part's bounds (the guide module computes it). */
data class Aabb(val min: Vec3, val max: Vec3) {
  fun distanceTo(p: Vec3): Float {
    val dx = maxOf(min.x - p.x, 0f, p.x - max.x)
    val dy = maxOf(min.y - p.y, 0f, p.y - max.y)
    val dz = maxOf(min.z - p.z, 0f, p.z - max.z)
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
  }
}

/**
 * The perception hand summary (`{type:"hands"}`), port of client/src/xr/handChecks.ts: at target = wrist or index
 * tip within 5 cm of the target box; still = every tracked wrist moved < 1 cm over the last 0.5 s; raised = a wrist
 * within 30 cm of the head; present = any hand tracked. Pure Kotlin, unit-tested. Not thread-safe.
 */
class HandChecker {
  private val history = arrayOf(ArrayDeque<Pair<Long, Vec3>>(), ArrayDeque<Pair<Long, Vec3>>())
  private var seq = 0

  fun update(f: HandsFrame, target: Aabb?, tsMs: Long): HandSummary {
    var tracked = 0
    var atTarget = false
    var still = true
    var raised = false
    listOf(f.left, f.right).forEachIndexed { i, hand ->
      val h = history[i]
      if (hand == null) {
        h.clear()
        return@forEachIndexed
      }
      tracked++
      val w = hand.wrist
      if (target != null) {
        if (target.distanceTo(w) <= AT_TARGET_M) atTarget = true
        if (hand.indexTip != null && target.distanceTo(hand.indexTip) <= AT_TARGET_M) atTarget = true
      }
      if (f.head != null && (w - f.head).length() < RAISED_M) raised = true
      h.addLast(f.tNs to w)
      while (h.isNotEmpty() && f.tNs - h.first().first > STILL_WINDOW_NS) h.removeFirst()
      val span = f.tNs - h.first().first
      val moved = h.maxOf { (it.second - w).length() }
      if (span < STILL_WINDOW_NS * 0.8 || moved >= STILL_M) still = false
    }
    if (tracked == 0) still = false
    return HandSummary(seq++, atTarget, still, raised, tracked > 0, tsMs)
  }

  companion object {
    const val AT_TARGET_M = 0.05f
    const val STILL_M = 0.01f
    const val STILL_WINDOW_NS = 500_000_000L
    const val RAISED_M = 0.3f
  }
}

/**
 * Fingertip hold detection for the two-corner touch (register.ts): a corner counts when an index tip stays within
 * 1 cm for 0.8 s. The touched point is the tip centre lowered by its radius. Pure Kotlin. Not thread-safe.
 */
class TipHold(private val holdM: Float = 0.01f, private val holdNs: Long = 800_000_000L) {
  private val at = arrayOfNulls<Vec3>(2)
  private val since = LongArray(2)
  private var fired = BooleanArray(2)

  /** 0..1 progress of the best current hold (for a ring UI). */
  var progress = 0f
    private set

  /** Returns a touched WORLD point when a hold completes (once per hold), else null. */
  fun update(f: HandsFrame, ignore: (Vec3) -> Boolean = { false }): Vec3? {
    var best = 0f
    var out: Vec3? = null
    listOf(f.left, f.right).forEachIndexed { i, hand ->
      val tip = hand?.indexTip
      if (tip == null || ignore(tip)) {
        at[i] = null
        fired[i] = false
        return@forEachIndexed
      }
      val a = at[i]
      if (a == null || (a - tip).length() > holdM) {
        at[i] = tip
        since[i] = f.tNs
        fired[i] = false
      }
      val held = (f.tNs - since[i]).toFloat() / holdNs
      best = maxOf(best, minOf(1f, held))
      if (held >= 1f && !fired[i]) {
        fired[i] = true
        val p = at[i]!!
        out = Vec3(p.x, p.y - hand.tipRadiusM, p.z)
      }
    }
    progress = best
    return out
  }

  fun reset() {
    at.fill(null)
    fired = BooleanArray(2)
    progress = 0f
  }
}
