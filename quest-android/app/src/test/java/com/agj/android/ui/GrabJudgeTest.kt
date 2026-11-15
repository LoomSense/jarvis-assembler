package com.agj.android.ui

import com.agj.android.core.Vec3
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE-COACH: which board the hand lifted, from the hand and the labelled rectangles on the floor: the right board,
 * a wrong one, a touch, a hand high above, two boards close together, a placed board, identical boards as one kind.
 */
class GrabJudgeTest {
  private val judge = GrabJudge()
  private var now = 50_000L

  /** The time stamp of the grip [grabAt] made last (`HeldState.sinceMs`). */
  private var since = 0L

  private val floorY = 0.02f

  /** A [l] x [w] m rectangle centred at ([cx], [cz]), its long side turned [yawDeg] from +X. */
  private fun rect(key: Int, ids: List<String>, cx: Float, cz: Float, l: Float, w: Float, yawDeg: Double = 0.0): GrabRect {
    val a = Math.toRadians(yawDeg)
    val lx = Vec3(cos(a).toFloat(), 0f, sin(a).toFloat()) * (l / 2)
    val sx = Vec3(-sin(a).toFloat(), 0f, cos(a).toFloat()) * (w / 2)
    val c = Vec3(cx, floorY, cz)
    return GrabRect(key, ids, listOf(c - lx - sx, c + lx - sx, c + lx + sx, c - lx + sx))
  }

  // the drawer's boards, as the headset measured them
  private val front = rect(1, listOf("front"), 0f, 0f, 0.404f, 0.200f)
  private val side = rect(2, listOf("side_left", "side_right"), 0f, 0.40f, 0.433f, 0.109f)
  private val back = rect(3, listOf("back"), 0.60f, 0.40f, 0.335f, 0.094f)
  private val bottom = rect(4, listOf("bottom"), 0.70f, -0.20f, 0.436f, 0.340f)
  private val all = listOf(front, side, back, bottom)

  private fun hand(at: Vec3, closed: Boolean, since: Long = 0L, id: Int = 1, points: List<Vec3> = emptyList()) =
      GrabHand(id, at, points, closed, since)

  private fun tick(h: GrabHand?, needed: String?, placed: Set<String> = emptySet(), rects: List<GrabRect> = all, dtMs: Long = 14): Grab? {
    now += dtMs
    return judge.tick(now, listOfNotNull(h), rects, needed, placed)
  }

  /** The hand comes down open at [at], closes, and lifts [liftM] straight up in 2 cm steps. Returns every pick decided. */
  private fun grabAt(at: Vec3, needed: String?, placed: Set<String> = emptySet(), liftM: Float = 0.14f, rects: List<GrabRect> = all): List<Grab> {
    val out = ArrayList<Grab>()
    repeat(5) { tick(hand(at, false), needed, placed, rects)?.let { out += it } }
    since = now
    var up = 0f
    while (up <= liftM + 1e-4f) {
      tick(hand(at + Vec3(0f, up, 0f), true, since), needed, placed, rects)?.let { out += it }
      up += 0.02f
    }
    return out
  }

  private fun over(r: GrabRect, dx: Float = 0f, dz: Float = 0f, up: Float = 0.03f): Vec3 {
    val c = r.corners.reduce { a, b -> a + b } * 0.25f
    return Vec3(c.x + dx, floorY + up, c.z + dz)
  }

  // ---- the two verdicts ----

  @Test
  fun `the needed board lifted is a right pick`() {
    val g = grabAt(over(side), "side_left").single()
    assertTrue(g.right)
    assertEquals("right", g.verdict)
    assertEquals("side_left", g.part)
    assertEquals("side_left", g.needed)
    assertEquals(2, g.key)
    assertEquals(0f, g.distM, 1e-4f)
    assertTrue("decided at the lift distance, not later: ${g.movedM}", g.movedM >= 0.10f && g.movedM < 0.125f)
  }

  @Test
  fun `another board lifted is a wrong pick that names it`() {
    val g = grabAt(over(back), "side_left").single()
    assertFalse(g.right)
    assertEquals("wrong", g.verdict)
    assertEquals("back", g.part)
    assertEquals("side_left", g.needed)
  }

