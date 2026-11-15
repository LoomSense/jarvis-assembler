package com.agj.android

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.GhostMaterial
import com.agj.android.guide.Glb
import com.agj.android.guide.Guidance
import com.agj.android.guide.Guide
import com.agj.android.guide.GuideAction
import com.agj.android.guide.GuideStep
import com.agj.android.guide.Manifest
import com.agj.android.guide.PartGeometry
import com.agj.android.guide.PartStatus
import com.agj.android.guide.Shapes
import com.agj.android.guide.TurnSense
import com.agj.android.vision.Frames
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guidance on the object, measured against the real guide data (src/test/resources/{malm-drawer,knagglig}: the
 * manifest and the GLB of each job): where each ring, path and arrow is drawn versus the hardware's own geometry, the
 * boards' surfaces, the pipeline's own hole marker and the guide's own animation. Every test prints its worst case
 * per job, and what the renderer drew before (the "before" lines).
 */
class GuidanceGeometryTest {
  private class Job(val name: String) {
    private fun res(f: String) = File(GuidanceGeometryTest::class.java.classLoader!!.getResource("$name/$f")!!.toURI())
    val manifest: Manifest = Manifest.parse(res("manifest.json").readText())
    val glb: Glb = Glb.read(res("walkthrough.glb"))
    val guide: Guide = Guide.build(manifest, glb)
    val clip = glb.animation("walkthrough")!!
    val root = glb.node(manifest.rootNode)!!

    /** Every (step, action) that goes into a hole. */
    val fasteners: List<Pair<GuideStep, GuideAction>> =
        guide.steps.flatMap { s -> s.actions.filter { Guidance.hasHole(it) }.map { s to it } }

    /** [node]'s position relative to the assembly root at clip time [t]. */
    fun nodeAt(node: String, t: Double) = glb.relativeTo(root.index, glb.node(node)!!.index, glb.localPosesAt(clip, t.toFloat()))!!

    /** Clip time of manifest action [a] of step [s] at fraction [f] of its duration. */
    fun timeOf(s: GuideStep, a: GuideAction, f: Double): Double {
      val ms = manifest.step(s.n)!!
      val ma = ms.actions[a.index]
      return ms.walkthrough!!.start_s + ma.t0_s!! + (ma.t1_s!! - ma.t0_s!!) * f
    }
  }

  private val jobs by lazy { listOf(Job("malm-drawer"), Job("knagglig")) }

  private fun mm(v: Float) = String.format(Locale.ROOT, "%.2f", v * 1000f)

  private fun f2(v: Double) = String.format(Locale.ROOT, "%.2f", v)

  /** Distance of [p] from the line through [o] along unit [d]. */
  private fun offLine(p: Vec3, o: Vec3, d: Vec3): Float = ((p - o) - d * (p - o).dot(d)).length()

  /** Ray [o] + t·[d] against [g]: (t, geometric normal) of every triangle hit. */
  private fun rayHits(o: Vec3, d: Vec3, g: PartGeometry): List<Pair<Float, Vec3>> {
    val out = ArrayList<Pair<Float, Vec3>>()
    val p = g.positions
    val idx = g.indices
    fun v(i: Int) = Vec3(p[3 * i], p[3 * i + 1], p[3 * i + 2])
    for (t in idx.indices step 3) {
      val a = v(idx[t])
      val e1 = v(idx[t + 1]) - a
      val e2 = v(idx[t + 2]) - a
      val h = d.cross(e2)
      val det = e1.dot(h)
      if (abs(det) < 1e-12f) continue
      val inv = 1f / det
      val s = o - a
      val u = s.dot(h) * inv
      if (u < -1e-5f || u > 1f + 1e-5f) continue
      val q = s.cross(e1)
      val w = d.dot(q) * inv
      if (w < -1e-5f || u + w > 1f + 1e-5f) continue
      out += (e2.dot(q) * inv) to Shapes.norm(e1.cross(e2))
    }
    return out
  }

