package com.agj.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Image helpers for the window's guide drawing (§2.4) and the camera preview. */
object Images {
  /**
   * Guide crop (dark lines on white paper) → white line art on the glass (§2.4: "no white block"): every pixel white,
   * alpha = darkness (1 − luminance), with paper below [paper] luminance dropped to 0. ARGB ints in place. Pure.
   */
  fun lineArt(argb: IntArray, paper: Float = 0.82f): IntArray {
    for (i in argb.indices) {
      val c = argb[i]
      val a0 = (c ushr 24) and 0xff
      val r = (c shr 16) and 0xff
      val g = (c shr 8) and 0xff
      val b = c and 0xff
      val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
      val ink = ((paper - lum) / paper).coerceIn(0f, 1f)
      // gamma lift so thin grey lines stay readable
      val a = (Math.pow(ink.toDouble(), 0.7) * 255.0 * (a0 / 255.0)).toInt().coerceIn(0, 255)
      argb[i] = (a shl 24) or 0x00F2F2F2
    }
    return argb
  }

  /** PNG/JPEG bytes → line-art bitmap at most [maxPx] on the long edge (background thread). */
  fun decodeLineArt(bytes: ByteArray, maxPx: Int = 480): ImageBitmap? {
    val bmp = decodeScaled(bytes, maxPx) ?: return null
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    lineArt(px)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    if (bmp !== out) bmp.recycle()
    return out.asImageBitmap()
  }

  /** Power-of-two subsampling for decode: the largest that keeps the long edge ≥ [maxPx]. Pure. */
  fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
    var s = 1
    while (maxOf(w, h) / (s * 2) >= maxPx) s *= 2
    return s
  }

  fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(o.outWidth, o.outHeight, maxPx) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
  }
}

/**
 * Camera preview for the side panel (coordinator request: "it doesn't show the camera preview"): [offer] is called from
 * the camera thread with every frame; at most [fps] a second it takes the JPEG (lazy-encoded by the vision module),
 * decodes it at ~[maxPx] off the main thread, and posts the bitmap to [model] on the main thread.
 */
class CameraPreviewFeed(
    private val scope: CoroutineScope,
    private val model: SidePanelModel,
    private val fps: Float = 8f,
    private val maxPx: Int = 320,
) {
  private val busy = AtomicBoolean(false)
  @Volatile private var lastMs = 0L
  @Volatile var enabled = true

  /** Camera thread. [jpeg] is read only when a preview frame is due. */
  fun offer(jpeg: () -> ByteArray) {
    if (!enabled) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastMs < 1000f / fps || !busy.compareAndSet(false, true)) return
    lastMs = now
    val bytes = try { jpeg() } catch (e: Exception) { ByteArray(0) }
    if (bytes.isEmpty()) {
      busy.set(false)
      return
    }
    scope.launch(Dispatchers.Default) {
      try {
        val bmp = Images.decodeScaled(bytes, maxPx)?.asImageBitmap()
        if (bmp != null) withContext(Dispatchers.Main) { model.preview = bmp }
      } finally {
        busy.set(false)
      }
    }
  }
}
