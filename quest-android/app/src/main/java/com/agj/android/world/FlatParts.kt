package com.agj.android.world

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.Assembly
import com.agj.android.guide.Guide
import com.agj.android.guide.GuideAction
import com.agj.android.vision.BoardCue
import com.agj.android.vision.BoardCueHub
import com.agj.android.vision.BoardCueResult
import com.agj.android.vision.CueVote
import com.agj.android.vision.FaceLook
import com.agj.android.vision.KindCue
import com.agj.android.vision.RectCue
import com.agj.android.vision.PanelConsensus
import com.agj.android.vision.RectsResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * FLAT-PARTS: which loose board each white rectangle on the work surface is, and the one to pick up now.
 *
 * The camera pass (vision/PanelPose.kt `findRects`, 3 Hz, on the device) gives every clean white rectangle on the
 * surface with its measured sides. Here, pure Kotlin:
 *  - the guide's flat boards and their two large sides ([FlatBoards.of]: each part's mesh boxed in its own frame;
 *    runners and hardware are not boards). Identical boards (the two drawer sides) are one [BoardKind];
 *  - rectangles followed over time in WORLD (the same rectangle = centre within 6 cm; sizes = the median of its last
 *    5 measurements), each labelled as a kind only when its size is within the tolerances of that kind AND that kind
 *    is clearly the best match ([FlatMatch]); a rectangle on the assembly's own footprint is the assembly, left out;
 *  - for the action shown now, the rectangle of the needed kind nearest to the assembly: [lit] (drawn by
 *    world/FlatPartOutline.kt) and, through [merge], the pick target the label and the hand arc point at.
 * When nothing is labelled, [merge] returns the server's targets untouched: everything behaves as before.
 *
 * Frames: WORLD only (right-handed, +Y up); the detector's TABLE frame comes in with each result as WORLD ← TABLE.
 */

/**
 * One flat board of the guide. [lengthM] ≥ [widthM] are its two large sides, [thickM] the small one.
 *
 * @property addedAt where in the guide's order it is placed (step index × 1000 + action index); null = never placed
 *   by an action (there from the start).
 */
data class Board(
    val partId: String,
    val node: String,
    val lengthM: Double,
    val widthM: Double,
    val thickM: Double = 0.016,
    val addedAt: Int? = null,
    val names: Map<String, String> = emptyMap(),
)

/** Boards of one size: from above they cannot be told apart. */
class BoardKind(val boards: List<Board>) {
  val lengthM = boards.map { it.lengthM }.average()
  val widthM = boards.map { it.widthM }.average()

  /** For the log: "drawer_side_left+drawer_side_right". */
  val name = boards.joinToString("+") { it.partId }
}

data class FlatPartsConfig(
    /**
     * A rectangle is a kind only within these of its sides. On the headset's first recording (lightness only) the
     * boards measured: front 408 × 201 (true 400 × 200), sides 446 × 97..108 (450 × 100), back 324 × 111 (350 × 100,
     * always about 26 mm short), bottom 441 × 338 (450 × 350); spread ±10 mm.
     */
    val tolLengthM: Double = 0.045,
    val tolWidthM: Double = 0.030,
    /** … and when the next best kind is at least this many times farther, in tolerances. */
    val clearRatio: Double = 1.6,
    /** The same rectangle as before: centre within this. */
    val sameM: Float = 0.06f,
    /** Sizes, centre and yaw are the median of this many measurements. */
    val window: Int = 5,
    /** Measurements before a rectangle may be lit (one frame can be a hand's edge or a shadow). */
    val minSeen: Int = 2,
    /** A rectangle is live this long after it was last seen: lit, and in place of the server's box. */
    val liveMs: Long = 2000,
    /** The outline fades over the last part of [liveMs]. */
    val fadeMs: Long = 500,
    /** A rectangle not seen for this long is forgotten (its count and index start again). */
    val forgetMs: Long = 10_000,
    /** Among several rectangles of the needed kind the lit one changes only for one this much nearer the assembly. */
    val stickM: Float = 0.10f,
    /** The assembly's footprint is grown by this before a rectangle counts as "on it". */
    val footprintGrowM: Float = 0.03f,
    /** Boards whose sides agree within this are one kind. */
    val sameKindM: Double = 0.005,
    /** A board: thinner than this, sides at least the detector's smallest rectangle. */
    val maxThickM: Double = 0.030,
    val minLengthM: Double = 0.150,
    val minWidthM: Double = 0.050,
)

