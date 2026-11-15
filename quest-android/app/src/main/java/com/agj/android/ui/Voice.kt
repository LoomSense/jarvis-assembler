package com.agj.android.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.agj.android.core.Agq
import com.agj.android.net.ServerApi
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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

  /**
   * A step entry: [lines] in order (the no-marker coach line first, then the step line), spoken as one sentence
   * group so the second cannot cut the first. Blank lines are skipped.
   */
  fun sayStep(lines: List<String>, lang: String) = say(lines.filter { it.isNotBlank() }.joinToString(" "), lang)

  /** Fetch [text] ahead of time so the next [say] starts without the TTS round trip. */
  fun prefetch(text: String, lang: String) {}

  /**
   * LIVE-COACH: fetch every one of [texts] ahead of time, in the order given, playing none: what is cached already
   * is skipped and only a few requests run at a time. Never blocks.
   */
  fun prefetch(texts: List<String>, lang: String) = texts.forEach { prefetch(it, lang) }

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
 * [ServerApi.tts] → WAV file in [cacheDir] → [MediaPlayer]. Each line is fetched once (cache by language + text
 * hash, also on disk) and [prefetch] warms the next lines. Logs `voice` with `ms` (fetch latency, 0 when cached),
 * `error`, and `tts_prefetch` for a list fetched ahead. [stop] also drops every line still waiting for its audio, so
 * a sentence that has become stale is never heard late.
 */
class ServerVoice(private val api: ServerApi, private val cacheDir: File) : Voice {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val pending = ConcurrentHashMap<String, Deferred<File?>>()
  private var player: MediaPlayer? = null
  private var current: Job? = null
  // LIVE-COACH: every line said and not yet playing (its audio is still being fetched): [stop] cancels all of them
  private val waiting = ConcurrentHashMap.newKeySet<Job>()
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

  private fun cached(text: String, lang: String): Boolean = File(cacheDir, "tts/${key(text, lang)}.wav").let { it.exists() && it.length() > 44 }

  /**
   * LIVE-COACH: the list in order, [PREFETCH_PARALLEL] requests at a time, nothing played. A line asked for by [say]
   * meanwhile is fetched at once by its own request. Stops early when the server does not answer (three failures
   * and nothing fetched). AGQ `tts_prefetch` when it ends: `count`, `cached` (already on disk), `fetched`, `failed`, `ms`.
   */
  override fun prefetch(texts: List<String>, lang: String) {
    val list = texts.filter { it.isNotBlank() }.distinct()
    if (list.isEmpty()) return
    scope.launch {
      val t0 = System.nanoTime()
      val cached = AtomicInteger()
      val fetched = AtomicInteger()
      val failed = AtomicInteger()
      val gate = Semaphore(PREFETCH_PARALLEL)
      coroutineScope {
        for (t in list) {
          if (cached(t, lang)) {
            cached.incrementAndGet()
            continue
          }
          if (failed.get() >= 3 && fetched.get() == 0) break
          gate.acquire() // the loop itself waits here: the lines are fetched in the order they will be needed
          launch {
            try {
              if (fetch(t, lang).await() != null) fetched.incrementAndGet() else failed.incrementAndGet()
            } finally {
              gate.release()
            }
          }
        }
      }
      Agq.log.event(
          "tts_prefetch",
          mapOf("count" to list.size, "cached" to cached.get(), "fetched" to fetched.get(), "failed" to failed.get(),
              "ms" to (System.nanoTime() - t0) / 1_000_000, "lang" to lang),
      )
    }
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
    val job =
        scope.launch {
          val f = fetch(text, lang).await() ?: return@launch
          withContext(Dispatchers.Main) {
            try {
              player?.release()
              player =
                  MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            // MEDIA: the headset's own recorder (and casting) capture only media, game and unknown
                            // audio; as "navigation guidance" the voice was missing from every recording
                            .setUsage(AudioAttributes.USAGE_MEDIA)
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
    current = job
    waiting += job
    job.invokeOnCompletion { waiting.remove(job) }
  }

  override fun stop() {
    current?.cancel()
    // LIVE-COACH: also the lines said without interrupting that are still waiting for their audio
    for (j in waiting) j.cancel()
    waiting.clear()
    runCatching { player?.stop() }
    playing = false
  }

  companion object {
    /** Speech requests a list prefetch runs at a time. */
    const val PREFETCH_PARALLEL = 2
  }
}
