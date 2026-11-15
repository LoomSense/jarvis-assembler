package com.agj.android.world

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.vision.FrameGrab
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** What placed the assembly anchor (`place.source`, `track.source`). */
enum class PlaceSource(val wire: String) {
  /** The room's table (MRUK), in front of the user. */
  TABLE("table"),
  /** No scene table: a spot in front of and below the head. */
  FIXED_SPOT("fixed_spot"),
  /** The server's box for the base part in a camera frame, ray-cast onto the table. */
  SERVER_BOX("server_box"),
  /** The user nudged it. */
  MANUAL("manual"),
  /** A markerless object pose plugged in through `onObjectPose`. */
  MARKERLESS("markerless"),
}

/**
 * The one assembly anchor of the no-marker path: WORLD ← TABLE (origin on the table, the assembly stands on it in
 * the step's pose). Sources, weakest first: `fixed_spot` < `table` < `server_box` < `markerless`; `manual` wins over
 * everything automatic until an explicit [requestRealign]. Pure, unit-tested; [onChange] receives every accepted
 * pose (WorldModule hands it to the tracker / renderer), and each one is logged as AGQ `place`
 * (`source`, `reason`, `pos_m`, `yaw_deg`, `moved_m`, `prev`).
 *
 * **The ghost must not jitter**, so the automatic sources are gated:
 *  - [placeInitial]: only when nothing is placed yet, or a table replaces the fixed spot;
 *  - [onServerBox]: the first answer moves the anchor (`first`); after that only an explicit re-align (`realign`),
 *    or answers that disagree with the anchor by more than [disagreeM] and agree with each other within
 *    [agreeM] for at least [disagreeMs] (`disagree`);
 *  - [onObjectPose]: a markerless pose replaces the anchor only when it moved more than [deadbandM] /
 *    [deadbandRad] (and confidence ≥ [minConfidence]).
 */
