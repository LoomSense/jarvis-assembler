package com.agj.android.ui

import com.agj.android.guide.ActionKind
import com.agj.android.guide.GuideAction
import com.agj.android.guide.LocalizedText
import com.agj.android.guide.PartLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE-COACH: the coach's spoken lines on the custom drawer guide (its names and its board texts, as in the job's
 * manifest): the exact Greek of each moment, every case in both languages, the length limit, the instruction's
 * imperative core, the articles, no sentence twice in a row, and the list fetched ahead.
 */
class LiveLinesTest {
  private class Names(val el: String, val en: String)

  private val front = Names("Πρόσοψη του συρταριού", "Drawer front")
  private val bolt = Names("Βίδα έκκεντρου", "Cam bolt")
  private val left = Names("Αριστερό πλαϊνό", "Left side panel")
  private val right = Names("Δεξί πλαϊνό", "Right side panel")
  private val cam = Names("Κλειδαριά έκκεντρου", "Cam lock")
  private val bottom = Names("Πάτο του συρταριού", "Drawer bottom")
  private val back = Names("Πλάτη του συρταριού", "Back panel")
  private val peg = Names("Λευκή πλαστική πρόκα", "White plastic peg")

  private val frontText =
      Names(
          "Ακούμπησε την πρόσοψη του συρταριού μπροστά σου με την εσωτερική πλευρά (με το αυλάκι και τις τρύπες) προς τα πάνω και το αυλάκι στη μακρινή από σένα πλευρά.",
          "Lay the drawer front flat in front of you with its inner face (the one with the groove and the holes) up, the groove on the side away from you.",
      )
  private val leftText =
      Names(
          "Πάρε το αριστερό πλαϊνό και κατέβασέ το πάνω στις δύο βίδες της αριστερής άκρης: με το αυλάκι προς τα μέσα και στην ίδια ευθεία με το αυλάκι της πρόσοψης, με τις μεταλλικές κλειδαριές προς τα έξω και προς την πρόσοψη, η ράγα απ' έξω.",
          "Take the left side panel and lower it onto the two bolts at the left end: groove facing inwards and in line with the groove of the front, metal cam locks facing outwards and down next to the front, rail on the outside.",
      )
  private val rightText =
      Names(
          "Πάρε το δεξί πλαϊνό και κατέβασέ το πάνω στις δύο βίδες της δεξιάς άκρης: με το αυλάκι προς τα μέσα και στην ίδια ευθεία με το αυλάκι της πρόσοψης, με τις μεταλλικές κλειδαριές προς τα έξω και προς την πρόσοψη, η ράγα απ' έξω.",
          "Take the right side panel and lower it onto the two bolts at the right end: groove facing inwards and in line with the groove of the front, metal cam locks facing outwards and down next to the front, rail on the outside.",
      )
  private val bottomText =
      Names(
          "Πάρε τον πάτο του συρταριού και πέρασέ τον στα αυλάκια των δύο πλαϊνών με τη λευκή πλευρά προς τα μέσα. Σπρώξ' τον μέχρι το αυλάκι της πρόσοψης.",
          "Take the drawer bottom and slide it into the grooves of the two side panels with its white face towards the inside of the drawer. Push it down until it sits in the groove of the front.",
      )
  private val backText =
      Names(
          "Πάρε την πλάτη του συρταριού και βάλε την ανάμεσα στα πλαϊνά, στις ελεύθερες άκρες τους, με το αυλάκι προς τα κάτω ώστε να πιάσει τον πάτο.",
          "Take the back panel and fit it between the side panels at their free ends, with its groove facing down so that it catches the edge of the bottom.",
      )
  private val hwText = Names("Πάρε μια βίδα έκκεντρου (BOLT-C) και βίδωσε τη μεριά με το σπείρωμα στην πρόσοψη.", "Take a cam bolt (BOLT-C) and screw it into the front.")

  private fun pick(n: Names, lang: String) = if (lang == "el") n.el else n.en

