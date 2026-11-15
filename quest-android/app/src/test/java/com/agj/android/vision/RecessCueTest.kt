package com.agj.android.vision

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.guide.Glb
import com.agj.android.guide.Guide
import com.agj.android.guide.Manifest
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecessCue]: the recess map of a part's mesh, both orientations scored on a rectified lightness image, and the
 * tracker's three-in-a-row rule ([RecessTurn], [PanelPoseTracker]). Pure JVM, no OpenCV.
 */
class RecessCueTest {
  private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

  private val manifest by lazy { Manifest.parse(res("malm-drawer/manifest.json").readText()) }
  private val guide by lazy { Guide.build(manifest, Glb.read(res("malm-drawer/walkthrough.glb"))) }

  // ---- a board like the real drawer front: 400 × 200 × 16 mm, a 4.5 mm groove 34 mm from the +Y long edge ----

  private val L = 0.400
  private val W = 0.200
  private val T = 0.016
  private val GROOVE_Y = 0.066
  private val GROOVE_HALF = 0.00225

  /** Triangles of the board's top side in FOOTPRINT coordinates (identity ASSEMBLY ← FOOTPRINT); [groove] false = plain. */
  private fun board(groove: Boolean): Pair<FloatArray, IntArray> {
    val pos = ArrayList<Float>()
    val idx = ArrayList<Int>()
    fun quad(y0: Double, y1: Double, z: Double) {
      val b = pos.size / 3
      for ((x, y) in listOf(-L / 2 to y0, L / 2 to y0, L / 2 to y1, -L / 2 to y1)) {
        pos += x.toFloat()
        pos += y.toFloat()
        pos += z.toFloat()
      }
      idx += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
    }
    quad(-W / 2, W / 2, 0.0) // the underside
    if (groove) {
      quad(-W / 2, GROOVE_Y - GROOVE_HALF, T)
      quad(GROOVE_Y - GROOVE_HALF, GROOVE_Y + GROOVE_HALF, T - 0.008)
      quad(GROOVE_Y + GROOVE_HALF, W / 2, T)
    } else quad(-W / 2, W / 2, T)
    return pos.toFloatArray() to idx.toIntArray()
  }

  private fun boardMap(): RecessMap = board(true).let { (p, i) -> RecessCue.map(p, i, Pose3.IDENTITY, L, W, T)!! }

  @Test
  fun aBoardWithAGrooveGivesAMapWithTheGrooveAndABandOnBothSides() {
    val m = boardMap()
    assertEquals(160, m.w)
    assertEquals(80, m.h)
    assertTrue("$m", m.isRecess(0.0, GROOVE_Y))
    assertTrue("$m", m.isRecess(-0.15, GROOVE_Y))
    assertFalse("the mirrored place is plain", m.isRecess(0.0, -GROOVE_Y))
    assertFalse(m.isRecess(0.0, 0.0))
    // every recess pixel within one pixel of the groove, the whole length but the edge margin
    for (j in 0 until m.h) for (i in 0 until m.w) {
      if (!m.recess[j * m.w + i]) continue
      val y = (j + 0.5) * 0.0025 - W / 2
      assertTrue("recess at row $j", abs(y - GROOVE_Y) <= GROOVE_HALF + 0.0025)
    }
    assertEquals(m.count, m.narrowCount)
    assertEquals(m.narrowCount, m.asymmetric)
    assertTrue("$m", m.narrowCount >= 160 - 2 * RecessCue.EDGE_PX)
    // the band lies on both sides of the groove and never on it
    val rows = (0 until m.h).filter { j -> m.band[j * m.w + 80] }.map { (it + 0.5) * 0.0025 - W / 2 }
    assertTrue(rows.any { it < GROOVE_Y - GROOVE_HALF } && rows.any { it > GROOVE_Y + GROOVE_HALF })
    assertTrue(rows.none { abs(it - GROOVE_Y) < GROOVE_HALF + 0.0025 })
  }

