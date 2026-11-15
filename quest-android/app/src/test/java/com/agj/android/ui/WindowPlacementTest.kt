package com.agj.android.ui

import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.facingPose
import com.agj.android.guide.toSdk
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the step window lands, seen from the head (judge round 2, A1): at most 30° from the object's direction, its
 * inner edge at least 10° from it, 0.75–0.95 m away, and never over the object's box in view.
 */
class WindowPlacementTest {
  private class Case(val name: String, val head: Vec3, val top: Vec3, val halfWidth: Float = 0.22f)

  private val cases =
      listOf(
          Case("straight ahead on a table, standing", Vec3(0f, 1.60f, 0f), Vec3(0f, 0.90f, -0.50f)),
          Case("straight ahead on a table, seated", Vec3(0f, 1.20f, 0f), Vec3(0f, 0.86f, -0.55f)),
          Case("low on the floor", Vec3(0f, 1.60f, 0f), Vec3(0f, 0.25f, -0.90f)),
          Case("to the left", Vec3(0.30f, 1.25f, 0.20f), Vec3(-0.35f, 0.86f, -0.25f)),
          Case("far and small", Vec3(0f, 1.60f, 0f), Vec3(0.4f, 0.80f, -2.2f), 0.10f),
      )

  private fun deg(rad: Float) = Math.toDegrees(rad.toDouble())

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

  /** The window panel's outline (0.54 × 0.42 m: pill row, window, toolbar) in WORLD, as MainActivity poses it. */
  private fun windowOutline(pos: Vec3, head: Vec3): List<Vec3> {
    val pose = Placement.facing(pos, head)
    val out = ArrayList<Vec3>()
    for (i in 0..8) {
      val t = -1f + i * 0.25f
      out += pose.transform(Vec3(t * STEP_PANEL_W / 2, -STEP_PANEL_H / 2, 0f))
      out += pose.transform(Vec3(t * STEP_PANEL_W / 2, STEP_PANEL_H / 2, 0f))
      out += pose.transform(Vec3(-STEP_PANEL_W / 2, t * STEP_PANEL_H / 2, 0f))
      out += pose.transform(Vec3(STEP_PANEL_W / 2, t * STEP_PANEL_H / 2, 0f))
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

  /** The old rule (before the fix): beside the object, 0.10 m beyond its half-width. */
  private fun oldBearing(c: Case): Float {
    val d = hypot(c.top.x - c.head.x, c.top.z - c.head.z)
    return atan2(c.halfWidth + 0.10f + 0.26f, d)
  }

  @Test
  fun windowStaysInItsZone() {
    for (c in cases) {
      val p = WindowFollow().target(c.top, c.halfWidth, c.head)
      val (az, el) = view(p, c.head, c.top)
      val flat = hypot(p.x - c.head.x, p.z - c.head.z)
      val dist = (p - c.head).length()
      val outline = windowOutline(p, c.head).map { view(it, c.head, c.top) }
      val inner = outline.minOf { it.first }
      val outer = outline.maxOf { it.first }
      // the side column hangs on the window's outer edge, in the window's plane (MainActivity.placeSide)
      val sideAz = az + atan2(STEP_PANEL_W / 2 + 0.02f + SIDE_PANEL_W / 2, flat)
      println(
          "WINDOW %-38s before %5.1f°  after %5.1f°  inner edge %5.1f°  outer edge %5.1f°  side column %5.1f°  elevation %6.1f°  dist %.2f m"
              .format(c.name, deg(oldBearing(c)), deg(az), deg(inner), deg(outer), deg(sideAz), deg(el), dist)
      )
      assertTrue("${c.name}: on the right", az > 0f)
      assertTrue("${c.name}: bearing ${deg(az)}° must be at most 30°", deg(az) <= 30.05)
      assertTrue("${c.name}: inner edge ${deg(inner)}° must keep 10° clear", deg(inner) >= 10.0)
      assertEquals("${c.name}: 0.8 m from the head", 0.80f, flat, 1e-3f)
      assertTrue("${c.name}: distance $dist", dist in 0.75f..0.95f)
      assertTrue("${c.name}: elevation ${deg(el)}° below eye level, not under 20°", deg(el) in -20.5..-1.5)
    }
  }

  @Test
  fun windowNeverCoversTheObjectInView() {
    for (c in cases) for (yaw in listOf(0f, 0.39f, 0.785f)) {
      // the box turned about the vertical, in the frame of the viewing direction
      val look = atan2(c.top.x - c.head.x, c.top.z - c.head.z)
      val p = WindowFollow().target(c.top, c.halfWidth, c.head)
      val w = windowOutline(p, c.head).map { view(it, c.head, c.top) }
      val (a0, a1) = w.minOf { it.first } to w.maxOf { it.first }
      val (e0, e1) = w.minOf { it.second } to w.maxOf { it.second }
      val box = boxOutline(c.top, c.halfWidth, look + yaw).map { view(it, c.head, c.top) }
      // angular distance from every point of the object's outline to the window's rectangle in view
      val gap =
          box.minOf { (a, e) ->
            val da = maxOf(a0 - a, a - a1, 0f)
            val de = maxOf(e0 - e, e - e1, 0f)
            hypot(da, de)
          }
      assertTrue("${c.name} yaw $yaw: object ${deg(gap)}° from the window, must be over 2.5°", deg(gap) >= 2.5)
      // and the window is not in the middle of the object either: above it, or beside it
      val (ca, ce) = view(p, c.head, c.top)
      assertTrue("${c.name}: window centre above or beside the object", ce > box.maxOf { it.second } || ca > box.maxOf { it.first })
    }
  }

  @Test
  fun placementIgnoresSmallHeadMovesAndStaysInTheZoneAfterBigOnes() {
    val c = cases[1]
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
    }
  }
}
