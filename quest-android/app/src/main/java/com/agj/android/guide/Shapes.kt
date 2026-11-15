package com.agj.android.guide

import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A triangle mesh in plain arrays (xyz positions, xyz normals, indices). Pure Kotlin. */
class MeshData(val positions: FloatArray, val normals: FloatArray, val indices: IntArray) {
  val vertexCount: Int
    get() = positions.size / 3
}

/**
 * Procedural meshes for the guidance (XR_NATIVE_SPEC §4): path dots, arrowheads, the 290° turning arc, leader lines,
 * rings. Built in their own frame, sized in metres; entities place and orient them.
 */
object Shapes {
  private class Builder {
    val p = ArrayList<Float>()
    val n = ArrayList<Float>()
    val i = ArrayList<Int>()

    fun v(pos: Vec3, nrm: Vec3): Int {
      p += pos.x; p += pos.y; p += pos.z
      n += nrm.x; n += nrm.y; n += nrm.z
      return p.size / 3 - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
      i += a; i += b; i += c
    }

    fun build() = MeshData(p.toFloatArray(), n.toFloatArray(), i.toIntArray())
  }

  /** UV sphere of radius [r] (low poly: path dots read as discs at 4 mm). */
  fun sphere(r: Float, seg: Int = 10, rings: Int = 6): MeshData {
    val b = Builder()
    for (y in 0..rings) {
      val th = PI * y / rings
      for (x in 0..seg) {
        val ph = 2 * PI * x / seg
        val nrm = Vec3((sin(th) * cos(ph)).toFloat(), cos(th).toFloat(), (sin(th) * sin(ph)).toFloat())
        b.v(nrm * r, nrm)
      }
    }
    for (y in 0 until rings) for (x in 0 until seg) {
      val a = y * (seg + 1) + x
      val c = a + seg + 1
      b.tri(a, c, a + 1)
      b.tri(a + 1, c, c + 1)
    }
    return b.build()
  }

  /** Cone along +Y: base (radius [r]) at y = 0, tip at y = [h]. */
  fun cone(r: Float, h: Float, seg: Int = 16): MeshData {
    val b = Builder()
    val tip = Vec3(0f, h, 0f)
    for (k in 0 until seg) {
      val a0 = 2 * PI * k / seg
      val a1 = 2 * PI * (k + 1) / seg
      val p0 = Vec3((r * cos(a0)).toFloat(), 0f, (r * sin(a0)).toFloat())
      val p1 = Vec3((r * cos(a1)).toFloat(), 0f, (r * sin(a1)).toFloat())
      val nrm = norm(Vec3(p0.x + p1.x, r * r / h, p0.z + p1.z))
      b.tri(b.v(p0, nrm), b.v(tip, nrm), b.v(p1, nrm))
      val down = Vec3(0f, -1f, 0f)
      b.tri(b.v(Vec3.ZERO, down), b.v(p0, down), b.v(p1, down))
    }
    return b.build()
  }

  /** Cylinder along +Y from y = 0 to y = [h] (leader lines: scale Y to the length). */
  fun cylinder(r: Float, h: Float, seg: Int = 8): MeshData {
    val b = Builder()
    for (k in 0 until seg) {
      val a0 = 2 * PI * k / seg
      val a1 = 2 * PI * (k + 1) / seg
      val n0 = Vec3(cos(a0).toFloat(), 0f, sin(a0).toFloat())
      val n1 = Vec3(cos(a1).toFloat(), 0f, sin(a1).toFloat())
      val v0 = b.v(n0 * r, n0)
      val v1 = b.v(n1 * r, n1)
      val v2 = b.v(n0 * r + Vec3(0f, h, 0f), n0)
      val v3 = b.v(n1 * r + Vec3(0f, h, 0f), n1)
      b.tri(v0, v2, v1)
      b.tri(v1, v2, v3)
    }
    return b.build()
  }

  /**
   * Tube along an arc in the XZ plane around +Y: radius [radius], tube radius [tube], from angle 0 to [sweepDeg]
   * (counter-clockwise seen from +Y, i.e. clockwise seen from −Y), plus an arrowhead at the end pointing along the
   * arc. To show "clockwise as seen from where the tool comes from", orient the entity's +Y **into** the hole (away
   * from the tool): seen from the tool side the arc then runs clockwise.
   */
  fun turnArrow(radius: Float, tube: Float, sweepDeg: Float = 290f, seg: Int = 48, sides: Int = 8): MeshData {
    val b = Builder()
    val sweep = (sweepDeg * PI / 180.0).toFloat()
    val head = 0.022f.coerceAtMost(radius * 1.2f) // arc length the arrowhead takes
    val arcEnd = sweep - head / radius
    for (k in 0..seg) {
      val a = arcEnd * k / seg
      val c = Vec3(radius * cos(a), 0f, -radius * sin(a))
      val outward = Vec3(cos(a), 0f, -sin(a))
      val up = Vec3(0f, 1f, 0f)
      for (s in 0..sides) {
        val t = 2 * PI * s / sides
        val nrm = outward * cos(t).toFloat() + up * sin(t).toFloat()
        b.v(c + nrm * tube, nrm)
      }
    }
    for (k in 0 until seg) for (s in 0 until sides) {
      val a = k * (sides + 1) + s
      val c = a + sides + 1
      b.tri(a, a + 1, c)
      b.tri(a + 1, c + 1, c)
    }
    // Arrowhead: a cone whose axis is the arc tangent at arcEnd.
    val cone = cone(tube * 2.8f, head)
    val tangent = Vec3(-sin(arcEnd), 0f, -cos(arcEnd))
    val q = fromTo(Vec3(0f, 1f, 0f), tangent)
    val base = Vec3(radius * cos(arcEnd), 0f, -radius * sin(arcEnd))
    val off = b.p.size / 3
    for (v in 0 until cone.vertexCount) {
      val pp = q.rotate(Vec3(cone.positions[v * 3], cone.positions[v * 3 + 1], cone.positions[v * 3 + 2])) + base
      val nn = q.rotate(Vec3(cone.normals[v * 3], cone.normals[v * 3 + 1], cone.normals[v * 3 + 2]))
      b.v(pp, nn)
    }
    for (idx in cone.indices) b.i += idx + off
    return b.build()
  }