  @Test
  fun aPlainBoardAndASymmetricOneGiveNoCue() {
    val (p, i) = board(false)
    assertNull(RecessCue.map(p, i, Pose3.IDENTITY, L, W, T))
    // a groove along the middle looks the same turned by 180°
    val mid = BooleanArray(160 * 80)
    for (x in 2 until 158) for (j in 39..40) mid[j * 160 + x] = true
    assertNull(RecessCue.of(160, 80, 2.5, mid))
    // a wide lowered area alone is not a cue (lit like the face), a groove next to it still is
    val wide = BooleanArray(160 * 80)
    for (x in 2 until 158) for (j in 2..19) wide[j * 160 + x] = true
    assertNull(RecessCue.of(160, 80, 2.5, wide))
    for (x in 2 until 158) for (j in 66..67) wide[j * 160 + x] = true
    val m = RecessCue.of(160, 80, 2.5, wide)!!
    assertEquals(2 * 156, m.narrowCount)
    assertTrue(m.count > m.narrowCount)
  }

  /** The test job's front panel: the map has a recess exactly where the mesh's surface is below the top face. */
  @Test
  fun theTestJobsFrontHasItsGrooveWhereTheMeshHasIt() {
    val t = StepFootprints.target(guide, manifest, 1)!!
    val m = t.recess
    assertNotNull("the front's mesh has a groove", m)
    m!!
    assertEquals(Math.round(t.lengthM / 0.0025).toInt(), m.w)
    assertEquals(Math.round(t.widthM / 0.0025).toInt(), m.h)
    // the mesh itself: vertices of the slab clearly below its top face and above its underside = the recess floors
    val part = guide.parts[t.slab!!]!!
    val fa = t.assemblyFromFootprint.inverse()
    var ya = Double.MAX_VALUE
    var yb = -Double.MAX_VALUE
    for (v in 0 until part.vertexCount) {
      val f = fa.transform(com.agj.android.core.Vec3(part.positions[3 * v], part.positions[3 * v + 1], part.positions[3 * v + 2]))
      if (f.z < t.topM - RecessCue.MIN_DEPTH_M && f.z > 0.001 && abs(f.x) < t.lengthM / 2 - 0.01) {
        ya = minOf(ya, f.y.toDouble())
        yb = maxOf(yb, f.y.toDouble())
      }
    }
    assertTrue("the mesh has vertices below the top face ($ya … $yb)", ya <= yb)
    val rows = (0 until m.h).filter { m.recess[it * m.w + m.w / 2] }
    assertTrue("the map has the mesh's groove ($ya … $yb): rows $rows of $m", rows.isNotEmpty())
    // nothing recessed farther than a pixel from the mesh's own recess floor, along the middle of the board
    for (j in 0 until m.h) {
      val y = (j + 0.5) * 0.0025 - t.widthM / 2
      if (m.recess[j * m.w + m.w / 2]) assertTrue("row $j (y = $y) is not in $ya … $yb", y > ya - 0.0026 && y < yb + 0.0026)
    }
    // steps 1–3 look for the same part: the same map
    assertEquals(m, StepFootprints.target(guide, manifest, 2)!!.recess)
  }

  // ---- the cue on a synthetic rectified image ----

  private val n = 560
  private val s = 0.0025
  private val x0 = -0.7
  private val y0 = -0.7

  private class Image(val light: ByteArray, val blob: ByteArray, val valid: BooleanArray)

  /**
   * The board at ([x], [y], [yaw]) on a darker table; [groove]: a dark line where the groove is for that yaw.
   * [hand]: the blob loses everything with footprint x > [hand] (a hand over that end).
   */
  private fun image(x: Double, y: Double, yaw: Double, groove: Boolean, hand: Double = 1.0): Image {
    val light = ByteArray(n * n)
    val blob = ByteArray(n * n)
    val ca = cos(yaw)
    val sa = sin(yaw)
    for (j in 0 until n) for (i in 0 until n) {
      val dx = x0 + i * s - x
      val dy = y0 + j * s - y
      val fx = ca * dx + sa * dy
      val fy = -sa * dx + ca * dy
      val inside = abs(fx) <= L / 2 && abs(fy) <= W / 2
      var v = if (inside) 200 + (fy * 100).toInt() else 90 // a lighting gradient across the board: 20 levels
      if (inside && groove && abs(fy - GROOVE_Y) <= GROOVE_HALF) v -= 45
      light[j * n + i] = v.toByte()
      blob[j * n + i] = if (inside && fx <= hand) 1 else 0
    }
    return Image(light, blob, BooleanArray(n * n) { true })
  }

