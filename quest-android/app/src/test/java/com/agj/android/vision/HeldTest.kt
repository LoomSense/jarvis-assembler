package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HeldDetector], [HandProjector] and [HandsStatusTracker]: the pure parts of hand tracking. */
class HeldTest {
  private val head = Vec3(0f, 1.5f, 0f)

  /** A hand with finger joints: [gap] between thumb tip and index tip, fingers straight (10 cm from the palm). */
  private fun open(wrist: Vec3, gap: Float): HandSample {
    val palm = wrist + Vec3(0f, 0f, -0.07f)
    val index = palm + Vec3(0f, 0f, -0.10f)
    return HandSample(wrist, index, palm = palm, thumbTip = index + Vec3(gap, 0f, 0f), middleTip = palm + Vec3(0.02f, 0f, -0.10f))
  }

  /** Index and middle curled to 5 cm from the palm, thumb away from both. */
  private fun fist(wrist: Vec3): HandSample {
    val palm = wrist + Vec3(0f, 0f, -0.07f)
    return HandSample(wrist, palm + Vec3(0f, 0f, -0.05f), palm = palm, thumbTip = palm + Vec3(0.09f, 0f, 0f),
        middleTip = palm + Vec3(0.02f, 0f, -0.05f))
  }

  private fun frame(tMs: Long, left: HandSample?, right: HandSample?) = HandsFrame(tMs * 1_000_000, head, left, right)

  private fun run(d: HeldDetector, from: Long, to: Long, left: (Long) -> HandSample?, right: (Long) -> HandSample?): HeldState {
    var s = HeldState.NONE
    var t = from
    while (t <= to) {
      s = d.update(frame(t, left(t), right(t)), t)
      t += 50
    }
    return s
  }

  @Test
  fun pinchTurnsOnAfterAQuarterSecondAndOffAfterTheHandOpens() {
    val d = HeldDetector()
    val w = Vec3(0.2f, 1.2f, -0.4f)
    var s = run(d, 0, 950, { null }, { open(w, 0.10f) })
    assertFalse(s.holding)
    assertEquals(HandSide.RIGHT, s.which)
    assertEquals(w, s.handWorldPos)
    assertTrue(s.fingers)
    s = run(d, 1000, 1200, { null }, { open(w, 0.02f) })
    assertFalse("not before 250 ms", s.holding)
    s = run(d, 1250, 1900, { null }, { open(w, 0.02f) })
    assertTrue(s.holding)
    assertEquals("pinch", s.grip)
    assertEquals(1250L, s.sinceMs)
    assertTrue("a wrist that does not move is still", s.still)
    // hysteresis: 5.5 cm is inside the exit threshold
    s = run(d, 1950, 2600, { null }, { open(w, 0.055f) })
    assertTrue(s.holding)
    s = run(d, 2650, 3000, { null }, { open(w, 0.10f) })
    assertTrue("still holding before 400 ms", s.holding)
    s = run(d, 3050, 3100, { null }, { open(w, 0.10f) })
    assertFalse(s.holding)
    assertNull(s.grip)
    assertEquals(3050L, s.sinceMs)
  }

  @Test
  fun aFistIsAGripAndAWristWithTheSystemPinchHoldsToo() {
    val w = Vec3(0f, 1.2f, -0.4f)
    var s = run(HeldDetector(), 0, 500, { fist(w) }, { null })
    assertTrue(s.holding)
    assertEquals("grip", s.grip)
    assertEquals(HandSide.LEFT, s.which)
    s = run(HeldDetector(), 0, 500, { null }, { HandSample(w, null, pinching = true) })
    assertTrue(s.holding)
    assertEquals("system_pinch", s.grip)
    assertFalse(s.fingers)
    s = run(HeldDetector(), 0, 500, { null }, { HandSample(w, null) })
    assertFalse("a wrist alone says nothing", s.holding)
    assertEquals(w, s.handWorldPos)
  }

  @Test
  fun aHoldingHandThatDropsOutKeepsItsStateForAShortWhile() {
    val d = HeldDetector()
    val w = Vec3(0f, 1.2f, -0.4f)
    run(d, 0, 500, { null }, { open(w, 0.02f) })
    var s = run(d, 550, 1000, { null }, { null })
    assertTrue(s.holding)
    assertEquals("the last wrist", w, s.handWorldPos)
    s = run(d, 1050, 1200, { null }, { null })
    assertFalse(s.holding)
    assertNull(s.handWorldPos)
    assertEquals(HandSide.RIGHT, s.which)
  }

  @Test
  fun theHoldingHandWinsElseTheOneNearerTheHead() {
    val near = Vec3(-0.1f, 1.3f, -0.2f)
    val far = Vec3(0.3f, 1.0f, -0.5f)
    var s = run(HeldDetector(), 0, 500, { open(near, 0.10f) }, { open(far, 0.02f) })
    assertTrue(s.holding)
    assertEquals(HandSide.RIGHT, s.which)
    assertEquals(far, s.handWorldPos)
    s = run(HeldDetector(), 0, 500, { open(near, 0.10f) }, { open(far, 0.10f) })
    assertFalse(s.holding)
    assertEquals(HandSide.LEFT, s.which)
  }

