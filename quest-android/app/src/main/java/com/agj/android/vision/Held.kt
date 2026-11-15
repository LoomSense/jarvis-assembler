package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3

/** Which hand. */
enum class HandSide(val wire: String) {
  LEFT("left"),
  RIGHT("right"),
}

/**
 * What the wearer's hand is doing, for the verdict badge ("is the part in my hand the right one?"). Read it from
 * `vision.input.held` on the render thread (it is updated there, every other frame), or from any thread.
 *
 * @property holding a hand is closed on something: a pinch (thumb tip near the index or middle tip), a grip (index
 *   and middle tips curled to the palm), or, when the source has no finger joints, the system's own index pinch.
 *   An empty pinch or fist reads as holding too: it says "the hand is shaped to hold", not "there is a part".
 * @property handWorldPos the wrist of [which] hand in WORLD (right-handed, metres; `toSdk()` before a Transform).
 *   Null when no hand is tracked or no hand source works: place the badge without a wrist then.
 * @property which the holding hand; when none holds, the tracked hand nearest the head; the last one otherwise.
 * @property sinceMs `SystemClock.elapsedRealtime()` at which [holding] took its current value for [which] hand
 *   (a time stamp, not a duration: `now - sinceMs` is how long).
 * @property still the wrist moved less than 1.5 cm over the last 0.4 s.
 * @property grip `pinch`, `grip`, `system_pinch`, or null when not holding.
 * @property fingers the hand has finger joints (false: wrist only, [holding] comes from the system pinch).
 */
data class HeldState(
    val holding: Boolean,
    val handWorldPos: Vec3?,
    val which: HandSide,
    val sinceMs: Long,
    val still: Boolean = false,
    val grip: String? = null,
    val fingers: Boolean = false,
) {
  /** A hand is tracked now. */
  val tracked: Boolean
    get() = handWorldPos != null

  companion object {
    val NONE = HeldState(false, null, HandSide.RIGHT, 0L)
  }
}

/**
 * Thresholds of [HeldDetector], metres and milliseconds. Tip joints sit inside the finger ends, so a screw held
 * between the pads reads 1.5–2.5 cm and a 16 mm board held at its edge 2.5–4 cm; an open hand reads 8–12 cm.
 */
data class HeldParams(
    val pinchEnterM: Float = 0.045f,
    val pinchExitM: Float = 0.065f,
    val gripEnterM: Float = 0.07f,
    val gripExitM: Float = 0.085f,
    val onMs: Long = 250,
    val offMs: Long = 400,
    val lostMs: Long = 600,
    val stillM: Float = 0.015f,
    val stillWindowMs: Long = 400,
)

/**
 * Turns hand samples into a [HeldState]. Pure Kotlin, unit-tested; call from one thread.
 *
 * Per hand: a raw "closed" reading (see [HeldState.holding]) with hysteresis, switched on after [HeldParams.onMs]
 * and off after [HeldParams.offMs]; a hand that disappears keeps its state for [HeldParams.lostMs] (fingers behind
 * a panel drop out for a few frames).
 */
class HeldDetector(private val p: HeldParams = HeldParams()) {
  private class Hand {
    var holding = false
    var grip: String? = null
    var rawSince = Long.MIN_VALUE // when the raw reading last differed from `holding`
    var lastSeen = Long.MIN_VALUE
    var last: HandSample? = null
    val track = ArrayDeque<Pair<Long, Vec3>>()
  }

  private val hands = arrayOf(Hand(), Hand())
  private var side = HandSide.RIGHT
  private var holding = false
  private var since = 0L

  fun update(f: HandsFrame?, nowMs: Long): HeldState {
    val samples = arrayOf(f?.left, f?.right)
    for (i in 0..1) step(hands[i], samples[i], nowMs)
    // the holding hand; with two, the one nearer the head (the one being shown); else the tracked hand nearest the head
    fun headDist(i: Int): Float {
      val w = hands[i].last?.wrist ?: return Float.MAX_VALUE
      val h = f?.head ?: return if (i == side.ordinal) 0f else 1f
      return (w - h).length()
    }
    val holders = (0..1).filter { hands[it].holding }
    val seen = (0..1).filter { samples[it] != null }
    val pick =
        when {
          holders.isNotEmpty() -> holders.minBy { headDist(it) }
          seen.isNotEmpty() -> seen.minBy { headDist(it) }
          else -> side.ordinal
        }
    val h = hands[pick]
    val nowSide = HandSide.entries[pick]
    if (h.holding != holding || (nowSide != side && h.holding)) since = nowMs
    holding = h.holding
    side = nowSide
    val s = samples[pick]
    // a holding hand that dropped out for a few frames keeps its last wrist (the badge does not jump away)
    val wrist = s?.wrist ?: if (holding) h.last?.wrist else null
    val fingers = (s ?: h.last.takeIf { holding })?.hasFingers == true
    return HeldState(holding, wrist, side, since, still(h, nowMs), if (holding) h.grip else null, fingers)
  }