  /** The drawer guide: 6 steps, 17 guided actions; the cam locks are already in the side panels (nothing to take). */
  private fun drawer(lang: String): List<LiveAct> {
    fun board(step: Int, id: String, n: Names, t: Names) = LiveAct(step, 0, CoachItem.BOARD, id, pick(n, lang), pick(t, lang))
    fun hw(step: Int, action: Int, item: CoachItem, id: String, n: Names, k: Int, of: Int, take: Boolean = true) =
        LiveAct(step, action, item, id, pick(n, lang), pick(hwText, lang), k, of, take)
    return listOf(board(1, "drawer_front", front, frontText)) +
        (1..4).map { hw(2, it - 1, CoachItem.SCREW, "BOLT-C", bolt, it, 4) } +
        board(3, "drawer_side_left", left, leftText) +
        (1..2).map { hw(3, it, CoachItem.CAM, "CAM-L", cam, it, 2, take = false) } +
        board(4, "drawer_side_right", right, rightText) +
        (1..2).map { hw(4, it, CoachItem.CAM, "CAM-L", cam, it, 2, take = false) } +
        board(5, "drawer_bottom", bottom, bottomText) +
        board(6, "drawer_back", back, backText) +
        (1..4).map { hw(6, it, CoachItem.PEG, "PEG-W", peg, it, 4) }
  }

  private val kinds = listOf(listOf("drawer_back"), listOf("drawer_bottom"), listOf("drawer_front"), listOf("drawer_side_left", "drawer_side_right"))
  private val el = drawer("el")
  private val en = drawer("en")

  private fun done(acts: List<LiveAct>, lang: String, i: Int) = LiveLines.done(lang, acts[i], acts.getOrNull(i + 1))

  // ---- the lines of each moment, in Greek ----

  @Test
  fun `the right bolt in the hand`() {
    assertEquals("Σωστή βίδα! Βίδωσέ τη στην τρύπα που φωτίζεται.", LiveLines.rightInHand("el", el[1]))
    assertEquals("Right screw! Screw it into the hole that is lit.", LiveLines.rightInHand("en", en[1]))
  }

  @Test
  fun `a bolt is done and another of the same comes`() {
    assertEquals(DoneLine("Μπράβο! Πάμε στην επόμενη, 2 από 4. Πάρε: Βίδα έκκεντρου.", true), done(el, "el", 1))
    assertEquals(DoneLine("Μπράβο! Πάμε στην επόμενη, 4 από 4. Πάρε: Βίδα έκκεντρου.", true), done(el, "el", 3))
    assertEquals(DoneLine("Well done! On to the next one, 2 of 4. Take: Cam bolt.", true), done(en, "en", 1))
  }

  @Test
  fun `the last bolt is done and the next step comes`() {
    assertEquals(DoneLine("Μπράβο, και οι τέσσερις μπήκαν. Επόμενο βήμα: πάρε το αριστερό πλαϊνό.", true), done(el, "el", 4))
    assertEquals(DoneLine("Well done, all four are in. Next step: take the left side panel.", true), done(en, "en", 4))
  }

  @Test
  fun `the right side panel in the hand`() {
    assertEquals("Σωστό κομμάτι! Κατέβασέ το πάνω στις δύο βίδες της αριστερής άκρης.", LiveLines.rightInHand("el", el[5]))
    assertEquals("Σωστό κομμάτι! Κατέβασέ το πάνω στις δύο βίδες της δεξιάς άκρης.", LiveLines.rightInHand("el", el[8]))
    assertEquals("Right part! Lower it onto the two bolts at the left end.", LiveLines.rightInHand("en", en[5]))
  }

  @Test
  fun `a wrong board in the hand`() {
    assertEquals("Λάθος κομμάτι. Πάρε το αριστερό πλαϊνό.", LiveLines.wrongBoard("el", back.el, left.el))
    assertEquals("Λάθος κομμάτι. Πάρε την πρόσοψη του συρταριού.", LiveLines.wrongBoard("el", bottom.el, front.el))
    assertEquals("Λάθος κομμάτι. Πάρε τον πάτο του συρταριού.", LiveLines.wrongBoard("el", right.el, bottom.el))
    assertEquals("Wrong part. Take the left side panel.", LiveLines.wrongBoard("en", back.en, left.en))
    // a name whose article cannot be guessed: no article at all
    assertEquals("Λάθος κομμάτι. Πάρε το αριστερό πλαϊνό.", LiveLines.wrongBoard("el", "Ράγες", left.el))
    assertEquals("Αυτό είναι: Πλάτη του συρταριού", LiveLines.wrongTitle("el", back.el))
  }

