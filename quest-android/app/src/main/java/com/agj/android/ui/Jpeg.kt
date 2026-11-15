package com.agj.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Re-encode a camera JPEG to what the server expects: long edge ≤ [longEdge] px, quality [quality]. */
fun downscaleJpeg(jpeg: ByteArray, longEdge: Int = 768, quality: Int = 70): ByteArray {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
  val w = bounds.outWidth
  val h = bounds.outHeight
  if (w <= 0 || h <= 0) return jpeg
  if (max(w, h) <= longEdge) return jpeg
  var sample = 1
  while (max(w, h) / (sample * 2) >= longEdge) sample *= 2
  val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
      ?: return jpeg
  val s = longEdge.toFloat() / max(bmp.width, bmp.height)
  val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
  val out = ByteArrayOutputStream()
  scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
  if (scaled !== bmp) scaled.recycle()
  bmp.recycle()
  return out.toByteArray()
}
