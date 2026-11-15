package com.agj.android.vision

import com.agj.android.core.AgqLog
import com.agj.android.core.Params
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The frame recorder (vision/FrameDump.kt): the pose conversion the offline detector depends on, the line format,
 * the launch extras, and the writer end to end (a session folder is a replay dataset).
 */
class FrameDumpTest {
  private val k = Intrinsics(870.25, 869.5, 641.0, 478.5, 1280, 960, listOf(0.01, -0.02, 0.0, 0.0, 0.003))

  /**
   * The segments of a JPEG file up to its scan, with a frame header ([sof]: 0xC0 baseline, 0xC2 progressive) for
   * [w] × [h]. A Huffman table (0xC4, inside the SOFn marker range but not a frame header) comes first, as some
   * encoders write it. No Android or desktop image library is needed (the unit-test classpath has neither).
   */
  private fun jpeg(w: Int, h: Int, sof: Int = 0xc0, scanBytes: Int = 300): ByteArray {
    fun seg(marker: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xff.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
    val app0 = seg(0xe0, "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
    val dqt = seg(0xdb, ByteArray(65) { if (it == 0) 0 else 16 })
    val dht = seg(0xc4, ByteArray(29) { it.toByte() })
    val frame = seg(sof, byteArrayOf(8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))
    val scan = seg(0xda, byteArrayOf(3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0)) + ByteArray(scanBytes) { (it * 7 + w).toByte() }
    return byteArrayOf(0xff.toByte(), 0xd8.toByte()) + app0 + dqt + dht + frame + scan + byteArrayOf(0xff.toByte(), 0xd9.toByte())
  }

  /** A real 1280 × 960 JPEG from the repository (tools/markerless/sample), null when the checkout has none. */
  private fun realJpeg(): ByteArray? {
    var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (d != null && !File(d, "tools/markerless/sample/img").isDirectory) d = d.parentFile
    return d?.let { File(it, "tools/markerless/sample/img/s00_f010.jpg") }?.takeIf { it.isFile }?.readBytes()
  }

  private fun flat(m: List<List<Double>>): DoubleArray = m.flatten().toDoubleArray()

  private fun mul(a: List<List<Double>>, b: List<List<Double>>): List<List<Double>> =
      List(4) { r -> List(4) { c -> (0 until 4).sumOf { a[r][it] * b[it][c] } } }

  private fun invRigid(m: List<List<Double>>): List<List<Double>> {
    val r = List(3) { i -> List(3) { j -> m[j][i] } }
    val t = List(3) { i -> -(0 until 3).sumOf { r[i][it] * m[it][3] } }
    return List(3) { i -> r[i] + t[i] } + listOf(listOf(0.0, 0.0, 0.0, 1.0))
  }

  private fun randomPose(rnd: Random): Pose3 =
      Pose3(
          Vec3(rnd.nextFloat() * 4 - 2, rnd.nextFloat() * 2, rnd.nextFloat() * 4 - 2),
          Quat(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalized(),
      )

  // ---- the pose conversion ----

  @Test
  fun matrixIsThePoseItselfRowMajor() {
    val rnd = Random(7)
    repeat(50) {
      val p = randomPose(rnd)
      val m = DumpFormat.matrix(p)
      assertEquals(listOf(0.0, 0.0, 0.0, 1.0), m[3])
      repeat(5) {
        val v = Vec3(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1)
        val want = p.transform(v) // Frames/Geom: p_A = R(q) · p_B + t
        val got = List(3) { r -> m[r][0] * v.x + m[r][1] * v.y + m[r][2] * v.z + m[r][3] }
        assertEquals(want.x.toDouble(), got[0], 2e-6)
        assertEquals(want.y.toDouble(), got[1], 2e-6)
        assertEquals(want.z.toDouble(), got[2], 2e-6)
      }
      // a rotation, right-handed: orthonormal rows, determinant +1 (a mirrored, left-handed pose would give −1)
      for (i in 0 until 3) for (j in 0 until 3) {
        val dot = (0 until 3).sumOf { m[i][it] * m[j][it] }
        assertEquals(if (i == j) 1.0 else 0.0, dot, 1e-9)
      }
      val det =
          m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
              m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
      assertEquals(1.0, det, 1e-9)
    }
  }

  /** The offline tool must see exactly the TABLE ← CAMERA the on-device detector (vision/PanelPose.kt) computes. */
  @Test
  fun matricesAreTheOnesTheOnDeviceDetectorUses() {
    val rnd = Random(11)
    repeat(30) {
      val cam = randomPose(rnd)
      val y = 0.6f + rnd.nextFloat() * 0.3f
      val wc = DumpFormat.matrix(cam)
      val wt = DumpFormat.tableMatrix(y)
      val refCam = PanelMath.toMatrix(cam)
      val refTable = PanelMath.toMatrix(PanelFrames.tableAtHeight(y))
      for (i in 0 until 16) {
        assertEquals(refCam[i], flat(wc)[i], 1e-6)
        assertEquals(refTable[i], flat(wt)[i], 1e-6)
      }
      val tc = flat(mul(invRigid(wt), wc)) // what detect_front_panel does first: inv(T_world_table) @ T_world_cam
      val refTc = PanelMath.tableFromCamera(cam, PanelFrames.tableAtHeight(y))
      for (i in 0 until 16) assertEquals(refTc[i], tc[i], 1e-5)
    }
  }

  /**
   * A camera built the way the app builds it (vision/Frames.kt): head pose from the Spatial SDK's left-handed axes
   * through [Frames.spatialToWorld], times HEAD ← CAMERA from [Frames.headFromCamera]. The matrix columns are then
   * the OpenCV camera axes in WORLD, and in the table frame the camera is above the table looking down at it.
   */
  @Test
  fun cameraFromFramesLooksDownAtTheTableInTheDetectorsConvention() {
    // head 1.25 m up, 0.4 m in front of the origin (WORLD −Z = forward), pitched 50° down
    val headWorld = Pose3(Vec3(0.1f, 1.25f, -0.4f), Quat.axisAngle(Vec3(1f, 0f, 0f), Math.toRadians(-50.0).toFloat()))
    val sdk = Frames.worldToSpatial(headWorld) // what the SDK would report (left-handed, +Z forward)
    assertEquals(0.4f, sdk[2], 1e-6f) // in front = +Z there
    val head = Frames.spatialToWorld(sdk[0], sdk[1], sdk[2], sdk[3], sdk[4], sdk[5], sdk[6])
    val lens = Frames.headFromCamera(floatArrayOf(0.032f, -0.02f, -0.06f), floatArrayOf(0f, 0f, 0f, 1f), LensConvention.FORWARD)
    val cam = head * lens
    val m = DumpFormat.matrix(cam)
    val forward = head.q.rotate(Vec3(0f, 0f, -1f))
    val down = head.q.rotate(Vec3(0f, -1f, 0f))
    val right = head.q.rotate(Vec3(1f, 0f, 0f))
    // columns: camera +X = head right, camera +Y = head down, camera +Z = optical axis = head forward
    for ((c, v) in listOf(0 to right, 1 to down, 2 to forward)) {
      assertEquals(v.x.toDouble(), m[0][c], 1e-6)
      assertEquals(v.y.toDouble(), m[1][c], 1e-6)
      assertEquals(v.z.toDouble(), m[2][c], 1e-6)
    }
    assertTrue("the optical axis points down in WORLD (+Y up)", m[1][2] < -0.7)
    assertTrue("and forward (WORLD −Z)", m[2][2] < -0.6)
    assertEquals(cam.t.y.toDouble(), m[1][3], 1e-6)

    val surfaceY = 0.74f
    val tc = mul(invRigid(DumpFormat.tableMatrix(surfaceY)), m)
    assertEquals(cam.t.y.toDouble() - surfaceY.toDouble(), tc[2][3], 1e-6) // TABLE z = height above the surface
    assertEquals(cam.t.x.toDouble(), tc[0][3], 1e-6) // TABLE x = WORLD x
    assertEquals(-cam.t.z.toDouble(), tc[1][3], 1e-6) // TABLE y = WORLD −z
    assertEquals(m[1][2], tc[2][2], 1e-9) // optical axis z in TABLE = its WORLD y
    assertTrue(PanelMath.looksDown(flat(tc))) // detect_front_panel's first gate
    // the point where the optical axis meets the table is straight ahead of the camera (x = y = 0, z > 0 in CAMERA)
    val s = -tc[2][3] / tc[2][2]
    val pTable = listOf(tc[0][3] + tc[0][2] * s, tc[1][3] + tc[1][2] * s, 0.0)
    val ct = invRigid(tc)
    val pc = List(3) { r -> ct[r][0] * pTable[0] + ct[r][1] * pTable[1] + ct[r][2] * pTable[2] + ct[r][3] }
    assertEquals(0.0, pc[0], 1e-6)
    assertEquals(0.0, pc[1], 1e-6)
    assertTrue(pc[2] > 0.3)
  }

  // ---- the line ----

  private fun obj(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject

  private fun doubles(a: JsonArray): List<Double> = a.map { it.jsonPrimitive.double }

  @Test
  fun lineHasEveryFieldTheOfflineToolsRead() {
    val cam = Pose3(Vec3(0.1f, 1.2f, -0.3f), Quat(0.5f, -0.5f, 0.5f, -0.5f)) // w < 0: written with w ≥ 0
    val ghost = Pose3(Vec3(0.0f, 0.756f, -0.6f), Quat.yaw(0.3f))
    val anchor = Pose3(Vec3(0.0f, 0.74f, -0.6f), Quat.yaw(0.3f))
    val panel = Pose3(Vec3(0.01f, 0.756f, -0.61f), Quat.yaw(0.31f))
    val track = TrackSnapshot(TrackState.HELD, "markerless", ghost, null, null, 4.5, 2_000_000_000L)
    val x =
        DumpExtras(surfaceY = 0.74f, anchor = anchor, anchorSource = "table", ghost = ghost, job = "malm-drawer", step = 1,
            action = 0, track = track, panel = panel, more = mapOf("note" to "x"))
    val line = DumpFormat.line(7, 2.5, 2_100_000_000L, 1_794_750_000_123L, 345L, k, cam, x)
    assertFalse(line.contains('\n'))
    val o = obj(line)
    assertEquals(
        listOf("frame", "t", "file", "ts_ns", "wall_ms", "index", "width", "height", "fx", "fy", "cx", "cy", "distortion", "cam_p",
            "cam_q", "T_world_cam", "surface_y", "T_world_table", "anchor_p", "anchor_q", "anchor_source", "ghost_p", "ghost_q",
            "job", "step", "action", "track", "panel_p", "panel_q", "more"),
        o.keys.toList(),
    )
    assertEquals(7, o["frame"]!!.jsonPrimitive.int)
    assertEquals("000007.jpg", o["file"]!!.jsonPrimitive.content)
    assertEquals(2.5, o["t"]!!.jsonPrimitive.double, 0.0)
    assertEquals(2_100_000_000L, o["ts_ns"]!!.jsonPrimitive.long)
    assertEquals(1_794_750_000_123L, o["wall_ms"]!!.jsonPrimitive.long)
    assertEquals(345L, o["index"]!!.jsonPrimitive.long)
    assertEquals(1280, o["width"]!!.jsonPrimitive.int)
    assertEquals(960, o["height"]!!.jsonPrimitive.int)
    assertEquals(870.25, o["fx"]!!.jsonPrimitive.double, 0.0)
    assertEquals(869.5, o["fy"]!!.jsonPrimitive.double, 0.0)
    assertEquals(641.0, o["cx"]!!.jsonPrimitive.double, 0.0)
    assertEquals(478.5, o["cy"]!!.jsonPrimitive.double, 0.0)
    assertEquals(listOf(0.01, -0.02, 0.0, 0.0, 0.003), doubles(o["distortion"]!!.jsonArray))
    val p = doubles(o["cam_p"]!!.jsonArray)
    assertEquals(0.1, p[0], 1e-6)
    assertEquals(1.2, p[1], 1e-6)
    assertEquals(-0.3, p[2], 1e-6)
    assertEquals(listOf(-0.5, 0.5, -0.5, 0.5), doubles(o["cam_q"]!!.jsonArray)) // same rotation, w ≥ 0
    val t = o["T_world_cam"]!!.jsonArray.map { doubles(it.jsonArray) }
    assertEquals(4, t.size)
    assertTrue(t.all { it.size == 4 })
    val want = DumpFormat.matrix(cam)
    for (r in 0 until 4) for (c in 0 until 4) assertEquals(want[r][c], t[r][c], 1e-12)
    assertEquals(0.74, o["surface_y"]!!.jsonPrimitive.double, 1e-6)
    val tt = o["T_world_table"]!!.jsonArray.map { doubles(it.jsonArray) }
    assertEquals(listOf(1.0, 0.0, 0.0, 0.0), tt[0])
    assertEquals(listOf(0.0, 0.0, 1.0), tt[1].take(3))
    assertEquals(0.74, tt[1][3], 1e-6)
    assertEquals(listOf(0.0, -1.0, 0.0, 0.0), tt[2])
    assertEquals(listOf(0.0, 0.0, 0.0, 1.0), tt[3])
    assertEquals("table", o["anchor_source"]!!.jsonPrimitive.content)
    assertEquals(0.74, doubles(o["anchor_p"]!!.jsonArray)[1], 1e-6)
    assertEquals(0.756, doubles(o["ghost_p"]!!.jsonArray)[1], 1e-6)
    assertEquals(4, o["ghost_q"]!!.jsonArray.size)
    assertEquals("malm-drawer", o["job"]!!.jsonPrimitive.content)
    assertEquals(1, o["step"]!!.jsonPrimitive.int)
    assertEquals(0, o["action"]!!.jsonPrimitive.int)
    val tr = o["track"]!!.jsonObject
    assertEquals("held", tr["state"]!!.jsonPrimitive.content)
    assertEquals("markerless", tr["method"]!!.jsonPrimitive.content)
    assertFalse(tr["tracked"]!!.jsonPrimitive.boolean)
    assertEquals(JsonNull, tr["reproj_px"])
    assertEquals(4.5, tr["solve_ms"]!!.jsonPrimitive.double, 0.0)
    assertEquals(100.0, tr["age_ms"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(0.756, doubles(tr["asm_p"]!!.jsonArray)[1], 1e-6)
    assertEquals(-0.61, doubles(o["panel_p"]!!.jsonArray)[2], 1e-6)
    assertEquals("x", o["more"]!!.jsonObject["note"]!!.jsonPrimitive.content)

    // the canonical replay keys are there too: the app's own replay reader takes the line as it is
    val poses = ReplayDataset.parsePoses(line, listOf("000007.jpg"))
    val back = poses[0]!!
    assertTrue((back.t - cam.t).length() < 1e-6f)
    assertTrue(Frames.angleDeg(back.q, cam.q) < 1e-3)
    assertEquals(7, ReplayDataset.parseFrameNumbers(line, listOf("000007.jpg"))[0])
  }

  @Test
  fun unknownValuesAreNullAndTheLineStaysValidJson() {
    val line = DumpFormat.line(0, 0.0, 5L, 6L, 0L, k.copy(distortion = null), null, DumpExtras(surfaceY = Float.NaN))
    val o = obj(line)
    for (key in
        listOf("distortion", "cam_p", "cam_q", "T_world_cam", "surface_y", "T_world_table", "anchor_p", "anchor_q", "anchor_source",
            "ghost_p", "ghost_q", "job", "step", "action", "track", "panel_p", "panel_q")) {
      assertEquals(key, JsonNull, o[key])
    }
    assertFalse(o.containsKey("more"))
    assertTrue(ReplayDataset.parsePoses(line, listOf("000000.jpg")).isEmpty()) // no pose: replay plays it without one
    val i = Json.parseToJsonElement(DumpFormat.intrinsicsJson(k, 3.0)).jsonObject
    assertEquals(listOf("fx", "fy", "cx", "cy", "width", "height", "fps", "distortion"), i.keys.toList())
    assertEquals(k, ReplayDataset.parseIntrinsics(i.toString()))
    assertEquals(3.0, ReplayDataset.parseFps(i.toString())!!, 0.0)
  }

  @Test
  fun jpegSizeComesFromTheJpegItself() {
    assertEquals(1280 to 960, DumpFormat.jpegSize(jpeg(1280, 960)))
    assertEquals(64 to 48, DumpFormat.jpegSize(jpeg(64, 48)))
    assertEquals(2560 to 1920, DumpFormat.jpegSize(jpeg(2560, 1920, sof = 0xc2))) // progressive
    assertNull(DumpFormat.jpegSize(ByteArray(0)))
    assertNull(DumpFormat.jpegSize(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
    assertNull(DumpFormat.jpegSize(jpeg(64, 48).copyOf(12))) // cut before the frame header
    assertEquals("000042.jpg", DumpFormat.fileName(42))
  }

  @Test
  fun jpegSizeOfARealCameraSizedJpeg() {
    val real = realJpeg()
    assumeTrue("tools/markerless/sample not found", real != null)
    assertEquals(1280 to 960, DumpFormat.jpegSize(real!!))
  }

  // ---- launch extras ----

  @Test
  fun dumpExtrasAreOffByDefaultAndReadFromAdbExtras() {
    assertFalse(Params().dumpFrames)
    assertEquals(3.0, Params().dumpHz, 0.0)
    assertEquals(400, Params().dumpMax)
    assertEquals(Params(), Params.resolve(null, emptyMap()))
    // adb: --ez dump_frames true --ei dump_hz 5 --ei dump_max 120
    val p = Params.resolve(null, mapOf("dump_frames" to true, "dump_hz" to 5, "dump_max" to 120))
    assertTrue(p.dumpFrames)
    assertEquals(5.0, p.dumpHz, 0.0)
    assertEquals(120, p.dumpMax)
    // --es / --ef and config.json
    val s = Params.resolve("""{"dump_frames": true, "dump_hz": 2.5, "dump_max": 50}""", mapOf("dump_max" to "80"))
    assertTrue(s.dumpFrames)
    assertEquals(2.5, s.dumpHz, 0.0)
    assertEquals(80, s.dumpMax)
    assertEquals(1.5, Params.resolve(null, mapOf("dump_hz" to 1.5f)).dumpHz, 1e-6)
    // nonsense falls back or is clamped
    assertEquals(3.0, Params.resolve(null, mapOf("dump_hz" to "fast")).dumpHz, 0.0)
    assertEquals(3.0, Params.resolve(null, mapOf("dump_hz" to 0)).dumpHz, 0.0)
    assertEquals(30.0, Params.resolve(null, mapOf("dump_hz" to 500)).dumpHz, 0.0)
    assertEquals(400, Params.resolve(null, mapOf("dump_max" to -3)).dumpMax)
    assertEquals(5000, Params.resolve(null, mapOf("dump_max" to 1_000_000)).dumpMax)
    val log = p.toLogMap()
    assertEquals(true, log["dump_frames"])
    assertEquals(5.0, log["dump_hz"])
    assertEquals(120, log["dump_max"])
    assertTrue(Params.KEYS.containsAll(listOf("dump_frames", "dump_hz", "dump_max")))
  }

  // ---- the writer ----

  private fun tempDir(): File = Files.createTempDirectory("agj-dump").toFile()

  private fun logTo(lines: MutableList<String>) = AgqLog({ 0L }, { synchronized(lines) { lines.add(it) } })

  private fun dumpEvents(lines: List<String>): List<JsonObject> =
      synchronized(lines) { lines.map { obj(it) } }.filter { it["ev"]!!.jsonPrimitive.content == "dump" }

  @Test
  fun aSessionFolderIsAReplayDataset() {
    val root = tempDir()
    try {
      val dir = File(root, "20261115-140000")
      val lines = ArrayList<String>()
      val dump = FrameDump(dir, hz = 5.0, max = 400, log = logTo(lines), meta = mapOf("device" to "test"), wallMs = { 1_794_750_000_000L })
      assertTrue(dump.enabled)
      assertEquals(dir.path, dump.path)
      val picture = jpeg(1280, 960)
      val asked = AtomicInteger()
      // 2 s of a 50 fps camera: a frame is due every 200 ms → 10 frames
      for (i in 0 until 100) {
        val cam = Pose3(Vec3(i * 0.001f, 1.2f, -0.3f), Quat.axisAngle(Vec3(1f, 0f, 0f), -2.2f))
        val f = CameraFrame(i.toLong(), picture, 1280, 960, 1_000_000_000L + i * 20_000_000L, k, if (i == 20) null else cam)
        dump.onFrame(f) {
          asked.incrementAndGet()
          DumpExtras(surfaceY = 0.74f, ghost = Pose3(Vec3(0f, 0.756f, -0.6f)), job = "malm-drawer", step = 1, action = i)
        }
        assertTrue(dump.flush())
      }
      dump.stop()
      assertEquals(10, dump.written)
      assertEquals(10, asked.get()) // the extras are only asked for frames that are dumped
      assertEquals(0, dump.droppedFrames)
      val names = dir.list()!!.sorted()
      assertEquals((0 until 10).map { DumpFormat.fileName(it) } + listOf("intrinsics.json", "poses.jsonl", "session.json"), names)
      assertTrue(File(dir, "000003.jpg").readBytes().contentEquals(picture))
      val rows = File(dir, "poses.jsonl").readLines().filter { it.isNotBlank() }.map { obj(it) }
      assertEquals(10, rows.size)
      assertEquals((0 until 10).toList(), rows.map { it["frame"]!!.jsonPrimitive.int })
      assertEquals((0 until 10).map { it * 10 }, rows.map { it["action"]!!.jsonPrimitive.int }) // camera frames 0, 10, 20, …
      assertEquals(0.2, rows[1]["t"]!!.jsonPrimitive.double, 1e-9)
      assertEquals(1_000_000_000L + 200_000_000L, rows[1]["ts_ns"]!!.jsonPrimitive.long)
      assertEquals(JsonNull, rows[2]["cam_p"]) // camera frame 20 had no pose
      assertEquals(JsonNull, rows[2]["T_world_cam"])
      assertNotNull(rows[3]["T_world_cam"]!!.jsonArray)

      // the app's own replay reader takes the folder as it is
      val ds = ReplayDataset.read(dir)
      assertEquals(10, ds.frames.size)
      assertEquals(k, ds.intrinsics)
      assertEquals(5.0, ds.fps!!, 0.0)
      assertEquals(9, ds.poses.size)
      assertNull(ds.poses[2])
      assertEquals(0.03f, ds.poses[3]!!.t.x, 1e-6f)
      assertEquals(7, ds.frameIndex(7))

      val session = obj(File(dir, "session.json").readText())
      assertEquals(DumpFormat.VERSION, session["format"]!!.jsonPrimitive.content)
      assertEquals("20261115-140000", session["session"]!!.jsonPrimitive.content)
      assertEquals("test", session["device"]!!.jsonPrimitive.content)

      val ev = dumpEvents(lines)
      assertEquals(dir.path, ev.first()["path"]!!.jsonPrimitive.content) // at start: where it writes
      assertEquals(0, ev.first()["frames"]!!.jsonPrimitive.int)
      assertEquals(1, ev[1]["frames"]!!.jsonPrimitive.int)
      val last = ev.last()
      assertTrue(last["done"]!!.jsonPrimitive.boolean)
      assertEquals("stopped", last["reason"]!!.jsonPrimitive.content)
      assertEquals(10, last["frames"]!!.jsonPrimitive.int)
      assertEquals(9, last["with_pose"]!!.jsonPrimitive.int)
      assertTrue(last["bytes"]!!.jsonPrimitive.long > 10L * picture.size)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun stopsAtTheFrameCapAndLogsEveryFiftyFrames() {
    val dir = tempDir()
    try {
      val lines = ArrayList<String>()
      val dump = FrameDump(dir, hz = 30.0, max = 120, log = logTo(lines))
      val picture = jpeg(64, 48)
      for (i in 0 until 400) {
        dump.onFrame(CameraFrame(i.toLong(), picture, 64, 48, i * 40_000_000L, k.scaledTo(64, 48), null)) { DumpExtras() }
        assertTrue(dump.flush())
      }
      assertEquals(120, dump.written)
      assertEquals(120, dir.list()!!.count { it.endsWith(".jpg") })
      assertEquals(120, File(dir, "poses.jsonl").readLines().count { it.isNotBlank() })
      val ev = dumpEvents(lines)
      assertEquals(listOf(0, 1, 50, 100, 120), ev.map { it["frames"]!!.jsonPrimitive.int })
      assertEquals("max_frames", ev.last()["reason"]!!.jsonPrimitive.content)
      dump.stop() // nothing more to say
      assertEquals(5, dumpEvents(lines).size)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun stopsAtTheByteCap() {
    val dir = tempDir()
    try {
      val lines = ArrayList<String>()
      val picture = jpeg(64, 48, scanBytes = 50_000) // big enough that the lines do not matter
      val dump = FrameDump(dir, hz = 30.0, max = 400, log = logTo(lines), maxBytes = 3L * picture.size)
      for (i in 0 until 50) {
        dump.onFrame(CameraFrame(i.toLong(), picture, 64, 48, i * 40_000_000L, k.scaledTo(64, 48), null)) { DumpExtras() }
        assertTrue(dump.flush())
      }
      assertEquals(3, dump.written)
      assertEquals("max_bytes", dumpEvents(lines).last()["reason"]!!.jsonPrimitive.content)
      dump.stop()
    } finally {
      dir.deleteRecursively()
    }
  }

  /** A slow disk must cost frames, never camera time: at most MAX_PENDING frames wait, the rest are dropped. */
  @Test
  fun dropsFramesInsteadOfBlockingTheCameraThread() {
    val dir = tempDir()
    try {
      val gate = CountDownLatch(1)
      val dump =
          FrameDump(dir, hz = 30.0, max = 400, log = logTo(ArrayList()), writeBytes = { f, b ->
            gate.await(10, TimeUnit.SECONDS)
            f.writeBytes(b)
          })
      val picture = jpeg(64, 48)
      val t0 = System.nanoTime()
      for (i in 0 until 20) {
        dump.onFrame(CameraFrame(i.toLong(), picture, 64, 48, i * 40_000_000L, k.scaledTo(64, 48), null)) { DumpExtras() }
      }
      val ms = (System.nanoTime() - t0) / 1e6
      assertTrue("onFrame blocked for $ms ms", ms < 2000)
      assertEquals(0, dump.written)
      assertEquals(20 - FrameDump.MAX_PENDING, dump.droppedFrames)
      gate.countDown()
      assertTrue(dump.flush(5000))
      dump.stop()
      assertEquals(FrameDump.MAX_PENDING, dump.written)
      assertEquals(FrameDump.MAX_PENDING, File(dir, "poses.jsonl").readLines().count { it.isNotBlank() })
    } finally {
      dir.deleteRecursively()
    }
  }

  /** Live frames: the recorder asks for the full-resolution picture at quality 90 and stores that picture's intrinsics. */
  @Test
  fun asksForFullResolutionAndWritesTheIntrinsicsOfTheStoredPicture() {
    val dir = tempDir()
    try {
      val dump = FrameDump(dir, hz = 3.0, max = 10, log = logTo(ArrayList()))
      val asked = ArrayList<Pair<Int, Int>>()
      val half = jpeg(640, 480) // a source that cannot deliver the full size: the line must describe what is stored
      val f = CameraFrame(0, null, 1280, 960, 1L, k, Pose3(), encodeAt = { edge, q -> asked.add(edge to q); half })
      dump.onFrame(f) { DumpExtras() }
      assertTrue(dump.flush())
      dump.stop()
      assertEquals(listOf(1280 to 90), asked)
      val o = obj(File(dir, "poses.jsonl").readLines().first())
      assertEquals(640, o["width"]!!.jsonPrimitive.int)
      assertEquals(480, o["height"]!!.jsonPrimitive.int)
      assertEquals(k.fx / 2, o["fx"]!!.jsonPrimitive.double, 1e-9)
      assertEquals(k.cy / 2, o["cy"]!!.jsonPrimitive.double, 1e-9)
      assertEquals(640, ReplayDataset.read(dir).intrinsics.width)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun switchedOffItDoesNothing() {
    val dump = FrameDump(null, log = logTo(ArrayList()))
    assertFalse(dump.enabled)
    assertNull(dump.path)
    var asked = 0
    dump.onFrame(CameraFrame(0, jpeg(64, 48), 64, 48, 1L, k, null)) { asked++; DumpExtras() }
    dump.stop()
    assertEquals(0, asked)
    assertEquals(0, dump.written)
    assertEquals(0L, dump.bytes)
  }

  @Test
  fun aFailingExtrasCallbackStillRecordsTheFrame() {
    val dir = tempDir()
    try {
      val dump = FrameDump(dir, hz = 3.0, max = 10, log = logTo(ArrayList()))
      dump.onFrame(CameraFrame(0, jpeg(64, 48), 64, 48, 1L, k.scaledTo(64, 48), Pose3())) { throw IllegalStateException("world not ready") }
      assertTrue(dump.flush())
      dump.stop()
      val o = obj(File(dir, "poses.jsonl").readLines().first())
      assertEquals("world not ready", o["more"]!!.jsonObject["extras_error"]!!.jsonPrimitive.content)
      assertNotNull(o["T_world_cam"]!!.jsonArray)
    } finally {
      dir.deleteRecursively()
    }
  }
}