/** Rectangle size → kind. Pure. */
object FlatMatch {
  /** How far a measured rectangle is from [k], in tolerances (1 = at one tolerance on one side). */
  fun distance(lengthM: Double, widthM: Double, k: BoardKind, cfg: FlatPartsConfig = FlatPartsConfig()): Double {
    val dl = (lengthM - k.lengthM) / cfg.tolLengthM
    val dw = (widthM - k.widthM) / cfg.tolWidthM
    return sqrt(dl * dl + dw * dw)
  }

  /**
   * The kind a [lengthM] × [widthM] rectangle is, or null: (a) within the tolerances of the nearest kind, taken
   * together ([distance] ≤ 1: 45 mm off in length alone, 30 mm in width alone, less of each when both are off:
   * every real sighting on the headset is inside, a rectangle at both limits is not) and (b) the next nearest kind
   * at least [FlatPartsConfig.clearRatio] times farther.
   */
  fun classify(lengthM: Double, widthM: Double, kinds: List<BoardKind>, cfg: FlatPartsConfig = FlatPartsConfig()): BoardKind? {
    val ds = kinds.map { it to distance(lengthM, widthM, it, cfg) }.sortedBy { it.second }
    val (best, d1) = ds.firstOrNull() ?: return null
    if (d1 > 1.0) return null
    val d2 = ds.getOrNull(1)?.second ?: return best
    return if (d2 >= cfg.clearRatio * d1) best else null
  }

  /** Boards → kinds (sides within [FlatPartsConfig.sameKindM]), in the order given. */
  fun kinds(boards: List<Board>, cfg: FlatPartsConfig = FlatPartsConfig()): List<BoardKind> {
    val groups = ArrayList<ArrayList<Board>>()
    for (b in boards) {
      val g = groups.firstOrNull { abs(it[0].lengthM - b.lengthM) <= cfg.sameKindM && abs(it[0].widthM - b.widthM) <= cfg.sameKindM }
      if (g != null) g += b else groups += arrayListOf(b)
    }
    return groups.map { BoardKind(it) }
  }

  /** Do two convex outlines overlap on the ground (WORLD X, Z)? Separating-axis test. */
  fun overlaps(a: List<Vec3>, b: List<Vec3>): Boolean {
    for (poly in listOf(a, b)) {
      for (i in poly.indices) {
        val p = poly[i]
        val q = poly[(i + 1) % poly.size]
        val nx = -(q.z - p.z)
        val nz = q.x - p.x
        if (abs(nx) + abs(nz) < 1e-9f) continue
        val pa = a.map { it.x * nx + it.z * nz }
        val pb = b.map { it.x * nx + it.z * nz }
        if (pa.max() < pb.min() || pb.max() < pa.min()) return false
      }
    }
    return true
  }
}

/** The guide's boards. */
object FlatBoards {
  /**
   * Every `part_*` node whose mesh, boxed in the node's own frame (the baked ASSEMBLY vertices through the inverse
   * of its final transform), is a board: small side ≤ [FlatPartsConfig.maxThickM], the others at least the
   * detector's smallest rectangle. Ids and names from the manifest. Sorted by node name.
   */
  fun of(a: Assembly, cfg: FlatPartsConfig = FlatPartsConfig()): List<Board> {
    val g = a.guide ?: return emptyList()
    val stepIndex = g.steps.withIndex().associate { it.value.n to it.index }
    val out = ArrayList<Board>()
    for (node in g.parts.keys.sorted()) {
      if (!node.startsWith("part_")) continue
      val geo = g.parts[node] ?: continue
      if (geo.vertexCount == 0) continue
      val inv = g.finalPoses[node]?.inverse() ?: Pose3.IDENTITY
      val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
      val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
      val p = geo.positions
      var i = 0
      while (i + 2 < p.size) {
        val v = inv.transform(Vec3(p[i], p[i + 1], p[i + 2]))
        lo[0] = minOf(lo[0], v.x); hi[0] = maxOf(hi[0], v.x)
        lo[1] = minOf(lo[1], v.y); hi[1] = maxOf(hi[1], v.y)
        lo[2] = minOf(lo[2], v.z); hi[2] = maxOf(hi[2], v.z)
        i += 3
      }
      val d = (0 until 3).map { (hi[it] - lo[it]).toDouble() }.sortedDescending()
      if (d[2] > cfg.maxThickM || d[0] < cfg.minLengthM || d[1] < cfg.minWidthM) continue
      val part = a.manifest.parts.firstOrNull { node in it.nodes }
      val act = g.steps.flatMap { it.actions }.firstOrNull { it.node == node }
      val id = part?.id ?: act?.partId ?: node
      val names = LinkedHashMap<String, String>()
      (part?.names?.en?.ifEmpty { null } ?: part?.name?.ifEmpty { null } ?: act?.labels?.get("en")?.name)?.let { names["en"] = it }
      (part?.names?.el?.ifEmpty { null } ?: act?.labels?.get("el")?.name)?.let { names["el"] = it }
      val added = g.addedAt[node]?.let { (s, ai) -> (stepIndex[s] ?: 0) * 1000 + ai }
      out += Board(id, node, d[0], d[1], d[2], added, names)
    }
    return out
  }
}

