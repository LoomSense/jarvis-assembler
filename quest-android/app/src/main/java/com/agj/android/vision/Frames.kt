package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Frame conversions for the vision pipeline. Pure Kotlin (no Android, Spatial SDK or OpenCV types): unit-tested on
 * the JVM.
 *
 * Frames (see also core/Geom.kt):
 *  - WORLD (ours): right-handed, +X right, +Y up, +Z backward (OpenXR). Every Pose3 the vision package returns is in
 *    this frame.
 *  - SPATIAL: the Meta Spatial SDK's world and Pose type. **Left-handed**: +X right, +Y up, +Z forward (Spatial SDK
 *    "Scene" docs: "Spatial SDK uses a left-handed coordinate system: X+ is right, Y+ is up, and Z+ is forward";
 *    `Vector3.Forward` is (0, 0, 1) in the 0.15.0 jar). WORLD ↔ SPATIAL is a mirror of the Z axis: [spatialToWorld],
 *    [worldToSpatial] / [mirrorZ]. The app works in WORLD/ASSEMBLY (right-handed) everywhere and converts only at
 *    the SDK edge (guide/SpatialGhostRenderer.kt `toSdk` / `toPose3`, `SpatialMesh.fromGltf`): every entity
 *    Transform with [mirrorZ], every mesh vertex/normal with [mirrorZ] plus flipped triangle winding. We build our
 *    own meshes from the GLB (guide/Glb.kt), so the SDK's glTF importer is not involved.
 *  - HEAD: the viewer pose (`scene.getViewerPose()`), mirrored into WORLD: +X right, +Y up, +Z backward.
 *  - CAMERA: OpenCV axes, +X right, +Y down, +Z forward along the optical axis.
 *  - MARKER: OpenCV ArUco axes, +X to the tag's right, +Y to its printed TOP edge, +Z out of the printed face,
 *    origin at the centre (tools/quest/marker/marker.json).
 *  - ASSEMBLY: GLB root node `assembly`, glTF axes (+Y up), metres.
 */
object Frames {
  /** Spatial SDK pose (left-handed) → WORLD (right-handed). [t] metres, [q] (x, y, z, w). */
  fun spatialToWorld(tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float): Pose3 =
      Pose3(Vec3(tx, ty, -tz), Quat(-qx, -qy, qz, qw).normalized())

  /** WORLD → Spatial SDK: the same mirror (it is its own inverse). Returns (tx, ty, tz, qx, qy, qz, qw). */
  fun worldToSpatial(p: Pose3): FloatArray = floatArrayOf(p.t.x, p.t.y, -p.t.z, -p.q.x, -p.q.y, p.q.z, p.q.w)

  /**
   * The Z mirror M = diag(1, 1, −1) on a point or direction: right-handed (WORLD, ASSEMBLY, any glTF-space local
   * frame) ↔ Spatial SDK axes. Its own inverse.
   */
  fun mirrorZ(v: Vec3): Vec3 = Vec3(v.x, v.y, -v.z)

  /**
   * The Z mirror on a rigid transform, M·T·M, for ANY right-handed parent ← child pose (not only WORLD ← X): a
   * Spatial entity tree whose every local Transform went through this, with mesh vertices through [mirrorZ], renders
   * exactly M × (the right-handed scene), i.e. un-mirrored in the left-handed SDK. Its own inverse; composes
   * (mirrorZ(a × b) = mirrorZ(a) × mirrorZ(b)).
   */
  fun mirrorZ(p: Pose3): Pose3 = Pose3(mirrorZ(p.t), Quat(-p.q.x, -p.q.y, p.q.z, p.q.w))

