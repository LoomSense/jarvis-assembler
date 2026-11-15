package com.agj.android.ui

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.facingPose
import com.agj.android.guide.toSdk
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the step window lands, seen from the head.
 *  - UI-FLOOR, looking down steeply at the work (seated or crouching over parts on the floor, standing over a table):
 *    just beyond the object, within 25° of the line eyes → object, below the horizon, 0.6–0.9 m from the eyes, facing
 *    the eyes, never over the object's box in view; it stays put while the head sways and takes a new place with a fade.
 *  - a table at arm's length seen at a shallow angle (judge round 2, A1): at most 30° from the object's direction, its
 *    inner edge at least 10° from it, 0.75–0.95 m away, and never over the object's box in view.
 *  - labels, pills and the badge: facing the eyes, upright in the view when the wearer looks down steeply.
 */
class WindowPlacementTest {
  private class Case(val name: String, val head: Vec3, val top: Vec3, val halfWidth: Float = 0.22f)

  /** A table at arm's length, seen at a shallow angle: the window stays beside the object, as before UI-FLOOR. */
  private val beside =
      listOf(
          Case("straight ahead on a table, seated", Vec3(0f, 1.20f, 0f), Vec3(0f, 0.86f, -0.55f)),
          Case("to the left", Vec3(0.30f, 1.25f, 0.20f), Vec3(-0.35f, 0.86f, -0.25f)),
          Case("far and small", Vec3(0f, 1.60f, 0f), Vec3(0.4f, 0.80f, -2.2f), 0.10f),
      )

  // UI-FLOOR: looking down steeply at the work
  private val seatedFloor = Case("(a) seated, floor 0.8 m ahead, 0.9 m below", Vec3(0f, 1.15f, 0f), Vec3(0f, 0.25f, -0.80f))
  private val crouching = Case("(b) crouching close, 0.5 m away", Vec3(0f, 0.85f, 0f), Vec3(0f, 0.25f, -0.50f))
  private val standingTable = Case("(c) standing at a table", Vec3(0f, 1.60f, 0f), Vec3(0f, 0.90f, -0.50f))
  private val steep =
      listOf(
          seatedFloor,
          crouching,
          standingTable,
          Case("standing, low on the floor", Vec3(0f, 1.60f, 0f), Vec3(0f, 0.25f, -0.90f)),
          Case("seated, floor to the left", Vec3(0.2f, 1.10f, 0.1f), Vec3(-0.45f, 0.20f, -0.35f)),
          Case("almost under the head", Vec3(0f, 1.10f, 0f), Vec3(0f, 0.20f, -0.25f)),
      )

  private fun deg(rad: Float) = Math.toDegrees(rad.toDouble())

  /** The angle between two directions, degrees. */
  private fun angle(a: Vec3, b: Vec3) = deg(acos((a.dot(b) / (a.length() * b.length())).coerceIn(-1f, 1f)))

  /** (azimuth to the right of the object's direction, elevation) of [p] seen from [head], radians. */
  private fun view(p: Vec3, head: Vec3, objectTop: Vec3): Pair<Float, Float> {
    val d = hypot(objectTop.x - head.x, objectTop.z - head.z)
    val fwd = Vec3((objectTop.x - head.x) / d, 0f, (objectTop.z - head.z) / d)
    val right = Vec3(-fwd.z, 0f, fwd.x)
    val v = p - head
    val f = v.dot(fwd)
    val r = v.dot(right)
    return atan2(r, f) to atan2(v.y, hypot(r, f))
  }

  /** The window panel's outline ([STEP_PANEL_W] × [STEP_PANEL_H]) in WORLD, as MainActivity poses it. */
  private fun windowOutline(pos: Vec3, head: Vec3, w: Float = STEP_PANEL_W, h: Float = STEP_PANEL_H, pose: Pose3 = Placement.facingEyes(pos, head)): List<Vec3> {
    val out = ArrayList<Vec3>()
    for (i in 0..8) {
      val t = -1f + i * 0.25f
      out += pose.transform(Vec3(t * w / 2, -h / 2, 0f))
      out += pose.transform(Vec3(t * w / 2, h / 2, 0f))
      out += pose.transform(Vec3(-w / 2, t * h / 2, 0f))
      out += pose.transform(Vec3(w / 2, t * h / 2, 0f))
    }
    return out
  }