class AssemblyAnchor(
    private val log: AgqLog,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val disagreeM: Float = 0.10f,
    private val agreeM: Float = 0.06f,
    private val disagreeMs: Long = 4000L,
    private val deadbandM: Float = 0.015f,
    private val deadbandRad: Float = 0.035f,
    private val minConfidence: Float = 0.5f,
    private val onChange: (Pose3, PlaceSource) -> Unit = { _, _ -> },
) {
  @Volatile var pose: Pose3? = null
    private set

  @Volatile var source: PlaceSource? = null
    private set

  /** The wire name of [source]; for a plugged-in pose, the name its tracker gave (default `markerless`). */
  @Volatile var sourceName: String? = null
    private set

  /** True once a server box has placed the anchor (then only disagreement or a re-align moves it again). */
  @Volatile var serverPlaced = false
    private set

  @Volatile var realignRequested = false
    private set

  @Volatile private var markerlessAtMs = Long.MIN_VALUE / 2
  private var pending: Pose3? = null
  private var plugName = PlaceSource.MARKERLESS.wire
  private var pendingSince = 0L

  /** A markerless pose arrived in the last [withinMs]: the server box is not needed. */
  fun markerlessFresh(withinMs: Long = 3000L): Boolean = clockMs() - markerlessAtMs < withinMs

  /** Should the base part be looked for now? ([sinceLastMs] = time since the last answer.) */
  fun wantsServerBox(sinceLastMs: Long, verifyEveryMs: Long = 10_000L): Boolean {
    if (pose == null) return false // a surface first
    if (realignRequested) return true
    if (source == PlaceSource.MANUAL || markerlessFresh()) return false
    if (!serverPlaced) return true
    return pending != null || sinceLastMs >= verifyEveryMs
  }

  @Synchronized
  fun placeInitial(p: Pose3, s: PlaceSource): Boolean {
    val cur = source
    val ok = cur == null || (cur == PlaceSource.FIXED_SPOT && s == PlaceSource.TABLE)
    if (!ok) return false
    apply(p, s, if (cur == null) "initial" else "table_found")
    return true
  }

  /** The base part's place from one camera answer ([BasePlacement.fromBox]), as WORLD ← TABLE. */
  @Synchronized
  fun onServerBox(candidate: Pose3): Boolean {
    val cur = pose ?: return false
    val now = clockMs()
    if (realignRequested) {
      realignRequested = false
      pending = null
      serverPlaced = true
      apply(candidate, PlaceSource.SERVER_BOX, "realign")
      return true
    }
    if (source == PlaceSource.MANUAL || markerlessFresh()) return false
    if (!serverPlaced) {
      serverPlaced = true
      apply(candidate, PlaceSource.SERVER_BOX, "first")
      return true
    }
    if (flatDist(candidate.t, cur.t) <= disagreeM) {
      pending = null
      return false
    }
    val p = pending
    if (p == null || flatDist(candidate.t, p.t) > agreeM) {
      pending = candidate
      pendingSince = now
      return false
    }
    if (now - pendingSince < disagreeMs) return false
    pending = null
    apply(candidate, PlaceSource.SERVER_BOX, "disagree")
    return true
  }

  /** The server did not see the base part: a pending disagreement is forgotten. */
  @Synchronized
  fun onServerMiss() {
    pending = null
  }

  /** "Re-align" (voice / menu): the next server answer moves the anchor, also after a manual nudge. */
  fun requestRealign() {
    realignRequested = true
    log.event("place", mapOf("source" to source?.wire, "reason" to "realign_requested"))
  }

  /**
   * Slide the anchor on the table by [rightM] / [forwardM] in its own axes seen from the user ([forwardM] = away
   * along the assembly's −Z, since its front faces the user) and twist it by [yawRad] about the vertical.
   */
  @Synchronized
  fun nudge(rightM: Float, forwardM: Float, yawRad: Float): Boolean {
    val cur = pose ?: return false
    val t = cur.t + cur.q.rotate(Vec3(rightM, 0f, -forwardM))
    apply(Pose3(Vec3(t.x, cur.t.y, t.z), (Quat.yaw(yawRad) * cur.q).normalized()), PlaceSource.MANUAL, "nudge")
    return true
  }

  /** Put the anchor at an absolute pose by hand (e.g. a pinch-drag end point). */
  @Synchronized
  fun placeManual(p: Pose3) = apply(p, PlaceSource.MANUAL, "manual")

  /** A markerless pose (already WORLD ← TABLE). Returns true when the anchor moved. */
  @Synchronized
  fun onObjectPose(worldFromTable: Pose3, confidence: Float, name: String = PlaceSource.MARKERLESS.wire): Boolean {
    if (confidence < minConfidence) return false
    markerlessAtMs = clockMs()
    plugName = name
    val cur = pose
    if (cur != null && source == PlaceSource.MARKERLESS) {
      val moved = (worldFromTable.t - cur.t).length()
      val turned = yawDiff(yawOf(worldFromTable.q), yawOf(cur.q))
      if (moved < deadbandM && turned < deadbandRad) return false
    }
    apply(worldFromTable, PlaceSource.MARKERLESS, if (source == PlaceSource.MARKERLESS) "update" else "first")
    return true
  }

  @Synchronized
  fun reset() {
    pose = null
    source = null
    sourceName = null
    serverPlaced = false
    realignRequested = false
    pending = null
  }

  private fun apply(p: Pose3, s: PlaceSource, reason: String) {
    val prev = pose
    val prevSource = source
    pose = p
    source = s
    val name = if (s == PlaceSource.MARKERLESS) plugName else s.wire
    sourceName = name
    // markerless updates are frequent: log the first, then moves of 5 cm or more
    val moved = prev?.let { (p.t - it.t).length() }
    if (s != PlaceSource.MARKERLESS || reason == "first" || (moved ?: 1f) >= 0.05f) {
      log.event(
          "place",
          mapOf("source" to name, "reason" to reason, "pos_m" to p.t,
              "yaw_deg" to Math.round(Math.toDegrees(yawOf(p.q).toDouble()) * 10) / 10.0, "moved_m" to moved,
              "prev" to prevSource?.wire),
      )
    }
    onChange(p, s)
  }

  companion object {
    fun flatDist(a: Vec3, b: Vec3): Float = Vec3(a.x - b.x, 0f, a.z - b.z).length()

    /** Heading of the pose's +Z about WORLD +Y (the convention of `Quat.yaw`), radians. */
    fun yawOf(q: Quat): Float = q.rotate(Vec3(0f, 0f, 1f)).let { atan2(it.x, it.z) }

    /** Smallest absolute difference of two angles, radians. */
    fun yawDiff(a: Float, b: Float): Float {
      var d = (a - b) % (2 * PI.toFloat())
      if (d > PI) d -= 2 * PI.toFloat()
      if (d < -PI) d += 2 * PI.toFloat()
      return abs(d)
    }
  }
}

/**
 * The base part's rectangle on the table, TABLE frame (x, z; the part lies flat or stands in the step's pose):
 * centre and half sizes along TABLE X and Z.
 */
data class Footprint(val cx: Float, val cz: Float, val halfX: Float, val halfZ: Float) {
  val longAlongX: Boolean
    get() = halfX >= halfZ

  val aspect: Float
    get() = maxOf(halfX, halfZ) / minOf(halfX, halfZ).coerceAtLeast(1e-4f)

  /** The four corners in the TABLE frame (y = 0), counter-clockwise seen from above. */
  fun corners(): List<Vec3> =
      listOf(Vec3(cx - halfX, 0f, cz - halfZ), Vec3(cx - halfX, 0f, cz + halfZ), Vec3(cx + halfX, 0f, cz + halfZ),
          Vec3(cx + halfX, 0f, cz - halfZ))

