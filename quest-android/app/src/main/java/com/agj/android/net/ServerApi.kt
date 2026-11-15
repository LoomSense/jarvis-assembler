package com.agj.android.net

import android.util.Base64
import com.agj.android.guide.GuideJson
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** `POST /api/identify` answer (server/identify.py). Only the fields the app uses; the rest is ignored. */
@Serializable
data class IdentifyResult(
    val match: IdentifyMatch? = null,
    val query: String? = null,
    val confidence: Double? = null,
    val message: String? = null,
    val latency_ms: Long? = null,
)

@Serializable data class IdentifyMatch(val job_id: String, val product: String = "", val ready: Boolean = false)

/** `POST /api/catalog/open {q}` answer. */
@Serializable data class CatalogOpen(val job_id: String, val ready: Boolean = false, val product: String? = null)

/** `GET /api/jobs/{id}`: build status. Kept loose; `status == "done"` means the guide is ready. */
@Serializable
data class JobStatus(val id: String? = null, val status: String? = null, val progress: Double? = null)

/** `POST /api/perception/{job}/locate` answer. */
@Serializable
data class LocateResult(
    val next_step: Int? = null,
    val done_through: Int? = null,
    val message: String? = null,
    val confidence: Double? = null,
)

/** One `found` item of `POST /api/perception/{job}/parts` (server/perception/README.md). */
@Serializable
data class PartsFound(
    val id: String,
    /** "part" or "hardware" */
    val kind: String = "",
    /** Gemini convention: [ymin, xmin, ymax, xmax], 0–1000, normalised to the picture sent. */
    val box_2d: List<Double> = emptyList(),
    val confidence: Double = 0.0,
    /** Short name in the request's `lang` (hardware: "Name · code"). */
    val label: String = "",
)

/** `POST /api/perception/{job}/parts` answer. */
@Serializable
data class PartsResult(
    val found: List<PartsFound> = emptyList(),
    val missing: List<String> = emptyList(),
    val message: String? = null,
    val step: Int? = null,
    val latency_ms: Long? = null,
    val model: String? = null,
    val simulated: Boolean = false,
)

/**
 * The guide server's REST API (docs/HANDOFF.md §2.1). Every call sends the demo key in `X-Demo-Key`.
 *
 * Images are JPEG bytes (768 px long edge, quality ~0.7 is what the server expects); they are base64-encoded here.
 * Threading: all functions are `suspend` and switch to `Dispatchers.IO` themselves; safe to call from any coroutine.
 * Errors: non-2xx responses and network failures throw [IOException] with the status in the message.
 */
interface ServerApi {
  /** `GET /healthz` reachable and answering 2xx. Never throws. */
  suspend fun healthy(): Boolean

  suspend fun identify(jpeg: ByteArray, lang: String): IdentifyResult

  suspend fun catalogOpen(query: String): CatalogOpen

  suspend fun job(jobId: String): JobStatus

  /** `GET /files/{job}/{path}`: raw bytes (manifest.json, walkthrough.glb, crops/step-NN.png …). */
  suspend fun file(jobId: String, path: String): ByteArray

  suspend fun locate(jobId: String, jpeg: ByteArray, lang: String): LocateResult

  /** `POST /api/tts {text}` → WAV bytes. */
  suspend fun tts(text: String, lang: String): ByteArray

  /**
   * `POST /api/perception/{job}/parts`: where the [want]ed part ids / hardware codes lie in [jpeg] (send the long
   * edge at 1280 px for small hardware). Default: unsupported (test fakes).
   */
  suspend fun parts(jobId: String, jpeg: ByteArray, want: List<String>, step: Int?, lang: String): PartsResult =
      throw UnsupportedOperationException("parts")
}

class HttpServerApi(
    private val baseUrl: String,
    private val key: String,
    private val client: OkHttpClient = defaultClient(),
) : ServerApi {
  private val jsonType = "application/json".toMediaType()

  private fun req(path: String) = Request.Builder().url(baseUrl + path).header("X-Demo-Key", key)

  private suspend fun call(r: Request): ByteArray =
      withContext(Dispatchers.IO) {
        client.newCall(r).execute().use { res ->
          if (!res.isSuccessful) throw IOException("HTTP ${res.code} ${r.method} ${r.url.encodedPath}")
          res.body?.bytes() ?: ByteArray(0)
        }
      }

  private suspend fun postJson(path: String, body: JsonObject): ByteArray =
      call(req(path).post(body.toString().toRequestBody(jsonType)).build())

  private fun b64(jpeg: ByteArray) = Base64.encodeToString(jpeg, Base64.NO_WRAP)

  override suspend fun healthy(): Boolean =
      try {
        call(req("/healthz").get().build())
        true
      } catch (e: Exception) {
        false
      }

  override suspend fun identify(jpeg: ByteArray, lang: String): IdentifyResult {
    val body = buildJsonObject {
      put("image", b64(jpeg))
      put("lang", lang)
    }
    return GuideJson.decodeFromString(IdentifyResult.serializer(), postJson("/api/identify", body).decodeToString())
  }

  override suspend fun catalogOpen(query: String): CatalogOpen {
    val body = buildJsonObject { put("q", query) }
    return GuideJson.decodeFromString(CatalogOpen.serializer(), postJson("/api/catalog/open", body).decodeToString())
  }

  override suspend fun job(jobId: String): JobStatus =
      GuideJson.decodeFromString(JobStatus.serializer(), call(req("/api/jobs/$jobId").get().build()).decodeToString())

  override suspend fun file(jobId: String, path: String): ByteArray = call(req("/files/$jobId/$path").get().build())

  override suspend fun locate(jobId: String, jpeg: ByteArray, lang: String): LocateResult {
    val body = buildJsonObject {
      put("image", b64(jpeg))
      put("lang", lang)
    }
    val raw = postJson("/api/perception/$jobId/locate", body).decodeToString()
    return GuideJson.decodeFromString(LocateResult.serializer(), raw)
  }

  override suspend fun parts(jobId: String, jpeg: ByteArray, want: List<String>, step: Int?, lang: String): PartsResult {
    val body = buildJsonObject {
      put("image", b64(jpeg))
      if (want.isNotEmpty()) put("want", JsonArray(want.take(12).map { JsonPrimitive(it) }))
      if (step != null) put("step", step)
      put("lang", if (lang == "el") "el" else "en")
    }
    val raw = postJson("/api/perception/$jobId/parts", body).decodeToString()
    return GuideJson.decodeFromString(PartsResult.serializer(), raw)
  }

  override suspend fun tts(text: String, lang: String): ByteArray {
    val body = buildJsonObject {
      put("text", text)
      put("lang", lang)
    }
    return postJson("/api/tts", body)
  }

  companion object {
    fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // identify takes 3–4 s, a GLB can be large
            .build()
  }
}
