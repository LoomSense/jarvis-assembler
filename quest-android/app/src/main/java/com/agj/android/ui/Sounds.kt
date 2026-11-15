package com.agj.android.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.agj.android.core.Agq
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * UI and guidance sounds (XR_NATIVE_SPEC §6.2): one soft, glassy family (sine + a quiet octave partial, fast attack,
 * exponential decay), peak about −14 dBFS. Synthesised once at start, played on a single background thread.
 * Every sound has a visual twin elsewhere (never sound-only). Spatialisation: not yet (UNVERIFIED API).
 */
class Sounds {
  enum class Cue {
    TICK,
    PLACE,
    SUCCESS,
    STEP,
    WARNING,
    UNSURE,
    COMPLETE,
    LOST,
  }

  private val rate = 48_000
  private val exec = Executors.newSingleThreadExecutor()
  private val pcm: Map<Cue, ShortArray> =
      mapOf(
          Cue.TICK to click(),
          Cue.PLACE to notes(listOf(220.0), 0.06, decay = 40.0),
          Cue.SUCCESS to notes(listOf(1318.5, 1661.2), 0.175), // E6 → G♯6, 350 ms
          Cue.STEP to notes(listOf(1046.5, 1318.5, 1568.0), 0.2), // C6 E6 G6, 600 ms
          Cue.WARNING to notes(listOf(440.0, 415.3), 0.15, decay = 9.0), // A4 → G♯4, muted
          Cue.UNSURE to notes(listOf(880.0), 0.25, bend = 1.06),
          Cue.COMPLETE to notes(listOf(1046.5, 1318.5, 1568.0, 2093.0), 0.25, tail = 0.4), // 1.4 s
          Cue.LOST to notes(listOf(330.0), 0.2, decay = 10.0),
      )

  fun play(cue: Cue) {
    val data = pcm[cue] ?: return
    exec.execute {
      try {
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA) // captured by the headset's recorder (see ui/Voice.kt)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
        track.write(data, 0, data.size)
        track.play()
        Thread.sleep(data.size * 1000L / rate + 50)
        track.release()
      } catch (e: Exception) {
        Agq.log.error("sound", e.message)
      }
    }
  }

  private val peak = 10.0.pow(-14.0 / 20.0) // −14 dBFS

  private fun click(): ShortArray {
    val n = (rate * 0.025).toInt()
    return ShortArray(n) { i ->
      val t = i / rate.toDouble()
      val v = sin(2 * PI * 3000 * t) * exp(-t * 260) * 0.7 + sin(2 * PI * 2200 * t) * exp(-t * 300) * 0.3
      (v * peak * 0.8 * Short.MAX_VALUE).toInt().toShort()
    }
  }

  /** Notes one after another, each [each] seconds, the last one ringing out for [tail] more. */
  private fun notes(freqs: List<Double>, each: Double, decay: Double = 6.0, bend: Double = 1.0, tail: Double = 0.0): ShortArray {
    val total = each * freqs.size + tail + 0.05
    val n = (rate * total).toInt()
    val out = DoubleArray(n)
    freqs.forEachIndexed { k, f ->
      val start = (k * each * rate).toInt()
      val len = if (k == freqs.size - 1) n - start else ((each + 0.12) * rate).toInt()
      for (i in 0 until len) {
        val idx = start + i
        if (idx >= n) break
        val t = i / rate.toDouble()
        val ff = f * (1 + (bend - 1) * (t / (each + tail)).coerceAtMost(1.0))
        val env = (t / 0.004).coerceAtMost(1.0) * exp(-t * decay)
        out[idx] += (sin(2 * PI * ff * t) + 0.18 * sin(4 * PI * ff * t)) * env
      }
    }
    val max = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
    return ShortArray(n) { (out[it] / max * peak * Short.MAX_VALUE).toInt().toShort() }
  }
}