  /**
   * Where the surface that receives the hardware is, along the hole's normal, measured from [center] (m, + = outside):
   * 12 rays parallel to the axis on a circle of radius [r] around it, against the boards already in place, keeping
   * for each ray the outward-facing surface nearest the hole; the median over the rays that hit.
   */
  private fun receivingSurface(j: Job, s: GuideStep, a: GuideAction, center: Vec3, normal: Vec3, r: Float): Float? {
    val boards = j.guide.parts.filter { (n, _) -> n.startsWith("part_") && j.guide.statusOf(n, s.n, a.index) == PartStatus.PLACED }.values
    val e1 = Shapes.norm(normal.cross(if (abs(normal.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)))
    val e2 = normal.cross(e1)
    val per = ArrayList<Float>()
    for (k in 0 until 12) {
      val ang = 2.0 * Math.PI * k / 12
      val o = center + (e1 * Math.cos(ang).toFloat() + e2 * Math.sin(ang).toFloat()) * r + normal * 0.5f
      val best = boards.flatMap { rayHits(o, normal * -1f, it) }.filter { it.second.dot(normal) > 0.5f }.map { 0.5f - it.first }.minByOrNull { abs(it) }
      if (best != null) per += best
    }
    if (per.size < 6) return null
    return per.sorted()[per.size / 2]
  }

  // ------------------------------------------------------------------ 1. the hole

  @Test
  fun ringIsOnTheHardwareAxisAndOnTheReceivingSurface() {
    for (j in jobs) {
      var worstAxis = 0f
      var worstSurface = 0f
      var worstTarget = 0f
      var worstMarker = 0f
      var worstMarkerDeg = 0.0
      var worstAxisDeg = 0.0
      var oldAlong = 0f
      var oldAlongWhere = ""
      var oldOff = 0f
      var minStroke = Float.MAX_VALUE
      var oldMinStroke = Float.MAX_VALUE
      val marker = j.manifest.markerNode
      for ((s, a) in j.fasteners) {
        val where = "${j.name} step ${s.n} action ${a.index} ${a.node}"
        val hole = assertNotNull(where, Guidance.holeOf(a)).let { Guidance.holeOf(a)!! }
        val geo = a.geometry!!
        val into = Guidance.hardwareAxis(a)!!
        assertEquals("$where hole source", "node", hole.source)

        // (a) on the hardware's final axis: the line through its mesh's centre along its own axis
        val axisErr = offLine(hole.ringCenter, geo.center, into)
        assertTrue("$where ring ${mm(axisErr)} mm from the hardware axis", axisErr <= 0.0015f)
        worstAxis = maxOf(worstAxis, axisErr)
        val dev = Guidance.angleDeg(into, hole.normal * -1f)
        assertTrue("$where hardware axis vs from_dir $dev deg", dev < 1.0)
        worstAxisDeg = maxOf(worstAxisDeg, dev)

        // (b) on the receiving surface along from_dir
        val surf = receivingSurface(j, s, a, hole.center, hole.normal, hole.radius + 0.001f)
        assertNotNull("$where no receiving surface under the ring", surf)
        val ringOff = abs(Guidance.RING_LIFT - surf!!)
        assertTrue("$where ring ${mm(ringOff)} mm from the receiving surface", ringOff <= 0.001f)
        worstSurface = maxOf(worstSurface, abs(surf))

        // (c) the manifest's target_m is the same point
        val t = (a.target!! - hole.center).length()
        assertTrue("$where target_m ${mm(t)} mm from the node", t <= 0.0005f)
        worstTarget = maxOf(worstTarget, t)

        // (d) the pipeline's own ring (GLB node `marker`, drawn 1.5 mm off the surface during this action)
        if (marker != null && j.glb.node(marker) != null) {
          val m = j.nodeAt(marker, j.timeOf(s, a, 0.5))
          val md = (m.t - hole.ringCenter).length()
          assertTrue("$where pipeline ring ${mm(md)} mm from ours", md <= 0.002f)
          worstMarker = maxOf(worstMarker, md)
          val mdeg = Guidance.angleDeg(m.r.rotate(Vec3(0f, 1f, 0f)), hole.normal)
          assertTrue("$where pipeline ring normal $mdeg deg", mdeg < 1.0)
          worstMarkerDeg = maxOf(worstMarkerDeg, mdeg)
        }

        // size: at least the spec's 1.2 mm stroke, the hardware's radius + 2 mm
        val stroke = Guidance.RING_STROKE * hole.radius
        assertTrue("$where stroke ${mm(stroke)} mm", stroke >= Guidance.RING_STROKE_MIN - 1e-6f)
        assertTrue("$where radius", hole.radius >= Guidance.extentAbout(geo, hole.center, hole.normal).radial + Guidance.RING_MARGIN - 1e-6f || hole.radius == Guidance.RING_MAX_R)
        minStroke = minOf(minStroke, stroke)

        // before: the ring on the hardware's outer end (bounding-box centre + half its length + 0.5 mm)
        val dir = hole.normal
        val old = geo.center + dir * (geo.extentAlong(dir) / 2f + 0.0005f)
        val along = abs((old - hole.center).dot(dir) - surf - Guidance.RING_LIFT)
        if (along > oldAlong) {
          oldAlong = along
          oldAlongWhere = "step ${s.n} ${a.node}"
        }
        oldOff = maxOf(oldOff, offLine(old, geo.center, into))
        oldMinStroke = minOf(oldMinStroke, 0.2f * (geo.widthAcross(dir) / 2f + 0.002f).coerceIn(0.004f, 0.03f))
      }
      println(
          "HOLE ${j.name}: ${j.fasteners.size} fasteners; ring vs hardware axis max ${mm(worstAxis)} mm (axis vs from_dir max ${f2(worstAxisDeg)} deg); " +
              "hole vs receiving surface max ${mm(worstSurface)} mm; target_m vs node max ${mm(worstTarget)} mm; " +
              "vs pipeline ring max ${mm(worstMarker)} mm, ${f2(worstMarkerDeg)} deg; min stroke ${mm(minStroke)} mm")
      println("HOLE ${j.name} before: ring up to ${mm(oldAlong)} mm off its spec place along the axis ($oldAlongWhere), ${mm(oldOff)} mm off axis; min stroke ${mm(oldMinStroke)} mm")
    }
  }

  // ------------------------------------------------------------------ 2a. the insertion path

  @Test
  fun pathComesFromFromDirOutsideThePartAndEndsAtTheHole() {
    for (j in jobs) {
      var checked = 0
      var worstMiss = 0f
      var worstLine = 0f
      var maxEndGap = 0f
      var minStart = Float.MAX_VALUE
      var worstAnimDeg = 0.0
      var oldBack = 0f
      var oldBackN = 0
      var insidePlaced = 0
      for (s in j.guide.steps) for (a in s.actions) {
        val geo = a.geometry ?: continue
        val u = a.fromDir ?: continue
        val where = "${j.name} step ${s.n} action ${a.index} ${a.node}"
        val up = s.tableFromAssembly.q.conjugate().rotate(Vec3(0f, 1f, 0f))
        val path = Guidance.insertionPath(a, up)
        assertTrue("$where has a path", path.size >= 3)
        checked++
        val hole = Guidance.holeOf(a)
        val end = hole?.center ?: (geo.center + u * (geo.extentAlong(u) / 2f))

        // from from_dir, never backing away from the part, far end first
        for (i in 1 until path.size) assertTrue("$where path backs away at dot $i", (path[i - 1] - path[i]).dot(u) >= -1e-4f)
        val start = (path.first() - end).dot(u)
        assertTrue("$where starts ${mm(start)} mm out", start >= 0.08f)
        minStart = minOf(minStart, start)
        // starts outside the part's own ghost
        val p0 = path.first()
        val insideGhost = p0.x in geo.min.x..geo.max.x && p0.y in geo.min.y..geo.max.y && p0.z in geo.min.z..geo.max.z
        assertTrue("$where starts inside its ghost", !insideGhost)
        // the guide's own animation brings the part in from the same side
        val moved = j.nodeAt(a.node!!, j.timeOf(s, a, 0.0)).t - a.finalPose!!.t
        if (moved.length() > 0.01f) {
          val deg = Guidance.angleDeg(moved, u)
          assertTrue("$where from_dir vs the animation $deg deg", deg < 1.0)
          worstAnimDeg = maxOf(worstAnimDeg, deg)
        }

        if (hole != null) {
          // hardware: every dot on the hole's axis, the last one 2 cm before the hardware's outer end, aimed at the hole
          for (p in path) worstLine = maxOf(worstLine, offLine(p, hole.center, hole.normal))
          val tangent = Shapes.norm(path.last() - path[path.size - 2])
          val miss = offLine(hole.center, path.last(), tangent)
          assertTrue("$where path misses the hole by ${mm(miss)} mm", miss <= 0.0005f)
          assertTrue("$where path points away from the hole", tangent.dot(hole.normal) < -0.999f)
          worstMiss = maxOf(worstMiss, miss)
          val out = Guidance.extentAbout(geo, hole.center, hole.normal).out
          val gap = (path.last() - hole.center).dot(hole.normal)
          assertEquals("$where last dot", out + Guidance.PATH_GAP, gap, 1e-4f)
          maxEndGap = maxOf(maxEndGap, gap)
          // data note: is the path's far end inside a board that is already in place?
          val boards = j.guide.parts.filter { (n, _) -> n.startsWith("part_") && j.guide.statusOf(n, s.n, a.index) == PartStatus.PLACED }.values
          if (boards.any { b -> p0.x in b.min.x..b.max.x && p0.y in b.min.y..b.max.y && p0.z in b.min.z..b.max.z }) insidePlaced++
        } else {
          // before: the arc was lifted along world up, so a board that comes from above first moved further away
          val e = end + u * 0.02f
          val st = end + u * 0.25f
          val back = (0..200).maxOf { (Shapes.bezier(st, st + up * 0.30f, e + u * 0.18f + up * 0.12f, e, it / 200f) - st).dot(u) }
          if (back > 0.005f) oldBackN++
          oldBack = maxOf(oldBack, back)
        }
      }
      assertTrue("${j.name} hardware dots off the axis ${mm(worstLine)} mm", worstLine <= 0.0005f)
      println(
          "PATH ${j.name}: $checked paths; start >= ${mm(minStart)} mm out along from_dir, never backs away; from_dir vs the guide animation max ${f2(worstAnimDeg)} deg; " +
              "hardware: dots off the hole axis max ${mm(worstLine)} mm, aim misses the hole by max ${mm(worstMiss)} mm, last dot <= ${mm(maxEndGap)} mm from the lip; " +
              "far end inside a board already in place: $insidePlaced")
      println("PATH ${j.name} before: $oldBackN board paths first moved up to ${mm(oldBack)} mm AWAY from the part before coming in")
    }
  }

  // ------------------------------------------------------------------ 2b. the turning arrow

  @Test
  fun turningArrowIsOnTheHardwareAxisClockwiseSeenFromTheHead() {
    for (j in jobs) {
      var worstDeg = 0.0
      var worstOff = 0f
      var cw = 0
      val none = sortedSetOf<String>()
      for ((s, a) in j.fasteners) {
        val where = "${j.name} step ${s.n} action ${a.index} ${a.node}"
        val push = a.hardwareKind in Guidance.PUSH_KINDS
        assertEquals("$where sense", if (push) TurnSense.NONE else TurnSense.CW, Guidance.turnSense(a))
        val arrow = Guidance.turnArrowOf(a)
        if (push) {
          assertNull("$where: no thread, no turning arrow", arrow)
          none += "${a.code} ${a.hardwareKind}"
          continue
        }
        cw++
        arrow!!
        val hole = Guidance.holeOf(a)!!
        val pose = Guidance.turnArrowPose(arrow)
        val axis = pose.q.rotate(Vec3(0f, 1f, 0f))
        val deg = Guidance.angleDeg(axis, Guidance.hardwareAxis(a)!!)
        assertTrue("$where arrow axis $deg deg from the hardware axis", deg < 1.0)
        assertTrue("$where arrow axis vs from_dir", Guidance.angleDeg(axis, a.fromDir!! * -1f) < 1.0)
        worstDeg = maxOf(worstDeg, deg)
        val off = offLine(arrow.center, a.geometry!!.center, Guidance.hardwareAxis(a)!!)
        assertTrue("$where arrow centre ${mm(off)} mm off the axis", off <= 0.0015f)
        worstOff = maxOf(worstOff, off)
        assertEquals("$where 6 mm in front of the head", Guidance.extentAbout(a.geometry!!, hole.center, hole.normal).out + Guidance.ARROW_LIFT, (arrow.center - hole.center).dot(hole.normal), 1e-4f)
        assertTrue("$where radius", arrow.radius in Guidance.ARROW_MIN_R..Guidance.ARROW_MAX_R)

        // Sense: the arrowhead's base and direction, as drawn. turning = r × v must point INTO the hole: seen from
        // the head (looking along it) that is clockwise, the way a screw tightens.
        val (base, dirn, len) = Shapes.turnArrowHead(arrow.radius)
        val r = pose.transform(base) - arrow.center
        val v = pose.q.rotate(dirn)
        val sense = Guidance.angleDeg(r.cross(v), arrow.into)
        assertTrue("$where arrowhead turns $sense deg off clockwise", sense < 1.0)
        // the mesh really carries its arrowhead there (tip = base + direction × length)
        val mesh = Shapes.turnArrow(arrow.radius, 0.00175f)
        val tipIdx = 49 * 9 + 1
        val tip = Vec3(mesh.positions[3 * tipIdx], mesh.positions[3 * tipIdx + 1], mesh.positions[3 * tipIdx + 2])
        assertTrue("$where arrowhead tip", (tip - (base + dirn * len)).length() < 1e-5f)
        // and the left-handed SDK shows the same thing: mirrored mesh + mirrored pose, read back in our frame
        val shown = Frames.mirrorZ(Frames.mirrorZ(pose).transform(Frames.mirrorZ(tip)))
        assertTrue("$where SDK mirror", (shown - pose.transform(tip)).length() < 1e-5f)
      }
      println("TURN ${j.name}: $cw clockwise arrows, axis vs hardware axis max ${f2(worstDeg)} deg, centre off axis max ${mm(worstOff)} mm; no arrow (pushed in): $none")
    }
    assertEquals(setOf("dowel", "pin"), jobs[0].fasteners.map { it.second }.filter { Guidance.turnSense(it) == TurnSense.NONE }.map { it.hardwareKind }.toSet())
    assertTrue(jobs[1].fasteners.all { Guidance.turnSense(it.second) == TurnSense.CW })
  }

  // ------------------------------------------------------------------ 2c. turning the assembly over

  @Test
  fun flipPreviewFollowsTheGuideAnimationAndEndsInTheNextPose() {
    for (j in jobs) {
      val steps = j.guide.steps
      var turns = 0
      for ((i, s) in steps.withIndex()) {
        // every step's pose is the manifest's assemblyRotationDeg
        val euler = Guide.blenderEulerXyzToGltf(j.manifest.step(s.n)!!.assemblyRotationDeg)
        assertTrue("${j.name} step ${s.n} pose vs assemblyRotationDeg", Frames.angleDeg(euler, s.tableFromAssembly.q) < 0.5)
        if (s.actions.none { it.kind == ActionKind.TURN }) {
          assertNull(s.turnTrack)
          continue
        }
        turns++
        val where = "${j.name} step ${s.n}"
        val track = assertNotNull(where, s.turnTrack).let { s.turnTrack!! }
        val before = steps[i - 1].tableFromAssembly
        fun same(a: Pose3, b: Pose3) = Frames.angleDeg(a.q, b.q) < 0.5 && (a.t - b.t).length() < 0.001f
        assertTrue("$where starts in the pose before", same(track.at(0f), before))
        assertTrue("$where ends in this step's pose", same(track.at(1f), s.tableFromAssembly))
        // ... which the following step keeps (until the next turn)
        steps.getOrNull(i + 1)?.takeIf { n -> n.actions.none { it.kind == ActionKind.TURN } }?.let { assertTrue("$where next step's pose", same(track.at(1f), it.tableFromAssembly)) }
        // it only ever turns onwards, by the angle between the two poses (no detour)
        val total = Frames.angleDeg(before.q, s.tableFromAssembly.q)
        var last = 0.0
        for (k in 0..20) {
          val ang = Frames.angleDeg(before.q, track.at(k / 20f).q)
          assertTrue("$where turns back at ${k / 20f}", ang >= last - 0.5)
          last = ang
        }
        assertTrue("$where travelled ${track.angleDeg} of $total", track.angleDeg >= total - 1.0 && track.angleDeg <= total * 1.25)
        // halfway through the travel it is about halfway round
        val mid = track.at(0.5f)
        val midDeg = Frames.angleDeg(before.q, mid.q)
        assertTrue("$where midpoint $midDeg of $total", midDeg > total * 0.3 && midDeg < total * 0.7)
        // before: the two end poses interpolated
        val oldMid = before.q.nlerp(s.tableFromAssembly.q, 0.5f)
        println("FLIP $where: ${f2(total)} deg from the clip (${track.poses.size} samples), ends in the step pose; before: interpolated midpoint ${f2(Frames.angleDeg(oldMid, mid.q))} deg from the guide's")
      }
      assertTrue("${j.name} has a turn", turns > 0)
    }
  }

  // ------------------------------------------------------------------ 3. several identical fasteners

  @Test
  fun identicalFastenersCountAndTheirRings() {
    for ((j, stepN, code, n) in listOf(Quad(jobs[0], 7, "100365", 4), Quad(jobs[1], 5, "108560", 8))) {
      val s = j.guide.step(stepN)!!
      val same = s.actions.filter { it.code == code }
      assertEquals(n, same.size)
      assertEquals(n, s.hardware.first { it.code == code }.qty)
      for ((i, a) in same.withIndex()) {
        val r = Guidance.repeatOf(s, a)!!
        assertEquals(i + 1, r.index)
        assertEquals(n, r.of)
        // dim rings: exactly the holes still to do; done ones and the current one get none
        assertEquals(same.drop(i + 1).map { it.node }, r.remaining.map { it.node })
        assertEquals("${i + 1} από $n", a.label("el")!!.count)
        assertEquals("${i + 1} of $n", a.label("en")!!.count)
        // each dim ring is on its own hole, clear of the bright one
        val h = Guidance.holeOf(a)!!
        for (o in r.remaining.map { Guidance.holeOf(it)!! }) assertTrue((o.center - h.center).length() > 2.2f * 2f * h.radius)
      }
      // boards of the same step show no rings
      for (a in s.actions.filter { it.kind == ActionKind.PLACE }) assertNull(Guidance.repeatOf(s, a))
      println("REPEAT ${j.name} step $stepN: $n × $code, label 1..$n of $n = ring index, dim rings ${n - 1}..0")
    }
    // every fastener of both jobs: the label's count is the ring's index
    for (j in jobs) for ((s, a) in j.fasteners) {
      val r = Guidance.repeatOf(s, a)!!
      assertEquals("${j.name} step ${s.n} action ${a.index}", if (r.of > 1) "${r.index} of ${r.of}" else null, a.label("en")!!.count)
      assertEquals(a.ordinal, r.index)
      assertEquals(a.sameCount, r.of)
    }
    assertEquals(3, Guidance.maxRemaining(jobs[0].guide))
    assertEquals(7, Guidance.maxRemaining(jobs[1].guide)) // before: 6 dim rings at most, one hole of 8 had none
  }

  private data class Quad(val job: Job, val step: Int, val code: String, val n: Int)

  // ------------------------------------------------------------------ colour of the marks

  @Test
  fun marksAreDrawnInTheTokenColour() {
    assertTrue(File("src/shaders/ghost_fresnel.frag").readText().contains("tint * (${GhostMaterial.BODY_GAIN} + 0.9 * rim)"))
    val t = GhostMaterial.Tint.ACCENT
    val s = GhostMaterial.shaderTint(t, mark = true)
    for (i in 0..2) assertEquals(t[i], s[i] * GhostMaterial.BODY_GAIN, 1e-6f)
    assertTrue(GhostMaterial.shaderTint(t, mark = false) === t)
  }
}