  /** Points on the edges of the object's box (square footprint turned by [yaw], 0.3 m tall, top at [top]). */
  private fun boxOutline(top: Vec3, halfWidth: Float, yaw: Float): List<Vec3> {
    val q = Quat.yaw(yaw)
    val corners = listOf(-1f to -1f, -1f to 1f, 1f to 1f, 1f to -1f).map { (a, b) -> q.rotate(Vec3(a * halfWidth, 0f, b * halfWidth)) }
    val out = ArrayList<Vec3>()
    for (i in corners.indices) {
      val a = corners[i]
      val b = corners[(i + 1) % 4]
      for (k in 0..20) {
        val p = a.lerp(b, k / 20f)
        out += top + p
        out += top + p + Vec3(0f, -0.30f, 0f)
      }
    }
    return out
  }

  /** Smallest angular distance (degrees, in azimuth/elevation) from the object's outline to the window's rectangle in view. */
  private fun gap(c: Case, outline: List<Vec3>, yaw: Float): Double {
    val look = atan2(c.top.x - c.head.x, c.top.z - c.head.z)
    val w = outline.map { view(it, c.head, c.top) }
    val (a0, a1) = w.minOf { it.first } to w.maxOf { it.first }
    val (e0, e1) = w.minOf { it.second } to w.maxOf { it.second }
    return boxOutline(c.top, c.halfWidth, look + yaw).map { view(it, c.head, c.top) }.minOf { (a, e) ->
      deg(hypot(maxOf(a0 - a, a - a1, 0f), maxOf(e0 - e, e - e1, 0f)))
    }
  }

  /**
   * The rule before UI-FLOOR, for every case: 30° to the right of the object's direction, 0.8 m from the head
   * horizontally, 10°–20° below eye level (higher when it would cover the object), window 0.54 × 0.42 m, 8° lean.
   */
  private fun before(c: Case): Vec3 {
    val dx = c.top.x - c.head.x
    val dz = c.top.z - c.head.z
    val d = hypot(dx, dz).coerceAtLeast(0.2f)
    val right = Vec3(-dz / d, 0f, dx / d)
    val flat = (Vec3(dx / d, 0f, dz / d) * cos(0.5236f) + right * sin(0.5236f)) * 0.8f
    var y = (c.top.y + 0.12f).coerceIn(c.head.y - 0.8f * 0.364f, c.head.y - 0.8f * 0.176f)
    val inner = 0.5236f - atan(0.26f / 0.8f)
    if (inner - atan2(c.halfWidth, (d - c.halfWidth).coerceAtLeast(0.05f)) < atan(0.08f / 0.8f)) {
      val reach = if (c.top.y < c.head.y) hypot(d + c.halfWidth, c.halfWidth) else (d - c.halfWidth).coerceAtLeast(0.1f)
      y = y.coerceAtLeast((c.head.y + 0.8f * (c.top.y - c.head.y) / reach + 0.08f + 0.21f).coerceAtMost(c.head.y - 0.8f * 0.035f))
    }
    return Vec3(c.head.x + flat.x, y, c.head.z + flat.z)
  }

  private fun line(what: String, c: Case, p: Vec3, facing: Double) =
      "%-7s bearing %5.1f°  elevation %6.1f°  distance %.2f m  off the eyes→object line %5.1f°  faces the eyes within %4.1f°"
          .format(what, deg(view(p, c.head, c.top).first), deg(view(p, c.head, c.top).second), (p - c.head).length(), angle(p - c.head, c.top - c.head), facing)

