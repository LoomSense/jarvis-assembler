package com.agj.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** What is spoken at a step entry (judge round 2, A4): the no-marker coach line first, then the step line, uncut. */
class VoiceLinesTest {
  private class Recorder : Voice {
    val said = ArrayList<Pair<String, Boolean>>()

    override fun say(text: String, lang: String, interrupt: Boolean) {
      said += text to interrupt
    }

    override fun stop() {}

    override val speaking = false
  }

  @Test
  fun stepEntrySpeaksTheCoachLineFirstInOneUtterance() {
    val v = Recorder()
    val coach = "Βάλε το κομμάτι «Πρόσοψη συρταριού» μέσα στο πράσινο περίγραμμα."
    val step = "Βήμα 1, σελίδα 5. Βίδωσε τις δύο βίδες στην πρόσοψη."
    v.sayStep(listOf(coach, step), "el")
    // one utterance: the step line cannot cut the coach line, and the coach line comes first
    assertEquals(listOf("$coach $step" to true), v.said)
    // a step that just ended: "Step 1 done." leads the next step's entry instead of being cut by it 1.4 s later (A6)
    v.said.clear()
    v.sayStep(listOfNotNull("Το βήμα 1 έγινε.", null, "Βήμα 2, σελίδα 6. Βάλε τον πάτο."), "el")
    assertEquals(listOf("Το βήμα 1 έγινε. Βήμα 2, σελίδα 6. Βάλε τον πάτο." to true), v.said)
    // no coach line (later steps, or the part is followed live): just the step line; blanks are skipped
    v.said.clear()
    v.sayStep(listOfNotNull(null, step), "el")
    v.sayStep(listOf("", step), "el")
    assertEquals(listOf(step to true, step to true), v.said)
  }
}
