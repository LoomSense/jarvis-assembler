package com.agj.android.world

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.vision.Intrinsics

/** A ray in WORLD (right-handed, metres); [dir] unit length. */
data class Ray(val origin: Vec3, val dir: Vec3) {
  fun at(t: Float): Vec3 = origin + dir * t
}

/**
 * From a server box in the camera picture to a ray in WORLD, and rays onto planes. Pure, unit-tested.
 *
 * Conventions: `box_2d` is Gemini's `[ymin, xmin, ymax, xmax]`, 0–1000, normalised to the picture sent (any size:
 * the app sends the full frame downscaled, same aspect, so the same numbers hold for the full-resolution image the
 * [Intrinsics] belong to). Pixels are OpenCV's: pixel `i` covers `[i − 0.5, i + 0.5]`, so the continuous picture
 * coordinate `x·W` maps to pixel `x·W − 0.5`. CAMERA is OpenCV (+X right, +Y down, +Z forward).
 */
object PickRays {
  /** The box in pixels of a [width] × [height] image: (u0, v0, u1, v1), ordered; null when malformed. */
  fun boxToPx(box: List<Double>, width: Int, height: Int): DoubleArray? {
    if (box.size != 4 || box.any { !it.isFinite() }) return null
    val y0 = minOf(box[0], box[2]).coerceIn(0.0, 1000.0)
    val y1 = maxOf(box[0], box[2]).coerceIn(0.0, 1000.0)
    val x0 = minOf(box[1], box[3]).coerceIn(0.0, 1000.0)
    val x1 = maxOf(box[1], box[3]).coerceIn(0.0, 1000.0)
    if (x1 - x0 < 1e-6 || y1 - y0 < 1e-6) return null
    return doubleArrayOf(x0 / 1000.0 * width - 0.5, y0 / 1000.0 * height - 0.5, x1 / 1000.0 * width - 0.5,
        y1 / 1000.0 * height - 0.5)
  }

  /** Box centre in pixels (u, v); null when malformed. */
  fun boxCenterPx(box: List<Double>, width: Int, height: Int): Pair<Double, Double>? =
      boxToPx(box, width, height)?.let { (it[0] + it[2]) / 2 to (it[1] + it[3]) / 2 }

  /**
   * Undistort normalised image coordinates (OpenCV model k1, k2, p1, p2[, k3]) by fixed-point iteration (what
   * `cv::undistortPoints` does). [d] null or empty = no distortion.
   */
  fun undistort(xd: Double, yd: Double, d: List<Double>?): Pair<Double, Double> {
    if (d == null || d.size < 4) return xd to yd
    val k1 = d[0]
    val k2 = d[1]
    val p1 = d[2]
    val p2 = d[3]
    val k3 = d.getOrElse(4) { 0.0 }
    var x = xd
    var y = yd
    repeat(20) {
      val r2 = x * x + y * y
      val radial = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2
      val dx = 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
      val dy = p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
      x = (xd - dx) / radial
      y = (yd - dy) / radial
    }
    return x to y
  }

  /** Forward distortion of normalised coordinates (the inverse of [undistort]; tests and [project]). */
  fun distort(x: Double, y: Double, d: List<Double>?): Pair<Double, Double> {
    if (d == null || d.size < 4) return x to y
    val r2 = x * x + y * y
    val k3 = d.getOrElse(4) { 0.0 }
    val radial = 1 + d[0] * r2 + d[1] * r2 * r2 + k3 * r2 * r2 * r2
    return (x * radial + 2 * d[2] * x * y + d[3] * (r2 + 2 * x * x)) to
        (y * radial + d[2] * (r2 + 2 * y * y) + 2 * d[3] * x * y)
  }

  /** Unit direction in CAMERA axes through pixel ([u], [v]). */
  fun pixelToCameraDir(u: Double, v: Double, k: Intrinsics, useDistortion: Boolean = false): Vec3 {
    val xd = (u - k.cx) / k.fx
    val yd = (v - k.cy) / k.fy
    val (x, y) = if (useDistortion) undistort(xd, yd, k.distortion) else xd to yd
    val n = kotlin.math.sqrt(x * x + y * y + 1.0)
    return Vec3((x / n).toFloat(), (y / n).toFloat(), (1.0 / n).toFloat())
  }

  /** The WORLD ray from the camera centre through pixel ([u], [v]). */
  fun worldRay(worldFromCamera: Pose3, u: Double, v: Double, k: Intrinsics, useDistortion: Boolean = false): Ray =
      Ray(worldFromCamera.t, worldFromCamera.q.rotate(pixelToCameraDir(u, v, k, useDistortion)))

  /** Box centre → WORLD ray, or null when the box is malformed. */
  fun boxRay(worldFromCamera: Pose3, box: List<Double>, k: Intrinsics, useDistortion: Boolean = false): Ray? =
      boxCenterPx(box, k.width, k.height)?.let { (u, v) -> worldRay(worldFromCamera, u, v, k, useDistortion) }

  /**
   * Where [ray] meets the plane through [point] with normal [normal]; null when parallel, behind the origin, or
   * farther than [maxM].
   */
  fun intersectPlane(ray: Ray, point: Vec3, normal: Vec3, maxM: Float = 10f): Vec3? {
    val den = ray.dir.dot(normal)
    if (kotlin.math.abs(den) < 1e-6f) return null
    val t = (point - ray.origin).dot(normal) / den
    if (t <= 0f || t > maxM) return null
    return ray.at(t)
  }

  /** Where [ray] meets the horizontal plane at height [y] (WORLD +Y up). */
  fun intersectHorizontal(ray: Ray, y: Float, maxM: Float = 10f): Vec3? =
      intersectPlane(ray, Vec3(0f, y, 0f), Vec3(0f, 1f, 0f), maxM)

  /** WORLD point → pixel (u, v) in the camera; null when behind it. Inverse of [worldRay] (tests, debug). */
  fun project(worldFromCamera: Pose3, k: Intrinsics, p: Vec3, useDistortion: Boolean = false): Pair<Double, Double>? {
    val c = worldFromCamera.inverse().transform(p)
    if (c.z <= 1e-6f) return null
    val x = c.x.toDouble() / c.z
    val y = c.y.toDouble() / c.z
    val (xd, yd) = if (useDistortion) distort(x, y, k.distortion) else x to y
    return (k.fx * xd + k.cx) to (k.fy * yd + k.cy)
  }
}