  companion object {
    /** Bounds of [positions] (flat xyz, ASSEMBLY frame) after [tableFromAssembly], projected on the table. */
    fun of(positions: FloatArray, tableFromAssembly: Pose3): Footprint? {
      if (positions.size < 9) return null
      var x0 = Float.MAX_VALUE
      var x1 = -Float.MAX_VALUE
      var z0 = Float.MAX_VALUE
      var z1 = -Float.MAX_VALUE
      for (i in 0 until positions.size - 2 step 3) {
        val p = tableFromAssembly.transform(Vec3(positions[i], positions[i + 1], positions[i + 2]))
        x0 = minOf(x0, p.x); x1 = maxOf(x1, p.x)
        z0 = minOf(z0, p.z); z1 = maxOf(z1, p.z)
      }
      return Footprint((x0 + x1) / 2, (z0 + z1) / 2, (x1 - x0) / 2, (z1 - z0) / 2)
    }
  }
}

/** Where a server box puts the anchor, and whether its yaw came from the box. */
data class BoxPlacement(val worldFromTable: Pose3, val yawFromBox: Boolean, val partCenter: Vec3, val lengthM: Float?)

/**
 * Coarse placement from one camera answer (pure, tested): the base part's box → its place on the table → the
 * anchor pose that puts the ghost's base part there.
 *
 * Position: the ray through the box centre meets the anchor's plane (height [planeY]) at the part's centre; the
 * anchor is moved so the [Footprint] centre lands there. Yaw: only when the box is clearly elongated. The box is
 * axis-aligned in the picture, so its long side is the part's long side only when the part lies along the picture
 * axes; measured on the table (metres, so perspective does not fool it), the box's aspect must be at least
 * [MIN_ASPECT_RATIO] of the part's own aspect (≈ within 8° for a 380 × 130 mm front) and its length within
 * 0.6–1.5 × the part's. Of the two headings 180° apart the one nearer the current yaw is taken. Otherwise the
 * current yaw is kept.
 */
object BasePlacement {
  const val MIN_PART_ASPECT = 1.8f
  const val MIN_ASPECT_RATIO = 0.75f

  fun fromBox(box: List<Double>, g: FrameGrab, planeY: Float, current: Pose3, fp: Footprint, useDistortion: Boolean = false): BoxPlacement? {
    val cam = g.worldFromCamera ?: return null
    val k = g.intrinsics
    val px = PickRays.boxToPx(box, k.width, k.height) ?: return null
    val uc = (px[0] + px[2]) / 2
    val vc = (px[1] + px[3]) / 2
    fun hit(u: Double, v: Double) = PickRays.intersectHorizontal(PickRays.worldRay(cam, u, v, k, useDistortion), planeY, PartsFinder.MAX_M)
    val center = hit(uc, vc) ?: return null
    var yaw = AssemblyAnchor.yawOf(current.q)
    var fromBox = false
    var length: Float? = null
    val l = hit(px[0], vc)
    val r = hit(px[2], vc)
    val t = hit(uc, px[1])
    val b = hit(uc, px[3])
    if (l != null && r != null && t != null && b != null && fp.aspect >= MIN_PART_ASPECT) {
      val across = r - l
      val along = b - t
      val la = AssemblyAnchor.flatDist(r, l)
      val lb = AssemblyAnchor.flatDist(b, t)
      val longDir = if (la >= lb) across else along
      val longLen = maxOf(la, lb)
      val shortLen = minOf(la, lb).coerceAtLeast(1e-4f)
      val partLen = 2 * maxOf(fp.halfX, fp.halfZ)
      length = longLen
      if (longLen / shortLen >= MIN_ASPECT_RATIO * fp.aspect && longLen in 0.6f * partLen..1.5f * partLen) {
        // yaw ψ turns TABLE +X to (cos ψ, 0, −sin ψ) and +Z to (sin ψ, 0, cos ψ)
        val psi = if (fp.longAlongX) atan2(-longDir.z, longDir.x) else atan2(longDir.x, longDir.z)
        val other = psi + PI.toFloat()
        yaw = if (AssemblyAnchor.yawDiff(psi, yaw) <= AssemblyAnchor.yawDiff(other, yaw)) psi else other
        fromBox = true
      }
    }
    val q = Quat.yaw(yaw)
    val off = q.rotate(Vec3(fp.cx, 0f, fp.cz))
    val origin = Vec3(center.x - off.x, planeY, center.z - off.z)
    return BoxPlacement(Pose3(origin, q), fromBox, center, length)
  }

  /** WORLD ← TABLE when nothing better is known: [aheadM] in front of the head, [belowM] below it, facing the user. */
  fun fixedSpot(head: Pose3, aheadM: Float = 0.55f, belowM: Float = 0.45f): Pose3 {
    val f = TableFinder.flatForward(head)
    val at = Vec3(head.t.x + f.x * aheadM, head.t.y - belowM, head.t.z + f.z * aheadM)
    return Pose3(at, Quat.yaw(TableFinder.yawFacing(at, head.t, f)))
  }

}