  @Test
  fun lookingDownSteeplyTheWindowSitsJustBeyondTheWorkFacingTheEyes() {
    for (c in steep) {
      val old = before(c)
      val oldFacing = angle(Placement.facing(old, c.head).q.rotate(Vec3(0f, 0f, 1f)), c.head - old)
      val p = WindowFollow().target(c.top, c.halfWidth, c.head)
      val pose = Placement.facingEyes(p, c.head)
      val facing = angle(pose.q.rotate(Vec3(0f, 0f, 1f)), c.head - p)
      val (az, el) = view(p, c.head, c.top)
      val dist = (p - c.head).length()
      val off = angle(p - c.head, c.top - c.head)
      val outline = windowOutline(p, c.head)
      val gaps = listOf(0f, 0.39f, 0.785f).map { gap(c, outline, it) }
      val oldGap = listOf(0f, 0.39f, 0.785f).minOf { gap(c, windowOutline(old, c.head, 0.54f, 0.42f, Placement.facing(old, c.head)), it) }
      println("WINDOW ${c.name}")
      println("  " + line("before", c, old, oldFacing) + "  object %4.1f° away".format(oldGap))
      println("  " + line("after", c, p, facing) + "  object %4.1f° away".format(gaps.min()))
      assertTrue("${c.name}: below the horizon, elevation ${deg(el)}°", deg(el) < -7.5)
      assertTrue("${c.name}: the whole window is below the horizon", outline.all { it.y < c.head.y })
      assertTrue("${c.name}: $dist m from the eyes", dist in 0.6f..0.9f)
      assertTrue("${c.name}: faces the eyes within 10°, is $facing°", facing <= 10.0)
      assertTrue("${c.name}: object ${gaps.min()}° from the window, must be over 2.5°", gaps.min() >= 2.5)
      assertTrue("${c.name}: beyond the object (above it in view), not across the hands", el > boxOutline(c.top, c.halfWidth, 0f).maxOf { view(it, c.head, c.top).second })
      assertEquals("${c.name}: straight beyond the object", 0.0, deg(az), 0.01)
      assertTrue("${c.name}: not further from the work than before ($off° vs ${angle(old - c.head, c.top - c.head)}°)", off <= angle(old - c.head, c.top - c.head))
      // (a) and (b): the glance from the work to the instruction is at most 25°
      if (c === seatedFloor || c === crouching) assertTrue("${c.name}: $off° from the eyes→object line, at most 25°", off <= 25.0)
    }
    // (c) standing at a table is not worse than before: nearer the work, nearer the reading distance, facing the eyes
    val c = standingTable
    val p = WindowFollow().target(c.top, c.halfWidth, c.head)
    val old = before(c)
    assertTrue(angle(p - c.head, c.top - c.head) < angle(old - c.head, c.top - c.head) - 10.0)
    assertTrue(abs((p - c.head).length() - 0.8f) <= abs((old - c.head).length() - 0.8f) + 1e-3f)
  }

  @Test
  fun atATableSeenAtAShallowAngleTheWindowStaysBesideTheObject() {
    for (c in beside) {
      val p = WindowFollow().target(c.top, c.halfWidth, c.head)
      val (az, el) = view(p, c.head, c.top)
      val flat = hypot(p.x - c.head.x, p.z - c.head.z)
      val dist = (p - c.head).length()
      val outline = windowOutline(p, c.head).map { view(it, c.head, c.top) }
      val inner = outline.minOf { it.first }
      val outer = outline.maxOf { it.first }
      val facing = angle(Placement.facingEyes(p, c.head).q.rotate(Vec3(0f, 0f, 1f)), c.head - p)
      // the side column hangs on the window's outer edge, in the window's plane (MainActivity.placeSide)
      val sideAz = az + atan2(STEP_PANEL_W / 2 + 0.02f + SIDE_PANEL_W / 2, flat)
      println(
          "WINDOW %-38s bearing %5.1f°  inner edge %5.1f°  outer edge %5.1f°  side column %5.1f°  elevation %6.1f°  dist %.2f m  (before: elevation %6.1f°)"
              .format(c.name, deg(az), deg(inner), deg(outer), deg(sideAz), deg(el), dist, deg(view(before(c), c.head, c.top).second))
      )
      assertTrue("${c.name}: on the right", az > 0f)
      assertTrue("${c.name}: bearing ${deg(az)}° must be at most 30°", deg(az) <= 30.05)
      assertTrue("${c.name}: inner edge ${deg(inner)}° must keep 10° clear", deg(inner) >= 10.0)
      assertEquals("${c.name}: 0.8 m from the head", 0.80f, flat, 1e-3f)
      assertTrue("${c.name}: distance $dist", dist in 0.75f..0.95f)
      assertTrue("${c.name}: elevation ${deg(el)}° below eye level, not under 20°", deg(el) in -20.5..-1.5)
      assertTrue("${c.name}: faces the eyes within 10°, is $facing°", facing <= 10.0)
    }
  }

