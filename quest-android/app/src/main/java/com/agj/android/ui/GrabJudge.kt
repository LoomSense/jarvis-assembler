package com.agj.android.ui

import com.agj.android.core.Vec3
import kotlin.math.sqrt

/*
 * LIVE-COACH: "which board did the hand just lift?", decided on the device from the hand joints and the labelled
 * rectangles on the floor (world/FlatParts.kt), with no server round trip. Pure Kotlin, JVM-tested (GrabJudgeTest);
 * MainActivity feeds it every frame (the `LIVE-COACH` hunks) and turns its answer into the verdict a server event
 * gives (the same badge, sound and debounce), logging AGQ `grab`.
 *
 *   A hand closes (grip or pinch) at a labelled rectangle: some point of the hand within [GrabJudge.Config.reachM]
 *   of it on the floor plane and no more than [GrabJudge.Config.aboveM] above its top face. Still closed, the wrist
 *   then moves [GrabJudge.Config.liftM]: the board is being lifted, not touched. That is a pick of that board:
 *   right when the rectangle's kind holds the needed part (identical boards are one kind: from above the two drawer
 *   sides cannot be told apart), wrong otherwise.
 *   Not candidates: a kind whose boards were all placed by earlier actions, and (already left out by FlatParts) a
 *   rectangle on the assembly. A hand between two different boards is not guessed. A hand that opens ends the grip
 *   and nothing more is said. Hardware (bolt or peg) is not judged here: the server tells those apart.
 */

/**
 * One labelled rectangle lying on the floor.
 *
 * @property key the rectangle's identity while it is followed (`LitPart.key`).
 * @property ids the part ids of its kind, in the kind's own order.
 * @property corners the four corners of its top face in WORLD, in order round the rectangle.
 */
class GrabRect(val key: Int, val ids: List<String>, val corners: List<Vec3>) {
  /** Height of the top face (WORLD y). */
  val y: Float = if (corners.isEmpty()) 0f else corners.map { it.y }.average().toFloat()

  /** Distance on the floor plane (WORLD X, Z) from [p] to the rectangle; 0 inside it. */
  fun floorDistance(p: Vec3): Float {
    if (corners.size < 3) return corners.minOfOrNull { flat(p.x - it.x, p.z - it.z) } ?: Float.MAX_VALUE
    var pos = false
    var neg = false
    var best = Float.MAX_VALUE
    for (i in corners.indices) {
      val a = corners[i]
      val b = corners[(i + 1) % corners.size]
      val ex = b.x - a.x
      val ez = b.z - a.z
      val px = p.x - a.x
      val pz = p.z - a.z
      val cross = ex * pz - ez * px
      if (cross > 0f) pos = true else if (cross < 0f) neg = true
      val len2 = ex * ex + ez * ez
      val t = if (len2 < 1e-12f) 0f else ((px * ex + pz * ez) / len2).coerceIn(0f, 1f)
      best = minOf(best, flat(px - t * ex, pz - t * ez))
    }
    return if (pos && neg) best else 0f // on one side of every edge: inside
  }

  private fun flat(dx: Float, dz: Float) = sqrt(dx * dx + dz * dz)
}

/**
 * One tracked hand this frame.
 *
 * @property id which hand (0 left, 1 right): the same hand keeps its id.
 * @property points every known point of the hand in WORLD (wrist, palm, fingertips); empty = the wrist only.
 * @property closed the hand is closed on something (`HeldState.holding`: a grip or a pinch).
 * @property sinceMs the time stamp that names this grip (`HeldState.sinceMs`); a new value = a new grip.
 */
class GrabHand(val id: Int, val wrist: Vec3, val points: List<Vec3>, val closed: Boolean, val sinceMs: Long)

/**
 * A board was lifted.
 *
 * @property part the board taken: [needed] when [right], else the first board of the rectangle's kind not yet placed.
 * @property distM how far the hand was from the rectangle on the floor plane when it closed.
 * @property movedM how far the wrist had moved, still closed, when this was decided.
 */
data class Grab(val part: String, val needed: String, val right: Boolean, val distM: Float, val movedM: Float, val key: Int) {
  val verdict: String
    get() = if (right) "right" else "wrong"
}

