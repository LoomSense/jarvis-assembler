package com.agj.android

import com.agj.android.core.Vec3
import com.agj.android.guide.Edges
import com.agj.android.guide.GhostMaterial
import com.agj.android.ui.Images
import com.agj.android.ui.PartWire
import com.agj.android.ui.WindowFollow
import com.agj.android.ui.WindowRelocator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 2: ghost material contract, edges/outline meshes, guide line art, window fade-relocate. */
class Round2Test {
  /** The shader's uniform block must list the material's attributes in declaration order (SDK packs by order). */
  @Test
  fun shaderUniformBlockMatchesMaterialAttributes() {
    for (f in listOf("src/shaders/ghost_fresnel.vert", "src/shaders/ghost_fresnel.frag")) {
      val src = File(f).readText()
      val block = Regex("uniform MaterialUniform \\{([^}]*)\\}").find(src)!!.groupValues[1]
      val names = Regex("vec4 (\\w+);").findAll(block).map { it.groupValues[1] }.toList()
      assertEquals(f, GhostMaterial.ATTRIBUTES, names)
      // never the SDK's default material block (its order differs: matParams, alphaParams, stereoParams, …)
      assertTrue(f, Regex("#include .*(MaterialBase|FragmentBase|VertexBase|DefaultVertex)").find(src) == null)
    }
  }

  @Test
  fun unlitFallbackAlpha() {
    assertEquals(0.14f + 0.3f * 0.65f, GhostMaterial.unlitAlpha(0.14f, 0.65f, 1f), 1e-5f)
    assertEquals(0f, GhostMaterial.unlitAlpha(0.14f, 0.65f, 0f), 1e-6f)
    assertEquals(1f, GhostMaterial.unlitAlpha(1f, 1f, 1f), 1e-6f)
  }

  /** Unit cube, 12 triangles, split normals (24 vertices): 12 hard edges, no face diagonals. */
  private fun cube(): Pair<FloatArray, IntArray> {
    val pos = ArrayList<Float>()
    val idx = ArrayList<Int>()
    val faces =
        listOf(
            listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 1f, 0f), Vec3(0f, 1f, 0f)),
            listOf(Vec3(0f, 0f, 1f), Vec3(0f, 1f, 1f), Vec3(1f, 1f, 1f), Vec3(1f, 0f, 1f)),
            listOf(Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 1f, 1f), Vec3(0f, 0f, 1f)),
            listOf(Vec3(1f, 0f, 0f), Vec3(1f, 0f, 1f), Vec3(1f, 1f, 1f), Vec3(1f, 1f, 0f)),
            listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(1f, 0f, 1f), Vec3(1f, 0f, 0f)),
            listOf(Vec3(0f, 1f, 0f), Vec3(1f, 1f, 0f), Vec3(1f, 1f, 1f), Vec3(0f, 1f, 1f)),
        )
    for (f in faces) {
      val b = pos.size / 3
      for (v in f) { pos += v.x * 0.1f; pos += v.y * 0.1f; pos += v.z * 0.1f }
      idx += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
    }
    return pos.toFloatArray() to idx.toIntArray()
  }

  @Test
  fun cubeHasTwelveHardEdges() {
    val (p, i) = cube()
    val e = Edges.featureEdges(p, i)
    assertEquals(12, e.size)
    e.forEach { assertEquals(0.1f, it.length, 1e-5f) }
    // a flat quad (2 triangles) has 4 boundary edges, not its diagonal
    val quad = Edges.featureEdges(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2, 0, 2, 3))
    assertEquals(4, quad.size)
  }

  @Test
  fun dashedOutlineAndTubes() {
    val seg = Edges.Seg(Vec3(0f, 0f, 0f), Vec3(0.1f, 0f, 0f))
    // 8 mm dash + 5 mm gap over 100 mm: dashes start at 0, 13, 26, … 91 → 8 dashes, the last 9 mm long cut to 8
    val d = Edges.dashed(listOf(seg), 0.008f, 0.005f)
    assertEquals(8, d.size)
    d.forEach { assertTrue(it.length <= 0.008f + 1e-6f) }
    val m = Edges.tubes(d, 0.002f)
    assertEquals(d.size * 8, m.vertexCount)
    assertEquals(d.size * 24, m.indices.size)
    assertTrue(m.indices.all { it in 0 until m.vertexCount })
  }

  @Test
  fun partWireIsNormalized() {
    val (p, i) = cube()
    val w = PartWire.of(Edges.featureEdges(p, i))
    assertEquals(12 * 6, w.size)
    assertTrue(w.all { it in -1.0001f..1.0001f })
  }

  @Test
  fun lineArtTurnsInkWhiteAndPaperClear() {
    val px = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFE0E0E0.toInt())
    Images.lineArt(px)
    assertEquals(0, px[0] ushr 24) // paper → clear
    assertEquals(255, px[1] ushr 24) // ink → opaque
    assertTrue((px[2] ushr 24) in 100..250) // grey line → partly
    assertEquals(0, px[3] ushr 24) // light paper tone → clear
    px.forEach { assertEquals(0xF2F2F2, it and 0xFFFFFF) } // all white
    assertEquals(4, Images.sampleSize(1280, 960, 320))
    assertEquals(1, Images.sampleSize(300, 200, 320))
  }

  @Test
  fun windowFollowsSmallMovesAndFadeRelocatesBigOnes() {
    val r = WindowRelocator(WindowFollow())
    val dt = 1f / 72f
    var p = r.update(Vec3(0f, 1f, -0.8f), dt)!!
    // a 20 cm slide: follows with the spring, alpha stays 1, within ~1 s
    repeat(72) { p = r.update(Vec3(0.2f, 1f, -0.8f), dt)!! ; assertEquals(1f, r.alpha, 0f) }
    assertTrue((p - Vec3(0.2f, 1f, -0.8f)).length() < 0.03f)
    // a 60 cm jump: never slides; waits 1 s still, fades out, snaps, fades in
    val far = Vec3(0.8f, 1f, -0.8f)
    val before = p
    val seen = ArrayList<Vec3>()
    repeat(200) { seen += r.update(far, dt)!! }
    assertEquals(1, r.relocations)
    assertEquals(1f, r.alpha, 1e-6f)
    assertEquals(WindowRelocator.Phase.FOLLOW, r.phase)
    // every position is either the old place or the new one: nothing in between
    seen.forEach { assertTrue((it - before).length() < 1e-4f || (it - far).length() < 0.031f) }
    // hold (turn step): no movement at all
    val held = r.update(Vec3(0.9f, 1f, -0.8f), dt, hold = true)!!
    assertEquals(seen.last(), held)
  }
}