  @Test
  fun windowNeverCoversTheObjectInView() {
    for (c in beside + steep) for (yaw in listOf(0f, 0.39f, 0.785f)) {
      val p = WindowFollow().target(c.top, c.halfWidth, c.head)
      val g = gap(c, windowOutline(p, c.head), yaw)
      assertTrue("${c.name} yaw $yaw: object $g° from the window, must be over 2.5°", g >= 2.5)
      // and the window is not in the middle of the object either: above it, or beside it
      val look = atan2(c.top.x - c.head.x, c.top.z - c.head.z)
      val box = boxOutline(c.top, c.halfWidth, look + yaw).map { view(it, c.head, c.top) }
      val (ca, ce) = view(p, c.head, c.top)
      assertTrue("${c.name}: window centre above or beside the object", ce > box.maxOf { it.second } || ca > box.maxOf { it.first })
    }
  }

  @Test
  fun theSwitchBetweenBesideAndBeyondHasHysteresis() {
    // the object 0.6 m ahead; the head's height sets how steeply the wearer looks down at it
    fun at(f: WindowFollow, depressionDeg: Double): Double {
      val head = Vec3(0f, 0.8f + 0.6f * Math.tan(Math.toRadians(depressionDeg)).toFloat(), 0f)
      val top = Vec3(0f, 0.8f, -0.6f)
      return deg(view(f.target(top, 0.22f, head), head, top).first)
    }
    assertEquals("first placement at 37°: beyond", 0.0, at(WindowFollow(), 37.0), 0.01)
    assertEquals("first placement at 33°: beside", 30.0, at(WindowFollow(), 33.0), 0.01)
    val f = WindowFollow()
    assertEquals(0.0, at(f, 45.0), 0.01)
    assertEquals("leaning back to 32° keeps it beyond", 0.0, at(f, 32.0), 0.01)
    assertEquals("28°: beside", 30.0, at(f, 28.0), 0.01)
    assertEquals("leaning in to 38° keeps it beside", 30.0, at(f, 38.0), 0.01)
    assertEquals("41°: beyond", 0.0, at(f, 41.0), 0.01)
    f.reset()
    assertEquals("after a reset the first rule again", 30.0, at(f, 33.0), 0.01)
  }

