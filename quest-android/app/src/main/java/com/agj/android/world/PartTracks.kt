package com.agj.android.world

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.vision.Frames
import com.agj.android.vision.Intrinsics
import com.agj.android.vision.PartFlowTracker
import kotlin.math.exp
import kotlin.math.hypot

/**
 * One box of one server answer, already in WORLD: instance [index] of item [id] (`boxes[index]` of the step-mode
 * answer; 0 = the one easiest to pick up).
 *
 * @property halfWpx @property halfHpx half the box size in pixels of the full camera image it was found in.
 * @property distM camera → [world] distance in that image (the box is re-drawn at the current distance).
 */
class PartInstance(
    val id: String,
    val index: Int,
    val world: Vec3,
    val method: String,
    val box: List<Double>,
    val halfWpx: Double,
    val halfHpx: Double,
    val distM: Float,
    val confidence: Double,
    val label: String,
    val kind: String = "",
    val radiusM: Float = 0.05f,
    val name: Map<String, String> = emptyMap(),
    val countText: Map<String, String> = emptyMap(),
    val countSeen: Int? = null,
    val countNeeded: Int? = null,
    /** Shown as a pick target (false: the base part tracked only to move the ghost). */
    val pick: Boolean = true,
    val base: Boolean = false,
    /** Belongs to the action shown now (the others are the rest of the step). */
    val current: Boolean = true,
)

/**
 * The parts on the table as **world-anchored tracks**, between server answers (pure, JVM-tested).
 *
 * Each track is a WORLD point. The headset moves all the time and the parts mostly do not, so a track's point does
 * not move because the picture moved: every camera frame ([onFlow]) the point is projected with that frame's camera
 * pose (the prediction) and compared with where the 2D tracker ([PartFlowTracker]) sees the part (the observation).
 *  - They agree (within [Config.movePx]): the part is where it was. The point is **not touched** (the Spatial SDK
 *    keeps a world-locked marker still whatever the head does).
 *  - They disagree by more than [Config.movePx] for [Config.moveFrames] frames while the head is fairly steady
 *    (under [Config.maxHeadDegS], [Config.maxHeadMS]: at higher rates a few ms of pose timing error looks like
 *    motion): **the part itself moved**. From then on the point is re-solved every frame (the ray through the
 *    observation onto the horizontal plane at the point's height: a part slides on the table), smoothed with
 *    [Config.tauS], until it rests for [Config.settleFrames] frames; [refine] (the depth raycast, then table /
 *    work height) corrects the height while it moves and when it settles.
 * A new server answer ([onAnswer]) re-seeds: an instance is matched to the nearest track of the same item (where
 * the track was when that picture was taken, [onGrab]), so identities, and the markers drawn from them, stay.
 * Logs AGQ `part_track` at most once a second.
 *
 * Threading: [onAnswer] on the parts worker, [onGrab] on the camera thread, [prepare]/[onFlow] on the flow thread,
 * [targets] on the render thread; every entry point is synchronized (they are short).
 */