  /**
   * HEAD ← CAMERA(OpenCV) from Camera2 `LENS_POSE_TRANSLATION` [t] and `LENS_POSE_ROTATION` [q] (x, y, z, w).
   *
   * Android defines the extrinsics as `x_camera = R(q) · (x_reference − t)` with the camera in OpenCV axes and the
   * reference in Android sensor axes (+X right, +Y up, +Z toward the user), which for a headset are the OpenXR head
   * axes. So HEAD ← CAMERA = (t, conj(q)). Meta's Spatial Scanner showcase does the same in left-handed Spatial
   * axes (`Quaternion(w, -x, -y, z).inverse() * Quaternion(180°, 0, 0)`, `t = (x, y, -z)`, camera +Y up); mirrored
   * back into right-handed OpenCV axes that is exactly (t, conj(q)) (derivation in vision/README section of
   * quest-android/README.md). [convention] selects alternatives for the first device test (UNVERIFIED on Quest).
   */
  fun headFromCamera(t: FloatArray, q: FloatArray, convention: LensConvention = LensConvention.ANDROID): Pose3 {
    val pos = Vec3(t[0], t[1], t[2])
    val rot = Quat(q[0], q[1], q[2], q[3]).normalized()
    return when (convention) {
      LensConvention.ANDROID -> Pose3(pos, rot.conjugate())
      LensConvention.DIRECT -> Pose3(pos, rot)
      // Ignore the rotation: camera looks along head forward (-Z), OpenCV axes = head axes rotated 180° about X.
      LensConvention.FORWARD -> Pose3(pos, Quat(1f, 0f, 0f, 0f))
    }
  }

  /** OpenCV `rvec` (axis × angle, radians) → quaternion, in double precision. */
  fun rodriguesToQuat(r: DoubleArray): Quat {
    val th = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
    if (th < 1e-12) return Quat.IDENTITY
    val s = sin(th / 2) / th
    return Quat((r[0] * s).toFloat(), (r[1] * s).toFloat(), (r[2] * s).toFloat(), cos(th / 2).toFloat()).normalized()
  }

  /** OpenCV PnP result → CAMERA ← MARKER. */
  fun cameraFromMarker(rvec: DoubleArray, tvec: DoubleArray): Pose3 =
      Pose3(Vec3(tvec[0].toFloat(), tvec[1].toFloat(), tvec[2].toFloat()), rodriguesToQuat(rvec))

  /** Angle between two rotations, degrees (0..180). */
  fun angleDeg(a: Quat, b: Quat): Double {
    // relative rotation a⁻¹b; atan2 keeps precision near 0 (acos of a float dot does not)
    val r = a.conjugate() * b
    val v = sqrt((r.x * r.x + r.y * r.y + r.z * r.z).toDouble())
    return Math.toDegrees(2 * kotlin.math.atan2(v, kotlin.math.abs(r.w.toDouble())))
  }

  /** Rotation matrix (row-major) → unit quaternion. */
  fun matToQuat(m: Array<DoubleArray>): Quat {
    val tr = m[0][0] + m[1][1] + m[2][2]
    val q: DoubleArray =
        if (tr > 0) {
          val s = sqrt(tr + 1.0) * 2
          doubleArrayOf((m[2][1] - m[1][2]) / s, (m[0][2] - m[2][0]) / s, (m[1][0] - m[0][1]) / s, 0.25 * s)
        } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
          val s = sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2
          doubleArrayOf(0.25 * s, (m[0][1] + m[1][0]) / s, (m[0][2] + m[2][0]) / s, (m[2][1] - m[1][2]) / s)
        } else if (m[1][1] > m[2][2]) {
          val s = sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2
          doubleArrayOf((m[0][1] + m[1][0]) / s, 0.25 * s, (m[1][2] + m[2][1]) / s, (m[0][2] - m[2][0]) / s)
        } else {
          val s = sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2
          doubleArrayOf((m[0][2] + m[2][0]) / s, (m[1][2] + m[2][1]) / s, 0.25 * s, (m[1][0] - m[0][1]) / s)
        }
    return Quat(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()).normalized()
  }

  /** Quaternion with w ≥ 0 (canonical sign, as tools/quest/replay/geom.py writes them). */
  fun canonical(q: Quat): Quat = if (q.w < 0f) Quat(-q.x, -q.y, -q.z, -q.w) else q
}