  @Test
  fun aMovingWristIsNotStill() {
    val d = HeldDetector()
    val s = run(d, 0, 1000, { null }, { t -> open(Vec3(0.001f * (t / 10f), 1.2f, -0.4f), 0.02f) }) // 10 cm a second
    assertTrue(s.holding)
    assertFalse(s.still)
  }

  @Test
  fun handBoxInTheCameraImage() {
    val intr = Intrinsics(870.0, 870.0, 640.0, 480.0, 1280, 960)
    val cam = Pose3.IDENTITY // camera axes = world axes: +Z forward, +Y down
    val hand = HandSample(Vec3(0f, 0.08f, 0.4f), Vec3(0f, -0.06f, 0.4f), palm = Vec3(0f, 0.02f, 0.4f),
        thumbTip = Vec3(-0.03f, -0.03f, 0.4f))
    val h = HandProjector.project(hand, intr, cam)!!
    assertTrue(h.inView)
    assertEquals(0.4f, h.distM, 0.01f)
    // joints span u 575..640, v 349..654; 7 cm of padding at 0.4 m is 152 px
    assertEquals((574.75 - 152.25) / 1280, h.box[0].toDouble(), 0.01)
    assertEquals((640 + 152.25) / 1280, h.box[2].toDouble(), 0.01)
    assertEquals((349.5 - 152.25) / 960, h.box[1].toDouble(), 0.01)
    assertEquals((654 + 152.25) / 960, h.box[3].toDouble(), 0.01)
    // a wrist alone gets twice the padding
    val wristOnly = HandProjector.project(HandSample(Vec3(0f, 0f, 0.4f), null), intr, cam)!!
    assertEquals((640 - 304.5) / 1280, wristOnly.box[0].toDouble(), 0.01)
    // off to the side: clamped box, not in view
    val side = HandProjector.project(HandSample(Vec3(0.6f, 0f, 0.4f), null), intr, cam)!!
    assertFalse(side.inView)
    assertEquals(1f, side.box[2], 1e-6f)
    // behind the camera
    assertNull(HandProjector.project(HandSample(Vec3(0f, 0f, -0.4f), null), intr, cam))
    // a camera turned and moved: the same hand expressed in WORLD lands in the same place
    val worldFromCamera = Pose3(Vec3(1f, 1.5f, -2f), com.agj.android.core.Quat.yaw(0.7f))
    val moved = HandSample(worldFromCamera.transform(hand.wrist), worldFromCamera.transform(hand.indexTip!!),
        palm = worldFromCamera.transform(hand.palm!!), thumbTip = worldFromCamera.transform(hand.thumbTip!!))
    val m = HandProjector.project(moved, intr, worldFromCamera)
    assertNotNull(m)
    for (i in 0..3) assertEquals(h.box[i], m!!.box[i], 1e-3f)
  }

  @Test
  fun statusNeverTrackedThenLiveThenOutOfView() {
    val s = HandsStatusTracker()
    var out: String? = null
    for (t in 0L until 12_000L step 100) out = s.update(t, fingers = false, wrist = false, controllers = false, works = true) ?: out
    assertNull("nothing in the first 12 s", out)
    assertEquals(HandsStatusTracker.NO_HANDS_TRACKED, s.update(12_000, false, false, false, true))
    assertNull(s.update(12_100, false, false, false, true))
    assertEquals("repeated after a minute", HandsStatusTracker.NO_HANDS_TRACKED, s.update(72_000, false, false, false, true))
    assertTrue(HandsStatusTracker.message(HandsStatusTracker.NO_HANDS_TRACKED).contains("off in the headset settings"))
    assertEquals(HandsStatusTracker.JOINTS_LIVE, s.update(80_000, true, true, false, true))
    assertNull(s.update(80_100, true, true, false, true))
    // hands leave: a different state from "never tracked", once
    assertNull(s.update(81_000, false, false, false, true))
    assertEquals(HandsStatusTracker.HANDS_OUT_OF_VIEW, s.update(93_000, false, false, false, true))
    assertNull(s.update(200_000, false, false, false, true))
    assertEquals(HandsStatusTracker.JOINTS_LIVE, s.update(200_100, true, true, false, true))
  }

  @Test
  fun statusWristOnlyControllersAndFailure() {
    var s = HandsStatusTracker()
    assertNull(s.update(0, false, true, false, true))
    assertEquals(HandsStatusTracker.WRIST_ONLY, s.update(1_000, false, true, false, true))
    assertEquals(HandsStatusTracker.JOINTS_LIVE, s.update(1_100, true, true, false, true))
    // fingertips hidden for a moment do not downgrade; 10 s do
    assertNull(s.update(1_200, false, true, false, true))
    assertNull(s.update(9_000, false, true, false, true))
    assertEquals(HandsStatusTracker.WRIST_ONLY, s.update(11_200, false, true, false, true))

    s = HandsStatusTracker()
    assertNull(s.update(0, false, false, true, true))
    assertEquals(HandsStatusTracker.CONTROLLERS_ACTIVE, s.update(3_000, false, false, true, true))

    s = HandsStatusTracker()
    assertNull(s.update(0, false, false, false, false))
    assertEquals(HandsStatusTracker.API_FAILED, s.update(2_000, false, false, false, false))
    assertNull(s.update(30_000, false, false, false, false))
    assertEquals(HandsStatusTracker.API_FAILED, s.update(62_000, false, false, false, false))
  }
}