/** The rectangle to outline now. [corners]: the four top-face corners in WORLD, in order round the rectangle. */
class LitPart(
    val key: Int,
    val partId: String,
    val index: Int,
    val center: Vec3,
    val corners: List<Vec3>,
    val lengthM: Double,
    val widthM: Double,
    val ageMs: Long,
    /** 1, fading to 0 over the last [FlatPartsConfig.fadeMs] before it stops being live. */
    val opacity: Float,
    /** BOARD-CUE: the face that is up, the board it is (part id) and whether its own length axis points against the corners' order; null = not known. */
    val look: FaceLook? = null,
    val which: String? = null,
    val turned: Boolean? = null,
)

/** BOARD-CUE: the words for how a board lies (the label's second part, the coach's hint). */
object BoardWords {
  fun face(look: FaceLook, lang: String): String =
      if (lang == "el")
          when (look) {
            FaceLook.RAIL -> "με τη ράγα προς τα πάνω"
            FaceLook.GROOVE -> "με το αυλάκι προς τα πάνω"
            FaceLook.HOLES -> "με τις τρύπες προς τα πάνω"
          }
      else
          when (look) {
            FaceLook.RAIL -> "rail side up"
            FaceLook.GROOVE -> "groove side up"
            FaceLook.HOLES -> "holes up"
          }

  /** The pill above the board to take: "ράγα πάνω" (short: the pill is one line). */
  fun short(look: FaceLook, lang: String): String =
      if (lang == "el")
          when (look) {
            FaceLook.RAIL -> "ράγα πάνω"
            FaceLook.GROOVE -> "αυλάκι πάνω"
            FaceLook.HOLES -> "τρύπες πάνω"
          }
      else
          when (look) {
            FaceLook.RAIL -> "rail up"
            FaceLook.GROOVE -> "groove up"
            FaceLook.HOLES -> "holes up"
          }

  /** Spoken after "Πάρε: …, αριστερά σου.": what the app sees of the board as it lies. */
  fun seen(look: FaceLook, lang: String): String =
      if (lang == "el") "Το βλέπω ${face(look, lang)}." else "I see it ${face(look, lang)}."

  /** "This is the right side panel, take the other one." [name]: the board that is seen (lower case, as in the manifest). */
  fun other(name: String, lang: String): String = if (lang == "el") "Αυτό είναι το $name, πάρε το άλλο." else "This is the $name. Take the other one."
}

/**
 * See the file comment. Thread-safe: [onRects] / [update] come from the detector's thread, [setAction] and the
 * readers ([lit], [merge]) from any thread.
 */
