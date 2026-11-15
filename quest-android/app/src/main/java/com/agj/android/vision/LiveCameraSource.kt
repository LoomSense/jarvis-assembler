package com.agj.android.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Raw Camera2 lens pose (Android `LENS_POSE_*`), before any frame conversion. */
data class LensPose(
    /** metres, `LENS_POSE_TRANSLATION` */
    val translation: FloatArray,
    /** quaternion (x, y, z, w), `LENS_POSE_ROTATION` */
    val rotation: FloatArray,
    /** `LENS_POSE_REFERENCE` (null when absent): 0 primary camera, 1 gyroscope, 2 undefined, 3 automotive */
    val reference: Int?,
)

/** What [LiveCameraSource.discover] finds for one passthrough camera. */
data class PassthroughCameraInfo(
    val cameraId: String,
    /** 0 = left, 1 = right (`com.meta.extra_metadata.position`) */
    val position: Int?,
    /** `LENS_INTRINSIC_CALIBRATION` [fx, fy, cx, cy, s] in pixels of the pre-correction array */
    val intrinsicsRaw: FloatArray?,
    /** pre-correction active array size [w, h] */
    val sensorSize: IntArray?,
    val lensPose: LensPose?,
    /** `LENS_DISTORTION` raw (Android order) */
    val distortionRaw: FloatArray?,
    /** `SENSOR_INFO_TIMESTAMP_SOURCE`: 0 unknown, 1 realtime (elapsedRealtimeNanos) */
    val timestampSource: Int?,
    val facing: Int?,
    val yuvSizes: List<String>,
    val jpegSizes: List<String>,
)

/**
 * The Quest 3 passthrough RGB camera through Camera2 (Meta Passthrough Camera API, Horizon OS v74+, permissions
 * `android.permission.CAMERA` + `horizonos.permission.HEADSET_CAMERA`), following Meta's Spatial Scanner showcase
 * (`CameraController`: YUV_420_888 ImageReader, `TEMPLATE_PREVIEW`, latest image only).
 *
 * Per frame: the Y plane → [GrayImage] for tracking (no colour conversion), a lazy JPEG (long edge
 * [VisionConfig.jpegLongEdge], quality [VisionConfig.jpegQuality]) for the server, intrinsics scaled to the stream,
 * and WORLD ← CAMERA = [heads] at the capture time × HEAD ← CAMERA ([Frames.headFromCamera]).
 *
 * **Buffer lifetime:** [CameraFrame.gray] and the lazy [CameraFrame.jpeg] use pooled buffers that are valid only
 * inside [FrameListener.onFrame]. Read `frame.jpeg` there if it is needed later (VisionModule does).
 *
 * Logs one `camera` event at start with everything the device reports (ids, positions, raw and stream intrinsics,
 * distortion, lens pose and reference, timestamp source, sizes, chosen camera, config) and one `camera_frame` event
 * for the first frame (capture age, head-pose availability, the camera pose).
 */
