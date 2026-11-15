package com.agj.android.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Small math types used by every module interface, so modules do not depend on Spatial SDK or OpenCV types and can be
 * unit-tested on the JVM. Convert at the edges (Spatial SDK `Vector3`/`Quaternion`/`Pose`; OpenCV rvec/tvec).
 *
 * Frames (all right-handed, metres; the app's math never sees a left-handed frame):
 *  - WORLD: the OpenXR tracking space; +X right, +Y up, −Z forward at session start. glTF Y-up matches it. The Meta
 *    Spatial SDK is LEFT-handed (+Z forward, its Scene docs): its world is WORLD mirrored in Z. Convert only at the
 *    SDK edge: guide/SpatialGhostRenderer.kt `toSdk` / `toPose3` / `facingPose` and `SpatialMesh.fromGltf` (meshes),
 *    all built on vision/Frames.kt `mirrorZ`.
 *  - ASSEMBLY: the GLB root node `assembly`; +Y up (glTF). Manifest `target_m`/`from_dir` are Blender Z-up and must
 *    go through [blenderToGltf] first.
 *  - CAMERA (OpenCV convention): +X right, +Y down, +Z forward along the optical axis. Pixels: origin top-left.
 *  - HEAD: the HMD pose the Spatial SDK reports.
 * Naming: `aFromB` maps points in frame B to frame A (`worldFromCamera.transform(pCam) = pWorld`).
 */

data class Vec3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
  operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
  operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
  fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
  fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
  fun length() = sqrt(dot(this))
  fun lerp(o: Vec3, t: Float) = this + (o - this) * t

  companion object {
    val ZERO = Vec3(0f, 0f, 0f)
  }
}

/** Unit quaternion (x, y, z, w). */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
  operator fun times(o: Quat) =
      Quat(
          w * o.x + x * o.w + y * o.z - z * o.y,
          w * o.y - x * o.z + y * o.w + z * o.x,
          w * o.z + x * o.y - y * o.x + z * o.w,
          w * o.w - x * o.x - y * o.y - z * o.z,
      )

  fun conjugate() = Quat(-x, -y, -z, w)

  fun rotate(v: Vec3): Vec3 {
    val u = Vec3(x, y, z)
    val t = u.cross(v) * 2f
    return v + t * w + u.cross(t)
  }

  fun normalized(): Quat {
    val n = sqrt(x * x + y * y + z * z + w * w)
    return if (n < 1e-9f) IDENTITY else Quat(x / n, y / n, z / n, w / n)
  }

  /** Heading about world +Y in degrees: the angle of the rotated +X axis in the XZ plane, three.js convention. */
  fun yawDeg(): Double {
    val ax = rotate(Vec3(1f, 0f, 0f))
    return Math.toDegrees(atan2(-ax.z.toDouble(), ax.x.toDouble()))
  }

  /** Normalised linear interpolation along the shorter arc; good enough for filtering small steps. */
  fun nlerp(o: Quat, t: Float): Quat {
    val d = x * o.x + y * o.y + z * o.z + w * o.w
    val s = if (d < 0f) -1f else 1f
    return Quat(
            x + (s * o.x - x) * t,
            y + (s * o.y - y) * t,
            z + (s * o.z - z) * t,
            w + (s * o.w - w) * t,
        )
        .normalized()
  }

  companion object {
    val IDENTITY = Quat(0f, 0f, 0f, 1f)

    fun axisAngle(axis: Vec3, rad: Float): Quat {
      val n = axis.length().takeIf { it > 1e-9f } ?: return IDENTITY
      val s = sin(rad / 2f) / n
      return Quat(axis.x * s, axis.y * s, axis.z * s, cos(rad / 2f))
    }

    fun yaw(rad: Float) = axisAngle(Vec3(0f, 1f, 0f), rad)
  }
}

/** Rigid transform: rotate by [q], then translate by [t] (metres). */
data class Pose3(val t: Vec3 = Vec3.ZERO, val q: Quat = Quat.IDENTITY) {
  fun transform(p: Vec3): Vec3 = q.rotate(p) + t

  /** this ∘ o: apply [o] first. */
  operator fun times(o: Pose3) = Pose3(q.rotate(o.t) + t, (q * o.q).normalized())

  fun inverse(): Pose3 {
    val qi = q.conjugate()
    return Pose3(qi.rotate(t) * -1f, qi)
  }

  companion object {
    val IDENTITY = Pose3()
  }
}

/** Blender Z-up point/direction → glTF Y-up: (x, y, z) → (x, z, −y). See docs/HANDOFF.md §2.1. */
fun blenderToGltf(v: List<Double>): Vec3 =
    Vec3(v.getOrElse(0) { 0.0 }.toFloat(), v.getOrElse(2) { 0.0 }.toFloat(), -v.getOrElse(1) { 0.0 }.toFloat())