  @Test
  fun `a board taken at its edge counts by the fingertips, not the wrist`() {
    // wrist 16 cm outside the long edge and 9 cm up, fingertips on the edge
    val wrist = over(side, dz = 0.109f / 2 + 0.16f, up = 0.09f)
    val tips = listOf(wrist, over(side, dz = 0.109f / 2 + 0.01f, up = 0.01f))
    repeat(3) { tick(hand(wrist, false, points = tips), "side_left") }
    since = now
    var got: Grab? = null
    for (i in 0..7) {
      val lift = Vec3(0f, 0.02f * i, 0f)
      got = tick(hand(wrist + lift, true, since, points = tips.map { it + lift }), "side_left") ?: got
    }
    assertEquals("side_left", got?.part)
    assertEquals(0.01f, got!!.distM, 2e-3f)
  }

  // ---- what is not a pick ----

  @Test
  fun `a touch without lifting is not a pick, and opening the hand says nothing`() {
    assertTrue(grabAt(over(back), "side_left", liftM = 0.06f).isEmpty())
    assertNull(tick(hand(over(back), false), "side_left"))
    assertNull(tick(hand(over(back, up = 0.30f), false), "side_left"))
  }

  @Test
  fun `a hand closing high above a board is not at it`() {
    assertTrue(grabAt(over(back, up = 0.30f), "side_left").isEmpty())
    // 14 cm up still is
    judge.reset()
    assertEquals("back", grabAt(over(back, up = 0.14f), "side_left").single().part)
  }

  @Test
  fun `a hand closing beside a board, out of reach, is not at it`() {
    assertTrue(grabAt(over(back, dz = 0.094f / 2 + 0.16f), "side_left").isEmpty())
    judge.reset()
    assertEquals("back", grabAt(over(back, dz = 0.094f / 2 + 0.10f), "side_left").single().part)
  }

  @Test
  fun `between two different boards lying close together nothing is guessed`() {
    // the back 5 cm beside the side: the hand in the gap, 2 cm from one and 3 cm from the other
    val nearBack = rect(3, listOf("back"), 0f, 0.40f + 0.109f / 2 + 0.05f + 0.094f / 2, 0.335f, 0.094f)
    val rects = listOf(front, side, nearBack, bottom)
    assertTrue(grabAt(over(side, dz = 0.109f / 2 + 0.02f), "side_left", rects = rects).isEmpty())
    // on the side itself (the back is 5 cm away): the side, clearly
    judge.reset()
    assertTrue(grabAt(over(side), "side_left", rects = rects).single().right)
    // on the back itself: the back, a wrong pick
    judge.reset()
    assertEquals("back", grabAt(over(nearBack), "side_left", rects = rects).single().part)
  }

  @Test
  fun `a board placed by an earlier action is not a candidate`() {
    // the front was placed in step 1; a rectangle still labelled "front" is not a loose board
    assertTrue(grabAt(over(front), "side_left", placed = setOf("front")).isEmpty())
  }

  // ---- identical boards ----

  @Test
  fun `the two sides are one kind - either is right for either side`() {
    assertTrue(grabAt(over(side), "side_left", placed = setOf("front")).single().right)
    judge.reset()
    val g = grabAt(over(side), "side_right", placed = setOf("front", "side_left")).single()
    assertTrue(g.right)
    assertEquals("side_right", g.part)
  }

  @Test
  fun `a side taken for another board is wrong and named by the side still loose`() {
    assertEquals("side_left", grabAt(over(side), "front").single().part)
    judge.reset()
    assertEquals("side_right", grabAt(over(side), "bottom", placed = setOf("front", "side_left")).single().part)
    // both sides placed: a rectangle of that size is not a loose board any more
    judge.reset()
    assertTrue(grabAt(over(side), "bottom", placed = setOf("front", "side_left", "side_right")).isEmpty())
  }

  // ---- once per grip, and timing ----

  @Test
  fun `one verdict per grip, and a new grip is judged again`() {
    assertEquals(1, grabAt(over(back), "side_left", liftM = 0.40f).size)
    // carried on, still closed: nothing more
    assertNull(tick(hand(over(back, up = 0.5f), true, since), "side_left"))
    // put down (the hand opens) and taken again
    assertNull(tick(hand(over(back), false), "side_left"))
    assertEquals("back", grabAt(over(back), "side_left").single().part)
  }

