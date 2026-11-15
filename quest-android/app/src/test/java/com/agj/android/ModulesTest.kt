package com.agj.android

import com.agj.android.core.Vec3
import com.agj.android.core.blenderToGltf
import com.agj.android.guide.Manifest
import com.agj.android.net.PerceptionSocket
import com.agj.android.ui.CoachFlow
import com.agj.android.vision.ReplayDataset
import com.agj.android.vision.TouchTracker
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulesTest {
  private fun near(a: Vec3, b: Vec3, eps: Float = 1e-4f) = assertTrue("$a != $b", (a - b).length() < eps)

  @Test
  fun blenderZupToGltfYup() {
    near(Vec3(1f, 3f, -2f), blenderToGltf(listOf(1.0, 2.0, 3.0)))
  }

  @Test
  fun manifestParsesLeniently() {
    val json =
        """
        {"title":"T","glb":"walkthrough.glb","rootNode":"assembly","overall_mm":{"L":400,"W":300,"H":100},
         "walkthrough":{"clip":"walkthrough","steps":[{"n":1},{"n":"finish"}]},
         "steps":[{"n":1,"title":"S1","assemblyRotationDeg":[90,0,0],"hardware":[{"code":"118331","qty":2}],
           "cite":{"page":4},"actions":[{"type":"place","text":{"en":"Lay it","el":"Ακούμπησε"},"node":"part_a_1",
           "target_m":[0.1,0.2,0.3],"from_dir":[0,0,1],"unknownField":5}]},
          {"n":2,"actions":[{"type":"fasten","code":"118331"},{"type":"turn"}]}],
         "newTopLevel":{"x":1}}
        """
    val m = Manifest.parse(json)
    assertEquals(2, m.steps.size)
    assertEquals(3, m.actionCount)
    val a = m.step(1)!!.actions[0]
    assertEquals("Ακούμπησε", a.text.get("el"))
    assertEquals("Lay it", a.text.get("en"))
    near(Vec3(0.1f, 0.3f, -0.2f), a.targetGltf!!)
    near(Vec3(0f, 1f, 0f), a.fromDirGltf!!) // Blender +Z (up) → glTF +Y
    assertEquals(4, m.step(1)!!.cite.page)
  }

  @Test
  fun packFrameIsBigEndianHeaderThenJpeg() {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2)
    val buf = PerceptionSocket.packFrame(PerceptionSocket.frameHeader(7, 2, 123L, "medium"), jpeg)
    val n = ByteBuffer.wrap(buf).int // ByteBuffer default is big-endian
    val head = Json.parseToJsonElement(buf.copyOfRange(4, 4 + n).decodeToString()).jsonObject
    assertEquals(7, head["seq"]!!.jsonPrimitive.int)
    assertEquals(2, head["step"]!!.jsonPrimitive.int)
    assertEquals("headset", head["source"]!!.jsonPrimitive.content)
    assertArrayEquals(jpeg, buf.copyOfRange(4 + n, buf.size))
  }

  @Test
  fun twoCornerSnapMatchesRegisterTs() {
    // model corners 0.4 m apart along +X; touched corners rotated 90° (towards -Z) and moved
    val a = Vec3(-0.2f, 0f, 0.15f)
    val b = Vec3(0.2f, 0f, 0.15f)
    val p = Vec3(1f, 0.75f, -1f)
    val q = Vec3(1f, 0.75f, -1.4f)
    val r = TouchTracker.solveSnap(a, b, p, q)
    assertEquals(PI / 2, r.yawRad.toDouble(), 1e-5)
    assertTrue(r.plausible)
    near(p, r.worldFromAssembly.transform(a))
    near(q, r.worldFromAssembly.transform(b))
    assertEquals(90.0, r.worldFromAssembly.q.yawDeg(), 1e-3)
  }

  @Test
  fun coachFlowHappyPathAndFailures() {
    val seen = mutableListOf<CoachFlow.Stage>()
    val f = CoachFlow { _, to, _ -> seen += to }
    f.frameCaptured()
    f.identified(null, null, false)
    assertEquals(CoachFlow.Stage.LOOK, f.stage)
    f.frameCaptured()
    f.identified("malm-drawer", "MALM", ready = false)
    f.built()
    f.located(3)
    f.registered()
    assertEquals(CoachFlow.Stage.GUIDE, f.stage)
    assertEquals(3, f.startStep)
    f.trackingLost()
    assertEquals(
        listOf("IDENTIFY", "LOOK", "IDENTIFY", "BUILD", "LOCATE", "REGISTER", "GUIDE", "REGISTER"),
        seen.map { it.name },
    )
    val g = CoachFlow()
    g.startWithJob("lack")
    g.failed()
    assertEquals(CoachFlow.Stage.REGISTER, g.stage)
    assertNull(g.startStep)
  }

  @Test
  fun replayFolderParses() {
    val dir = createTempDir("replay")
    try {
      File(dir, "000001.jpg").writeBytes(byteArrayOf(1))
      File(dir, "000000.jpg").writeBytes(byteArrayOf(0))
      File(dir, "notes.txt").writeText("x")
      File(dir, "intrinsics.json").writeText("""{"fx":500,"fy":501,"cx":320,"cy":240,"width":640,"height":480}""")
      File(dir, "poses.jsonl")
          .writeText(
              """
              {"frame":0,"pos_m":[0,1.6,0],"quat_xyzw":[0,0,0,1]}
              garbage
              {"file":"000001.jpg","p":[0.1,1.6,0],"q":[0,0,0,2]}
              """.trimIndent(),
          )
      val ds = ReplayDataset.read(dir)
      assertEquals(listOf("000000.jpg", "000001.jpg"), ds.frames.map { it.name })
      assertEquals(501.0, ds.intrinsics.fy, 0.0)
      assertEquals(640, ds.intrinsics.width)
      assertEquals(2, ds.poses.size)
      near(Vec3(0.1f, 1.6f, 0f), ds.poses[1]!!.t)
      assertEquals(1f, ds.poses[1]!!.q.w, 1e-6f) // normalised
      val half = ds.intrinsics.scaledTo(320, 240)
      assertEquals(250.0, half.fx, 1e-9)
      assertEquals(120.0, half.cy, 1e-9)
    } finally {
      dir.deleteRecursively()
    }
  }
}