/** See the file comment. One clock for every call. Not thread-safe (main thread). */
class GrabJudge(private val cfg: Config = Config()) {
  class Config(
      /** The hand is at a rectangle within this of it on the floor plane. */
      val reachM: Float = 0.12f,
      /** … with its lowest point no more than this above the top face… */
      val aboveM: Float = 0.15f,
      /** … and no more than this below it (the surface height is measured, not exact). */
      val belowM: Float = 0.10f,
      /** Still closed, the wrist has moved this far: lifted, not touched. */
      val liftM: Float = 0.10f,
      /** Two rectangles of different kinds whose distances from the hand differ by less than this: no guess. */
      val clearM: Float = 0.04f,
      /** A grip counts for the rectangle the hand was at within this before it closed (the grip is confirmed late). */
      val recentMs: Long = 600,
      /** A rectangle the camera no longer reports (the hand covers it) is remembered this long. */
      val rememberMs: Long = 3_000,
      /** A closed hand that drops out of tracking keeps its grip this long. */
      val lostMs: Long = 700,
  )

  private class Near(val rect: GrabRect, val distM: Float, val wrist: Vec3, val atMs: Long)

  private class Grip(val sinceMs: Long, val near: Near?, var seenMs: Long, var fired: Boolean = false)

  private val known = LinkedHashMap<Int, Pair<GrabRect, Long>>()
  private val near = HashMap<Int, Near>()
  private val grips = HashMap<Int, Grip>()

  /**
   * Every frame while guiding. [rects]: the live labelled rectangles; [needed]: the part id of the board to take
   * now, null when no board is being taken (nothing is decided then, but hands and grips are still followed, so a
   * board already in the hand when "take" begins is judged at once); [placed]: part ids placed by earlier actions.
   * Returns a pick the moment it is decided, once per grip.
   */
  fun tick(nowMs: Long, hands: List<GrabHand>, rects: List<GrabRect>, needed: String?, placed: Set<String> = emptySet()): Grab? {
    for (r in rects) known[r.key] = r to nowMs
    known.entries.removeAll { nowMs - it.value.second > cfg.rememberMs }
    val loose = known.values.map { it.first }.filter { r -> r.ids.any { it !in placed } }
    val present = hands.associateBy { it.id }
    val gi = grips.entries.iterator()
    while (gi.hasNext()) {
      val e = gi.next()
      val h = present[e.key]
      if (h != null) {
        e.value.seenMs = nowMs
        if (!h.closed) gi.remove() // the hand opened: the board was put down, or nothing was held; nothing is said
      } else if (nowMs - e.value.seenMs > cfg.lostMs) gi.remove()
    }
    var out: Grab? = null
    for (h in hands) {
      look(h, loose, nowMs)
      if (!h.closed) continue
      var g = grips[h.id]
      if (g == null || g.sinceMs != h.sinceMs) {
        g = Grip(h.sinceMs, near[h.id]?.takeIf { nowMs - it.atMs <= cfg.recentMs }, nowMs)
        grips[h.id] = g
      }
      val n = g.near ?: continue // closed somewhere else: not a board from the floor
      if (g.fired || needed == null) continue
      val moved = (h.wrist - n.wrist).length()
      if (moved < cfg.liftM) continue
      g.fired = true
      val right = needed in n.rect.ids
      val part = if (right) needed else n.rect.ids.firstOrNull { id -> id !in placed } ?: continue
      if (out == null) out = Grab(part, needed, right, n.distM, moved, n.rect.key)
    }
    return out
  }

  /** The rectangle [h] is at now, kept as "where this hand last was"; a hand between two different boards is at none. */
  private fun look(h: GrabHand, loose: List<GrabRect>, nowMs: Long) {
    val pts = if (h.points.isEmpty()) listOf(h.wrist) else h.points
    val low = pts.minOf { it.y }
    val at =
        loose
            .mapNotNull { r ->
              val up = low - r.y
              if (up > cfg.aboveM || up < -cfg.belowM) return@mapNotNull null
              val d = pts.minOf { r.floorDistance(it) }
              if (d > cfg.reachM) null else r to d
            }
            .sortedBy { it.second }
    val best = at.firstOrNull() ?: return // at no board now: where it was a moment ago stands for [Config.recentMs]
    val other = at.firstOrNull { it.first.ids != best.first.ids }
    if (other != null && other.second - best.second < cfg.clearM) near.remove(h.id)
    else near[h.id] = Near(best.first, best.second, h.wrist, nowMs)
  }

  /** Forget everything (a new guide). */
  fun reset() {
    known.clear()
    near.clear()
    grips.clear()
  }
}