  /** Flat ring in the XZ plane (hole highlight): inner radius [r], width [w]. */
  fun ring(r: Float, w: Float, seg: Int = 40): MeshData {
    val b = Builder()
    val up = Vec3(0f, 1f, 0f)
    for (k in 0..seg) {
      val a = 2 * PI * k / seg
      val d = Vec3(cos(a).toFloat(), 0f, sin(a).toFloat())
      b.v(d * r, up)
      b.v(d * (r + w), up)
    }
    for (k in 0 until seg) {
      val a = k * 2
      b.tri(a, a + 2, a + 1)
      b.tri(a + 1, a + 2, a + 3)
    }
    return b.build()
  }

  fun norm(v: Vec3): Vec3 = v.length().let { if (it < 1e-9f) Vec3(0f, 1f, 0f) else v * (1f / it) }

  /** Shortest rotation taking unit [a] to unit [b]. */
  fun fromTo(a: Vec3, b: Vec3): Quat {
    val an = norm(a)
    val bn = norm(b)
    val d = an.dot(bn)
    if (d > 0.99999f) return Quat.IDENTITY
    if (d < -0.99999f) {
      val axis = if (abs(an.x) < 0.9f) an.cross(Vec3(1f, 0f, 0f)) else an.cross(Vec3(0f, 1f, 0f))
      return Quat.axisAngle(axis, PI.toFloat())
    }
    val c = an.cross(bn)
    return Quat(c.x, c.y, c.z, 1f + d).normalized()
  }

  /**
   * Insertion path points (ASSEMBLY frame) for a part that ends at [end] and comes from [fromDir], per spec §4.2:
   * boards: cubic Bézier from 25 cm out along from_dir, lifted; dowels/pins/screws: a straight line 10 cm long.
   * Dots every [spacing] metres; the last dot stops [gap] short of the end. [up] is world-up in ASSEMBLY coordinates.
   */
  fun insertionPath(end: Vec3, fromDir: Vec3, up: Vec3, straight: Boolean, spacing: Float = 0.012f, gap: Float = 0.02f): List<Vec3> {
    val u = norm(fromDir)
    val e = end + u * gap
    if (straight) {
      val start = end + u * 0.10f
      val len = (start - e).length()
      val n = maxOf(2, (len / spacing).toInt())
      return (0..n).map { start.lerp(e, it / n.toFloat()) }
    }
    val start = end + u * 0.25f
    val c1 = start + up * 0.30f
    val c2 = e + u * 0.18f + up * 0.12f
    // Sample densely, then resample by arc length.
    val dense = (0..200).map { bezier(start, c1, c2, e, it / 200f) }
    val out = ArrayList<Vec3>()
    var acc = 0f
    out += dense[0]
    for (k in 1 until dense.size) {
      acc += (dense[k] - dense[k - 1]).length()
      if (acc >= spacing) {
        out += dense[k]
        acc = 0f
      }
    }
    // Always end exactly 2 cm short of the part (the arrowhead goes there).
    if ((out.last() - e).length() > spacing * 0.5f) out += e else out[out.size - 1] = e
    return out
  }

  fun bezier(a: Vec3, b: Vec3, c: Vec3, d: Vec3, t: Float): Vec3 {
    val s = 1 - t
    return a * (s * s * s) + b * (3 * s * s * t) + c * (3 * s * t * t) + d * (t * t * t)
  }
}

/**
 * Meshes built in a right-handed frame (glTF Y-up, our ASSEMBLY, [Shapes]) → the left-handed Spatial SDK: z of
 * every position and normal negated ([com.agj.android.vision.Frames.mirrorZ]) and every triangle's winding reversed,
 * so faces keep their outward side and the part is not mirrored (the left side panel stays on the left). Pure.
 */
object SpatialMesh {
  fun fromGltf(d: MeshData): MeshData {
    val p = d.positions.copyOf()
    val n = d.normals.copyOf()
    for (i in 2 until p.size step 3) p[i] = -p[i]
    for (i in 2 until n.size step 3) n[i] = -n[i]
    val idx = d.indices.copyOf()
    for (t in 0 until idx.size - 2 step 3) {
      val b = idx[t + 1]
      idx[t + 1] = idx[t + 2]
      idx[t + 2] = b
    }
    return MeshData(p, n, idx)
  }
}