  private fun step(h: Hand, s: HandSample?, nowMs: Long) {
    if (s == null) {
      h.track.clear()
      if (h.holding && nowMs - h.lastSeen > p.lostMs) {
        h.holding = false
        h.grip = null
        h.rawSince = Long.MIN_VALUE
      }
      return
    }
    h.lastSeen = nowMs
    h.last = s
    h.track.addLast(nowMs to s.wrist)
    while (h.track.isNotEmpty() && nowMs - h.track.first().first > p.stillWindowMs) h.track.removeFirst()
    val raw = closed(s, h.holding)
    if ((raw != null) == h.holding) {
      h.rawSince = Long.MIN_VALUE
      if (raw != null) h.grip = raw
      return
    }
    if (h.rawSince == Long.MIN_VALUE) h.rawSince = nowMs
    if (nowMs - h.rawSince >= if (raw != null) p.onMs else p.offMs) {
      h.holding = raw != null
      h.grip = raw
      h.rawSince = Long.MIN_VALUE
    }
  }

  /** `pinch`, `grip` or `system_pinch` when the hand is closed on something, else null. */
  private fun closed(s: HandSample, was: Boolean): String? {
    val thumb = s.thumbTip
    val index = s.indexTip
    if (thumb != null && index != null) {
      val gap = minOf((thumb - index).length(), s.middleTip?.let { (thumb - it).length() } ?: Float.MAX_VALUE)
      if (gap < if (was) p.pinchExitM else p.pinchEnterM) return "pinch"
      val palm = s.palm
      val middle = s.middleTip
      if (palm != null && middle != null) {
        val curl = maxOf((index - palm).length(), (middle - palm).length())
        if (curl < if (was) p.gripExitM else p.gripEnterM) return "grip"
      }
      return null
    }
    return if (s.pinching == true) "system_pinch" else null
  }

  private fun still(h: Hand, nowMs: Long): Boolean {
    val t = h.track
    if (t.isEmpty() || nowMs - t.first().first < p.stillWindowMs * 0.7) return false
    val w = t.last().second
    return t.maxOf { (it.second - w).length() } < p.stillM
  }
}

/**
 * A hand in one camera image.
 *
 * @property box `[x0, y0, x1, y1]` of the hand in the picture, normalised 0..1 (origin top-left), clamped.
 * @property distM camera to the hand's centre, metres.
 * @property inView the hand's centre is inside the picture (5 % margin) and in front of the camera.
 */
class HandInFrame(val box: FloatArray, val distM: Float, val inView: Boolean)

/** Projects a hand into a camera image (pinhole, no distortion). Pure, unit-tested. */
object HandProjector {
  /**
   * @param worldFromCamera the frame's camera pose (OpenCV axes: +X right, +Y down, +Z forward).
   * @param padM added around the joints (a part sticks out of the fingers); a wrist-only hand gets 2×.
   * @return null when no point of the hand is in front of the camera.
   */
  fun project(hand: HandSample, intr: Intrinsics, worldFromCamera: Pose3, padM: Float = 0.07f): HandInFrame? {
    val camFromWorld = worldFromCamera.inverse()
    var x0 = Double.MAX_VALUE
    var y0 = Double.MAX_VALUE
    var x1 = -Double.MAX_VALUE
    var y1 = -Double.MAX_VALUE
    var n = 0
    var sum = Vec3.ZERO
    for (w in hand.points) {
      val c = camFromWorld.transform(w)
      if (c.z < 0.05f) continue
      val u = intr.fx * c.x / c.z + intr.cx
      val v = intr.fy * c.y / c.z + intr.cy
      x0 = minOf(x0, u)
      y0 = minOf(y0, v)
      x1 = maxOf(x1, u)
      y1 = maxOf(y1, v)
      sum += c
      n++
    }
    if (n == 0) return null
    val centre = sum * (1f / n)
    val pad = (if (hand.hasFingers) padM else padM * 2) / centre.z
    x0 -= pad * intr.fx
    x1 += pad * intr.fx
    y0 -= pad * intr.fy
    y1 += pad * intr.fy
    val cu = (intr.fx * centre.x / centre.z + intr.cx) / intr.width
    val cv = (intr.fy * centre.y / centre.z + intr.cy) / intr.height
    val inView = cu in 0.05..0.95 && cv in 0.05..0.95
    fun nx(v: Double) = (v / intr.width).coerceIn(0.0, 1.0).toFloat()
    fun ny(v: Double) = (v / intr.height).coerceIn(0.0, 1.0).toFloat()
    return HandInFrame(floatArrayOf(nx(x0), ny(y0), nx(x1), ny(y1)), centre.length(), inView)
  }
}
