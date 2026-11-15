package com.agj.android.vision

/**
 * YUV_420_888 helpers. Pure Kotlin (unit-tested); the Android JPEG encode (`YuvImage`) lives in [LiveCameraSource].
 */
object Yuv {
  /**
   * Resize (nearest neighbour, or a 2 x 2 luma average with [smooth]) of a YUV 4:2:0 image (any plane layout, as `android.media.Image` gives it) into NV21
   * (`Y…, then V,U interleaved`) of [ow] × [oh] (both even).
   */
  fun toNv21Scaled(
      y: ByteArray,
      yRowStride: Int,
      u: ByteArray,
      v: ByteArray,
      uvRowStride: Int,
      uvPixelStride: Int,
      w: Int,
      h: Int,
      ow: Int,
      oh: Int,
      smooth: Boolean = false,
  ): ByteArray {
    require(ow % 2 == 0 && oh % 2 == 0) { "output size must be even" }
    val out = ByteArray(ow * oh * 3 / 2)
    val xs = IntArray(ow) { ((it.toLong() * w) / ow).toInt() }
    if (smooth && (ow < w || oh < h)) {
      // 2 x 2 average of the luma when shrinking: the nearest-neighbour picture is aliased, and the server threw
      // away most headset frames as too poor to judge (15 Nov)
      for (j in 0 until oh) {
        val sy = ((j.toLong() * h) / oh).toInt()
        val r0 = sy * yRowStride
        val r1 = minOf(sy + 1, h - 1) * yRowStride
        val o = j * ow
        for (i in 0 until ow) {
          val x0 = xs[i]
          val x1 = minOf(x0 + 1, w - 1)
          val sum = (y[r0 + x0].toInt() and 0xFF) + (y[r0 + x1].toInt() and 0xFF) +
              (y[r1 + x0].toInt() and 0xFF) + (y[r1 + x1].toInt() and 0xFF)
          out[o + i] = ((sum + 2) shr 2).toByte()
        }
      }
    } else {
      for (j in 0 until oh) {
        val sy = ((j.toLong() * h) / oh).toInt()
        val row = sy * yRowStride
        val o = j * ow
        for (i in 0 until ow) out[o + i] = y[row + xs[i]]
      }
    }
    var o = ow * oh
    for (j in 0 until oh / 2) {
      val sy = (((2 * j).toLong() * h) / oh).toInt() / 2
      val row = sy * uvRowStride
      for (i in 0 until ow / 2) {
        val sx = xs[2 * i] / 2
        val k = row + sx * uvPixelStride
        out[o++] = v.getOrElse(k) { 128.toByte() }
        out[o++] = u.getOrElse(k) { 128.toByte() }
      }
    }
    return out
  }

  /** Output size with the long edge at most [longEdge], aspect kept, both sides even. */
  fun fitLongEdge(w: Int, h: Int, longEdge: Int): Pair<Int, Int> {
    val s = minOf(1.0, longEdge.toDouble() / maxOf(w, h))
    fun even(x: Double) = (Math.round(x).toInt() / 2) * 2
    return even(w * s) to even(h * s)
  }

  /**
   * Intrinsics of an output stream from `LENS_INTRINSIC_CALIBRATION` [k] (pixels of the [sensorW] × [sensorH]
   * pre-correction array). Camera2 streams are a centred crop of the array at the stream's aspect ratio (widest
   * field of view), then scaled: Meta's Unity PCA sample (PassthroughCameraUtils) computes the same crop.
   */
  fun streamIntrinsics(
      k: FloatArray,
      sensorW: Int,
      sensorH: Int,
      outW: Int,
      outH: Int,
      distortion: List<Double>? = null,
  ): Intrinsics {
    val s = maxOf(outW.toDouble() / sensorW, outH.toDouble() / sensorH)
    val cropW = outW / s
    val cropH = outH / s
    val offX = (sensorW - cropW) / 2
    val offY = (sensorH - cropH) / 2
    return Intrinsics(
        k[0] * s,
        k[1] * s,
        (k[2] - offX) * s,
        (k[3] - offY) * s,
        outW,
        outH,
        distortion,
    )
  }

  /**
   * Camera2 `LENS_DISTORTION` [kappa1, kappa2, kappa3, kappa4, kappa5] (3 radial, 2 tangential) → OpenCV
   * (k1, k2, p1, p2, k3). Same polynomial; only the order differs. Null when absent or all zero.
   */
  fun androidToOpenCvDistortion(d: FloatArray?): List<Double>? {
    if (d == null || d.size < 5 || d.all { it == 0f }) return null
    return listOf(d[0].toDouble(), d[1].toDouble(), d[3].toDouble(), d[4].toDouble(), d[2].toDouble())
  }
}