  @Test
  fun theWindowStaysPutWhileTheHeadMovesAndTakesANewPlaceOnlyOnceTheWearerSettles() {
    val c = seatedFloor
    val dt = 1f / 72f
    val r = WindowRelocator()
    val p0 = r.update(r.target(c.top, c.halfWidth, c.head), dt)!!
    // working: the head sways 8 cm, the object's pose shivers by 5 mm: not one millimetre of window motion
    for (i in 0 until 216) {
      val sway = Vec3(0.07f * sin(i * 0.11f), 0.02f * sin(i * 0.07f), 0.04f * cos(i * 0.05f))
      val jitter = Vec3(0.005f * sin(i * 1.3f), 0f, 0.005f * cos(i * 0.9f))
      assertEquals(p0, r.update(r.target(c.top + jitter, c.halfWidth, c.head + sway), dt))
    }
    // leaning in 12 cm for a moment (under a second) and back: the window never moved
    val near = c.head + Vec3(0f, -0.04f, -0.115f)
    for (i in 0 until 60) assertEquals(p0, r.update(r.target(c.top, c.halfWidth, near), dt))
    for (i in 0 until 216) assertEquals(p0, r.update(r.target(c.top, c.halfWidth, c.head), dt))
    // leaning in and staying: still for a second, then one short glide to the new place (no fade for 12 cm)
    for (i in 0 until 70) assertEquals(p0, r.update(r.target(c.top, c.halfWidth, near), dt))
    var last = p0
    for (i in 0 until 144) {
      val p = r.update(r.target(c.top, c.halfWidth, near), dt)!!
      assertTrue("a glide, not a jump", (p - last).length() < 0.01f)
      assertEquals(1f, r.alpha, 0f)
      last = p
    }
    val leaned = WindowFollow().target(c.top, c.halfWidth, near)
    assertTrue("moved ${(leaned - p0).length()} m", (leaned - p0).length() in 0.05f..0.15f)
    assertTrue((last - leaned).length() < 0.035f)
    assertEquals(0, r.relocations)
    // the wearer stands up and steps round to the side of the work: nothing for a second, then fade out, snap, fade in
    val moved = Vec3(1.0f, 1.6f, -1.2f)
    val there = WindowFollow().target(c.top, c.halfWidth, moved)
    assertTrue((there - last).length() > 0.25f)
    var faded = false
    for (i in 0 until 60) {
      assertEquals("still where it was after ${i * dt} s", last, r.update(r.target(c.top, c.halfWidth, moved), dt))
      assertEquals(1f, r.alpha, 0f)
    }
    for (i in 0 until 144) {
      val at = r.update(r.target(c.top, c.halfWidth, moved), dt)!!
      if (r.alpha < 0.05f) faded = true
      // only ever at the old place or at the new one, never in between (no slide across the work)
      assertTrue((at - last).length() < 1e-4f || (at - there).length() < 1e-3f)
      if ((at - there).length() < 1e-3f) assertTrue("it moved while faded out", faded)
    }
    assertTrue("faded out before moving", faded)
    assertEquals(1, r.relocations)
    assertEquals(WindowRelocator.Phase.FOLLOW, r.phase)
    assertEquals(1f, r.alpha, 0f)
    assertTrue("within 25° of the work again", angle(r.position!! - moved, c.top - moved) <= 25.0)
    // the object is slid 10 cm: the window follows it with the spring (no fade), and settles
    val slid = c.top + Vec3(0.10f, 0f, 0f)
    for (i in 0 until 144) r.update(r.target(slid, c.halfWidth, moved), dt)
    assertEquals(1, r.relocations)
    assertTrue((r.position!! - WindowFollow().target(slid, c.halfWidth, moved)).length() < 0.035f)
  }

  @Test
  fun placementIgnoresSmallHeadMovesAndStaysInTheZoneAfterBigOnes() {
    val c = beside[0]
    val r = WindowRelocator()
    val t0 = r.target(c.top, c.halfWidth, c.head)
    // swaying 6 cm while working: the window is placed, it does not swim with the head
    for (dx in listOf(-0.06f, 0.03f, 0.06f)) assertEquals(t0, r.target(c.top, c.halfWidth, c.head + Vec3(dx, 0.02f, 0f)))
    // the wearer walks half a metre round the table: placed again, inside the zone
    val moved = c.head + Vec3(-0.50f, 0f, -0.20f)
    val t1 = r.target(c.top, c.halfWidth, moved)
    assertTrue((t1 - t0).length() > 0.1f)
    assertTrue(deg(view(t1, moved, c.top).first) <= 30.05)
    r.reset()
    assertEquals(WindowFollow().target(c.top, c.halfWidth, moved), r.target(c.top, c.halfWidth, moved))
  }

