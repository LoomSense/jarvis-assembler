package com.agj.android.vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.agj.android.core.Agq

/** Raw Camera2 lens pose (Android `LENS_POSE_*`), before any frame conversion. */
data class LensPose(
    /** metres, `LENS_POSE_TRANSLATION` */
    val translation: FloatArray,
    /** quaternion (x, y, z, w), `LENS_POSE_ROTATION` */
    val rotation: FloatArray,
    /** `LENS_POSE_REFERENCE` (null when absent) */
    val reference: Int?,
)

/** What [LiveCameraSource.discover] finds for one passthrough camera. */
data class PassthroughCameraInfo(
    val cameraId: String,
    /** 0 = left, 1 = right (`com.meta.extra_metadata.position`) */
    val position: Int?,
    /** `LENS_INTRINSIC_CALIBRATION` [fx, fy, cx, cy, s] scaled to [Intrinsics.width]×[Intrinsics.height] */
    val intrinsics: Intrinsics?,
    val lensPose: LensPose?,
    val jpegSizes: List<String>,
)

/**
 * The Quest 3 passthrough RGB camera through Camera2 (Meta Passthrough Camera API, Horizon OS v74+, permissions
 * `android.permission.CAMERA` + `horizonos.permission.HEADSET_CAMERA`).
 *
 * Phase 1: [discover] is real (camera ids, vendor tags, intrinsics, lens pose) and logged as `camera` at start-up;
 * [start] is a stub.
 *
 * TODO(phase 2, vision owner), following Meta's CameraDemo sample (Meta-Passthrough-Camera-API-Samples) and the
 * Spatial Scanner showcase `CameraController`:
 *  1. open the camera at `position == 1` (right; UNVERIFIED which eye is better for us) with an `ImageReader`
 *     (YUV_420_888 or JPEG, 1280×960; JPEG output is listed by the device per the showcase's comments);
 *  2. per image: JPEG bytes (768 px long edge for the server), [Intrinsics.scaledTo] the delivered size;
 *  3. pose: WORLD ← HEAD at the image's `SENSOR_TIMESTAMP` (Spatial SDK head pose; interpolate, the camera is 20–40 ms
 *     behind) × HEAD ← CAMERA from [LensPose]. The axis conversion is UNVERIFIED: the showcase uses
 *     `Quaternion(r[3], -r[0], -r[1], r[2]).inverse() * Quaternion(180°, 0, 0)` and `t = (x, y, -z)`; verify with a
 *     marker at a known place before trusting it.
 */
class LiveCameraSource(private val context: Context) : CameraSource {
  override val kind = "live"

  @Volatile
  override var intrinsics: Intrinsics? = null
    private set

  fun discover(): List<PassthroughCameraInfo> {
    val cm = context.getSystemService(CameraManager::class.java) ?: return emptyList()
    return cm.cameraIdList.mapNotNull { id ->
      val c = cm.getCameraCharacteristics(id)
      val source = c.get(KEY_SOURCE)
      if (source != null && source != CAMERA_SOURCE_PASSTHROUGH) return@mapNotNull null
      val array = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
      val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
      val intr =
          if (k != null && array != null && k.size >= 4)
              Intrinsics(k[0].toDouble(), k[1].toDouble(), k[2].toDouble(), k[3].toDouble(), array.width(), array.height())
          else null
      val t = c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
      val r = c.get(CameraCharacteristics.LENS_POSE_ROTATION)
      val pose = if (t != null && r != null) LensPose(t, r, c.get(CameraCharacteristics.LENS_POSE_REFERENCE)) else null
      val sizes =
          c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
              ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
              ?.map { "${it.width}x${it.height}" } ?: emptyList()
      PassthroughCameraInfo(id, c.get(KEY_POSITION), intr, pose, sizes)
    }
  }

  override fun start(listener: FrameListener) {
    val cams =
        try {
          discover()
        } catch (e: Exception) {
          Agq.log.error("camera", "discover: ${e.message}")
          emptyList()
        }
    intrinsics = cams.firstOrNull { it.position == 1 }?.intrinsics ?: cams.firstOrNull()?.intrinsics
    Agq.log.event(
        "camera",
        mapOf(
            "kind" to kind,
            "cameras" to
                cams.map {
                  mapOf(
                      "id" to it.cameraId,
                      "position" to it.position,
                      "intrinsics" to it.intrinsics?.let { i -> listOf(i.fx, i.fy, i.cx, i.cy, i.width, i.height) },
                      "lens_t" to it.lensPose?.translation,
                      "lens_q" to it.lensPose?.rotation,
                      "lens_ref" to it.lensPose?.reference,
                      "jpeg_sizes" to it.jpegSizes,
                  )
                },
            "capture" to "stub",
        ),
    )
  }

  override fun stop() {}

  companion object {
    const val KEY_CAMERA_SOURCE = "com.meta.extra_metadata.camera_source"
    const val KEY_CAMERA_POSITION = "com.meta.extra_metadata.position"
    const val CAMERA_SOURCE_PASSTHROUGH = 0
    val KEY_SOURCE = CameraCharacteristics.Key(KEY_CAMERA_SOURCE, Int::class.java)
    val KEY_POSITION = CameraCharacteristics.Key(KEY_CAMERA_POSITION, Int::class.java)
  }
}