class PartTracks(
    private val log: AgqLog,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val cfg: Config = Config(),
    private val useDistortion: Boolean = false,
) {
  class Config(
      /** Prediction vs observation, pixels of the full camera image (1280 wide: 10 px ≈ 7 mm at 0.6 m). */
      val movePx: Double = 10.0,
      val moveFrames: Int = 3,
      /** A moving track rests when it moves less than this per frame… */
      val stillPx: Double = 4.0,
      /** …for this many frames. */
      val settleFrames: Int = 5,
      /** Smoothing time constant of the shown point, s. */
      val tauS: Double = 0.08,
      /** A still track is moved by a server answer only when that is farther than this, m. */
      val snapM: Float = 0.015f,
      /** A jump of more than this in one frame is a tracking error, not a part, m. */
      val maxStepM: Float = 0.25f,
      val maxHeadDegS: Double = 25.0,
      val maxHeadMS: Double = 0.4,
      /** A track whose 2D tracking was lost is looked for again where its point projects, this long after. */
      val reseedAfterMs: Long = 1000L,
      /** An unmatched instance of an item still in view goes after this many answers without it. */
      val maxMisses: Int = 2,
      /** A track no answer has confirmed for this long is dropped. */
      val maxUnconfirmedMs: Long = 30_000L,
      val refineEveryMs: Long = 500L,
      val refineMaxM: Float = 0.30f,
  )

  private class Track(val key: Int, var inst: PartInstance) {
    var goal = inst.world // the solved point
    var pos = inst.world // the shown point (smoothed)
    var goalAtGrab = inst.world
    var movedSinceGrab = false
    var moving = false
    var moveCnt = 0
    var stillCnt = 0
    var needSeed = true
    var flow = false // the 2D tracker has it
    var points = 0
    var lostAtMs = 0L
    var confirmedMs = 0L
    var misses = 0
    var obsU = 0.0
    var obsV = 0.0
    var refineAtMs = 0L
    var seedGen = 0
  }

  private val tracks = ArrayList<Track>()
  private var nextKey = 1
  private val drops = ArrayList<Int>()
  private var prevCam: Pose3? = null
  private var prevTNs = 0L
  private var lastFlowMs = Long.MIN_VALUE / 2

  // the `part_track` second
  private var logAtMs = Long.MIN_VALUE / 2
  private var nFrames = 0
  private var nSeeded = 0
  private var nLost = 0
  private val lostWhy = HashMap<String, Int>()
  private var nMoved = 0
  private var nGated = 0
  private var nBase = 0
  private var msSum = 0.0
  private var msMax = 0.0

  /** The base part's track moved on the table: (from, to), WORLD. Flow thread. */
  @Volatile var onBaseMoved: (Vec3, Vec3) -> Unit = { _, _ -> }

  /**
   * A better WORLD point for a ray through a moving part (depth raycast → table → work height); answers on any
   * thread, null = nothing hit. Null property = keep the horizontal-plane solve.
   */
  @Volatile var refine: ((Ray, (Vec3?) -> Unit) -> Unit)? = null

  /** Are there tracks to follow (does the camera thread need to copy a frame)? */
  @Volatile var wantsFrames = false
    private set

  val size: Int
    @Synchronized get() = tracks.size

  @Synchronized
  fun clear(keepBase: Boolean = false) {
    val it = tracks.iterator()
    while (it.hasNext()) {
      val t = it.next()
      if (keepBase && t.inst.base) {
        if (t.inst.pick) t.inst = t.inst.copyOf(pick = false)
        continue
      }
      drops += t.key
      it.remove()
    }
    wantsFrames = tracks.isNotEmpty()
  }

  /** The picture for the next answer is being taken now: remember where every track is. Any thread. */
  @Synchronized
  fun onGrab() {
    for (t in tracks) {
      t.goalAtGrab = t.goal
      t.movedSinceGrab = t.moving
    }
  }

  /**
   * A server answer: [answered] = the item ids it spoke about (found or missing), [instances] = every box located
   * in WORLD, taken at [tMs]. Tracks of items not in [answered] stay as they are.
   */
  @Synchronized
  fun onAnswer(answered: Set<String>, instances: List<PartInstance>, tMs: Long) {
    val flowRuns = clockMs() - lastFlowMs < 500
    val byId = instances.groupBy { it.id }
    for (id in answered) {
      val insts = byId[id] ?: emptyList()
      val old = tracks.filter { it.inst.id == id }
      val pairs = matchNearest(old.map { if (it.movedSinceGrab) it.goalAtGrab else it.goal }, insts.map { it.world }) { i ->
        maxOf(MATCH_M, insts[i].radiusM)
      }
      val usedTracks = HashSet<Int>()
      val usedInsts = HashSet<Int>()
      for ((ti, ii) in pairs) {
        val t = old[ti]
        val inst = insts[ii]
        usedTracks += ti
        usedInsts += ii
        t.inst = inst
        t.confirmedMs = tMs
        t.misses = 0
        if (!t.movedSinceGrab && !t.moving) {
          // it has not moved since the picture: the server's point is as fresh as ours; take it when it differs
          if ((inst.world - t.goal).length() > cfg.snapM) {
            t.goal = inst.world
            if (!flowRuns) t.pos = inst.world
          }
          t.needSeed = true
        } else if (!t.flow) {
          t.needSeed = true
        }
      }
      insts.forEachIndexed { i, inst ->
        if (i in usedInsts) return@forEachIndexed
        tracks += Track(nextKey++, inst).also { it.confirmedMs = tMs }
      }
      old.forEachIndexed { i, t ->
        if (i in usedTracks) return@forEachIndexed
        t.misses++
        // the item is gone from the picture, or this instance was not seen twice / is not being followed
        if (insts.isEmpty() || t.misses >= cfg.maxMisses || !t.flow) {
          drops += t.key
          tracks.remove(t)
        }
      }
    }
    wantsFrames = tracks.isNotEmpty()
  }

  /** The tracks as pick targets (age = since the last answer that saw them); [hideId] is left out. */
  @Synchronized
  fun targets(nowMs: Long = clockMs(), hideId: String? = null): List<PickTarget> =
      tracks
          .filter { it.inst.pick && it.inst.id != hideId }
          .map { t ->
            val i = t.inst
            PickTarget(i.id, i.label.ifEmpty { i.id }, t.pos, i.confidence, nowMs - t.confirmedMs, i.kind, i.method, i.radiusM,
                i.box, t.key, i.index, i.name, i.countText, i.countSeen, i.countNeeded, t.flow, t.moving, i.current)
          }

  /** What the 2D tracker must do before this frame: boxes to start (where the points project now) and to forget. */
  class Prep(val seeds: List<PartFlowTracker.Seed>, val drops: List<Int>)

  @Synchronized
  fun prepare(worldFromCamera: Pose3, k: Intrinsics): Prep {
    val now = clockMs()
    val seeds = ArrayList<PartFlowTracker.Seed>()
    // no answer has seen it for a long time (the step's request no longer asks for it, or the server is away)
    if (tracks.removeAll { t -> (now - t.confirmedMs > cfg.maxUnconfirmedMs).also { if (it) drops += t.key } }) {
      wantsFrames = tracks.isNotEmpty()
    }
    for (t in tracks) {
      if (!t.needSeed && !t.flow && t.lostAtMs != 0L && now - t.lostAtMs >= cfg.reseedAfterMs) t.needSeed = true
      if (!t.needSeed) continue
      val uv = PickRays.project(worldFromCamera, k, t.goal, useDistortion) ?: continue
      if (uv.first < 0 || uv.second < 0 || uv.first > k.width - 1 || uv.second > k.height - 1) continue // not in view yet
      val d = (t.goal - worldFromCamera.t).length().coerceAtLeast(0.05f)
      val s = (t.inst.distM / d).toDouble().coerceIn(0.5, 2.0)
      val hw = t.inst.halfWpx * s
      val hh = t.inst.halfHpx * s
      t.needSeed = false
      t.lostAtMs = now // a seed that finds nothing is tried again after reseedAfterMs
      t.seedGen++
      seeds += PartFlowTracker.Seed(t.key, doubleArrayOf(uv.first - hw, uv.second - hh, uv.first + hw, uv.second + hh))
    }
    val d = ArrayList(drops)
    drops.clear()
    return Prep(seeds, d)
  }

  /**
   * One camera frame's observations ([obs]: track key → box centre in full-image pixels, or lost), with that
   * frame's camera pose, intrinsics and capture time. [ms] = what the 2D tracking cost (for the log).
   */
  @Synchronized
  fun onFlow(obs: List<PartFlowTracker.Obs>, worldFromCamera: Pose3, k: Intrinsics, tNs: Long, ms: Double = 0.0, seeded: Int = 0) {
    val now = clockMs()
    lastFlowMs = now
    val prev = prevCam
    val dt = if (prev == null) 0.0 else (tNs - prevTNs) / 1e9
    // a first frame or a gap says nothing about the head: wait
    val steady =
        prev != null && dt > 0 && dt < 0.5 &&
            Frames.angleDeg(prev.q, worldFromCamera.q) / dt <= cfg.maxHeadDegS &&
            (worldFromCamera.t - prev.t).length() / dt <= cfg.maxHeadMS
    prevCam = worldFromCamera
    prevTNs = tNs
    val a = if (dt > 0 && dt < 0.5) (1 - exp(-dt / cfg.tauS)).toFloat() else 1f
    val byKey = HashMap<Int, PartFlowTracker.Obs>(obs.size * 2)
    for (o in obs) byKey[o.key] = o
    var gated = false
    for (t in tracks) {
      val o = byKey[t.key]
      if (o != null && o.lost != null) {
        lose(t, o.lost, now, tell = false)
      } else if (o != null) {
        t.flow = true
        t.points = o.points
        t.obsU = o.cx
        t.obsV = o.cy
        val pred = PickRays.project(worldFromCamera, k, t.goal, useDistortion)
        if (pred == null) {
          lose(t, "behind", now, tell = true)
        } else if (!steady) {
          t.moveCnt = 0
          gated = true
        } else {
          val r = hypot(o.cx - pred.first, o.cy - pred.second)
          if (!t.moving) {
            if (r > cfg.movePx) {
              if (++t.moveCnt >= cfg.moveFrames) {
                t.moving = true
                t.movedSinceGrab = true
                t.stillCnt = 0
                t.refineAtMs = now
                nMoved++
              }
            } else {
              t.moveCnt = 0
            }
          } else if (r < cfg.stillPx) {
            if (++t.stillCnt >= cfg.settleFrames) {
              t.moving = false
              t.moveCnt = 0
              askRefine(t, worldFromCamera, k)
            }
          } else {
            t.stillCnt = 0
          }
          if (t.moving) follow(t, worldFromCamera, k, now)
        }
      }
      t.pos = if (a >= 1f || (t.pos - t.goal).length() < 0.0005f) t.goal else t.pos.lerp(t.goal, a)
    }
    if (gated) nGated++
    nFrames++
    nSeeded += seeded
    msSum += ms
    msMax = maxOf(msMax, ms)
    if (now - logAtMs >= 1000) logSecond(now)
  }

  /** The part moved: put its point under the observation, on the horizontal plane at its height. */
  private fun follow(t: Track, cam: Pose3, k: Intrinsics, now: Long) {
    val ray = PickRays.worldRay(cam, t.obsU, t.obsV, k, useDistortion)
    val p = PickRays.intersectHorizontal(ray, t.goal.y, PartsFinder.MAX_M)
    if (p == null || (p - t.goal).length() > cfg.maxStepM) {
      lose(t, "jump", now, tell = true)
      return
    }
    val from = t.goal
    t.goal = p
    if (t.inst.base) {
      nBase++
      onBaseMoved(from, p)
    } else if (now - t.refineAtMs >= cfg.refineEveryMs) {
      askRefine(t, cam, k)
    }
  }

  private fun askRefine(t: Track, cam: Pose3, k: Intrinsics) {
    val f = refine ?: return
    if (t.inst.base) return // the base part slides on the anchor's plane
    t.refineAtMs = clockMs()
    val key = t.key
    val gen = t.seedGen
    f(PickRays.worldRay(cam, t.obsU, t.obsV, k, useDistortion)) { p -> if (p != null) applyRefine(key, gen, p) }
  }

  @Synchronized
  private fun applyRefine(key: Int, gen: Int, p: Vec3) {
    val t = tracks.firstOrNull { it.key == key } ?: return
    if (t.seedGen != gen || (p - t.goal).length() > cfg.refineMaxM) return
    t.goal = p
  }

  private fun lose(t: Track, why: String, now: Long, tell: Boolean) {
    t.flow = false
    t.moving = false
    t.moveCnt = 0
    t.points = 0
    t.lostAtMs = now
    if (tell) drops += t.key
    nLost++
    lostWhy[why] = (lostWhy[why] ?: 0) + 1
  }

  private fun logSecond(now: Long) {
    val dtS = if (logAtMs < 0) 1.0 else (now - logAtMs) / 1000.0
    logAtMs = now
    val alive = tracks.filter { it.flow }
    log.event(
        "part_track",
        mapOf(
            "tracks" to tracks.size,
            "alive" to alive.size,
            "moving" to tracks.count { it.moving },
            "seeded" to nSeeded,
            "lost" to nLost,
            "lost_why" to lostWhy.toMap(),
            "pts" to if (alive.isEmpty()) 0.0 else Math.round(alive.sumOf { it.points } * 10.0 / alive.size) / 10.0,
            "ms" to if (nFrames == 0) 0.0 else Math.round(msSum / nFrames * 100) / 100.0,
            "ms_max" to Math.round(msMax * 100) / 100.0,
            "frames" to nFrames,
            "hz" to Math.round(nFrames / dtS.coerceAtLeast(0.2) * 10) / 10.0,
            "moved" to nMoved,
            "head_gated" to nGated,
            "base_moves" to nBase,
            "ids" to tracks.map { "${it.inst.id}#${it.inst.index}" },
        ),
    )
    nFrames = 0
    nSeeded = 0
    nLost = 0
    lostWhy.clear()
    nMoved = 0
    nGated = 0
    nBase = 0
    msSum = 0.0
    msMax = 0.0
  }

  private fun PartInstance.copyOf(pick: Boolean) =
      PartInstance(id, index, world, method, box, halfWpx, halfHpx, distM, confidence, label, kind, radiusM, name,
          countText, countSeen, countNeeded, pick, base, current)

  companion object {
    /** An answer's instance is the same thing as a track within this (or the part's own radius), m. */
    const val MATCH_M = 0.06f

    /**
     * Greedy nearest matching of [old] points to [fresh] points: pairs (old index, fresh index), closest first,
     * each used once, only within `gate(fresh index)` metres.
     */
    fun matchNearest(old: List<Vec3>, fresh: List<Vec3>, gate: (Int) -> Float): List<Pair<Int, Int>> {
      val cand = ArrayList<Triple<Float, Int, Int>>()
      for (i in old.indices) for (j in fresh.indices) {
        val d = (old[i] - fresh[j]).length()
        if (d <= gate(j)) cand += Triple(d, i, j)
      }
      cand.sortBy { it.first }
      val usedO = HashSet<Int>()
      val usedF = HashSet<Int>()
      val out = ArrayList<Pair<Int, Int>>()
      for ((_, i, j) in cand) {
        if (i in usedO || j in usedF) continue
        usedO += i
        usedF += j
        out += i to j
      }
      return out
    }
  }
}
