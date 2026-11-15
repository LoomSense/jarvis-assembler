package com.agj.android.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Structured log, shared format with the Unity app: one JSON object per line on logcat tag `AGQ`.
 *
 * `{"t": <epoch ms>, "app": "android", "ev": "<event>", "t_rel": <ms since start>, ...fields}`; `t`, `app`, `ev`
 * always come first. `t` is epoch milliseconds (tools/quest/agq.py reads `t > 1e11` as epoch ms), advanced by the
 * monotonic clock from the wall time at start, so it never jumps backwards. Read it with
 * `adb logcat -s AGQ:I -v raw`.
 *
 * Pure Kotlin: [sink] receives finished lines (the Android sink is [Agq]); [clockMs] is a monotonic clock in ms,
 * [wallMs] the wall clock (epoch ms), read once.
 * Thread-safe: [event] only builds an immutable object and calls [sink], which must itself be thread-safe
 * (`android.util.Log` is).
 *
 * Non-finite floats (NaN, ±Inf) become `null` so every line stays valid JSON.
 */
class AgqLog(
    private val clockMs: () -> Long,
    private val sink: (String) -> Unit,
    private val app: String = "android",
    wallMs: () -> Long = { System.currentTimeMillis() },
) {
  private val t0 = clockMs()
  private val wall0 = wallMs()

  /** Build the line without emitting it (used by tests and by [event]). */
  fun line(ev: String, fields: Map<String, Any?> = emptyMap()): String {
    val m = LinkedHashMap<String, JsonElement>()
    val rel = clockMs() - t0
    m["t"] = JsonPrimitive(wall0 + rel)
    m["app"] = JsonPrimitive(app)
    m["ev"] = JsonPrimitive(ev)
    m["t_rel"] = JsonPrimitive(rel)
    for ((k, v) in fields) if (k !in RESERVED) m[k] = toJson(v)
    return JsonObject(m).toString()
  }

  fun event(ev: String, fields: Map<String, Any?> = emptyMap()) {
    sink(line(ev, fields))
  }

  // ---- the events every app must emit (names and fields are the shared contract) ----

  fun start(version: String, params: Params) =
      event("start", mapOf("version" to version, "params" to params.toLogMap()))

  fun jobLoaded(job: String, steps: Int, actions: Int, ms: Long) =
      event("job_loaded", mapOf("job" to job, "steps" to steps, "actions" to actions, "ms" to ms))

  fun identify(ms: Long, product: String?, confidence: Double?) =
      event("identify", mapOf("ms" to ms, "product" to product, "confidence" to confidence))

  fun locate(ms: Long, nextStep: Int?) = event("locate", mapOf("ms" to ms, "next_step" to nextStep))

  /** Every 1 s. [posM] world position of the assembly in metres, [yawDeg] about world +Y. */
  fun track(
      fps: Double,
      tracked: Boolean,
      posM: Vec3?,
      yawDeg: Double?,
      reprojPx: Double?,
      source: TrackSource,
  ) =
      event(
          "track",
          mapOf(
              "fps" to fps,
              "tracked" to tracked,
              "pos_m" to posM?.let { listOf(it.x, it.y, it.z) },
              "yaw_deg" to yawDeg,
              "reproj_px" to reprojPx,
              "source" to source.wire,
          ),
      )

  fun perception(event: String, ms: Long?) = event("perception", mapOf("event" to event, "ms" to ms))

  fun step(step: Int, action: Int, by: StepBy) =
      event("step", mapOf("step" to step, "action" to action, "by" to by.wire))

  /** Every 5 s. cpu/gpu frame times in ms when the platform exposes them, else null. */
  fun frame(renderFps: Double, cpuMs: Double?, gpuMs: Double?) =
      event("frame", mapOf("render_fps" to renderFps, "cpu_ms" to cpuMs, "gpu_ms" to gpuMs))

  fun error(where: String, message: String?) =
      event("error", mapOf("where" to where, "message" to (message ?: "")))

  companion object {
    const val TAG = "AGQ"
    private val RESERVED = setOf("t", "app", "ev", "t_rel")

    fun toJson(v: Any?): JsonElement =
        when (v) {
          null -> JsonNull
          is JsonElement -> v
          is Boolean -> JsonPrimitive(v)
          is Double -> if (v.isFinite()) JsonPrimitive(v) else JsonNull
          is Float -> if (v.isFinite()) JsonPrimitive(v) else JsonNull
          is Number -> JsonPrimitive(v)
          is String -> JsonPrimitive(v)
          is Enum<*> -> JsonPrimitive(v.name.lowercase())
          is Vec3 -> JsonArray(listOf(toJson(v.x), toJson(v.y), toJson(v.z)))
          is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
          is Iterable<*> -> JsonArray(v.map { toJson(it) })
          is Array<*> -> JsonArray(v.map { toJson(it) })
          is FloatArray -> JsonArray(v.map { toJson(it) })
          is DoubleArray -> JsonArray(v.map { toJson(it) })
          is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
          else -> JsonPrimitive(v.toString())
        }
  }
}

/** `track.source` values. */
enum class TrackSource(val wire: String) {
  MARKER("marker"),
  TOUCH("touch"),
  NONE("none"),
}

/** `step.by` values. */
enum class StepBy(val wire: String) {
  AUTO("auto"),
  BUTTON("button"),
}
