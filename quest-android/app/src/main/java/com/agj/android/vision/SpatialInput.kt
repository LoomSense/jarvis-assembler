package com.agj.android.vision

import android.os.SystemClock
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.JointType
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SkeletonJoint
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spatial SDK system (runs once per render frame on the render thread) that feeds the vision package:
 *  - head pose: `scene.getViewerPose()` → [HeadPoseBuffer] stamped with `SystemClock.elapsedRealtimeNanos()` (the
 *    clock of Camera2 REALTIME timestamps). Whether the viewer pose is the pose *now* or predicted for display time
 *    is UNVERIFIED; `head_time_offset_ms` in vision.json compensates.
 *  - hands (every other frame), a chain of sources, each logged when it becomes the live one (`hands_source`):
 *    1. `skeleton`: the body-tracking skeleton (`scene.updateBodyTrackingBuffersAtTime`), the only Kotlin API of
 *       SDK 0.15.0 with finger joints. The **first** list it fills holds the tracked joint poses with validity
 *       flags (index = `JointType.index`); the second is the bind pose and hierarchy, not where the hand is.
 *       Needs the manifest feature `com.oculus.software.body_tracking` (see [HandSetup]); without it the first
 *       call throws "Failed to initialize session for body". A failure is retried after 3, 10 and 30 s and once
 *       more when the body-tracking permission is granted, then given up.
 *    2. `avatar_body`: the AvatarBody hand entities: wrist pose only, plus the system's index pinch from the
 *       `Controller` component. Used for a frame whenever the skeleton gave no hand.
 *    3. none: [hands] stays null and no hand summary is sent (the server then does not gate frames on hands).
 *    [hands] also stays null until a hand was tracked once, for the same reason: with hand tracking off in the
 *    headset settings a stream of "no hand" summaries would slow the server's frames to 1 a second.
 *  - [held]: is a hand closed on something ([HeldDetector]), for the verdict badge and [HeldPartCheck].
 *  - `hands_status` ([HandsStatusTracker]): `joints_live`, `wrist_only`, `no_hands_tracked` (hand tracking is
 *    probably off in the headset settings), `hands_out_of_view`, `controllers_active`, `api_failed`.
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

  /** Is a hand closed on something, and where is its wrist (the render thread writes, any thread reads). */
  @Volatile
  var held: HeldState = HeldState.NONE
    private set

  /** `skeleton`, `avatar_body` or `none`: where the last tracked hand came from. */
  @Volatile
  var handsSource: String = "none"
    private set

  /** The last `hands_status` state ([HandsStatusTracker]); null before the first. */
  val handsStatus: String?
    get() = status.reported

  private val detector = HeldDetector()
  private val status = HandsStatusTracker()
  private val jointPoses: MutableList<ControllerPose> = ArrayList()
  private val skeletonJoints: MutableList<SkeletonJoint> = ArrayList()
  private var jointCount = 0
  private var skel = SKEL_TRY
  private var skelFails = 0
  private var skelRetryAtMs = 0L
  private var skelError: String? = null
  private var skelCalls = 0
  private var skelData = false
  private var seenTrackedBit = false
  private val wristFlags = IntArray(2)
  private var permCheckAtMs = 0L
  private var permWasGranted: Boolean? = null
  private var avatarError: String? = null
  private val avatarKind = arrayOf("none", "none") // left, right: hand | controller | inactive | unknown | other | none
  private var avatarHandSeen = false
  private var everTracked = false
  private var loggedSource = "none"
  private var sourceLogMs = Long.MIN_VALUE / 2
  private var errors = 0

  private val avatarKinds: String
    get() = "${avatarKind[0]}/${avatarKind[1]}"

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
    if (renderFrames.get() % 2 == 0) {
      val f = readHands(sc, now, head?.t)
      hands = f
      val h = detector.update(f, now / 1_000_000)
      val was = held
      held = h
      if (h.holding != was.holding || (h.holding && h.which != was.which)) {
        Agq.log.event("held", mapOf("holding" to h.holding, "which" to h.which.wire, "grip" to h.grip,
            "fingers" to h.fingers, "pos" to h.handWorldPos, "source" to handsSource))
      }
    }
  }

  /** Head samples pushed so far (the HUD / `camera_pose_missing` diagnosis). */
  val headPushes = AtomicInteger()
  private var headLogged = false
  private var invalidHeads = 0

  private fun isOrigin(p: Pose3): Boolean =
      p.t.length() < 1e-4f && kotlin.math.abs(kotlin.math.abs(p.q.w) - 1f) < 1e-5f

  private fun readHands(sc: Scene, now: Long, head: Vec3?): HandsFrame? {
    val nowMs = now / 1_000_000
    val sk = readSkeleton(sc, nowMs)
    val av = readAvatar() // also says what each hand entity is (avatarKind)
    // The body tracker can go on giving estimated hand joints when a hand is not tracked (or hand tracking is off).
    // The SDK's own hand input knows: a side it calls inactive or a controller is not a tracked hand.
    fun vetoed(i: Int) = av != null && avatarKind[i] != "hand" && avatarKind[i] != "unknown"
    var left = sk?.first
    var right = sk?.second
    // Drop such a side only once the SDK has shown a tracked hand at all (then its "inactive" is known to mean it).
    if (avatarHandSeen) {
      if (vetoed(0)) left = null
      if (vetoed(1)) right = null
    }
    var src = if (left != null || right != null) "skeleton" else "none"
    if (src == "none" && av != null) {
      left = av.first
      right = av.second
      if (left != null || right != null) src = "avatar_body"
    }
    val works = sk != null || av != null
    val any = left != null || right != null
    // for the status, a hand counts only when the SDK's hand input does not contradict it
    val realLeft = left != null && !vetoed(0)
    val realRight = right != null && !vetoed(1)
    val fingers = (realLeft && left?.hasFingers == true) || (realRight && right?.hasFingers == true)
    if (any) {
      everTracked = true
      handsSource = src
      if (src != loggedSource && nowMs - sourceLogMs >= SOURCE_LOG_MS) {
        sourceLogMs = nowMs
        loggedSource = src
        Agq.log.event("hands_source", mapOf("source" to src, "joints" to if (src == "skeleton") jointCount else 2,
            "fingertips" to (left?.hasFingers == true || right?.hasFingers == true), "tracked" to (realLeft || realRight),
            "wrist_flags" to wristFlags, "skeleton" to skeletonState(), "avatar" to avatarKinds,
            "render_frame" to renderFrames.get()))
      }
    }
    val state = status.update(nowMs, fingers, realLeft || realRight, "controller" in avatarKinds, works)
    if (state != null) {
      Agq.log.event("hands_status", mapOf("state" to state, "message" to HandsStatusTracker.message(state),
          "source" to src, "skeleton" to skeletonState(), "skeleton_error" to skelError, "avatar" to avatarKinds,
          "avatar_error" to avatarError, "body_permission" to HandSetup.bodyPermission(),
          "hand_permission" to HandSetup.handPermission(), "render_frame" to renderFrames.get()))
    }
    // No summary until a hand was tracked once (hand tracking off, controllers): the server must not gate on hands.
    return if (works && everTracked) HandsFrame(now, head, left, right) else null
  }

  private fun skeletonState(): String =
      when {
        skel == SKEL_DEAD -> "failed"
        skel == SKEL_WAIT -> "retrying"
        skelData -> "live"
        else -> "no_data"
      }

  /**
   * The body-tracking skeleton's hands. Null = the source gave nothing (failed, waiting for a retry, no data yet);
   * a pair of nulls = it works and no hand is tracked.
   */
  private fun readSkeleton(sc: Scene, nowMs: Long): Pair<HandSample?, HandSample?>? {
    if (nowMs >= permCheckAtMs) {
      permCheckAtMs = nowMs + 2000
      val g = HandSetup.bodyPermission()
      // granted after a failure (the prompt was answered): start over
      if (g == true && permWasGranted == false && skel != SKEL_TRY && !skelData) {
        skel = SKEL_TRY
        skelFails = 0
        jointCount = 0
      }
      if (g != null) permWasGranted = g
    }
    if (skel == SKEL_DEAD) return null
    if (skel == SKEL_WAIT) {
      if (nowMs < skelRetryAtMs) return null
      skel = SKEL_TRY
    }
    try {
      if (jointCount <= 0) {
        val n = sc.getBodyJointCount()
        if (n <= JointType.RIGHT_HAND_LITTLE_TIP.index) {
          throw IllegalStateException("body joint count is $n: body tracking is not available")
        }
        jointCount = n
        skelCalls = 0
      }
      if (!sc.updateBodyTrackingBuffersAtTime(jointPoses, skeletonJoints)) {
        if (!skelData && ++skelCalls >= NO_DATA_CALLS) {
          throw IllegalStateException("no body data in $NO_DATA_CALLS calls")
        }
        return null
      }
      skelData = true
      skelError = null
      val l =
          hand(JointType.LEFT_HAND_WRIST, JointType.LEFT_HAND_PALM, JointType.LEFT_HAND_THUMB_TIP,
              JointType.LEFT_HAND_INDEX_TIP, JointType.LEFT_HAND_MIDDLE_TIP, JointType.LEFT_HAND_RING_TIP,
              JointType.LEFT_HAND_LITTLE_TIP)
      val r =
          hand(JointType.RIGHT_HAND_WRIST, JointType.RIGHT_HAND_PALM, JointType.RIGHT_HAND_THUMB_TIP,
              JointType.RIGHT_HAND_INDEX_TIP, JointType.RIGHT_HAND_MIDDLE_TIP, JointType.RIGHT_HAND_RING_TIP,
              JointType.RIGHT_HAND_LITTLE_TIP)
      return l to r
    } catch (e: Throwable) {
      skelFails++
      skelData = false
      jointCount = 0
      val msg = "skeleton: ${e.message}"
      if (msg != skelError || skelFails <= 1) {
        Agq.log.event("error", mapOf("where" to "hands", "message" to msg, "attempt" to skelFails,
            "body_permission" to HandSetup.bodyPermission(),
            "hint" to "the body-tracking session needs the manifest feature com.oculus.software.body_tracking " +
                "and, if the OS asks, the body tracking permission; using avatar_body (wrist only) meanwhile"))
      }
      skelError = msg
      val wait = RETRY_MS.getOrNull(skelFails - 1)
      if (wait == null) {
        skel = SKEL_DEAD
      } else {
        skel = SKEL_WAIT
        skelRetryAtMs = nowMs + wait
      }
      return null
    }
  }

  /**
   * One hand from the tracked joint poses; null when its wrist is not valid (or not tracked, once the runtime has
   * shown that it sets the tracked bit on a wrist).
   */
  private fun hand(wrist: JointType, palm: JointType, thumb: JointType, index: JointType, middle: JointType,
      ring: JointType, little: JointType): HandSample? {
    val wf = jointPoses.getOrNull(wrist.index)?.flags ?: return null
    wristFlags[if (wrist == JointType.LEFT_HAND_WRIST) 0 else 1] = wf
    if (wf and ControllerPose.LocationTrackedBit != 0) seenTrackedBit = true
    val w = joint(wrist) ?: return null
    return HandSample(w, joint(index), palm = joint(palm), thumbTip = joint(thumb), middleTip = joint(middle),
        ringTip = joint(ring), littleTip = joint(little))
  }

  private fun joint(t: JointType): Vec3? {
    val p = jointPoses.getOrNull(t.index) ?: return null
    val f = p.flags
    if (f and ControllerPose.LocationValidBit == 0) return null
    if (seenTrackedBit && f and ControllerPose.LocationTrackedBit == 0) return null
    val w = p.pose.toWorld().t
    return if (w.length() < 1e-6f) null else w
  }

  /**
   * The AvatarBody hand entities: wrist pose and the system pinch. Null = the source failed; a pair of nulls = no
   * hand (a controller is the active input, or the hand is not tracked).
   */
  private fun readAvatar(): Pair<HandSample?, HandSample?>? {
    return try {
      val body = systemManager.findSystem<PlayerBodyAttachmentSystem>().tryGetLocalPlayerAvatarBody()
      if (body == null) {
        avatarKind[0] = "none"
        avatarKind[1] = "none"
        avatarError = "no local AvatarBody yet"
        return null
      }
      val l = avatarHand(body.leftHand, ButtonBits.ButtonX or ButtonBits.ButtonTriggerL or ButtonBits.ButtonSqueezeL)
      val r = avatarHand(body.rightHand, ButtonBits.ButtonA or ButtonBits.ButtonTriggerR or ButtonBits.ButtonSqueezeR)
      avatarKind[0] = l.second
      avatarKind[1] = r.second
      if (l.second == "hand" || r.second == "hand") avatarHandSeen = true
      avatarError = null
      l.first to r.first
    } catch (e: Throwable) {
      val msg = "avatar_body: ${e.message}"
      if (msg != avatarError && errors++ < 6) Agq.log.error("hands", msg)
      avatarError = msg
      null
    }
  }

  /** The entity's wrist (null for a controller) and what the SDK says it is: `hand`, `controller`, `inactive`, `unknown`, `other`. */
  private fun avatarHand(e: Entity?, pinchBits: Int): Pair<HandSample?, String> {
    if (e == null) return null to "none"
    val c = e.tryGetComponent<Controller>()
    val kind =
        when {
          c == null -> "unknown"
          !c.isActive -> "inactive"
          c.type == ControllerType.HAND -> "hand"
          c.type == ControllerType.CONTROLLER -> "controller"
          else -> "other"
        }
    if (kind == "controller" || kind == "other") return null to kind
    // "inactive" is believed once the SDK has shown a tracked hand; until then its pose is used as before
    if (kind == "inactive" && avatarHandSeen) return null to kind
    val p = e.tryGetComponent<Transform>()?.transform ?: return null to kind
    val w = p.toWorld().t
    if (w.length() < 1e-6f) return null to kind
    return HandSample(w, null, pinching = c?.let { it.buttonState and pinchBits != 0 }) to kind
  }

  private fun Pose.toWorld(): Pose3 = Frames.spatialToWorld(t.x, t.y, t.z, q.x, q.y, q.z, q.w)

  private companion object {
    const val SKEL_TRY = 0
    const val SKEL_WAIT = 1
    const val SKEL_DEAD = 2

    /** Waits before the 2nd, 3rd and 4th attempt at the body-tracking session; after that it is given up. */
    val RETRY_MS = longArrayOf(3_000L, 10_000L, 30_000L)

    /** Calls without data (about 36 a second) before a session that answers but stays empty counts as failed. */
    const val NO_DATA_CALLS = 180
    const val SOURCE_LOG_MS = 10_000L
  }
}