  @Test
  fun `a step change after a single board`() {
    assertEquals(DoneLine("Τέλειο. Επόμενο βήμα: πάρε τη βίδα έκκεντρου, 1 από 4.", true), done(el, "el", 0))
    assertEquals(DoneLine("Τέλειο. Επόμενο βήμα: πάρε την πλάτη του συρταριού.", true), done(el, "el", 11))
    assertEquals(DoneLine("Great. Next step: take the cam bolt, 1 of 4.", true), done(en, "en", 0))
  }

  @Test
  fun `a new part in the same step`() {
    assertEquals(DoneLine("Μπράβο! Τώρα πάρε τη λευκή πλαστική πρόκα, 1 από 4.", true), done(el, "el", 12))
    assertEquals(DoneLine("Well done! Now take the white plastic peg, 1 of 4.", true), done(en, "en", 12))
  }

  @Test
  fun `cam locks already in the panel are not taken - their own instruction is the entry line`() {
    // the side is in: the cam lock's instruction follows by itself
    assertEquals(DoneLine("Μπράβο!", false), done(el, "el", 5))
    // the second of two: no "Πάρε"
    assertEquals(DoneLine("Μπράβο! Πάμε στην επόμενη, 2 από 2.", true), done(el, "el", 6))
    assertEquals(DoneLine("Μπράβο, και οι δύο έσφιξαν. Επόμενο βήμα: πάρε το δεξί πλαϊνό.", true), done(el, "el", 7))
    assertEquals(DoneLine("Μπράβο, και οι δύο έσφιξαν. Επόμενο βήμα: πάρε τον πάτο του συρταριού.", true), done(el, "el", 10))
    assertEquals(DoneLine("Well done, both are tight. Next step: take the drawer bottom.", true), done(en, "en", 10))
    assertNull("the guide's end has its own line", done(el, "el", 16))
  }

  @Test
  fun `the encouragement while the hand works - not for boards`() {
    assertEquals("Ωραία, συνέχισε μέχρι να σφίξει.", LiveLines.keepGoing("el", CoachItem.SCREW))
    assertEquals("Ωραία, συνέχισε μέχρι να σφίξει.", LiveLines.keepGoing("el", CoachItem.CAM))
    assertEquals("Ωραία, σπρώξε μέχρι τέρμα.", LiveLines.keepGoing("el", CoachItem.PEG))
    assertNull(LiveLines.keepGoing("el", CoachItem.BOARD))
    assertNull(LiveLines.keepGoing("en", CoachItem.OTHER))
  }

  @Test
  fun `the wrong way round keeps the server's sentence`() {
    assertEquals("Προσοχή: Γύρισε το πλαϊνό, το αυλάκι πάει προς τα μέσα.", LiveLines.wrongWay("el", "Γύρισε το πλαϊνό, το αυλάκι πάει προς τα μέσα."))
    assertEquals("Careful: Turn the panel over.", LiveLines.wrongWay("en", "Turn the panel over."))
    assertEquals("Προσοχή: είναι ανάποδα. Γύρισέ το.", LiveLines.wrongWay("el", null))
    assertEquals("Προσοχή, είναι ανάποδα.", LiveLines.wrongWay("el", "Προσοχή, είναι ανάποδα."))
  }

  // ---- the instruction's imperative core ----

  @Test
  fun `a long instruction is shortened to its imperative core`() {
    assertEquals("Ακούμπησε την πρόσοψη του συρταριού μπροστά σου.", LiveLines.core("el", frontText.el))
    assertEquals("Πέρασέ τον στα αυλάκια των δύο πλαϊνών με τη λευκή πλευρά προς τα μέσα.", LiveLines.core("el", bottomText.el))
    assertEquals("Βάλε την ανάμεσα στα πλαϊνά, στις ελεύθερες άκρες τους.", LiveLines.core("el", backText.el))
    assertEquals("Lay the drawer front flat in front of you with its inner face up.", LiveLines.core("en", frontText.en))
    assertEquals("Slide it into the grooves of the two side panels.", LiveLines.core("en", bottomText.en))
    assertEquals("Fit it between the side panels at their free ends.", LiveLines.core("en", backText.en))
  }