  private fun score(im: Image, x: Double, y: Double, yaw: Double) = RecessCue.score(boardMap(), im.light, 1, im.valid, im.blob, n, x0, y0, s, x, y, yaw)

  @Test
  fun theOrientationWithTheDarkLineAtTheGrooveWins() {
    val yaw = Math.toRadians(25.0)
    val asIs = score(image(0.05, -0.10, yaw, true), 0.05, -0.10, yaw)
    assertTrue("as is ${asIs.asIs} turned ${asIs.turned}", asIs.decided)
    assertFalse(asIs.saysTurned)
    assertTrue(asIs.asIs!! > 20 && abs(asIs.turned!!) < 3)
    // the same picture, the detector's yaw the other way round
    val turned = score(image(0.05, -0.10, yaw + Math.PI, true), 0.05, -0.10, yaw)
    assertTrue("as is ${turned.asIs} turned ${turned.turned}", turned.decided)
    assertTrue(turned.saysTurned)
    assertTrue(turned.turned!! > 20 && abs(turned.asIs!!) < 3)
    assertEquals(turned.asIs!! - turned.turned!!, turned.margin!!, 1e-9)
  }

  @Test
  fun aPoseAFewMillimetresOffStillDecides() {
    val yaw = Math.toRadians(-40.0)
    val c = score(image(0.05, -0.10, yaw + Math.PI, true), 0.054, -0.105, yaw + Math.toRadians(0.5))
    assertTrue("as is ${c.asIs} turned ${c.turned}", c.decided && c.saysTurned)
  }

  @Test
  fun noDarkLineOrAHandOverItDecidesNothing() {
    val yaw = Math.toRadians(25.0)
    val plain = score(image(0.05, -0.10, yaw, false), 0.05, -0.10, yaw)
    assertFalse("as is ${plain.asIs} turned ${plain.turned}", plain.decided)
    assertFalse(plain.saysTurned)
    // half of the board is not in the blob: too little of the groove is seen
    val hand = score(image(0.05, -0.10, yaw, true, hand = 0.0), 0.05, -0.10, yaw)
    assertFalse(hand.decided)
    assertTrue(hand.seenAsIs < RecessCueParams().minSeen)
  }

  // ---- three in a row ----

  private fun cue(saysTurned: Boolean, decided: Boolean = true) =
      if (saysTurned) RecessCueResult(1.0, 12.0, decided, decided, 1.0, 1.0, 0.7) else RecessCueResult(12.0, 1.0, decided, false, 1.0, 1.0, 0.7)

  @Test
  fun onlyThreeConfidentResultsInARowTurnThePose() {
    val t = RecessTurn()
    assertFalse(t.add(true, 0.3, cue(true)))
    assertFalse(t.add(true, 0.3, cue(true)))
    assertEquals(2, t.streak)
    assertFalse("an undecided result breaks the row", t.add(true, 0.3, cue(true, decided = false)))
    assertEquals(0, t.streak)
    assertFalse(t.add(true, 0.3, cue(true)))
    assertFalse(t.add(true, 0.3, cue(true)))
    assertFalse("an untrusted result breaks the row", t.add(false, null, null))
    assertFalse(t.add(true, 0.3, cue(true)))
    assertFalse("a result that agrees breaks the row", t.add(true, 0.3, cue(false)))
    assertFalse("no cue (a part with no recess) breaks the row", t.add(true, 0.3, null))
    assertNull(t.yaw)
    assertEquals(0, t.flips)
    assertFalse(t.add(true, 0.3, cue(true)))
    assertFalse(t.add(true, 0.3, cue(true)))
    assertTrue(t.add(true, 0.3, cue(true)))
    assertEquals(1, t.flips)
    assertEquals(0.3 + Math.PI, t.yaw!!, 1e-12)
    // from then on the detector is given the kept yaw, its results agree with the cue and the yaw follows the part
    assertFalse(t.add(true, 0.32 + Math.PI, cue(false)))
    assertEquals(0.32 + Math.PI, t.yaw!!, 1e-12)
    assertFalse(t.add(false, null, null))
    assertEquals(0.32 + Math.PI, t.yaw!!, 1e-12)
    assertEquals(1, t.flips)
    t.reset()
    assertNull(t.yaw)
    assertEquals(0, t.flips)
  }