  @Test
  fun facingKeepsTheSdkConventionAndLeansBack() {
    val head = Vec3(0.2f, 1.5f, 0.1f)
    for (p in listOf(Vec3(0.4f, 1.3f, -0.69f), Vec3(-0.7f, 1.2f, 0.3f), Vec3(0.2f, 1.1f, 0.9f))) {
      // no tilt: exactly the pose every panel had (guide/SpatialGhostRenderer.facingPose), so no panel shows its back
      val mine = Placement.facing(p, head, tilt = 0f).toSdk()
      val sdk = facingPose(p, head)
      val same = abs(mine.q.w * sdk.q.w + mine.q.x * sdk.q.x + mine.q.y * sdk.q.y + mine.q.z * sdk.q.z)
      assertEquals("same rotation as facingPose at $p", 1f, same, 1e-5f)
      // tilted: the readable side (+Z) still faces the head, looks up by 8°, and the top edge leans away
      val q = Placement.facing(p, head).q
      val normal = q.rotate(Vec3(0f, 0f, 1f))
      val toHead = Vec3(head.x - p.x, 0f, head.z - p.z)
      assertTrue("faces the head", normal.dot(toHead) > 0f)
      assertEquals(8.0, deg(atan2(normal.y, hypot(normal.x, normal.z))), 0.1)
      assertTrue("top leans away", q.rotate(Vec3(0f, 1f, 0f)).dot(toHead) < 0f)
      assertEquals("no roll", 0f, q.rotate(Vec3(1f, 0f, 0f)).y, 1e-6f)
      // UI-FLOOR: facingEyes leans exactly as far as the eyes are above the window: the readable side looks at them
      val e = Placement.facingEyes(p, head)
      assertTrue("looks at the eyes", angle(e.q.rotate(Vec3(0f, 0f, 1f)), head - p) < 0.1)
      assertEquals("no roll", 0f, e.q.rotate(Vec3(1f, 0f, 0f)).y, 1e-6f)
    }
    // near eye level the reading lean stays (8°); right under the head it is still a window (80°), not a floor tile
    assertEquals(8.0, deg(atan2(Placement.facingEyes(Vec3(0f, 1.48f, -0.8f), head).q.rotate(Vec3(0f, 0f, 1f)).y, 1f) * 0f + Placement.WINDOW_TILT), 0.1)
    val level = Placement.facingEyes(Vec3(0.2f, 1.48f, -0.7f), head).q.rotate(Vec3(0f, 0f, 1f))
    assertEquals(8.0, deg(atan2(level.y, hypot(level.x, level.z))), 0.1)
    val under = Placement.facingEyes(Vec3(0.2f, 0.5f, 0.09f), head).q.rotate(Vec3(0f, 0f, 1f))
    assertEquals(80.0, deg(atan2(under.y, hypot(under.x, under.z))), 0.1)
  }

  // ---- labels, pills and the badge (UI-FLOOR) ----