  @Test
  fun `a short instruction is said as it is, and one that cannot be cut becomes put it where it is lit`() {
    val short = LiveAct(1, 0, CoachItem.BOARD, "shelf", "Ράφι", "Βάλε το ράφι πάνω στους πείρους.")
    assertEquals("Σωστό κομμάτι! Βάλε το ράφι πάνω στους πείρους.", LiveLines.rightInHand("el", short))
    val uncut = short.copy(text = "Στερέωσε το ράφι στη θέση του προσέχοντας οι τέσσερις τρύπες του να είναι γυρισμένες προς τα κάτω και προς τον τοίχο")
    assertEquals("Σωστό κομμάτι! Βάλ' το στη θέση που φωτίζεται.", LiveLines.rightInHand("el", uncut))
    assertEquals("Right part! Put it in the place that is lit.", LiveLines.rightInHand("en", short.copy(text = "")))
    assertNull(LiveLines.core("el", ""))
  }

  // ---- every case, both languages, the length ----

  @Test
  fun `every case has a sentence in both languages and none is longer than the limit`() {
    for ((lang, acts) in listOf("el" to el, "en" to en)) {
      val lines = ArrayList<String>()
      for ((i, a) in acts.withIndex()) {
        lines += LiveLines.rightInHand(lang, a)
        lines += LiveLines.instruction(lang, a)
        LiveLines.keepGoing(lang, a.item)?.let { lines += it }
        val d = LiveLines.done(lang, a, acts.getOrNull(i + 1))
        assertEquals("$lang action $i", i == acts.lastIndex, d == null)
        d?.let { lines += it.text }
      }
      for (seen in listOf(front, left, right, bottom, back)) for (need in listOf(front, left, right, bottom, back))
          if (seen !== need) lines += LiveLines.wrongBoard(lang, pick(seen, lang), pick(need, lang))
      lines += LiveLines.wrongWay(lang, null)
      lines += LiveLines.noHands(lang)
      lines += LiveLines.all(lang, acts, kinds)
      for (l in lines) {
        assertTrue("$lang: blank", l.isNotBlank())
        assertTrue("$lang: ${l.length} characters: $l", l.length <= LiveLines.MAX)
        assertFalse("$lang: $l", l.contains("null") || l.contains("{") || l.contains("  "))
        assertTrue("$lang: $l", l.last() in ".!;»\"")
      }
    }
    // the two languages do not share a sentence
    assertTrue(LiveLines.all("el", el, kinds).intersect(LiveLines.all("en", en, kinds).toSet()).isEmpty())
  }

  @Test
  fun `the sentences fetched ahead for the drawer guide`() {
    val all = LiveLines.all("el", el, kinds)
    assertEquals(all.size, all.toSet().size)
    assertEquals(39, all.size)
    assertEquals(39, LiveLines.all("en", en, kinds).size)
    // the first action's lines come first (after the two fixed ones): they are needed first
    assertEquals(LiveLines.noHands("el"), all[0])
    assertEquals("Πάρε: Πρόσοψη του συρταριού.", all[2])
    // a wrong-board line for each board that can still lie loose: at step 1 the back, the bottom and a side
    for (seen in listOf(back, bottom, left)) assertTrue(LiveLines.wrongBoard("el", seen.el, front.el) in all)
    // the two sides look the same: neither is "wrong" for the other; at step 5 both sides are in
    assertTrue(LiveLines.wrongBoard("el", right.el, left.el) in all)
    assertTrue(LiveLines.wrongBoard("el", left.el, bottom.el) in all)
    assertTrue(LiveLines.wrongBoard("el", back.el, bottom.el) in all)
    assertTrue(all.count { it.startsWith("Λάθος κομμάτι. Πάρε") } >= 5)
    // nothing is "taken" for a cam lock already in its panel
    assertFalse(all.any { it.contains("Πάρε: Κλειδαριά") || it.contains("πάρε την κλειδαριά") })
  }

