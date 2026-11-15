package com.agj.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Launch parameters, shared with the Unity app (same names, same meaning).
 *
 * Sources, lowest to highest priority: built-in defaults, `files/config.json` in the app's external files dir
 * (`/sdcard/Android/data/com.agj.android/files/config.json`), then intent extras
 * (`adb shell am start -n com.agj.android/.MainActivity --es server … --es key … --es job … --es lang el --ez replay true --ez hud true`).
 *
 * Pure Kotlin: no Android types, so it is unit-tested on the JVM. Thread-safe (immutable).
 *
 * @property server base URL of the guide server, without a trailing slash, e.g. `https://host.example`. Empty = not set.
 * @property key demo key sent as `X-Demo-Key`. Never logged in clear (see [toLogMap]).
 * @property job job id to open directly (e.g. `malm-drawer`); null = start with the look → identify flow.
 * @property lang `el` or `en`; anything else falls back to `en`.
 * @property replay true = read camera frames from the replay folder instead of the headset camera.
 * @property hud true = show the debug HUD panel.
 * @property reducedMotion true = no breathing, mover or travelling dash (XR_NATIVE_SPEC §7); extra `reduced_motion`.
 * @property markerless true (the default) = the front-panel detector (vision/PanelPose.kt) may snap the ghost onto
 *   the real drawer front; `--ez markerless false` switches it off.
 * @property dumpFrames true = record camera frames with everything the trackers were given (vision/FrameDump.kt) to
 *   `files/dump/<session>/` for the offline tools; extra `dump_frames`. Off by default.
 * @property dumpHz frames a second to record (extra `dump_hz`, `--ei dump_hz 3`; 0.2–30, default 3).
 * @property dumpMax frames at most in one recording (extra `dump_max`, `--ei dump_max 400`; 1–5000, default 400).
 */
data class Params(
    val server: String = "",
    val key: String = "",
    val job: String? = null,
    val lang: String = "en",
    val replay: Boolean = false,
    val hud: Boolean = false,
    val reducedMotion: Boolean = false,
    val markerless: Boolean = true,
    val dumpFrames: Boolean = false,
    val dumpHz: Double = 3.0,
    val dumpMax: Int = 400,
) {
  /** For the `start` log event: the key is reduced to whether it is set. */
  fun toLogMap(): Map<String, Any?> =
      mapOf(
          "server" to server,
          "key_set" to key.isNotEmpty(),
          "job" to job,
          "lang" to lang,
          "replay" to replay,
          "hud" to hud,
          "reduced_motion" to reducedMotion,
          "markerless" to markerless,
          "dump_frames" to dumpFrames,
          "dump_hz" to dumpHz,
          "dump_max" to dumpMax,
      )

  companion object {
    val KEYS =
        listOf("server", "key", "job", "lang", "replay", "hud", "reduced_motion", "markerless", "dump_frames", "dump_hz", "dump_max")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Merge [configJson] (may be null, empty or invalid: then ignored) with [extras] (intent extras as a map; values
     * may be String, Boolean or anything whose toString is meaningful). Extras win.
     */
    fun resolve(configJson: String?, extras: Map<String, Any?>): Params {
      val merged = HashMap<String, Any?>()
      parseConfig(configJson).forEach { (k, v) -> merged[k] = v }
      extras.forEach { (k, v) -> if (k in KEYS && v != null) merged[k] = v }
      return fromMap(merged)
    }

    /** config.json → flat map of the known keys. Never throws. */
    fun parseConfig(configJson: String?): Map<String, Any?> {
      if (configJson.isNullOrBlank()) return emptyMap()
      val obj: JsonObject =
          try {
            json.parseToJsonElement(configJson).jsonObject
          } catch (e: Exception) {
            return emptyMap()
          }
      return obj.filterKeys { it in KEYS }.mapValues { (_, v) -> primitive(v) }
    }

    private fun primitive(v: JsonElement): Any? {
      val p = v as? JsonPrimitive ?: return null
      if (!p.isString) p.booleanOrNull?.let { return it }
      return p.contentOrNull
    }

    fun fromMap(m: Map<String, Any?>): Params {
      val lang = str(m["lang"])?.lowercase()?.takeIf { it == "el" || it == "en" } ?: "en"
      return Params(
          server = str(m["server"])?.trimEnd('/') ?: "",
          key = str(m["key"]) ?: "",
          job = str(m["job"])?.takeIf { it.isNotBlank() },
          lang = lang,
          replay = bool(m["replay"]),
          hud = bool(m["hud"]),
          reducedMotion = bool(m["reduced_motion"]),
          markerless = m["markerless"]?.let { bool(it) } ?: true,
          dumpFrames = bool(m["dump_frames"]),
          dumpHz = number(m["dump_hz"])?.takeIf { it.isFinite() && it > 0 }?.coerceIn(0.2, 30.0) ?: 3.0,
          dumpMax = number(m["dump_max"])?.takeIf { it.isFinite() && it >= 1 }?.toInt()?.coerceIn(1, 5000) ?: 400,
      )
    }

    /** A number from an extra (`--ei` Int, `--ef` Float, `--es` String) or config.json; null when it is not one. */
    private fun number(v: Any?): Double? =
        when (v) {
          is Number -> v.toDouble()
          null -> null
          else -> v.toString().trim().toDoubleOrNull()
        }

    private fun str(v: Any?): String? = v?.toString()?.trim()

    /** Accepts true/false, "true"/"false", "1"/"0", "yes"/"no" (adb `--es replay true` passes a string). */
    fun bool(v: Any?): Boolean =
        when (v) {
          is Boolean -> v
          is Number -> v.toInt() != 0
          null -> false
          else -> v.toString().trim().lowercase() in setOf("true", "1", "yes", "on")
        }
  }
}