class FlatParts(
    private val log: AgqLog,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    val cfg: FlatPartsConfig = FlatPartsConfig(),
) {
  private class Meas(val x: Float, val y: Float, val z: Float, val yaw: Double, val l: Double, val w: Double, val rectness: Double, val contrast: Double,
      /** BOARD-CUE: this sighting's cue, and the WORLD yaw of the board's own +length axis when the cue says which way round. */
      val cue: BoardCueResult? = null, val heading: Double? = null)

  private class Track(val key: Int) {
    val m = ArrayDeque<Meas>()
    var seen = 0
    var lastMs = 0L
    var kind: BoardKind? = null
    var index = -1

    // BOARD-CUE: how it lies; each is taken (or changed) after three agreeing sightings in a row
    val look = CueVote<FaceLook>()
    val which = CueVote<String>()
    val heading = CueVote<Double>(same = { a, b -> abs(atan2(sin(a - b), cos(a - b))) < Math.PI / 3 })
    var fresh: Meas? = null

    fun resetCue() {
      look.reset()
      which.reset()
      heading.reset()
    }

    /** The board's own +length axis points against [yaw] (the corners' order); null = not known. */
    fun turned(): Boolean? = heading.value?.let { h -> cos(h - yaw) < 0 }

    fun med(f: (Meas) -> Double): Double = PanelConsensus.median(m.map(f))

    val x get() = med { it.x.toDouble() }.toFloat()
    val z get() = med { it.z.toDouble() }.toFloat()
    val y get() = m.last().y
    val l get() = med { it.l }
    val w get() = med { it.w }

    /** Median yaw, taken near the newest (the long side's direction is known modulo 180°). */
    val yaw: Double
      get() {
        val newest = m.last().yaw
        return PanelConsensus.median(m.map { newest + PanelConsensus.wrapHalfPi(it.yaw - newest) })
      }

    fun center() = Vec3(x, y, z)

    fun corners(): List<Vec3> {
      val c = center()
      val a = yaw
      val lx = Vec3(cos(a).toFloat(), 0f, sin(a).toFloat()) * (l / 2).toFloat()
      val sx = Vec3(-sin(a).toFloat(), 0f, cos(a).toFloat()) * (w / 2).toFloat()
      return listOf(c - lx - sx, c + lx - sx, c + lx + sx, c - lx + sx)
    }
  }

  private var boards: List<Board> = emptyList()
  private var kinds: List<BoardKind> = emptyList()
  private var placedGeo: List<Pair<Int?, FloatArray>> = emptyList()
  private var stepIndex: Map<Int, Int> = emptyMap()
  private val tracks = ArrayList<Track>()
  private var nextKey = 1
  private var curStep: Int? = null
  private var curAction: Int? = null
  private var cur = 0
  private var needed: Board? = null
  private var litKey: Int? = null
  private var assemblyPos: Vec3? = null
  private var lastLogMs = Long.MIN_VALUE / 2

  /** The guide has boards to look for: run the detector's rectangle pass. */
  @Volatile var wanted = false
    private set

  /** WHERE-IS: the session's memory of where each board was last seen (world/WhereIs.kt); fed by [update]. */
  @Volatile var whereIs: WhereIs? = null

  /** The loaded guide; null clears everything. */
  @Synchronized
  fun setAssembly(a: Assembly?) {
    val g = a?.guide
    stepIndex = g?.steps?.withIndex()?.associate { it.value.n to it.index } ?: emptyMap()
    placedGeo = g?.parts?.filterKeys { it.startsWith("part_") }?.map { (node, geo) ->
      g.addedAt[node]?.let { (s, ai) -> (stepIndex[s] ?: 0) * 1000 + ai } to geo.positions
    } ?: emptyList()
    setBoards(if (a == null) emptyList() else FlatBoards.of(a, cfg))
    val cueLog = if (g != null) buildCues(g) else null // BOARD-CUE: every way each board can lie, from the meshes
    log.event("flat_parts", mapOf("boards" to boards.map { mapOf("part" to it.partId, "node" to it.node,
        "length_mm" to Math.round(it.lengthM * 1000), "width_mm" to Math.round(it.widthM * 1000), "thick_mm" to Math.round(it.thickM * 1000)) },
        "kinds" to kinds.map { it.name }, "board_cue" to cueLog))
  }

  /** The boards to tell apart (tests give them directly; the app goes through [setAssembly]). */
  @Synchronized
  fun setBoards(b: List<Board>) {
    boards = b
    kinds = FlatMatch.kinds(b, cfg)
    tracks.clear()
    needed = null
    litKey = null
    wanted = kinds.isNotEmpty()
    setCues(emptyMap()) // BOARD-CUE: the cue belongs to a guide's meshes ([setAssembly])
  }

  /** The action shown now: its board (a `place` of one) is the one to light; boards placed before it are not looked for. */
  @Synchronized
  fun setAction(step: Int, action: Int, act: GuideAction?) = setAction(step, action, if (act?.kind == ActionKind.PLACE) act.node else null, act?.partId)

  @Synchronized
  fun setAction(step: Int, action: Int, node: String?, partId: String?) {
    curStep = step
    curAction = action
    cur = (stepIndex[step] ?: step) * 1000 + action
    val n = if (node == null) null else boards.firstOrNull { it.node == node } ?: boards.firstOrNull { it.partId == partId && !placed(it) }
    if (n?.node != needed?.node) litKey = null
    needed = n
  }

  private fun placed(b: Board): Boolean = b.addedAt != null && b.addedAt < cur

  private fun kindOf(b: Board?): BoardKind? = if (b == null) null else kinds.firstOrNull { k -> k.boards.any { it.node == b.node } }

  /**
   * The footprint on the surface of everything placed before the action shown now, WORLD corners (grown by
   * [FlatPartsConfig.footprintGrowM]); null when nothing is placed yet or there is no anchor.
   * [worldFromAnchor]: the assembly anchor (WORLD ← its Y-up table frame); [anchorFromAssembly]: the step's pose.
   */
  @Synchronized
  fun assemblyFootprint(worldFromAnchor: Pose3?, anchorFromAssembly: Pose3): List<Vec3>? {
    if (worldFromAnchor == null) return null
    var all: Footprint? = null
    for ((added, positions) in placedGeo) {
      if (added != null && added >= cur) continue
      val f = Footprint.of(positions, anchorFromAssembly) ?: continue
      val u = all
      all =
          if (u == null) f
          else {
            val x0 = minOf(u.cx - u.halfX, f.cx - f.halfX)
            val x1 = maxOf(u.cx + u.halfX, f.cx + f.halfX)
            val z0 = minOf(u.cz - u.halfZ, f.cz - f.halfZ)
            val z1 = maxOf(u.cz + u.halfZ, f.cz + f.halfZ)
            Footprint((x0 + x1) / 2, (z0 + z1) / 2, (x1 - x0) / 2, (z1 - z0) / 2)
          }
    }
    val f = all ?: return null
    return Footprint(f.cx, f.cz, f.halfX + cfg.footprintGrowM, f.halfZ + cfg.footprintGrowM).corners().map { worldFromAnchor.transform(it) }
  }

  /** One detector pass, with the assembly from the world module's anchor. Detector thread. */
  fun onRects(r: RectsResult, worldFromTable: Pose3?, worldFromAnchor: Pose3?, anchorFromAssembly: Pose3, nowMs: Long = clockMs()) =
      update(r, worldFromTable, assemblyFootprint(worldFromAnchor, anchorFromAssembly), worldFromAnchor?.t, nowMs)

  /**
   * One detector pass: [r]'s rectangles (TABLE frame of [worldFromTable]) into the tracks, labels, the lit one, the
   * log. [assembly]: the assembly's footprint in WORLD (rectangles on it are left out); [assemblyAt]: where the
   * assembly is, for "the nearest to the assembly".
   */
  @Synchronized
  fun update(r: RectsResult, worldFromTable: Pose3?, assembly: List<Vec3>?, assemblyAt: Vec3?, nowMs: Long = clockMs()) {
    assemblyPos = assemblyAt
    var onAssembly = 0
    if (worldFromTable != null) {
      val taken = HashSet<Int>()
      for (f in r.rects) {
        val c = worldFromTable.transform(Vec3(f.x.toFloat(), f.y.toFloat(), f.topM.toFloat()))
        val d = worldFromTable.q.rotate(Vec3(cos(f.yaw).toFloat(), sin(f.yaw).toFloat(), 0f))
        val yaw = atan2(d.z.toDouble(), d.x.toDouble())
        val cue = f.cue // BOARD-CUE
        val meas = Meas(c.x, c.y, c.z, yaw, f.lengthM, f.widthM, f.rectness, f.contrast, cue, cue?.turned?.let { if (it) yaw + Math.PI else yaw })
        if (assembly != null) {
          val one = Track(0).also { it.m.addLast(meas) }
          if (FlatMatch.overlaps(one.corners(), assembly)) {
            onAssembly++
            continue
          }
        }
        val t =
            tracks.filter { it.key !in taken }.minByOrNull { flat(it.x - c.x, it.z - c.z) }?.takeIf { flat(it.x - c.x, it.z - c.z) <= cfg.sameM }
                ?: Track(nextKey++).also { tracks += it }
        taken += t.key
        if (nowMs - t.lastMs > cfg.liveMs && t.m.isNotEmpty()) {
          // seen again after a while: another board may lie there now (the recording: the bottom, then the front):
          // its size is measured afresh, not mixed with the old one
          t.m.clear()
          t.seen = 0
          t.resetCue() // BOARD-CUE
        }
        t.m.addLast(meas)
        t.fresh = meas // BOARD-CUE
        while (t.m.size > cfg.window) t.m.removeFirst()
        t.seen++
        t.lastMs = nowMs
      }
    }
    tracks.removeAll { nowMs - it.lastMs > cfg.forgetMs }
    // a rectangle that now lies on the assembly (the part was put in place, or the ghost moved onto it) is not loose
    if (assembly != null) tracks.removeAll { FlatMatch.overlaps(it.corners(), assembly) }
    for (t in tracks) {
      val k = FlatMatch.classify(t.l, t.w, kinds, cfg)
      if (k !== t.kind) {
        t.index = -1
        t.resetCue() // BOARD-CUE
      }
      t.kind = k
      // BOARD-CUE: this pass's sighting votes on how the board lies (a sighting scored as another kind does not)
      val m = t.fresh
      t.fresh = null
      if (m != null) {
        val cue = m.cue?.takeIf { k != null && it.kind.name == k.name }
        t.look.add(cue?.look)
        t.which.add(cue?.board?.let { cue.kind.boards[it] })
        t.heading.add(if (cue != null) m.heading else null)
      }
    }
    // identical boards: each rectangle of a kind keeps its index while it is followed; new ones take the free ones by position
    for (k in kinds) {
      val of = tracks.filter { it.kind === k }
      val used = of.map { it.index }.filter { it >= 0 }.toHashSet()
      for (t in of.filter { it.index < 0 }.sortedWith(compareBy({ it.x }, { it.z }))) {
        var i = 0
        while (i in used) i++
        t.index = i
        used += i
      }
    }
    // WHERE-IS: every live labelled board into the memory; a board placed by an earlier action leaves it
    whereIs?.boards(
        tracks.filter { it.kind != null && it.seen >= cfg.minSeen && nowMs - it.lastMs <= cfg.liveMs }
            .map { WhereIs.BoardSight(it.kind!!.name, it.index, it.center(), it.which.value) },
        kinds.associate { k -> k.name to k.boards.count { b -> !placed(b) } },
        boards.filter { placed(it) }.map { it.partId }.toSet())
    val lit = choose(nowMs)
    litKey = lit?.key
    if (nowMs - lastLogMs >= 1000) {
      lastLogMs = nowMs
      val live = tracks.filter { nowMs - it.lastMs <= cfg.liveMs }
      fun mm(v: Double) = Math.round(v * 1000)
      fun fields(t: Track): Map<String, Any?> =
          mapOf("length_mm" to mm(t.l), "width_mm" to mm(t.w), "rectness" to Math.round(t.med { it.rectness } * 1000) / 1000.0,
              "contrast" to Math.round(t.med { it.contrast } * 10) / 10.0, "n" to t.seen, "pos_m" to t.center()) + cueFields(t) // BOARD-CUE
      log.event(
          "flat_parts",
          mapOf(
              "rects" to r.rects.size,
              "reason" to r.reason,
              "ms" to Math.round(r.ms * 10) / 10.0,
              "candidates" to r.candidates,
              "parts" to live.filter { it.kind != null }.map { mapOf("part" to it.kind!!.name, "index" to it.index) + fields(it) },
              "cue_ms" to Math.round(r.rects.sumOf { it.cue?.ms ?: 0.0 } * 100) / 100.0, // BOARD-CUE: what the cue added to this pass
              "unknown" to
                  live.filter { it.kind == null }.map { t ->
                    val near = kinds.minByOrNull { FlatMatch.distance(t.l, t.w, it, cfg) }
                    fields(t) + mapOf("nearest" to near?.name, "tolerances_away" to near?.let { Math.round(FlatMatch.distance(t.l, t.w, it, cfg) * 100) / 100.0 })
                  },
              "on_assembly" to onAssembly,
              "needed" to needed?.partId,
              "lit" to lit?.let { mapOf("part" to needed?.partId, "index" to it.index, "pos_m" to it.center(), "n" to it.seen) },
              "step" to curStep,
              "action" to curAction,
              "surface_y_m" to worldFromTable?.t?.y,
              "img" to r.imageSize,
          ),
      )
    }
  }

  private fun flat(dx: Float, dz: Float): Float = sqrt(dx * dx + dz * dz)

  /** The live rectangle of the needed kind nearest to the assembly; null when there is none, or more than the kind has boards. */
  private fun choose(nowMs: Long): Track? {
    val kind = kindOf(needed?.takeIf { !placed(it) }) ?: return null
    val all = tracks.filter { it.kind === kind && it.seen >= cfg.minSeen && nowMs - it.lastMs <= cfg.liveMs }
    if (all.isEmpty() || all.size > kind.boards.size) return null // one of them is not this board: light none
    // BOARD-CUE: a rectangle told to be the needed board is the one; a rectangle told to be another board never is.
    // Nothing told apart: every rectangle of the kind, as before.
    val want = needed?.partId
    val c = all.filter { it.which.value == want }.ifEmpty { all.filter { it.which.value == null } }
    if (c.isEmpty()) return null
    val held = c.firstOrNull { it.key == litKey }
    val at = assemblyPos ?: return held ?: c.minByOrNull { it.index }
    val best = c.minByOrNull { flat(it.x - at.x, it.z - at.z) }!!
    return if (held != null && flat(held.x - at.x, held.z - at.z) <= flat(best.x - at.x, best.z - at.z) + cfg.stickM) held else best
  }

  /** The rectangle to outline now (the needed part, live); null = none. */
  @Synchronized
  fun lit(nowMs: Long = clockMs()): LitPart? {
    val n = needed ?: return null
    val t = choose(nowMs) ?: return null
    val age = nowMs - t.lastMs
    val left = cfg.liveMs - age
    return LitPart(t.key, n.partId, t.index, t.center(), t.corners(), t.l, t.w, age, if (left >= cfg.fadeMs) 1f else (left.toFloat() / cfg.fadeMs).coerceIn(0f, 1f),
        t.look.value, t.which.value, t.turned()) // BOARD-CUE
  }

  // ---- BOARD-CUE ----

  private var cues: Map<String, KindCue> = emptyMap()

  /**
   * The hypotheses of every kind of board, from the guide's meshes (and the hardware that sits in the boards). Returns
   * what goes into the log: per kind and hypothesis the board, the face (0 / 1), what it shows, turned or not, the
   * pixels of its long features away from the edges and at an edge, and the number of its small features.
   */
  private fun buildCues(g: Guide): Map<String, Any?> {
    val hw = g.parts.filterKeys { it.startsWith("hw_") }.values.map { it.positions to it.indices }
    val t0 = System.nanoTime()
    val made = LinkedHashMap<String, KindCue>()
    for (k in kinds) {
      val meshes = k.boards.mapNotNull { b -> g.parts[b.node]?.let { BoardCue.BoardMesh(b.partId, it.positions, it.indices, g.finalPoses[b.node] ?: Pose3.IDENTITY) } }
      if (meshes.size != k.boards.size) continue
      val kc = runCatching { BoardCue.kind(k.name, meshes, hw) }.getOrNull() ?: continue
      if (kc.lies.isNotEmpty()) made[k.name] = kc
    }
    setCues(made)
    return mapOf("ms" to Math.round((System.nanoTime() - t0) / 1e5) / 10.0,
        "kinds" to made.map { (name, kc) -> mapOf("kind" to name, "lies" to kc.lies.map { listOf(kc.boards[it.board], it.face, it.look.name.lowercase(), it.turned, it.template.interiorPx, it.template.edgePx, it.template.smallGroups) }) })
  }

  /** The cue's hypotheses per kind name (tests give them directly), and the detector's scorer with them. */
  @Synchronized
  fun setCues(c: Map<String, KindCue>) {
    cues = c
    BoardCueHub.scorer = if (c.isEmpty()) null else scorer()
  }

  /** Scores a found rectangle as the kind its own size says (the same rule as the labels). Null: no kind, or the kind has no features. */
  @Synchronized
  fun scorer(): RectCue {
    val ks = kinds
    val c = cues
    return RectCue { light, stride, valid, n, x0, y0, s, r ->
      val k = FlatMatch.classify(r.lengthM, r.widthM, ks, cfg) ?: return@RectCue null
      val kc = c[k.name] ?: return@RectCue null
      BoardCue.score(kc, light, stride, valid, n, x0, y0, s, r.x, r.y, r.yaw)
    }
  }

  private fun cueFields(t: Track): Map<String, Any?> {
    val c = t.m.lastOrNull()?.cue ?: return emptyMap()
    fun r1(v: Double) = if (v.isNaN()) null else Math.round(v * 10) / 10.0
    return mapOf(
        "face" to t.look.value?.name?.lowercase(),
        "which" to t.which.value,
        "turned" to t.turned(),
        "heading_deg" to t.heading.value?.let { Math.round(Math.toDegrees(it)) },
        "cue" to mapOf(
            "decided" to c.decided,
            "face" to c.look?.name?.lowercase(),
            "which" to c.board?.let { c.kind.boards[it] },
            "turned" to c.turned,
            // per hypothesis: board, face up, turned by 180°, how much darker (8-bit L) its long features away from the
            // edges, its long features at an edge and its small ones are than the face round them
            "scores" to c.kind.lies.indices.map { i -> c.kind.lies[i].let { l -> listOf(c.kind.boards[l.board], l.look.name.lowercase(), l.turned, r1(c.long[i]), r1(c.edge[i]), r1(c.small[i])) } },
            "ms" to Math.round(c.ms * 100) / 100.0,
        ),
    )
  }

  /**
   * What the app sees of the lit board, for the label: "Αριστερό πλαϊνό · με τη ράγα προς τα πάνω". The board's name
   * only when the kind has one board or the cue told which it is; null while the face is not known.
   */
  @Synchronized
  fun describe(lang: String, nowMs: Long = clockMs()): String? {
    val n = needed ?: return null
    val t = choose(nowMs) ?: return null
    val look = t.look.value ?: return null
    val b = t.which.value?.let { id -> boards.firstOrNull { it.partId == id } } ?: n.takeIf { (kindOf(it)?.boards?.size ?: 0) == 1 }
    val face = BoardWords.face(look, lang)
    val name = b?.let { it.names[if (lang == "el") "el" else "en"] ?: it.names["en"] ?: it.partId } ?: return face.replaceFirstChar { it.uppercaseChar() }
    return name.replaceFirstChar { it.uppercaseChar() } + " · " + face
  }

  /**
   * One line the coach can speak when every board of the needed kind in view is told to be ANOTHER board
   * ("Αυτό είναι το δεξί πλαϊνό, πάρε το άλλο."); null otherwise (also while nothing is told apart).
   */
  @Synchronized
  fun hint(lang: String, nowMs: Long = clockMs()): String? {
    val n = needed?.takeIf { !placed(it) } ?: return null
    val kind = kindOf(n) ?: return null
    val c = tracks.filter { it.kind === kind && it.seen >= cfg.minSeen && nowMs - it.lastMs <= cfg.liveMs }
    if (c.isEmpty() || c.size > kind.boards.size || c.any { it.which.value == null || it.which.value == n.partId }) return null
    val other = boards.firstOrNull { it.partId == c.first().which.value } ?: return null
    return BoardWords.other(other.names[if (lang == "el") "el" else "en"] ?: other.names["en"] ?: other.partId, lang)
  }

  /**
   * Every live labelled rectangle, the needed board and the others: what "which board did the hand lift"
   * (ui/GrabJudge.kt) looks at. [LitPart.partId] is the kind's name here ("a+b" for boards of one size).
   */
  @Synchronized
  fun live(nowMs: Long = clockMs()): List<LitPart> =
      tracks.filter { it.kind != null && it.seen >= cfg.minSeen && nowMs - it.lastMs <= cfg.liveMs }
          .map { LitPart(it.key, it.kind!!.name, it.index, it.center(), it.corners(), it.l, it.w, nowMs - it.lastMs, 1f) }

  /**
   * The pick targets with the lit rectangle in: while it is live, one target stands first, at the rectangle's centre
   * (method [METHOD]; the label and the hand arc take it), and the server's boxes for that part stay in the list
   * marked [PickTarget.shadowed] (not drawn; the action coach, which follows the server's boxes over time, sees none
   * of them vanish). [hideId]: a part whose targets are hidden anyway (the base part once the ghost lies on it).
   * No lit rectangle: [server] as it is.
   */
  fun merge(server: List<PickTarget>, hideId: String? = null, nowMs: Long = clockMs()): List<PickTarget> {
    val l = lit(nowMs) ?: return server
    if (l.partId == hideId) return server
    val old = server.firstOrNull { it.id == l.partId }
    val names = old?.name?.takeIf { it.isNotEmpty() } ?: synchronized(this) { needed?.names } ?: emptyMap()
    val t =
        PickTarget(l.partId, old?.label ?: names["en"] ?: l.partId, l.center, 1.0, l.ageMs, old?.kind ?: "part", METHOD,
            (l.widthM / 2).toFloat().coerceIn(0.03f, 0.25f), old?.box ?: emptyList(), KEY_BASE + l.key, 0, names,
            old?.countText ?: emptyMap(), old?.countSeen, old?.countNeeded, tracked = true, moving = false, current = true)
    return listOf(t) + server.map { if (it.id == l.partId) it.copy(shadowed = true) else it }
  }

  companion object {
    /** `PickTarget.method` of a target that stands on a recognised rectangle. */
    const val METHOD = "rect"

    /** Keys of rectangle targets start here (world/PartTracks.kt counts its own from 1). */
    const val KEY_BASE = 1_000_000
  }
}
