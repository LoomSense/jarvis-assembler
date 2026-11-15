package com.agj.android.vision

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * [PartFlowTracker] with the desktop OpenCV bindings (as [MarkerReplayAccuracyTest]): Lucas–Kanade on synthetic
 * 640 × 480 pictures of a pale desk with white parts that slide. Skipped when the native library does not load.
 */
class PartFlowTrackerTest {
  private fun loadOpenCv(): Boolean =
      try {
        nu.pattern.OpenCV.loadLocally()
        true
      } catch (t: Throwable) {
        System.err.println("desktop OpenCV did not load: $t")
        false
      }

  private val w = 640
  private val h = 480

  /** A part: a rectangle of brightness [value] with two darker holes, top-left corner at ([x], [y]). */
  private class Part(var x: Double, var y: Double, val pw: Int, val ph: Int, val value: Int)

  /** The desk (pale, [desk] ± sensor noise, a soft shading gradient) with the [parts] on it. */
  private fun picture(parts: List<Part>, rnd: Random, desk: Int = 205, noise: Double = 1.5): Mat {
    val px = ByteArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
      var v = desk + 8.0 * i / w - 6.0 * j / h
      for (p in parts) {
        val u = i - p.x
        val q = j - p.y
        if (u >= 0 && q >= 0 && u < p.pw && q < p.ph) {
          v = p.value.toDouble()
          // two holes (cam-lock / dowel holes) a little darker than the face
          if (Math.hypot(u - p.pw * 0.25, q - p.ph * 0.5) < 2.5 || Math.hypot(u - p.pw * 0.75, q - p.ph * 0.5) < 2.5) v -= 40
        }
      }
      px[j * w + i] = (v + rnd.nextGaussian() * noise).coerceIn(0.0, 255.0).toInt().toByte()
    }
    val m = Mat(h, w, CvType.CV_8UC1)
    m.put(0, 0, px)
    return m
  }

  /** Full-image (1280 × 960) box of a part drawn in the 640 × 480 picture. */
  private fun fullBox(p: Part) =
      FlowMath.boxToFull(doubleArrayOf(p.x - 0.5, p.y - 0.5, p.x + p.pw - 0.5, p.y + p.ph - 0.5), 0.5)

  @Test
  fun followsAWhitePanelAndASmallScrewOnAPaleDesk() {
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val rnd = Random(11)
    val panel = Part(200.0, 150.0, 190, 65, 238) // a 380 × 130 mm front at ≈ 0.9 m: white on cream (238 on 205)
    val screw = Part(120.0, 330.0, 22, 6, 150) // a 44 px screw of the full image: 22 × 6 px here, grey metal
    val still = Part(450.0, 330.0, 60, 40, 235) // another part that does not move
    val parts = listOf(panel, screw, still)
    val tr = PartFlowTracker()
    val first = tr.process(picture(parts, rnd), 0.5, parts.mapIndexed { i, p -> PartFlowTracker.Seed(i + 1, fullBox(p)) }, emptyList())
    assertEquals("all three have corners to follow: ${first.obs.map { it.points to it.lost }}", 3, first.seeded)
    var ms = 0.0
    var frames = 0
    var last = first
    for (i in 1..40) { // the panel slides 3 px right, 1.5 px down per frame; the screw 2 px left
      panel.x += 3.0
      panel.y += 1.5
      screw.x -= 2.0
      last = tr.process(picture(parts, rnd), 0.5, emptyList(), emptyList(), nowNs = i * 40_000_000L)
      ms += last.ms
      frames++
    }
    fun err(key: Int, p: Part): Double {
      val o = last.obs.single { it.key == key }
      assertNull("track $key lost: ${o.lost}", o.lost)
      val c = fullBox(p)
      return Math.hypot(o.cx - (c[0] + c[2]) / 2, o.cy - (c[1] + c[3]) / 2)
    }
    val ePanel = err(1, panel)
    val eScrew = err(2, screw)
    val eStill = err(3, still)
    println("PartFlowTracker synthetic: panel err ${"%.1f".format(ePanel)} px, screw ${"%.1f".format(eScrew)} px, still " +
        "${"%.1f".format(eStill)} px (full-image px after 40 frames; panel moved 240 px); points ${last.obs.map { it.points }}; " +
        "${"%.2f".format(ms / frames)} ms/frame on this machine for 3 tracks, ${last.points} points")
    assertTrue("panel within 6 px of the full image after moving 240 px: $ePanel", ePanel < 6.0)
    assertTrue("screw within 6 px after moving 160 px: $eScrew", eScrew < 6.0)
    assertTrue("a part that does not move does not drift: $eStill", eStill < 1.5)
  }

  @Test
  fun aCoveredPartIsLostNotDragged() {
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val rnd = Random(5)
    val part = Part(300.0, 200.0, 60, 40, 236)
    val tr = PartFlowTracker()
    assertEquals(1, tr.process(picture(listOf(part), rnd), 0.5, listOf(PartFlowTracker.Seed(7, fullBox(part))), emptyList()).seeded)
    tr.process(picture(listOf(part), rnd), 0.5, emptyList(), emptyList())
    // the part is gone from the picture (taken away between two frames): nothing to follow
    val r = tr.process(picture(emptyList(), rnd), 0.5, emptyList(), emptyList())
    val o = r.obs.single()
    assertNotNull("lost, with a reason", o.lost)
    assertEquals(0, tr.size)
    // an empty desk has nothing to seed on
    val none = tr.process(picture(emptyList(), rnd, noise = 0.0), 0.5, listOf(PartFlowTracker.Seed(8, fullBox(part))), emptyList())
    assertEquals(0, none.seeded)
  }

  @Test
  fun costOfTenTracks() {
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val rnd = Random(2)
    val parts = (0 until 10).map { Part(40.0 + 58 * it, 100.0 + 25 * (it % 4), 40, 26, 236) }
    val tr = PartFlowTracker()
    tr.process(picture(parts, rnd), 0.5, parts.mapIndexed { i, p -> PartFlowTracker.Seed(i + 1, fullBox(p)) }, emptyList())
    var ms = 0.0
    var max = 0.0
    var pts = 0
    for (i in 1..30) {
      parts.forEach { it.x += 1.0 }
      val r = tr.process(picture(parts, rnd), 0.5, emptyList(), emptyList(), nowNs = i * 40_000_000L)
      if (i > 5) { // after the JIT warms up
        ms += r.ms
        max = maxOf(max, r.ms)
      }
      pts = r.points
    }
    println("PartFlowTracker cost: ${"%.2f".format(ms / 25)} ms/frame mean, ${"%.2f".format(max)} max, 10 tracks, $pts points, 640x480, this machine")
    assertTrue(tr.size >= 8)
  }
}
