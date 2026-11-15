package com.agj.android.ui

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.agj.android.core.Agq
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/*
 * Voice commands (R7, XR_NATIVE_SPEC §5.3): push-to-talk on the toolbar mic, 16 kHz mono PCM16 → WAV →
 * `POST /api/voice/{job}` (server/perception/README.md "Voice endpoint") → intent + `target {step, action}` + `answer`
 * (+ `tts` base64 WAV with `speak: true`). Pure parts (WAV, request JSON, reply parsing) are JVM-tested.
 */

/** PCM16 little-endian mono → a 44-byte-header WAV. */
object Wav {
  fun fromPcm16(pcm: ShortArray, rate: Int = 16_000): ByteArray {
    val data = pcm.size * 2
    val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    b.put("data".toByteArray()).putInt(data)
    for (s in pcm) b.putShort(s)
    return b.array()
  }

  /** RMS of a PCM16 block, 0..1. */
  fun rms(pcm: ShortArray, n: Int = pcm.size): Float {
    if (n <= 0) return 0f
    var acc = 0.0
    for (i in 0 until n) {
      val v = pcm[i] / 32768.0
      acc += v * v
    }
    return sqrt(acc / n).toFloat()
  }
}

@Serializable data class VoiceTarget(val step: Int, val action: Int = 0)

/** The server's reply (fields not used by the headset are ignored). */
@Serializable
data class VoiceReply(
    val transcript: String = "",
    val intent: String = "none",
    val answer: String = "",
    val target: VoiceTarget? = null,
    val tts: String? = null,
    val tts_ms: Long? = null,
    val latency_ms: Long? = null,
    val model: String? = null,
    val simulated: Boolean = false,
) {
  /** The synthesized answer, decoded (null without `speak: true` or when synthesis failed). */
  fun ttsWav(): ByteArray? = tts?.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

  /** `next` / `back` / `repeat` / `where_am_i` (the app moves or re-reads); `ask` answers; `none` says nothing. */
  val isCommand: Boolean
    get() = intent in setOf("next", "back", "repeat", "where_am_i")
}

class VoiceHttpException(val code: Int, message: String) : Exception(message)

/** `POST /api/voice/{job}` with the demo key (`X-Demo-Key`, like [com.agj.android.net.HttpServerApi]). */
class VoiceCommandClient(
    baseUrl: String,
    private val key: String,
    private val client: OkHttpClient =
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build(),
) {
  private val base = baseUrl.trimEnd('/')

  companion object {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    /** The request body (pure): `{audio (base64 WAV), mime, lang, step, action, speak, rate}`. */
    fun requestJson(wav: ByteArray, lang: String, step: Int, action: Int, speak: Boolean = true): JsonObject = buildJsonObject {
      put("audio", Base64.getEncoder().encodeToString(wav))
      put("mime", "audio/wav")
      put("lang", if (lang == "el") "el" else "en")
      put("step", step)
      put("action", action)
      put("speak", speak)
      put("rate", 16_000)
    }

    /** Typed input (tests, simulate): `{text, lang, step, action}`. */
    fun textJson(text: String, lang: String, step: Int, action: Int, speak: Boolean = false): JsonObject = buildJsonObject {
      put("text", text)
      put("lang", if (lang == "el") "el" else "en")
      put("step", step)
      put("action", action)
      put("speak", speak)
    }

    fun parse(body: String): VoiceReply = json.decodeFromString(VoiceReply.serializer(), body)
  }

  suspend fun send(job: String, body: JsonObject): VoiceReply {
    val r =
        Request.Builder()
            .url("$base/api/voice/$job")
            .header("X-Demo-Key", key)
            .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
    val text = call(r)
    return parse(text)
  }

  suspend fun audio(job: String, wav: ByteArray, lang: String, step: Int, action: Int, speak: Boolean = true) =
      send(job, requestJson(wav, lang, step, action, speak))

  suspend fun text(job: String, text: String, lang: String, step: Int, action: Int, speak: Boolean = false) =
      send(job, textJson(text, lang, step, action, speak))

  private suspend fun call(r: Request): String = suspendCancellableCoroutine { cont ->
    val c = client.newCall(r)
    cont.invokeOnCancellation { c.cancel() }
    c.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: java.io.IOException) {
            if (cont.isActive) cont.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            response.use {
              val body = it.body?.string() ?: ""
              if (!it.isSuccessful) cont.resumeWithException(VoiceHttpException(it.code, "voice ${it.code}: ${body.take(200)}"))
              else cont.resume(body)
            }
          }
        }
    )
  }
}

/**
 * 16 kHz mono PCM16 capture with [AudioRecord] (RECORD_AUDIO must be granted). [start] begins on a background thread;
 * [stop] returns the samples. Stops by itself after [maxMs], or after [silenceMs] of quiet once speech was heard
 * (then [onAutoStop] runs on the recording thread with the samples).
 */
class MicRecorder(
    private val maxMs: Int = 7000,
    private val silenceMs: Int = 1200,
    private val speechRms: Float = 0.02f,
) {
  @Volatile private var running = false
  private var thread: Thread? = null
  private val out = ByteArrayOutputStream()
  @Volatile var level = 0f
    private set
  var onAutoStop: ((ShortArray) -> Unit)? = null

  val recording: Boolean
    get() = running

  @SuppressLint("MissingPermission")
  fun start(): Boolean {
    if (running) return true
    val rate = 16_000
    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec =
        try {
          AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (e: Exception) {
          Agq.log.error("mic", e.message)
          return false
        }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      Agq.log.error("mic", "AudioRecord not initialized (permission?)")
      rec.release()
      return false
    }
    synchronized(out) { out.reset() }
    running = true
    thread =
        Thread({
              val buf = ShortArray(rate / 20) // 50 ms
              var heard = false
              var quietMs = 0
              var totalMs = 0
              var auto = false
              try {
                rec.startRecording()
                while (running) {
                  val n = rec.read(buf, 0, buf.size)
                  if (n <= 0) continue
                  val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                  for (i in 0 until n) bytes.putShort(buf[i])
                  synchronized(out) { out.write(bytes.array()) }
                  level = Wav.rms(buf, n)
                  val ms = n * 1000 / rate
                  totalMs += ms
                  if (level > speechRms) {
                    heard = true
                    quietMs = 0
                  } else quietMs += ms
                  if (totalMs >= maxMs || (heard && quietMs >= silenceMs)) {
                    auto = true
                    break
                  }
                }
              } catch (e: Exception) {
                Agq.log.error("mic", e.message)
              } finally {
                runCatching { rec.stop() }
                rec.release()
                level = 0f
              }
              if (auto && running) {
                running = false
                onAutoStop?.invoke(samples())
              }
            }, "agq-mic")
            .also { it.start() }
    return true
  }

  /** Stop and return what was recorded (empty if nothing). */
  fun stop(): ShortArray {
    if (!running) return samples()
    running = false
    thread?.join(300)
    return samples()
  }

  private fun samples(): ShortArray {
    val b = synchronized(out) { out.toByteArray() }
    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(b.size / 2) { bb.short }
  }
}