  /** A head at eye height 1.2 m, heading 17° to the left, pitched by [pitchDeg] (negative = down), rolled by [rollDeg]. */
  private fun head(pitchDeg: Double, rollDeg: Double = 0.0) =
      Pose3(
          Vec3(0.1f, 1.2f, 0.2f),
          Quat.yaw(0.3f) * Quat.axisAngle(Vec3(1f, 0f, 0f), Math.toRadians(pitchDeg).toFloat()) *
              Quat.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(rollDeg).toFloat()),
      )

  /** A point seen [rightDeg] to the right and [upDeg] above the centre of the view, [dist] m away. */
  private fun seen(h: Pose3, rightDeg: Double, upDeg: Double, dist: Float = 0.9f): Vec3 {
    val a = Math.toRadians(rightDeg)
    val b = Math.toRadians(upDeg)
    return h.t + h.q.rotate(Vec3((Math.sin(a) * Math.cos(b)).toFloat(), Math.sin(b).toFloat(), (-Math.cos(a) * Math.cos(b)).toFloat())) * dist
  }

  /** How far the text's baseline is turned in the view, degrees: 0 = reads left to right, 90 = reads sideways. */
  private fun textRoll(pose: Pose3, h: Pose3): Double {
    val x = pose.q.rotate(Vec3(1f, 0f, 0f))
    return deg(atan2(x.dot(h.q.rotate(Vec3(0f, 1f, 0f))), x.dot(h.q.rotate(Vec3(1f, 0f, 0f)))))
  }

  private val spots = listOf(-30.0 to 0.0, 30.0 to 0.0, 0.0 to 0.0, -20.0 to 15.0, 25.0 to -15.0, 0.0 to 20.0)

  @Test
  fun labelsFaceTheEyesAndReadUprightInTheViewFromAbove() {
    for (pitch in listOf(-80.0, -45.0, 0.0)) for (roll in listOf(0.0, 12.0)) {
      val h = head(pitch, roll)
      val k = Placement.upBlend(h)
      val rolls = ArrayList<Double>()
      val olds = ArrayList<Double>()
      for ((right, up) in spots) {
        val p = seen(h, right, up)
        val b = Placement.billboard(p, h)
        // a proper rotation whose readable side looks straight at the eyes
        val x = b.q.rotate(Vec3(1f, 0f, 0f))
        val y = b.q.rotate(Vec3(0f, 1f, 0f))
        val z = b.q.rotate(Vec3(0f, 0f, 1f))
        assertTrue("faces the eyes", angle(z, h.t - p) < 0.1)
        assertEquals(0f, x.dot(y), 1e-4f)
        assertTrue("right-handed", x.cross(y).dot(z) > 0.999f)
        rolls += textRoll(b, h)
        olds += textRoll(Placement.facing(p, h.t, 0f), h) // before: turned about the vertical only
      }
      println("LABEL gaze pitch %5.1f° head roll %4.1f°: up blend %.2f, text turned in the view by at most %5.1f° (before: %5.1f°)"
          .format(pitch, roll, k, rolls.maxOf { abs(it) }, olds.maxOf { abs(it) }))
      when (pitch) {
        -80.0 -> {
          // looking down steeply: the head's up, so the text is upright in the view wherever the part lies
          assertEquals(1f, k, 0f)
          assertTrue("upright in the view: ${rolls}", rolls.all { abs(it) <= 1.0 })
          assertTrue("before, a pill left or right of the centre read sideways: $olds", olds.maxOf { abs(it) } > 60.0)
        }
        -45.0 -> {
          // in the blend, nearly the head's up already
          assertTrue("blend $k", k > 0.8f && k < 1f)
          assertTrue("nearly upright in the view: $rolls", rolls.all { abs(it) <= 8.0 })
        }
        else -> {
          // near the horizon: world up, whatever the head's roll: labels stand upright in the room and do not tilt with the head
          assertEquals(0f, k, 0f)
          for ((right, up) in spots) assertEquals("level baseline", 0f, Placement.billboard(seen(h, right, up), h).q.rotate(Vec3(1f, 0f, 0f)).y, 1e-5f)
        }
      }
    }
  }

  @Test
  fun labelsDoNotSpinWhileTheGazeGoesDown() {
    // a pill on a part on the floor, 0.7 m ahead and 0.35 m to the left; the head nods from the horizon to straight down
    val p = Vec3(-0.25f, 0.2f, -0.5f)
    var last: Pose3? = null
    var worst = 0.0
    for (i in 0..900) {
      val b = Placement.billboard(p, head(-i / 10.0))
      last?.let { l ->
        val d = abs(l.q.x * b.q.x + l.q.y * b.q.y + l.q.z * b.q.z + l.q.w * b.q.w).coerceAtMost(1f)
        worst = maxOf(worst, 2 * deg(acos(d)))
      }
      last = b
    }
    println("LABEL turns by at most %.2f° per 0.1° of gaze pitch".format(worst))
    assertTrue("no jump: $worst° per 0.1° of pitch", worst < 0.6)
    // right under the head, looking straight down (where "turn about the vertical" has no answer): still defined
    val down = head(-90.0)
    val under = Placement.billboard(Vec3(down.t.x, 0.2f, down.t.z), down)
    assertTrue(angle(under.q.rotate(Vec3(0f, 0f, 1f)), Vec3(0f, 1f, 0f)) < 0.1)
    assertTrue(abs(textRoll(under, down)) < 0.1)
  }

  @Test
  fun coachCardComesDownWithTheGaze() {
    // looking ahead: 0.8 m ahead, 15° below eye level, as before
    val level = head(0.0)
    assertEquals(Placement.ahead(level, 0.8f, 0.21f), Placement.coachCard(level))
    assertEquals(Placement.ahead(head(-20.0), 0.8f, 0.21f), Placement.coachCard(head(-20.0)))
    // looking down at the floor: 12° above the gaze, 0.8 m from the eyes, in view
    val down = head(-55.0)
    val p = Placement.coachCard(down)
    assertEquals(0.8f, (p - down.t).length(), 1e-3f)
    assertEquals(12.0, angle(p - down.t, down.q.rotate(Vec3(0f, 0f, -1f))), 0.6)
    assertEquals(-43.0, deg(atan2(p.y - down.t.y, hypot(p.x - down.t.x, p.z - down.t.z))), 0.6)
    // never lower than 45°, so it stays a window to look at, not something under the feet
    val straightDown = Placement.coachCard(head(-85.0))
    assertEquals(-45.0, deg(atan2(straightDown.y - 1.2f, hypot(straightDown.x - 0.1f, straightDown.z - 0.2f))), 0.6)
  }

  // ---- what the window says (UI-FLOOR) ----

  @Test
  fun textSizesAreReadableAcrossTheWindowZone() {
    fun degAt(sp: Float, m: Float) = deg(2 * atan(sp / 1000f / 2 / m))
    // 1 dp = 1 mm ([DP_PER_M]); the smallest text anywhere is 0.85° also at the far end of the window zone (0.9 m)
    assertEquals(1000f, DP_PER_M, 0f)
    assertTrue(degAt(TextSize.caption.value, 0.9f) >= 0.85)
    assertTrue(degAt(TextSize.body.value, WindowFollow.FLOOR_DIST) >= 1.25)
    // the instruction is the hero: at least 1.5 × the title it replaces, two lines of it fit the window
    assertTrue(TextSize.hero.value >= 1.5f * TextSize.title.value)
    assertTrue(degAt(TextSize.hero.value, WindowFollow.FLOOR_DIST) >= 3.0)
    assertTrue(2 * TextSize.heroLine.value + TextSize.bodyLine.value + 36f + 22f + 14f + 12f + 8f + 2f <= STEP_PANEL_H * DP_PER_M)
  }

  @Test
  fun greekCapitalsLoseTheTonosKeepTheDialytikaAndTheFinalSigma() {
    assertEquals("ΑΡΙΣΤΕΡΟ ΠΛΑΪΝΟ", Strings.upper("el", "Αριστερό πλαϊνό"))
    assertEquals("ΕΛΕΓΧΟΣ", Strings.upper("el", Strings.t("el", "check")))
    assertEquals("ΤΕΛΟΣ", Strings.upper("el", "τέλος")) // final sigma ς → Σ
    assertEquals("ΜΑΪΟΥ", Strings.upper("el", "Μαΐου")) // dialytika + tonos → dialytika
    assertEquals("ΒΗΜΑ 2 ΑΠΟ 7", Strings.upper("el", Strings.t("el", "step", "n" to 2, "total" to 7)))
    for (key in Strings.keys("el")) {
      val up = Strings.upper("el", Strings.t("el", key))
      assertTrue("$key: no tonos and no small letter left in «$up»", up.none { it in "ΆΈΉΊΌΎΏάέήίόύώΐΰς" || (it.isLetter() && it.isLowerCase()) })
    }
  }
}
