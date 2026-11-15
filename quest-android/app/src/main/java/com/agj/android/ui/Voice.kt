package com.agj.android.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.agj.android.core.Agq
import com.agj.android.net.ServerApi
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speaks guidance in the launch language (`el` | `en`). Server voice (`POST /api/tts` → WAV, Gemini TTS, the same
 * voice as the web client).
 *
 * Threading: [say] from any thread; it must not block (fetch and playback run on a background thread). A new [say]
 * with `interrupt = true` stops the current sentence.
 */
interface Voice {
  fun say(text: String, lang: String, interrupt: Boolean = true)

  /** Fetch [text] ahead of time so the next [say] starts without the TTS round trip. */
  fun prefetch(text: String, lang: String) {}

  /** Play a WAV the server already synthesized (e.g. `/api/voice` with speak: true); default: synthesize [text]. */
  fun play(wav: ByteArray, text: String, lang: String) = say(text, lang)

  fun stop()

  /** True while a sentence plays (for the HUD, and to avoid talking over a perception event). */
  val speaking: Boolean
}

/** No server: logs what would be said. */
class StubVoice : Voice {
  override fun say(text: String, lang: String, interrupt: Boolean) {
    Agq.log.event("voice", mapOf("lang" to lang, "text" to text, "stub" to true))
  }

  override fun stop() {}

  override val speaking = false
}

/**
 * [ServerApi.tts] → WAV file in [cacheDir] → [MediaPlayer]. Each line is fetched once (cache by text hash, also on
 * disk) and [prefetch] warms the next lines. Logs `voice` with `ms` (fetch latency, 0 when cached) and `error`.
 */
class ServerVoice(private val api: ServerApi, private val cacheDir: File) : Voice {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val pending = ConcurrentHashMap<String, Deferred<File?>>()
  private var player: MediaPlayer? = null
  private var current: Job? = null
  @Volatile private var playing = false

  override val speaking: Boolean
    get() = playing

  private fun key(text: String, lang: String): String {
    val d = MessageDigest.getInstance("SHA-1").digest("$lang|$text".toByteArray())
    return d.joinToString("") { "%02x".format(it) }.take(20)
  }

  private fun fetch(text: String, lang: String): Deferred<File?> {
    val k = key(text, lang)
    return pending.getOrPut(k) {
      scope.async {
        val f = File(cacheDir, "tts/$k.wav")
        if (f.exists() && f.length() > 44) return@async f
        try {
          val t0 = System.nanoTime()
          val wav = api.tts(text, lang)
          f.parentFile?.mkdirs()
          f.writeBytes(wav)
          Agq.log.event("voice_fetch", mapOf("lang" to lang, "bytes" to wav.size, "ms" to (System.nanoTime() - t0) / 1_000_000))
          f
        } catch (e: Exception) {
          pending.remove(k)
          Agq.log.error("voice", e.message)
          null
        }
      }
    }
  }

  override fun prefetch(text: String, lang: String) {
    if (text.isNotBlank()) fetch(text, lang)
  }

  override fun play(wav: ByteArray, text: String, lang: String) {
    if (wav.size <= 44) return say(text, lang)
    // the reply's WAV becomes the cached line, so a later Repeat of the same text is instant
    val f = File(cacheDir, "tts/${key(text, lang)}.wav")
    runCatching {
      f.parentFile?.mkdirs()
      f.writeBytes(wav)
    }
    pending.remove(key(text, lang))
    say(text, lang)
  }

  override fun say(text: String, lang: String, interrupt: Boolean) {
    if (text.isBlank()) return
    if (interrupt) stop()
    val t0 = System.nanoTime()
    current =
        scope.launch {
          val f = fetch(text, lang).await() ?: return@launch
          withContext(Dispatchers.Main) {
            try {
              player?.release()
              player =
                  MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(f.path)
                    setOnCompletionListener { playing = false }
                    prepare()
                    start()
                  }
              playing = true
              Agq.log.event(
                  "voice",
                  mapOf("lang" to lang, "text" to text, "ms" to (System.nanoTime() - t0) / 1_000_000),
              )
            } catch (e: Exception) {
              playing = false
              Agq.log.error("voice", e.message)
            }
          }
        }
  }

  override fun stop() {
    current?.cancel()
    runCatching { player?.stop() }
    playing = false
  }
}