  private class Got(val pose: Pose3)

  private fun result(yaw: Double, cue: RecessCueResult?) =
      PanelPoseResult(true, "ok", 0.100, 0.400, yaw, DoubleArray(8), 21.0, doubleArrayOf(2.0, 1.0), 0.95, 60.0, 0.4, null, cue)

  @Test
  fun theTrackerTurnsTheReportedPoseOnceAndLogsIt() {
    val got = ArrayList<Got>()
    val lines = ArrayList<String>()
    var now = 0L
    val tr =
        PanelPoseTracker(
            tableHeight = { 0.74f },
            onObjectPose = { p, _, _ -> got += Got(p) },
            log = AgqLog({ now }, { lines += it }),
            loadNative = { throw AssertionError("the detector must not run in this test") },
            clockMs = { now },
        )
    val target = PanelTarget(L, W, T, false, Pose3.IDENTITY, "part_front", boardMap())
    tr.setJob("drawer")
    tr.setStep(1, target)
    assertTrue(lines.last(), lines.last().contains("\"recess_px\":[${target.recess!!.narrowCount},${target.recess!!.asymmetric}]"))
    val table = PanelFrames.tableAtHeight(0.74f)
    val yaw = Math.toRadians(20.0)
    fun reportedYaw() = PanelFrames.panelYawInTable(table, got.last().pose, target.assemblyFromFootprint)
    fun diff(a: Double, b: Double) = abs((a - b + Math.PI).mod(2 * Math.PI) - Math.PI)
    // two confident results and one without a cue: reported as the detector chose
    tr.onResult(result(yaw, cue(true)), table, now, target)
    now += 300
    tr.onResult(result(yaw, cue(true)), table, now, target)
    now += 300
    tr.onResult(result(yaw, null), table, now, target)
    assertEquals(1, got.size)
    assertTrue(diff(reportedYaw(), yaw) < 1e-3)
    assertFalse(lines.any { it.contains("\"flipped\":true") })
    // three in a row: turned, reported at once and logged although the last log line is not a second old
    repeat(3) {
      now += 300
      tr.onResult(result(yaw, cue(true)), table, now, target)
    }
    assertEquals(2, got.size)
    assertTrue("turned by 180°", diff(reportedYaw(), yaw + Math.PI) < 1e-3)
    val line = lines.last { it.contains("\"flipped\":true") }
    assertTrue(line, line.contains("\"cue\":{") && line.contains("\"score\":[1.0,12.0]") && line.contains("\"margin\":-11.0") && line.contains("\"decided\":true") && line.contains("\"flips\":1"))
    // the detector now continues from the kept yaw: the same way round, no second turn
    repeat(4) {
      now += 300
      tr.onResult(result(yaw + Math.PI + 0.001, cue(false)), table, now, target)
    }
    assertTrue(diff(reportedYaw(), yaw + Math.PI) < 0.01)
    assertEquals(1, lines.count { it.contains("\"flipped\":true") })
    // another rectangle starts again from the ghost's yaw
    tr.setStep(2, PanelTarget(0.3, 0.2, T, false, Pose3.IDENTITY, "part_other"))
    tr.setStep(1, target)
    repeat(3) {
      now += 300
      tr.onResult(result(yaw, cue(false)), table, now, target)
    }
    assertTrue(diff(reportedYaw(), yaw) < 1e-3)
  }
}