  @Test
  fun `a board already in the hand when take begins is judged at once`() {
    // lifted while a bolt was being fastened (nothing needed): followed, not judged
    assertTrue(grabAt(over(side), null).isEmpty())
    assertNull(tick(hand(over(side, up = 0.3f), true, since), null))
    // the next action needs the side: it is in the hand
    val g = tick(hand(over(side, up = 0.3f), true, since), "side_left")
    assertNotNull(g)
    assertTrue(g!!.right)
  }

  @Test
  fun `a rectangle the hand covers is remembered for a few seconds`() {
    repeat(3) { tick(hand(over(back, up = 0.4f), false), "side_left") }
    // the camera stops reporting the back (the arm is over it) 2.5 s before the hand closes on it
    val rects = listOf(front, side, bottom)
    tick(hand(over(back), false), "side_left", rects = rects, dtMs = 2500)
    assertEquals("back", grabAt(over(back), "side_left", rects = rects).single().part)
    // long gone: forgotten
    judge.reset()
    tick(null, "side_left")
    tick(hand(over(back), false), "side_left", rects = rects, dtMs = 4000)
    assertTrue(grabAt(over(back), "side_left", rects = rects).isEmpty())
  }

  @Test
  fun `a grip confirmed after the lift began still counts for the board the hand was at`() {
    // open at the board, then the closed reading arrives 300 ms later with the wrist already 18 cm up
    repeat(3) { tick(hand(over(back), false), "side_left") }
    val g = tick(hand(over(back, up = 0.21f), true, now + 300), "side_left", dtMs = 300)
    assertEquals("back", g?.part)
    // a second later the hand is nowhere near: a grip then is not a board
    judge.reset()
    repeat(3) { tick(hand(over(back), false), "side_left") }
    assertNull(tick(hand(over(back, up = 0.5f), true, now + 1000), "side_left", dtMs = 1000))
    assertNull(tick(hand(over(back, up = 0.8f), true, now), "side_left"))
  }

  @Test
  fun `a closed hand that drops out of tracking for a moment keeps its grip`() {
    repeat(3) { tick(hand(over(back), false), "side_left") }
    since = now
    assertNull(tick(hand(over(back, up = 0.05f), true, since), "side_left"))
    assertNull(tick(null, "side_left", dtMs = 300))
    assertEquals("back", tick(hand(over(back, up = 0.17f), true, since), "side_left")?.part)
  }

  @Test
  fun `the other hand closing does not take over a board it was not at`() {
    // left hand (0) rests open on the back; the right hand (1) closes on a screwdriver far away and moves
    repeat(3) {
      now += 14
      judge.tick(now, listOf(hand(over(back), false, id = 0), hand(Vec3(-0.8f, 0.4f, 0.9f), false, id = 1)), all, "side_left")
    }
    since = now
    var got: Grab? = null
    for (i in 1..10) {
      now += 14
      got = judge.tick(now, listOf(hand(over(back), false, id = 0), hand(Vec3(-0.8f, 0.4f + 0.03f * i, 0.9f), true, since, id = 1)), all, "side_left") ?: got
    }
    assertNull(got)
  }

  // ---- geometry ----

  @Test
  fun `floor distance to a turned rectangle`() {
    val r = rect(9, listOf("x"), 1f, 1f, 0.40f, 0.10f, yawDeg = 90.0) // long side along Z
    assertEquals(0f, r.floorDistance(Vec3(1f, 0.5f, 1.15f)), 1e-5f) // inside, whatever the height
    assertEquals(0.05f, r.floorDistance(Vec3(1.10f, 0f, 1f)), 1e-4f) // beside the long edge
    assertEquals(0.10f, r.floorDistance(Vec3(1f, 0f, 1.30f)), 1e-4f) // beyond the short edge
    assertEquals(0.05f, r.floorDistance(Vec3(1.08f, 0f, 1.24f)), 1e-4f) // off a corner (3-4-5)
    assertEquals(floorY, r.y, 1e-6f)
  }
}