class LiveCameraSource(
    private val context: Context,
    private val heads: HeadPoseBuffer? = null,
    private val config: VisionConfig = VisionConfig(),
) : CameraSource {
  override val kind = "live"

  @Volatile
  override var intrinsics: Intrinsics? = null
    private set

  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private val busy = AtomicBoolean(false)
  private var index = 0L
  private var firstLogged = false
  private var poseLogged = false
  private var framesWithoutPose = 0

  // pooled plane copies (frames are processed one at a time on the camera thread)
  private var yBuf = ByteArray(0)
  private var uBuf = ByteArray(0)
  private var vBuf = ByteArray(0)

  fun discover(): List<PassthroughCameraInfo> {
    val cm = context.getSystemService(CameraManager::class.java) ?: return emptyList()
    return cm.cameraIdList.mapNotNull { id ->
      val c = cm.getCameraCharacteristics(id)
      val source = runCatching { c.get(KEY_SOURCE) }.getOrNull()
      if (source != null && source != CAMERA_SOURCE_PASSTHROUGH) return@mapNotNull null
      val array = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
      val t = c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
      val r = c.get(CameraCharacteristics.LENS_POSE_ROTATION)
      val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      PassthroughCameraInfo(
          cameraId = id,
          position = runCatching { c.get(KEY_POSITION) }.getOrNull(),
          intrinsicsRaw = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
          sensorSize = array?.let { intArrayOf(it.width(), it.height()) },
          lensPose = if (t != null && r != null) LensPose(t, r, c.get(CameraCharacteristics.LENS_POSE_REFERENCE)) else null,
          distortionRaw = c.get(CameraCharacteristics.LENS_DISTORTION),
          timestampSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
          facing = c.get(CameraCharacteristics.LENS_FACING),
          yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { "${it.width}x${it.height}" } ?: emptyList(),
          jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.map { "${it.width}x${it.height}" } ?: emptyList(),
      )
    }
  }

  @SuppressLint("MissingPermission")
  override fun start(listener: FrameListener) {
    stop()
    val cams =
        try {
          discover()
        } catch (e: Exception) {
          Agq.log.error("camera", "discover: ${e.message}")
          emptyList()
        }
    val cam = cams.firstOrNull { it.position == config.cameraPosition } ?: cams.firstOrNull()
    val size = cam?.let { pickSize(it.yuvSizes) }
    val k = cam?.intrinsicsRaw
    val sensor = cam?.sensorSize
    val dist = Yuv.androidToOpenCvDistortion(cam?.distortionRaw)
    val intr =
        if (k != null && sensor != null && size != null && k.size >= 4)
            Yuv.streamIntrinsics(k, sensor[0], sensor[1], size.first, size.second, dist)
        else null
    intrinsics = intr
    val headFromCamera =
        cam?.lensPose?.let { Frames.headFromCamera(it.translation, it.rotation, config.lensConvention) }
    logCameraEvent(cams, cam, size, intr, headFromCamera)
    if (cam == null || size == null || intr == null) {
      Agq.log.error("camera", if (cam == null) "no passthrough camera" else "no YUV size or intrinsics for ${cam.cameraId}")
      return
    }

    val th = HandlerThread("agj-camera").apply { start() }
    val h = Handler(th.looper)
    thread = th
    handler = h
    val r = ImageReader.newInstance(size.first, size.second, ImageFormat.YUV_420_888, 3)
    reader = r
    r.setOnImageAvailableListener(
        { rd ->
          val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
          if (!busy.compareAndSet(false, true)) {
            img.close()
            return@setOnImageAvailableListener
          }
          try {
            onImage(img, cam, intr, headFromCamera, listener)
          } catch (e: Exception) {
            Agq.log.error("camera", "frame: ${e.message}")
          } finally {
            img.close()
            busy.set(false)
          }
        },
        h,
    )
    val cm = context.getSystemService(CameraManager::class.java)
    try {
      cm.openCamera(
          cam.cameraId,
          object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
              device = d
              createSession(d, r, h)
            }

            override fun onDisconnected(d: CameraDevice) {
              Agq.log.error("camera", "disconnected ${cam.cameraId}")
              d.close()
            }

            override fun onError(d: CameraDevice, error: Int) {
              Agq.log.error("camera", "open ${cam.cameraId}: error $error")
              d.close()
            }
          },
          h,
      )
    } catch (e: SecurityException) {
      Agq.log.error("camera", "permission: ${e.message}")
    } catch (e: Exception) {
      Agq.log.error("camera", "open: ${e.message}")
    }
  }

  private fun createSession(d: CameraDevice, r: ImageReader, h: Handler) {
    val exec = Executor { h.post(it) }
    val cfg =
        SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(r.surface)),
            exec,
            object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }.build()
                try {
                  s.setRepeatingRequest(req, null, h)
                } catch (e: Exception) {
                  Agq.log.error("camera", "repeating request: ${e.message}")
                }
              }

              override fun onConfigureFailed(s: CameraCaptureSession) {
                Agq.log.error("camera", "session configure failed")
              }
            },
        )
    try {
      d.createCaptureSession(cfg)
    } catch (e: Exception) {
      Agq.log.error("camera", "session: ${e.message}")
    }
  }

  private fun onImage(
      img: android.media.Image,
      cam: PassthroughCameraInfo,
      intr: Intrinsics,
      headFromCamera: Pose3?,
      listener: FrameListener,
  ) {
    val nowNs = SystemClock.elapsedRealtimeNanos()
    val w = img.width
    val h = img.height
    val planes = img.planes
    val yp = planes[0]
    val yRow = yp.rowStride
    // the last row of a plane buffer may stop at `width`: size the copy to whole rows for the OpenCV Mat
    yBuf = copy(yp.buffer, yBuf, yRow * h)
    uBuf = copy(planes[1].buffer, uBuf)
    vBuf = copy(planes[2].buffer, vBuf)
    val uvRow = planes[1].rowStride
    val uvPix = planes[1].pixelStride
    // capture time on the elapsedRealtimeNanos clock
    val captureNs =
        if (cam.timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) img.timestamp
        else nowNs - (config.unknownTsLatencyMs * 1e6).toLong()
    val lookup = captureNs + (config.headTimeOffsetMs * 1e6).toLong()
    val head = heads?.at(lookup)
    val world = if (head != null && headFromCamera != null) head * headFromCamera else null
    val y = yBuf
    val u = uBuf
    val v = vBuf
    val valid = AtomicBoolean(true)
    val (ow, oh) = Yuv.fitLongEdge(w, h, config.jpegLongEdge)
    val frame =
        CameraFrame(
            index++,
            null,
            w,
            h,
            captureNs,
            intr.scaledTo(w, h),
            world,
            gray = GrayImage(y, w, h, yRow),
            encode = {
              if (!valid.get()) {
                Agq.log.error("camera", "frame.jpeg read after onFrame returned")
                ByteArray(0)
              } else {
                val nv21 = Yuv.toNv21Scaled(y, yRow, u, v, uvRow, uvPix, w, h, ow, oh)
                val out = ByteArrayOutputStream(64 * 1024)
                YuvImage(nv21, ImageFormat.NV21, ow, oh, null).compressToJpeg(Rect(0, 0, ow, oh), config.jpegQuality, out)
                out.toByteArray()
              }
            },
        )
    // The first frame often comes before the head-pose buffer has samples: log when the camera pose first exists
    // (or that it never does), since marker tracking needs it.
    if (!poseLogged) {
      if (world != null) {
        poseLogged = true
        Agq.log.event("camera_pose", mapOf("frame" to index - 1, "frames_without_pose" to framesWithoutPose,
            "head_samples" to (heads?.size() ?: 0), "cam_p" to world.t, "head_p" to head?.t))
      } else if (++framesWithoutPose == 300) {
        Agq.log.error("camera_pose", "no head pose for the first 300 frames (head_samples ${heads?.size() ?: 0}, " +
            "lens pose ${headFromCamera != null})")
      }
    }
    if (!firstLogged) {
      firstLogged = true
      Agq.log.event(
          "camera_frame",
          mapOf(
              "size" to listOf(w, h),
              "y_row_stride" to yRow,
              "uv_row_stride" to uvRow,
              "uv_pixel_stride" to uvPix,
              "sensor_ts_ns" to img.timestamp,
              "now_ns" to nowNs,
              "age_ms" to (nowNs - img.timestamp) / 1e6,
              "ts_source" to cam.timestampSource,
              "head_samples" to (heads?.size() ?: 0),
              "head_newest_age_ms" to heads?.newestNs()?.let { (nowNs - it) / 1e6 },
              "head_found" to (head != null),
              "cam_p" to world?.t,
              "cam_q" to world?.q?.let { listOf(it.x, it.y, it.z, it.w) },
          ),
      )
    }
    try {
      listener.onFrame(frame)
    } finally {
      valid.set(false)
    }
  }

  private fun copy(src: java.nio.ByteBuffer, dst: ByteArray, minSize: Int = 0): ByteArray {
    val b = src.duplicate()
    b.rewind()
    val n = b.remaining()
    val size = maxOf(n, minSize)
    val out = if (dst.size == size) dst else ByteArray(size)
    b.get(out, 0, n)
    return out
  }

  private fun pickSize(sizes: List<String>): Pair<Int, Int>? {
    val all = sizes.mapNotNull { s -> s.split("x").takeIf { it.size == 2 }?.let { it[0].toInt() to it[1].toInt() } }
    if (all.isEmpty()) return null
    return all.firstOrNull { it.first == config.width && it.second == config.height }
        ?: all.minBy { kotlin.math.abs(it.first * it.second - config.width * config.height) }
  }

  private fun logCameraEvent(
      cams: List<PassthroughCameraInfo>,
      cam: PassthroughCameraInfo?,
      size: Pair<Int, Int>?,
      intr: Intrinsics?,
      headFromCamera: Pose3?,
  ) {
    Agq.log.event(
        "camera",
        mapOf(
            "kind" to kind,
            "cameras" to
                cams.map {
                  mapOf(
                      "id" to it.cameraId,
                      "position" to it.position,
                      "facing" to it.facing,
                      "k_raw" to it.intrinsicsRaw,
                      "sensor" to it.sensorSize,
                      "dist_raw" to it.distortionRaw,
                      "lens_t" to it.lensPose?.translation,
                      "lens_q" to it.lensPose?.rotation,
                      "lens_ref" to it.lensPose?.reference,
                      "ts_source" to it.timestampSource,
                      "yuv_sizes" to it.yuvSizes,
                      "jpeg_sizes" to it.jpegSizes,
                  )
                },
            "chosen" to cam?.cameraId,
            "position" to cam?.position,
            "size" to size?.let { listOf(it.first, it.second) },
            "intrinsics" to intr?.let { listOf(it.fx, it.fy, it.cx, it.cy, it.width, it.height) },
            "distortion" to intr?.distortion,
            "head_from_cam_t" to headFromCamera?.t,
            "head_from_cam_q" to headFromCamera?.q?.let { listOf(it.x, it.y, it.z, it.w) },
            "head_pose" to (heads != null),
            "config" to config.toLogMap(),
            "capture" to if (cam != null && size != null && intr != null) "yuv" else "none",
        ),
    )
  }

  override fun stop() {
    runCatching { session?.close() }
    runCatching { device?.close() }
    runCatching { reader?.close() }
    session = null
    device = null
    reader = null
    thread?.quitSafely()
    thread = null
    handler = null
  }

  companion object {
    const val KEY_CAMERA_SOURCE = "com.meta.extra_metadata.camera_source"
    const val KEY_CAMERA_POSITION = "com.meta.extra_metadata.position"
    const val CAMERA_SOURCE_PASSTHROUGH = 0
    val KEY_SOURCE = CameraCharacteristics.Key(KEY_CAMERA_SOURCE, Int::class.java)
    val KEY_POSITION = CameraCharacteristics.Key(KEY_CAMERA_POSITION, Int::class.java)
  }
}
