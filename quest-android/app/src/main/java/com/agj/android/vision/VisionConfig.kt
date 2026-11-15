package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Vision tuning, so the first device runs can try the UNVERIFIED choices without a rebuild. Optional file
 * `/sdcard/Android/data/com.agj.android/files/vision.json` (any subset of the keys below); defaults otherwise.
 *
 * | key | default | |
 * |---|---|---|
 * | `camera_position` | 0 | passthrough camera: 0 left, 1 right (`com.meta.extra_metadata.position`) |
 * | `width`, `height` | 1280, 960 | YUV stream size (the nearest listed size is used) |
 * | `lens_convention` | `android` | `android`, `direct`, `forward` ([LensConvention]) |
 * | `head_time_offset_ms` | 0 | added to the capture time before looking up the head pose |
 * | `unknown_ts_latency_ms` | 35 | capture latency assumed when `SENSOR_INFO_TIMESTAMP_SOURCE` is UNKNOWN |
 * | `track_log_hz` | 10 | `track` events per second in live mode (replay logs every frame) |
 * | `jpeg_long_edge`, `jpeg_quality` | 768, 70 | server frames (perception hello.limits) |
 * | `subpixel`, `aruco3`, `use_distortion` | true, false, false | [DetectorOptions] |
 * | `marker_id`, `marker_size_m` | 0, 0.08 | marker.json |
 * | `marker_offset_m`, `marker_q` | MALM drawer front | ASSEMBLY ← MARKER override ([MarkerPlacement]) |
 * | `pos_min_cutoff_hz`, `pos_beta`, `rot_min_cutoff_hz`, `rot_beta` | 1, 20, 1, 2 | [OneEuroParams] |
 */
data class VisionConfig(
    val cameraPosition: Int = 0,
    val width: Int = 1280,
    val height: Int = 960,
    val lensConvention: LensConvention = LensConvention.ANDROID,
    val headTimeOffsetMs: Double = 0.0,
    val unknownTsLatencyMs: Double = 35.0,
    val trackLogHz: Double = 10.0,
    val jpegLongEdge: Int = 768,
    val jpegQuality: Int = 70,
    val detector: DetectorOptions = DetectorOptions(),
    val marker: MarkerConfig = MarkerConfig(),
    val filter: OneEuroParams = OneEuroParams(),
) {
  fun toLogMap(): Map<String, Any?> =
      mapOf(
          "camera_position" to cameraPosition,
          "size" to listOf(width, height),
          "lens_convention" to lensConvention.name.lowercase(),
          "head_time_offset_ms" to headTimeOffsetMs,
          "unknown_ts_latency_ms" to unknownTsLatencyMs,
          "track_log_hz" to trackLogHz,
          "jpeg" to listOf(jpegLongEdge, jpegQuality),
          "subpixel" to detector.subpixel,
          "aruco3" to detector.aruco3,
          "use_distortion" to detector.useDistortion,
          "marker_id" to marker.markerId,
          "marker_size_m" to marker.sizeM,
          "marker_offset_m" to marker.assemblyFromMarker.t,
          "marker_q" to marker.assemblyFromMarker.q.let { listOf(it.x, it.y, it.z, it.w) },
      )

  companion object {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Never throws: an invalid file gives the defaults. */
    fun parse(text: String?): VisionConfig {
      val d = VisionConfig()
      if (text.isNullOrBlank()) return d
      val o: JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return d
      fun num(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
      fun int(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
      fun bool(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
      fun arr(k: String) =
          o[k]?.let { runCatching { it.jsonArray.map { e -> e.jsonPrimitive.doubleOrNull!!.toFloat() } }.getOrNull() }
      val conv =
          o["lens_convention"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.let { s ->
            LensConvention.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
          }
      val off = arr("marker_offset_m")?.takeIf { it.size >= 3 }
      val mq = arr("marker_q")?.takeIf { it.size >= 4 }
      val place =
          Pose3(
              off?.let { Vec3(it[0], it[1], it[2]) } ?: d.marker.assemblyFromMarker.t,
              mq?.let { Quat(it[0], it[1], it[2], it[3]).normalized() } ?: d.marker.assemblyFromMarker.q,
          )
      return VisionConfig(
          cameraPosition = int("camera_position") ?: d.cameraPosition,
          width = int("width") ?: d.width,
          height = int("height") ?: d.height,
          lensConvention = conv ?: d.lensConvention,
          headTimeOffsetMs = num("head_time_offset_ms") ?: d.headTimeOffsetMs,
          unknownTsLatencyMs = num("unknown_ts_latency_ms") ?: d.unknownTsLatencyMs,
          trackLogHz = num("track_log_hz") ?: d.trackLogHz,
          jpegLongEdge = int("jpeg_long_edge") ?: d.jpegLongEdge,
          jpegQuality = int("jpeg_quality") ?: d.jpegQuality,
          detector =
              DetectorOptions(
                  subpixel = bool("subpixel") ?: d.detector.subpixel,
                  aruco3 = bool("aruco3") ?: d.detector.aruco3,
                  useDistortion = bool("use_distortion") ?: d.detector.useDistortion,
              ),
          marker =
              MarkerConfig(
                  markerId = int("marker_id") ?: d.marker.markerId,
                  sizeM = num("marker_size_m") ?: d.marker.sizeM,
                  assemblyFromMarker = place,
              ),
          filter =
              d.filter.copy(
                  posMinCutoffHz = num("pos_min_cutoff_hz") ?: d.filter.posMinCutoffHz,
                  posBeta = num("pos_beta") ?: d.filter.posBeta,
                  rotMinCutoffHz = num("rot_min_cutoff_hz") ?: d.filter.rotMinCutoffHz,
                  rotBeta = num("rot_beta") ?: d.filter.rotBeta,
              ),
      )
    }
  }
}
