package com.agj.android.vision

import android.os.SystemClock
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.JointType
import com.meta.spatial.runtime.SkeletonJoint
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spatial SDK system (runs once per render frame on the render thread) that feeds the vision package:
 *  - head pose: `scene.getViewerPose()` → [HeadPoseBuffer] stamped with `SystemClock.elapsedRealtimeNanos()` (the
 *    clock of Camera2 REALTIME timestamps). Whether the viewer pose is the pose *now* or predicted for display time
 *    is UNVERIFIED; `head_time_offset_ms` in vision.json compensates.
 *  - hands (every other frame): first the body-tracking skeleton (`scene.updateBodyTrackingBuffersAtTime`, whose
 *    joint set includes `*_HAND_WRIST` and `*_HAND_INDEX_TIP`; UNVERIFIED that it delivers hand-tracking joints on
 *    Quest 3 without body-tracking permission), else the AvatarBody hand entities (wrist/grip pose only, no tip).
 *    The path in use is logged once as `hands_source`.
 *  - render frame counter for `track.fps`.
 *
 * The head buffer is filled here, in `execute()`, which the SDK calls once per frame on its own loop thread (the
 * same tick as MainActivity's FrameTick), so `getViewerPose()` is read where the SDK expects. Identity poses at the
 * origin (before tracking starts) are skipped. The first good sample logs `head_pose` (render frame, thread); 300
 * frames without one log an `error`. The camera thread reads the buffer ([CameraPoseResolver]).
 *
 * All poses are converted from the left-handed Spatial frame into our WORLD ([Frames.spatialToWorld]).
 */
@OptIn(SpatialSDKExperimentalAPI::class)
class SpatialInput(private val heads: HeadPoseBuffer) : SystemBase() {
  val renderFrames = AtomicInteger()

  @Volatile
  var hands: HandsFrame? = null
    private set

  private var skeleton: Boolean? = null // null = not tried yet
  private var controllers: MutableList<ControllerPose> = ArrayList()
  private var joints: MutableList<SkeletonJoint> = ArrayList()
  private var logged = false
  private var errors = 0

  override fun execute() {
    renderFrames.incrementAndGet()
    val now = SystemClock.elapsedRealtimeNanos()
    val sc =
        try {
          getScene()
        } catch (e: Exception) {
          return
        }
    val head =
        try {
          sc.getViewerPose().toWorld()
        } catch (e: Exception) {
          if (errors++ < 3) Agq.log.error("head_pose", e.message)
          null
        }
    // Before tracking starts the viewer pose is the identity at the origin: not a head pose, keep it out of the buffer
    val valid = head != null && !isOrigin(head)
    if (valid) {
      heads.push(now, head!!)
      headPushes.incrementAndGet()
      if (!headLogged) {
        headLogged = true
        Agq.log.event("head_pose", mapOf("render_frame" to renderFrames.get(), "head_p" to head.t,
            "invalid_before" to invalidHeads, "thread" to Thread.currentThread().name))
      }
    } else if (!headLogged && ++invalidHeads == 300) {
      Agq.log.error("head_pose", "no valid viewer pose in the first 300 render frames (null or identity at the origin)")
    }
    if (renderFrames.get() % 2 == 0) hands = readHands(sc, now, head?.t)
  }

  /** Head samples pushed so far (the HUD / `camera_pose_missing` diagnosis). */
  val headPushes = AtomicInteger()
  private var headLogged = false
  private var invalidHeads = 0

  private fun isOrigin(p: Pose3): Boolean =
      p.t.length() < 1e-4f && kotlin.math.abs(kotlin.math.abs(p.q.w) - 1f) < 1e-5f

  private fun readHands(sc: com.meta.spatial.runtime.Scene, now: Long, head: Vec3?): HandsFrame? {
    if (skeleton != false) {
      try {
        if (skeleton == null) {
          val n = sc.getBodyJointCount()
          if (n > JointType.RIGHT_HAND_INDEX_TIP.ordinal) {
            controllers = MutableList(2) { ControllerPose(Pose(), 0) }
            joints = MutableList(n) { SkeletonJoint(it, -1, Pose()) }
          }
        }
        if (joints.isNotEmpty() && sc.updateBodyTrackingBuffersAtTime(controllers, joints)) {
          fun hand(wrist: JointType, tip: JointType): HandSample? {
            val w = joints[wrist.ordinal].pose.toWorld().t
            val t = joints[tip.ordinal].pose.toWorld().t
            if (w.length() < 1e-6f) return null // never written
            return HandSample(w, t.takeIf { it.length() > 1e-6f })
          }
          val f =
              HandsFrame(
                  now,
                  head,
                  hand(JointType.LEFT_HAND_WRIST, JointType.LEFT_HAND_INDEX_TIP),
                  hand(JointType.RIGHT_HAND_WRIST, JointType.RIGHT_HAND_INDEX_TIP),
              )
          if (skeleton == null) logSource("skeleton", joints.size)
          skeleton = true
          return f
        }
        if (skeleton == null) {
          skeleton = false
          logSource("avatar_body", joints.size)
        }
      } catch (e: Throwable) {
        skeleton = false
        Agq.log.error("hands", "skeleton: ${e.message}")
        logSource("avatar_body", -1)
      }
    }
    return try {
      val body = systemManager.findSystem<PlayerBodyAttachmentSystem>().tryGetLocalPlayerAvatarBody() ?: return null
      fun hand(e: com.meta.spatial.core.Entity?): HandSample? {
        val p = e?.tryGetComponent<Transform>()?.transform ?: return null
        val w = p.toWorld().t
        return if (w.length() < 1e-6f) null else HandSample(w, null)
      }
      HandsFrame(now, head, hand(body.leftHand), hand(body.rightHand))
    } catch (e: Exception) {
      null
    }
  }

  private fun logSource(src: String, joints: Int) {
    if (logged) return
    logged = true
    Agq.log.event("hands_source", mapOf("source" to src, "joints" to joints, "fingertips" to (src == "skeleton")))
  }

  private fun Pose.toWorld(): Pose3 = Frames.spatialToWorld(t.x, t.y, t.z, q.x, q.y, q.z, q.w)
}
