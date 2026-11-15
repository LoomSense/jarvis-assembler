package com.agj.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Register card in the no-marker flow (judge round 2, A5): it asks for a look at the part, never for a marker. */
class CoachCopyTest {
  @Test
  fun registerCardDoesNotAskForAMarker() {
    assertEquals("Κοίτα το κομμάτι από πάνω", Strings.t("el", "coachLockTitle"))
    assertEquals("Μείνε έτσι για λίγα δευτερόλεπτα.", Strings.t("el", "coachLockBody"))
    assertEquals("Look down at the part", Strings.t("en", "coachLockTitle"))
    for (lang in listOf("el", "en")) for (key in listOf("coachLockTitle", "coachLockBody", "coachLocked")) {
      val text = Strings.t(lang, key).lowercase()
      assertFalse("$lang $key: $text", listOf("marker", "σημάδι", "σημαδι", "touch", "άγγιξ").any { it in text })
    }
    // capitals: no tonos, final sigma as a capital
    assertEquals("ΚΟΙΤΑ ΤΟ ΚΟΜΜΑΤΙ ΑΠΟ ΠΑΝΩ", Strings.upper("el", Strings.t("el", "coachLockTitle")))
    assertEquals("ΤΕΛΟΣ", Strings.upper("el", Strings.t("el", "finish")))
    assertEquals("ΛΑΘΟΣ", Strings.upper("el", Strings.t("el", "wrong")))
  }
}