/** How to read Camera2 `LENS_POSE_ROTATION` (selectable in `vision.json` for the first device test). */
enum class LensConvention {
  /** Android documentation and Meta's showcase: HEAD ← CAMERA rotation = conj(q). Default. */
  ANDROID,
  /** q used directly as HEAD ← CAMERA. */
  DIRECT,
  /** rotation ignored, camera assumed to look straight ahead. */
  FORWARD,
}

/**
 * Marker configuration.
 *
 * @property dictionary OpenCV predefined dictionary id; 20 = `Objdetect.DICT_APRILTAG_36h11` (checked in a test).
 * @property markerId the id printed on the sticker (marker.json: 0).
 * @property sizeM black-square side length in metres (marker.json `tags[tag36h11_0].size_m`: 0.08).
 * @property tagOffset the detected tag's centre in STRIP axes (marker.json `tags[tag36h11_0].offset_m`: the 36h11
 *   tag sits 50 mm left of the strip centre). "MARKER" everywhere downstream is the **strip centre**:
 *   STRIP ← TAG = Translation([tagOffset]) (both tags are upright on the strip, `offset_q` identity), so
 *   WORLD ← STRIP = WORLD ← TAG × Translation(−[tagOffset]). Override with `vision.json` `marker_tag_offset_m`
 *   (e.g. `[0, 0, 0]` for the old single-tag print).
 * @property assemblyFromMarker where the strip centre sits on the base part, ASSEMBLY ← MARKER(strip centre).
 */
data class MarkerConfig(
    val dictionary: Int = 20,
    val markerId: Int = 0,
    val sizeM: Double = 0.08,
    val tagOffset: Vec3 = STRIP_36H11_OFFSET,
    val assemblyFromMarker: Pose3 = MarkerPlacement.MALM_DRAWER_FRONT,
) {
  /** TAG ← STRIP, applied to every PnP result. */
  val tagFromStrip: Pose3
    get() = Pose3(tagOffset * -1f)

  companion object {
    /** tools/quest/marker/marker.json `tags[key=tag36h11_0].offset_m` (a unit test keeps the two in sync). */
    val STRIP_36H11_OFFSET = Vec3(-0.05f, 0f, 0f)
  }
}

/**
 * Marker placements per job, ASSEMBLY ← MARKER, where MARKER is the printed strip's centre (marker.json `object`:
 * `strip_centre`; the strip centre sits where the single tag's centre was, so the derivation below is unchanged).
 * The GLB's own `marker` node (manifest `markerNode`) is NOT this: it is the screw-hole ring of the viewer.
 *
 * MALM drawer (`jobs/malm-drawer/walkthrough.glb`, node `part_drawer_front_1`, identity under `assembly`): the
 * front's mesh spans X −0.19…0.19 (380 mm length), Y −0.015…0.115 (130 mm height), Z 0…0.016 (thickness). The sides
 * and the bottom sit at Z < 0, so the **inside face is the plane Z = 0, facing −Z**. The bottom panel (Y
 * 0.012…0.015) slides into the groove near the lower long edge (Y = −0.015).
 *
 * marker.json: centred along the length (X = 0); centred between the groove (top edge Y ≈ 0.015) and the opposite
 * long edge (Y = 0.115), so Y = 0.065 (the 100 mm cut square then fills exactly 0.015…0.115); TOP edge toward the
 * groove (marker +Y = assembly −Y); marker +Z out of the inside face (= assembly −Z); hence marker +X = assembly +X.
 * That is a 180° turn about X: q = (1, 0, 0, 0), t = (0, 0.065, 0). Paper thickness (0.1 mm) ignored.
 *
 * Consequence used by the scorer: the marker's +X is the assembly's +X, so `yaw_deg` of the assembly equals the
 * marker's heading. On the synthetic dataset (marker face up at the world origin, TOP toward −Z) the assembly origin
 * is at (0, 0, −0.065) with assembly −Z pointing up: the front lying inside face up, as in step 1.
 */
object MarkerPlacement {
  val MALM_DRAWER_FRONT = Pose3(Vec3(0f, 0.065f, 0f), Quat(1f, 0f, 0f, 0f))
}