  // ---- no sentence twice in a row ----

  @Test
  fun `the same sentence is not said twice in a row`() {
    val said = NoRepeat()
    val line = LiveLines.rightInHand("el", el[1])
    assertEquals(line, said.pick(line, LiveLines.instruction("el", el[1])))
    assertEquals("the second candidate", LiveLines.instruction("el", el[1]), said.pick(line, LiveLines.instruction("el", el[1])))
    assertEquals("not in a row any more", line, said.pick(line))
    assertNull("no other candidate: nothing", said.pick(line))
    assertNull(said.pick(null, "", line))
    said.other() // something else was said in between
    assertEquals(line, said.pick(line))
  }

  // ---- the Greek articles ----

  @Test
  fun `greek names take their article from their first word`() {
    assertEquals("το αριστερό πλαϊνό", LiveLines.El.acc(left.el))
    assertEquals("την πλάτη του συρταριού", LiveLines.El.acc(back.el))
    assertEquals("την πρόσοψη του συρταριού", LiveLines.El.acc(front.el))
    assertEquals("τη βίδα έκκεντρου", LiveLines.El.acc(bolt.el))
    assertEquals("την κλειδαριά έκκεντρου", LiveLines.El.acc(cam.el))
    assertEquals("τη λευκή πλαστική πρόκα", LiveLines.El.acc(peg.el))
    assertEquals("τον πάτο του συρταριού", LiveLines.El.acc(bottom.el))
    assertEquals("ο πάτος του συρταριού", LiveLines.El.nom(bottom.el))
    assertEquals("η βίδα έκκεντρου", LiveLines.El.nom(bolt.el))
    assertEquals("το στήριγμα", LiveLines.El.acc("Στήριγμα"))
    assertNull(LiveLines.El.acc("Ράγες"))
    assertEquals("στο επόμενο", LiveLines.El.toNext("Ράγες"))
    assertEquals("στον επόμενο", LiveLines.El.toNext("Πείρο 8 mm"))
    // a part code stays in capitals inside a sentence
    assertEquals("BOLT-C long", LiveLines.lower("BOLT-C long", java.util.Locale.ENGLISH))
  }

  // ---- from the guide ----

  @Test
  fun `a guide action becomes what the lines need`() {
    fun act(kind: ActionKind, code: String?, part: String, n: Names?, hwKind: String?, pre: Boolean = false, k: Int = 1, of: Int = 1) =
        GuideAction(
            index = 2, kind = kind, text = LocalizedText("Do it.", "Κάν' το."), node = n?.let { "node" }, partId = part, code = code, fromDir = null,
            target = null, finalPose = null, geometry = null,
            labels = if (n == null) emptyMap() else mapOf("el" to PartLabel(n.el, code, null), "en" to PartLabel(n.en, code, null)),
            ordinal = k, sameCount = of, hardwareKind = hwKind, preinstalled = pre,
        )
    assertEquals(LiveAct(2, 2, CoachItem.SCREW, "BOLT-C", "Βίδα έκκεντρου", "Κάν' το.", 3, 4, true),
        LiveLines.of(2, act(ActionKind.FASTEN, "BOLT-C", "BOLT-C", bolt, "bolt", k = 3, of = 4), "el"))
    assertEquals(LiveAct(3, 2, CoachItem.BOARD, "drawer_side_left", "Left side panel", "Do it.", 1, 1, true),
        LiveLines.of(3, act(ActionKind.PLACE, null, "drawer_side_left", left, null), "en"))
    val seated = LiveLines.of(3, act(ActionKind.FASTEN, "CAM-L", "CAM-L", cam, "cam", pre = true, k = 1, of = 2), "el")
    assertEquals(CoachItem.CAM, seated.item)
    assertFalse("already in its part: nothing to take", seated.hasPick)
    assertEquals(CoachItem.PEG, LiveLines.of(6, act(ActionKind.FASTEN, "PEG-W", "PEG-W", peg, "pin"), "el").item)
    val turn = LiveLines.of(4, act(ActionKind.TURN, null, "x", null, null), "el")
    assertFalse(turn.hasPick)
    assertEquals(CoachItem.OTHER, turn.item)
  }
}
