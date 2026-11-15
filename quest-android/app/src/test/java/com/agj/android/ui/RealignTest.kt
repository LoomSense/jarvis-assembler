package com.agj.android.ui

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.world.AssemblyAnchor
import com.agj.android.world.PlaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Re-align" button (judge round 2, A3): the sequence MainActivity.realign() runs on the world package's public
 * anchor API, so a ghost that started in the wrong place can be brought back in front of the wearer.
 */
class RealignTest {
  @Test
  fun resetThenRealignPutsTheGhostInFrontAgainAndLetsTheCameraMoveIt() {
    var now = 0L
    val a = AssemblyAnchor(AgqLog({ now }, { }, wallMs = { 0L }), { now })
    // the ghost started where the head pointed while the guide loaded, and one camera answer already moved it
    assertTrue(a.placeInitial(Pose3(Vec3(2.0f, 0.7f, 1.0f)), PlaceSource.FIXED_SPOT))
    assertTrue(a.onServerBox(Pose3(Vec3(2.1f, 0.7f, 1.0f))))
    val inFront = Pose3(Vec3(0f, 0.7f, -0.55f))
    // nothing can place it "in front of me" again: this is why the button drops the anchor first
    assertFalse(a.placeInitial(inFront, PlaceSource.FIXED_SPOT))
    assertFalse(a.onServerBox(Pose3(Vec3(2.12f, 0.7f, 1.0f))))

    a.reset() // MainActivity.realign(), 1.5 s after the press
    a.requestRealign()
    assertNull(a.source)
    assertTrue(a.placeInitial(inFront, PlaceSource.FIXED_SPOT)) // WorldModule.tick on the next frame
    assertEquals(inFront.t, a.pose!!.t)
    // and the next camera answer for the base part moves it at once, however near
    now += 100
    assertTrue(a.wantsServerBox(0))
    assertTrue(a.onServerBox(Pose3(Vec3(0.03f, 0.7f, -0.50f))))
    assertEquals(PlaceSource.SERVER_BOX, a.source)
    assertFalse(a.realignRequested)
  }

  @Test
  fun theButtonHasANameInBothLanguages() {
    assertEquals("Ευθυγράμμιση", Strings.t("el", "align"))
    assertEquals("Re-align", Strings.t("en", "align"))
    assertEquals("ΕΥΘΥΓΡΑΜΜΙΣΗ", Strings.upper("el", Strings.t("el", "align")))
    assertEquals("Κοίτα το κομμάτι πάνω στο τραπέζι.", Strings.t("el", "alignLook"))
  }
}
