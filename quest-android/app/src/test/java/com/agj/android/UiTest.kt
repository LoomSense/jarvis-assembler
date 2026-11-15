package com.agj.android

import com.agj.android.core.Vec3
import com.agj.android.guide.Shapes
import com.agj.android.ui.Strings
import com.agj.android.ui.WindowFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTest {
  @Test
  fun greekUppercaseDropsTonos() {
    assertEquals("ΒΗΜΑ 3 ΑΠΟ 7", Strings.upper("el", Strings.t("el", "step", "n" to 3, "total" to 7)))
    assertEquals("STEP 3 OF 7", Strings.upper("en", Strings.t("en", "step", "n" to 3, "total" to 7)))
  }

  @Test
  fun windowSitsBesideAndBelowEyeAndIgnoresJitter() {
    val f = WindowFollow()
    // seated at a table (the object 30° below the eyes): beside it. Standing over it is the steep case (UI-FLOOR):
    // the window is then just beyond the object, pinned in ui/WindowPlacementTest case (c).
    val head = Vec3(0f, 1.2f, 0f)
    val obj = Vec3(0f, 0.85f, -0.6f)
    val t = f.target(obj, 0.2f, head)
    assertTrue("right of the object", t.x > 0.3f)
    val dist = kotlin.math.hypot(t.x - head.x, t.z - head.z)
    assertTrue("10–20° below eye", t.y <= head.y - dist * 0.17f && t.y >= head.y - dist * 0.37f)
    f.update(t, 0.016f)
    val p0 = f.position!!
    repeat(60) { f.update(t + Vec3(0.01f, 0f, 0f), 0.016f) } // 1 cm jitter: inside the dead band
    assertTrue((f.position!! - p0).length() < 1e-4f)
    repeat(120) { f.update(t + Vec3(0.20f, 0f, 0f), 0.016f) } // 20 cm move: follows within ~1 s
    assertTrue((f.position!! - (t + Vec3(0.20f, 0f, 0f))).length() < 0.01f)
  }

  @Test
  fun insertionPathEndsShortOfThePart() {
    val pts = Shapes.insertionPath(Vec3.ZERO, Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f), straight = false)
    assertTrue(pts.size in 15..60)
    assertEquals(0.02f, pts.last().z, 0.003f)
    val straight = Shapes.insertionPath(Vec3.ZERO, Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), straight = true)
    assertEquals(0.10f, straight.first().x, 1e-4f)
    val arrow = Shapes.turnArrow(0.02f, 0.00175f)
    assertTrue(arrow.indices.all { it < arrow.vertexCount })
  }
}
