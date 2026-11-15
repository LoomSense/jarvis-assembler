package com.agj.android.guide

import com.agj.android.core.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * Hard edges of a part (XR_NATIVE_SPEC §4.1: dihedral > 30°, drawn as 1.5 mm lines) and dashed outlines (§4.8 wrong:
 * 8 mm dash / 5 mm gap, 2 mm wide; §4.12 lost: edges dashed). Pure Kotlin, ASSEMBLY frame, unit-tested.
 *
 * Edges are meshes (thin square prisms), not lines: they are what makes a flat board readable through a 14 % fill,
 * and with the unlit fallback material they are the "brighter edges" the fresnel rim would give.
 */
object Edges {
  /** A segment a → b. */
  data class Seg(val a: Vec3, val b: Vec3) {
    val length: Float
      get() = (b - a).length()
  }

  /**
   * Feature edges of a triangle mesh: boundary edges and edges whose two faces meet at more than [angleDeg]. Vertices
   * are welded by position (0.1 mm grid) so split normals do not create fake boundaries. At most [maxSegments], the
   * longest kept (threads of a screw would otherwise give thousands).
   */
  fun featureEdges(
      positions: FloatArray,
      indices: IntArray,
      angleDeg: Float = 30f,
      maxSegments: Int = 600,
  ): List<Seg> {
    val n = positions.size / 3
    fun p(i: Int) = Vec3(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
    // weld
    val key = HashMap<Long, Int>()
    val weld = IntArray(n)
    for (i in 0 until n) {
      val q = 1e4 // 0.1 mm
      val kx = (positions[i * 3] * q).roundToLong()
      val ky = (positions[i * 3 + 1] * q).roundToLong()
      val kz = (positions[i * 3 + 2] * q).roundToLong()
      val k = (kx * 73856093L) xor (ky * 19349663L) xor (kz * 83492791L)
      weld[i] = key.getOrPut(k) { i }
    }
    // edge → face normals
    val faces = HashMap<Long, MutableList<Vec3>>()
    val ends = HashMap<Long, Pair<Int, Int>>()
    var t = 0
    while (t + 2 < indices.size) {
      val a = weld[indices[t]]
      val b = weld[indices[t + 1]]
      val c = weld[indices[t + 2]]
      t += 3
      if (a == b || b == c || a == c) continue
      val nrm = (p(b) - p(a)).cross(p(c) - p(a))
      val len = nrm.length()
      if (len < 1e-12f) continue
      val fn = nrm * (1f / len)
      for ((u, v) in listOf(a to b, b to c, c to a)) {
        val lo = minOf(u, v)
        val hi = maxOf(u, v)
        val k = lo.toLong() shl 32 or hi.toLong()
        faces.getOrPut(k) { ArrayList(2) } += fn
        ends[k] = lo to hi
      }
    }
    val cosMax = cos(Math.toRadians(angleDeg.toDouble())).toFloat()
    val out = ArrayList<Seg>()
    for ((k, ns) in faces) {
      val hard =
          ns.size == 1 ||
              ns.size > 2 ||
              // double-sided meshes (same face twice with flipped normals) are not hard: |dot| ≈ 1
              ns[0].dot(ns[1]).let { d -> d < cosMax && abs(d + 1f) > 1e-3f }
      if (!hard) continue
      val (u, v) = ends[k]!!
      out += Seg(p(u), p(v))
    }
    if (out.size <= maxSegments) return out
    return out.sortedByDescending { it.length }.take(maxSegments)
  }

  /** Split segments into dashes of [dash] m with [gap] m gaps (the pattern restarts on every segment). */
  fun dashed(segs: List<Seg>, dash: Float, gap: Float): List<Seg> {
    val out = ArrayList<Seg>()
    for (s in segs) {
      val len = s.length
      if (len < 1e-6f) continue
      if (len <= dash) {
        out += s
        continue
      }
      var d = 0f
      while (d < len) {
        val e = minOf(d + dash, len)
        out += Seg(s.a.lerp(s.b, d / len), s.a.lerp(s.b, e / len))
        d = e + gap
      }
    }
    return out
  }

  /** Thin square prisms of [width] m around each segment (4 sides, 8 vertices, 8 triangles each). */
  fun tubes(segs: List<Seg>, width: Float): MeshData {
    val pos = FloatArray(segs.size * 8 * 3)
    val nrm = FloatArray(segs.size * 8 * 3)
    val idx = IntArray(segs.size * 8 * 3)
    val h = width / 2f
    var vi = 0
    var ii = 0
    for (s in segs) {
      val d = Shapes.norm(s.b - s.a)
      val ref = if (abs(d.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
      val u = Shapes.norm(d.cross(ref))
      val w = d.cross(u)
      val offs = listOf(u * h, w * h, u * -h, w * -h)
      val base = vi
      for (end in listOf(s.a, s.b)) for (o in offs) {
        val p = end + o
        val nn = Shapes.norm(o)
        pos[vi * 3] = p.x; pos[vi * 3 + 1] = p.y; pos[vi * 3 + 2] = p.z
        nrm[vi * 3] = nn.x; nrm[vi * 3 + 1] = nn.y; nrm[vi * 3 + 2] = nn.z
        vi++
      }
      for (k in 0 until 4) {
        val a0 = base + k
        val a1 = base + (k + 1) % 4
        val b0 = a0 + 4
        val b1 = a1 + 4
        idx[ii++] = a0; idx[ii++] = b0; idx[ii++] = a1
        idx[ii++] = a1; idx[ii++] = b0; idx[ii++] = b1
      }
    }
    return MeshData(pos, nrm, idx.copyOf(ii))
  }
}
