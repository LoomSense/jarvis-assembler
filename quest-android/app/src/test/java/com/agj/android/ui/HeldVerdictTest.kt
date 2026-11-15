package com.agj.android.ui

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.HandArc
import com.agj.android.guide.PartLabel
import com.agj.android.world.PickTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The held-part verdict: server payloads → verdicts, the one-badge rules, where the badge goes, the copy. */
class HeldVerdictTest {
  private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

  private fun right(id: String = "118331", source: String = HeldEvent.SOCKET) = HeldEvent(Verdict.RIGHT, id, id, source = source, event = "right_hardware")

  private fun wrong(seen: String = "108560", expected: String = "118331", source: String = HeldEvent.SOCKET) =
      HeldEvent(Verdict.WRONG, expected, seen, say = "reason", source = source, event = "wrong_hardware")

  private fun unsure(source: String = HeldEvent.SOCKET) = HeldEvent(Verdict.UNSURE, "118331", null, source = source, event = "unsure_show_part")

  // ---- server payloads (server/perception/README.md) ----

  /** The README's `wrong_hardware` example, as produced. */
  private val readmeWrongHardware =
      """{"type":"event","event":"wrong_hardware","step":2,"page":3,"expected":"long screw 108363","observed":"short screw 108560",
 "evidence":{"hardware_in_hand.108560":0.93,"right_hardware":0.07},"ts":1794702198777,"id":1,"action":0,
 "expected_id":"108363","observed_id":"108560","p":0.93,
 "say":{"en":"That's the short screw, 108560. Step 2 needs the long screw, 108363. Page 3.",
        "el":"Κρατάς κοντή βίδα, 108560. Το βήμα 2 θέλει μακριά βίδα, 108363. Σελίδα 3."},
 "perception":"[PERCEPTION event=wrong_hardware step=2 page=3 expected=\"long screw 108363\" observed=\"short screw 108560\" p=0.93] Tell the user briefly, cite step 2 page 3.",
 "speak":true,"turn_complete":true,"repeat":0,"seq":4,"latency_ms":43,"votes":"4 of last 4 frames"}"""

  @Test
  fun readmeWrongHardwareExampleParses() {
    val ev = HeldEvent.parse("wrong_hardware", json(readmeWrongHardware), "el")!!
    assertEquals(Verdict.WRONG, ev.verdict)
    assertEquals("108363", ev.expectedId)
    assertEquals("108560", ev.seenId)
    assertEquals("long screw 108363", ev.expectedText)
    assertEquals("short screw 108560", ev.seenText)
    assertEquals(0.93, ev.p!!, 1e-9)
    assertEquals("Κρατάς κοντή βίδα, 108560. Το βήμα 2 θέλει μακριά βίδα, 108363. Σελίδα 3.", ev.say)
    assertEquals(HeldEvent.SOCKET, ev.source)
    assertEquals(2, ev.step)
    assertEquals(
        "That's the short screw, 108560. Step 2 needs the long screw, 108363. Page 3.",
        HeldEvent.parse("wrong_hardware", json(readmeWrongHardware), "en")!!.say,
    )
    assertEquals(HeldSpeech.WARNING, HeldSpeech.of(ev))
    // the server's reason is what is spoken
    assertEquals(ev.say, HeldCopy.spoken("el", ev))
  }

  @Test
  fun confirmationUnsureAndShowPartResultParse() {
    // the confirmation: expected_id == observed_id, the README's sentences
    val ok =
        HeldEvent.parse(
            "right_hardware",
            json(
                """{"type":"event","event":"right_hardware","step":2,"page":3,"expected":"long screw 108363","observed":"long screw 108363",
                "expected_id":"108363","observed_id":"108363","p":0.91,"evidence":{"hardware_in_hand.108363":0.91},
                "say":{"en":"Good. That's the long screw, 108363.","el":"Ωραία. Κρατάς μακριά βίδα, 108363."},"speak":true}"""),
            "el",
        )!!
    assertEquals(Verdict.RIGHT, ok.verdict)
    assertEquals("108363", ok.expectedId)
    assertEquals(0.91, ok.p!!, 1e-9)
    assertEquals("Ωραία. Κρατάς μακριά βίδα, 108363.", ok.say)
    assertEquals(HeldSpeech.CONFIRM, HeldSpeech.of(ok))
    assertEquals(Verdict.RIGHT, HeldEvent.parse("right_part", json("""{"event":"right_part","expected_id":"drawer_side_left","observed_id":"drawer_side_left","p":0.9}"""), "en")!!.verdict)
    assertEquals(Verdict.WRONG, HeldEvent.parse("wrong_part", json("""{"event":"wrong_part","expected_id":"a","observed_id":"b"}"""), "en")!!.verdict)

    // unsure_show_part: nothing observed, JSON nulls stay null (not the string "null")
    val un = HeldEvent.parse("unsure_show_part", json("""{"event":"unsure_show_part","step":2,"expected":"wooden dowel 101345","observed":null,"expected_id":"101345","observed_id":null,"p":0.31,"say":{"en":"I can't tell. Show me the part.","el":"Δεν το βλέπω καθαρά. Δείξε μου το κομμάτι."}}"""), "el")!!
    assertEquals(Verdict.UNSURE, un.verdict)
    assertEquals("101345", un.expectedId)
    assertNull(un.seenId)
    assertNull(un.seenText)
    assertEquals(HeldSpeech.UNSURE, HeldSpeech.of(un))

    // wrong_hardware on a step that uses no hardware: `expected: null`
    val none = HeldEvent.parse("wrong_hardware", json("""{"event":"wrong_hardware","step":1,"expected":null,"observed":"short screw 108560","expected_id":null,"observed_id":"108560","p":0.9}"""), "en")!!
    assertNull(none.expectedId)
    assertEquals("108560", none.seenId)
    assertNull(none.say)

    // a hardware code sent as a number is read as its digits
    assertEquals("108363", HeldEvent.parse("right_hardware", json("""{"expected_id":108363,"observed_id":108363}"""), "en")!!.expectedId)

    // show_part_result: `match` decides; it is the answer to a manual check
    fun show(match: String, observed: String) =
        HeldEvent.parse("show_part_result", json("""{"event":"show_part_result","step":2,"expected_id":"101345","observed_id":$observed,"p":0.88,"match":$match,"say":"x"}"""), "en")!!
    assertEquals(Verdict.RIGHT, show("true", "\"101345\"").verdict)
    assertEquals(Verdict.WRONG, show("false", "\"118331\"").verdict)
    assertEquals(Verdict.UNSURE, show("null", "null").verdict)
    assertEquals(HeldEvent.MANUAL, show("true", "\"101345\"").source)
    assertEquals("x", show("true", "\"101345\"").say)
    assertEquals(HeldSpeech.ANSWER, HeldSpeech.of(show("true", "\"101345\"")))

    // events that are not about the hand
    assertNull(HeldEvent.parse("step_complete", json("""{"event":"step_complete","step":2}"""), "en"))
    assertNull(HeldEvent.parse("more_light", json("""{"event":"more_light"}"""), "en"))
    assertNull(HeldEvent.parse("flip_needed", json("""{"event":"flip_needed"}"""), "en"))
    assertTrue(HeldEvent.EVENTS.all { HeldEvent.parse(it, json("{}"), "en") != null })
    // only the two lines that must never cut a sentence are quiet
    assertEquals(setOf(HeldSpeech.CONFIRM, HeldSpeech.PROMPT), HeldSpeech.entries.filter { it.quiet }.toSet())
  }

  // ---- the one-badge state machine ----

  @Test
  fun aDifferentVerdictWaitsOneAndAHalfSeconds() {
    val m = HeldVerdictMachine()
    val first = m.offer(right(), 0)!!
    assertTrue(first.speak && first.sound)
    assertNull("no flip 0.5 s after the last one", m.offer(wrong(), 500))
    assertEquals(Verdict.RIGHT, m.shown!!.ev.verdict)
    assertEquals(Verdict.WRONG, m.pending!!.verdict)
    assertNull(m.tick(1499))
    assertEquals(Verdict.RIGHT, m.shown!!.ev.verdict)
    val flipped = m.tick(1500)!!
    assertEquals(Verdict.WRONG, flipped.ev.verdict)
    assertTrue(flipped.speak && flipped.sound)
    assertNull("one badge, nothing left waiting", m.pending)
    assertEquals(Verdict.WRONG, m.shown!!.ev.verdict)
  }

  @Test
  fun rightThenWrongThenUnsureFlipInOrderNeverFaster() {
    val m = HeldVerdictMachine()
    assertNotNull(m.offer(right(), 0))
    assertNotNull(m.offer(wrong(), 1600)) // 1.6 s later: at once
    assertNull(m.offer(unsure(), 2000)) // 0.4 s after the flip: waits
    assertNull(m.tick(3000))
    val u = m.tick(3100)!!
    assertEquals(Verdict.UNSURE, u.ev.verdict)
    assertTrue(u.speak)
    // unsure stays until the next verdict, however long
    assertNull(m.tick(600_000))
    assertEquals(Verdict.UNSURE, m.shown!!.ev.verdict)
    assertEquals(1f, m.alpha(600_000), 1e-6f)
    // a second unsure about the same thing neither flips nor speaks
    assertNull(m.offer(unsure(), 600_100))
    val r = m.offer(right(), 600_200)!!
    assertEquals(Verdict.RIGHT, r.ev.verdict)
  }

  @Test
  fun whileWaitingTheHigherPriorityWinsElseTheNewer() {
    val m = HeldVerdictMachine()
    m.offer(right("a"), 0)
    assertNull(m.offer(wrong(), 300))
    assertNull(m.offer(unsure(), 600)) // unsure does not push out a waiting "wrong"
    assertEquals(Verdict.WRONG, m.pending!!.verdict)
    assertEquals(Verdict.WRONG, m.tick(1500)!!.ev.verdict)

    val n = HeldVerdictMachine()
    n.offer(right("a"), 0)
    assertNull(n.offer(unsure(), 300))
    assertNull(n.offer(wrong(), 600)) // wrong replaces a waiting unsure
    assertEquals(Verdict.WRONG, n.pending!!.verdict)
    assertNull(n.offer(wrong("999999"), 900)) // equal priority: the newer one
    assertEquals("999999", n.pending!!.seenId)
    // the shown verdict confirmed again: what waited is dropped, the badge does not flip
    assertNull(n.offer(right("a"), 1000))
    assertNull(n.pending)
    assertNull(n.tick(1600))
    assertEquals(Verdict.RIGHT, n.shown!!.ev.verdict)
  }

  @Test
  fun rightFadesAfterTwoSecondsWrongStaysWhileHeld() {
    val m = HeldVerdictMachine()
    m.offer(right(), 0)
    assertEquals(0f, m.alpha(0), 1e-6f)
    assertEquals(1f, m.alpha(1000), 1e-6f)
    assertTrue(m.alpha(1850) < 1f && m.alpha(1850) > 0f)
    assertNull(m.tick(1999))
    assertNotNull(m.shown)
    m.tick(2000)
    assertNull("gone after about 2 s", m.shown)
    assertEquals(0f, m.alpha(2000), 1e-6f)

    val w = m.offer(wrong(), 3000)!!
    assertTrue(w.speak)
    // still in the hand: the server may say it again; the badge stays up, nothing is said twice
    assertNull(m.offer(wrong(), 8000))
    assertFalse(m.shown!!.speak)
    m.tick(14_000)
    assertNotNull("7 s after the last report", m.shown)
    m.tick(15_000)
    assertNull(m.shown)
    // the same wrong thing again within 20 s of the spoken one: shown, with its cue, not spoken
    val again = m.offer(wrong(), 16_000)!!
    assertFalse(again.speak)
    assertTrue(again.sound)
    m.tick(24_000)
    // after 20 s it is spoken again; another wrong thing is spoken at once
    assertTrue(m.offer(wrong(), 24_000)!!.speak)
    m.tick(32_000)
    assertTrue(m.offer(wrong("101345"), 32_000)!!.speak)
  }

  @Test
  fun rightIsSpokenOncePerPickAndAManualAnswerAlways() {
    val m = HeldVerdictMachine()
    assertTrue(m.offer(right("118331"), 0)!!.speak)
    m.tick(2500)
    val second = m.offer(right("118331"), 3000)!!
    assertFalse("same pick: shown again, silent", second.speak)
    assertFalse(second.sound)
    m.tick(6000)
    // another part of the step is its own pick
    assertTrue(m.offer(right("101345"), 6000)!!.speak)
    m.tick(9000)
    // a wrong verdict re-arms the confirmation (as the server does)
    assertTrue(m.offer(wrong(), 9000)!!.speak)
    val after = m.offer(right("118331"), 11_000)!!
    assertTrue(after.speak && after.sound)
    m.tick(14_000)
    // the person asked: always answered aloud
    val manual = m.offer(right("118331", HeldEvent.MANUAL), 14_000)!!
    assertTrue(manual.speak && manual.sound)
    m.tick(17_000)
    assertTrue(m.offer(unsure(), 17_000)!!.speak)
    m.offer(right("118331"), 19_000)
    m.tick(22_000)
    assertTrue("a new unsure spell is spoken again", m.offer(unsure(), 22_000)!!.speak)
  }

  @Test
  fun aManualCheckTakesTheBadgeDownAndItsAnswerShowsAtOnce() {
    val m = HeldVerdictMachine()
    assertTrue(m.offer(right("118331"), 0)!!.speak)
    m.dismiss() // "Έλεγχος" / "είναι σωστό αυτό;"
    assertNull(m.shown)
    assertEquals(0f, m.alpha(100), 1e-6f)
    // 0.3 s after the last flip, yet shown at once: the person asked
    val answer = m.offer(wrong(source = HeldEvent.MANUAL), 300)!!
    assertEquals(Verdict.WRONG, answer.ev.verdict)
    assertTrue(answer.speak && answer.sound)
    // dismissing does not forget what was already confirmed aloud (only a step change or a wrong verdict does)
    val k = HeldVerdictMachine()
    assertTrue(k.offer(right("118331"), 0)!!.speak)
    k.dismiss()
    assertFalse(k.offer(right("118331"), 100)!!.speak)
    k.dismiss()
    assertTrue("an unsure answer to a check is spoken", k.offer(unsure(HeldEvent.MANUAL), 200)!!.speak)
    k.dismiss()
    assertNull(k.tick(10_000))
  }

  @Test
  fun stepChangeClearsEverything() {
    val m = HeldVerdictMachine()
    m.offer(right("118331"), 0)
    m.offer(wrong(), 400)
    assertNotNull(m.shown)
    assertNotNull(m.pending)
    m.reset()
    assertNull(m.shown)
    assertNull(m.pending)
    assertEquals(0f, m.alpha(500), 1e-6f)
    assertNull("nothing left to release", m.tick(5000))
    // the next step's first verdict is immediate (no debounce carried over) and spoken again
    val r = m.offer(right("118331"), 500)!!
    assertTrue(r.speak)
    m.reset()
    assertTrue("unsure is spoken again after a step change", m.offer(unsure(), 600)!!.speak)
  }

  // ---- where the badge goes ----

  private val head = Pose3(Vec3(0f, 1.6f, 0f), Quat.IDENTITY) // looks down −Z

  @Test
  fun badgeSitsBesideTheWristTheWearerLooksAt() {
    val inHand = Vec3(0.05f, 1.25f, -0.40f) // held up in front
    val resting = Vec3(-0.30f, 0.95f, -0.25f) // the other hand, low and to the side
    val picked = BadgePlacement.pickWrist(listOf(resting, inHand), head)
    assertEquals(inHand, picked)
    val p = BadgePlacement.place(listOf(resting, inHand), head, Vec3(0.4f, 1.3f, -0.8f), head.t)!!
    assertEquals(BadgePlacement.WRIST, p.where)
    assertEquals(BadgePlacement.atWrist(inHand, head.t), p.pos)
    assertTrue("at least 10 cm above the wrist", p.pos.y >= inHand.y + 0.10f)
    assertTrue("nearer the eyes than the wrist", (p.pos - head.t).length() < (inHand - head.t).length())
    assertTrue("10–16 cm from the wrist", (p.pos - inHand).length() in 0.10f..0.16f)
    assertTrue("in front of the wearer", p.pos.z < -0.2f)
    // never in the face: a wrist right under the eyes is pushed out to 28 cm
    val close = BadgePlacement.atWrist(Vec3(0f, 1.48f, -0.14f), head.t)
    assertEquals(BadgePlacement.MIN_HEAD_M, (close - head.t).length(), 1e-3f)
    // the badge keeps to the hand it started on (no hop to the other one), and holds when that hand is lost
    assertEquals(Vec3(0.07f, 1.24f, -0.41f), BadgePlacement.follow(inHand, listOf(resting, Vec3(0.07f, 1.24f, -0.41f))))
    assertNull(BadgePlacement.follow(inHand, listOf(resting)))
    assertNull(BadgePlacement.follow(inHand, emptyList()))
  }

  @Test
  fun badgeFallsBackToTheWindowWithoutAUsableWrist() {
    val window = Vec3(0.4f, 1.3f, -0.8f)
    // no hand source at all
    val none = BadgePlacement.place(emptyList(), head, window, head.t)!!
    assertEquals(BadgePlacement.WINDOW, none.where)
    assertEquals(window.y + STEP_PANEL_H / 2 + VERDICT_PANEL_H / 2 + 0.01f, none.pos.y, 1e-4f)
    assertTrue("a little nearer the wearer than the window", Math.hypot(none.pos.x.toDouble(), none.pos.z.toDouble()) < Math.hypot(window.x.toDouble(), window.z.toDouble()))
    assertEquals(0.015f, Math.hypot((none.pos.x - window.x).toDouble(), (none.pos.z - window.z).toDouble()).toFloat(), 1e-4f)
    // wrists that cannot be the hand being looked at: behind the head, out of reach, at the origin (never written),
    // beside the head, at the head, far below the gaze (hanging at the side while looking ahead)
    for (bad in listOf(Vec3(0f, 1.3f, 0.4f), Vec3(0f, 1.2f, -1.6f), Vec3(0f, 0f, 0f), Vec3(0.6f, 1.5f, 0.05f), head.t, Vec3(0.2f, 0.9f, -0.15f))) {
      assertNull("$bad", BadgePlacement.pickWrist(listOf(bad), head))
      assertEquals(BadgePlacement.WINDOW, BadgePlacement.place(listOf(bad), head, window, head.t)!!.where)
    }
    // no head pose: the window; no window either: nothing to anchor to
    assertEquals(BadgePlacement.WINDOW, BadgePlacement.place(listOf(Vec3(0f, 1.3f, -0.4f)), null, window, null)!!.where)
    assertEquals(window + Vec3(0f, BadgePlacement.ABOVE_WINDOW_M, 0f), BadgePlacement.atWindow(window, null))
    assertNull(BadgePlacement.place(emptyList(), head, null, null))
    // text stays at the window's body size (in degrees) or larger: 17.5 mm at 0.8 m vs 17.5 mm × scale at the hand
    for (d in listOf(0.3f, 0.45f, 0.6f, 0.9f, 1.1f)) {
      val perMetre = BadgePlacement.scale(d) * 17.5f / d
      assertTrue("at $d m", perMetre >= 17.5f / 0.8f - 1e-3f)
    }
    assertEquals(0.6f, BadgePlacement.scale(0.3f), 1e-6f)
    assertEquals(1.5f, BadgePlacement.scale(3f), 1e-6f)
  }

  @Test
  fun lookingDownAtTheTableStillFindsTheHand() {
    // head pitched 50° down (rotation about +X by −50°): the hands are low and near
    val down = Pose3(Vec3(0f, 1.5f, 0f), Quat.axisAngle(Vec3(1f, 0f, 0f), Math.toRadians(-50.0).toFloat()))
    val fwd = down.q.rotate(Vec3(0f, 0f, -1f))
    assertTrue("looks down", fwd.y < -0.7f)
    val wrist = Vec3(0.08f, 1.15f, -0.30f)
    assertEquals(wrist, BadgePlacement.pickWrist(listOf(wrist), down))
    assertEquals(BadgePlacement.WRIST, BadgePlacement.place(listOf(wrist), down, Vec3(0.4f, 1.2f, -0.7f), down.t)!!.where)
  }

  // ---- which part, where ----

  private fun target(id: String, instance: Int = 0, confidence: Double = 0.9, seen: Int? = null, needed: Int? = null, count: Map<String, String> = emptyMap()) =
      PickTarget(id, id, Vec3(0f, 0.8f, -0.5f), confidence, 0, instance = instance, countText = count, countSeen = seen, countNeeded = needed)

  @Test
  fun pickTargetsOfTheActionBearingAndCounts() {
    // step mode: every instance of every item of the step; the action's own item, easiest to pick up first
    val all = listOf(target("drawer_side_left"), target("101345", instance = 1, confidence = 0.99), target("101345", instance = 0, confidence = 0.7), target("118331"))
    assertEquals(listOf(0, 1), PickPrompt.matches(all, listOf("101345")).map { it.instance })
    assertEquals(listOf("drawer_side_left"), PickPrompt.matches(all, listOf("drawer_side_left", "nothing")).map { it.id })
    assertTrue(PickPrompt.matches(all, emptyList()).isEmpty())
    assertTrue(PickPrompt.matches(emptyList(), listOf("101345")).isEmpty())

    assertEquals(PickPrompt.AHEAD, PickPrompt.bearing(head, Vec3(0.05f, 0.8f, -0.6f)))
    assertEquals(PickPrompt.LEFT, PickPrompt.bearing(head, Vec3(-0.4f, 0.8f, -0.5f)))
    assertEquals(PickPrompt.RIGHT, PickPrompt.bearing(head, Vec3(0.4f, 0.8f, -0.5f)))
    // turned 90° to the left (looking down −X): what was ahead is now on the right
    val turned = Pose3(head.t, Quat.yaw(Math.toRadians(90.0).toFloat()))
    assertEquals(PickPrompt.RIGHT, PickPrompt.bearing(turned, Vec3(0f, 0.8f, -0.6f)))
    assertEquals(PickPrompt.AHEAD, PickPrompt.bearing(turned, Vec3(-0.6f, 0.8f, 0f)))

    val counted = target("101345", seen = 1, needed = 2, count = mapOf("en" to "1 of 2 in view", "el" to "φαίνονται 1 από 2"))
    assertEquals("φαίνονται 1 από 2", PickPrompt.countText(counted, "el"))
    assertEquals("1 of 2 in view", PickPrompt.countText(counted, "en"))
    assertEquals("1 of 2 in view", PickPrompt.countText(target("x", count = mapOf("en" to "1 of 2 in view")), "el"))
    assertNull(PickPrompt.countText(target("x"), "el"))
    assertNull(PickPrompt.countText(null, "el"))
    // the pill on the pick marker: the name the card uses, and seen/needed as its chip
    val label = PartLabel("Ξύλινη καβίλια", "101345", "1 από 2")
    assertEquals(PartLabel("Ξύλινη καβίλια", null, "1/2"), PickPrompt.pill(counted, label))
    assertEquals(PartLabel("Ξύλινη καβίλια", null, null), PickPrompt.pill(target("101345"), label))

    assertEquals("Πάρε: Βίδα έκκεντρου, αριστερά σου.", PickPrompt.line("el", "Βίδα έκκεντρου", Strings.t("el", PickPrompt.LEFT)))
    assertEquals("Πάρε: Βίδα έκκεντρου, πάνω αριστερά.", PickPrompt.line("el", "Βίδα έκκεντρου", "πάνω αριστερά."))
    assertEquals("Take: Cam bolt, on your right.", PickPrompt.line("en", "Cam bolt", Strings.t("en", PickPrompt.RIGHT)))
    assertEquals("Πάρε: Ξύλινη καβίλια.", PickPrompt.line("el", "Ξύλινη καβίλια", null))
    assertEquals("Take: Wooden dowel.", PickPrompt.line("en", "Wooden dowel", " "))

    assertEquals("φαίνονται 1 από 2", PickPrompt.countLine("el", 2, "φαίνονται 1 από 2", null))
    assertEquals("φαίνονται 3 από 4", PickPrompt.countLine("el", 4, null, 3))
    assertEquals("3 of 4 in view", PickPrompt.countLine("en", 4, null, 3))
    assertEquals("Χρειάζεσαι 2", PickPrompt.countLine("el", 2, null, null))
    assertEquals("You need 1", PickPrompt.countLine("en", 1, " ", null))
    assertNull(PickPrompt.countLine("el", null, null, null))
    assertNull(PickPrompt.countLine("el", 0, null, null))
  }

  @Test
  fun checkPhraseHearsIsThisRight() {
    for (t in listOf("είναι σωστό αυτό;", "Είναι σωστό αυτό", "αυτό είναι το σωστό;", "σωστή είναι αυτή η βίδα;", "έλεγξε το", "τσέκαρε αυτό", "is this right?", "Is this the correct one?", "am I holding the right part", "check this", "check it please"))
        assertTrue(t, CheckPhrase.matches(t, "ask"))
    // the server's `identify` intent ("τι είναι αυτό;") is followed by a look at the hand
    assertTrue(CheckPhrase.matches("τι είναι αυτό;", "identify"))
    assertTrue(CheckPhrase.matches("", "identify"))
    // questions for the guide and commands are not checks
    for (t in listOf("ποια είναι η σωστή βίδα;", "ποια βίδα χρειάζομαι τώρα;", "which screw is the right one?", "πού πάει αυτό;", "what is the correct order?", "πόσες βίδες θέλει;", ""))
        assertFalse(t, CheckPhrase.matches(t, "ask"))
    assertFalse(CheckPhrase.matches("σωστά, επόμενο", "next"))
    assertFalse(CheckPhrase.matches("is this right", "repeat"))
    assertFalse(CheckPhrase.matches("", "none"))
  }

  // ---- copy ----

  @Test
  fun badgeCopyForTheThreeVerdicts() {
    val bolt = HeldCopy.nameLine(PartLabel("Βίδα έκκεντρου", "118331", "2 από 4"))
    assertEquals("Βίδα έκκεντρου 118331", bolt)
    assertEquals("Αριστερό πλαϊνό", HeldCopy.nameLine(PartLabel("Αριστερό πλαϊνό", null, null)))
    assertEquals("", HeldCopy.nameLine(null))
    // "Σωστό · Βίδα έκκεντρου 118331"
    assertEquals(VerdictBadgeState(StatusKind.SUCCESS, "Σωστό", "Βίδα έκκεντρου 118331"), HeldCopy.badge("el", Verdict.RIGHT, bolt))
    // "Λάθος εξάρτημα · Χρειάζεσαι: Ξύλινη καβίλια 101345"
    assertEquals(
        VerdictBadgeState(StatusKind.ERROR, "Λάθος εξάρτημα", "Χρειάζεσαι: Ξύλινη καβίλια 101345"),
        HeldCopy.badge("el", Verdict.WRONG, HeldCopy.nameLine(PartLabel("Ξύλινη καβίλια", "101345", null))),
    )
    assertEquals(VerdictBadgeState(StatusKind.UNSURE, "Δείξε μου το εξάρτημα πιο κοντά"), HeldCopy.badge("el", Verdict.UNSURE, bolt))
    assertEquals(VerdictBadgeState(StatusKind.ERROR, "Wrong part", "You need: Wooden dowel 101345"), HeldCopy.badge("en", Verdict.WRONG, "Wooden dowel 101345"))
    assertEquals(VerdictBadgeState(StatusKind.SUCCESS, "Right part", "Cam bolt 118331"), HeldCopy.badge("en", Verdict.RIGHT, "Cam bolt 118331"))
    // nothing known about what is needed: the word alone, never an empty line
    assertNull(HeldCopy.badge("el", Verdict.RIGHT, "").detail)
    assertNull(HeldCopy.badge("el", Verdict.WRONG, " ").detail)
    assertEquals(VerdictBadgeState(StatusKind.INFO, "Ελέγχω…"), HeldCopy.checking("el"))

    // spoken: short, the name without its code; the server's reason for a wrong part; a local line when unsure
    assertEquals("Σωστό. Βίδα έκκεντρου.", HeldCopy.spoken("el", right(), "Βίδα έκκεντρου"))
    assertEquals("Correct. Cam bolt.", HeldCopy.spoken("en", right(), "Cam bolt"))
    assertEquals("Σωστό.", HeldCopy.spoken("el", right(), " "))
    assertEquals("Σωστό.", HeldCopy.spoken("el", right()))
    assertEquals("reason", HeldCopy.spoken("el", wrong()))
    assertEquals(Strings.t("el", "wrongSay"), HeldCopy.spoken("el", wrong().copy(say = null)))
    assertEquals("Δείξε μου το εξάρτημα πιο κοντά.", HeldCopy.spoken("el", unsure()))
    val timeout = HeldEvent(Verdict.UNSURE, "118331", null, say = Strings.t("el", "heldNoAnswer"), source = HeldEvent.MANUAL, event = HeldCopy.TIMEOUT)
    assertEquals("Δεν πήρα απάντηση. Δείξε το ξανά.", HeldCopy.spoken("el", timeout))
    assertEquals(HeldSpeech.ANSWER, HeldSpeech.of(timeout))
  }

  @Test
  fun everyStringExistsInGreekAndEnglish() {
    val en = Strings.keys("en")
    val el = Strings.keys("el")
    assertEquals("keys only in English", emptySet<String>(), en - el)
    assertEquals("keys only in Greek", emptySet<String>(), el - en)
    val mine =
        listOf("heldRight", "heldWrong", "heldNeed", "heldUnsure", "heldRightSay", "heldRightSayPlain", "heldUnsureSay", "heldChecking",
            "heldNoAnswer", "check", "needN", "inViewN", "pickSay", "pickSayPlain", PickPrompt.LEFT, PickPrompt.AHEAD, PickPrompt.RIGHT)
    val placeholder = Regex("\\{[a-z]+\\}")
    for (k in en) {
      val a = Strings.t("en", k)
      val b = Strings.t("el", k)
      assertTrue(k, a.isNotBlank() && b.isNotBlank())
      assertEquals("placeholders of $k", placeholder.findAll(a).map { it.value }.toSet(), placeholder.findAll(b).map { it.value }.toSet())
    }
    for (k in mine) {
      assertTrue(k, k in en && k in el)
      assertTrue("$k is Greek", Strings.t("el", k).any { it in 'Α'..'ώ' })
    }
    assertEquals("ΛΑΘΟΣ ΕΞΑΡΤΗΜΑ", Strings.upper("el", Strings.t("el", "heldWrong")))
    assertEquals("ΕΛΕΓΧΩ…", Strings.upper("el", Strings.t("el", "heldChecking")))
  }

  // ---- the arc from the hand to the right part ----

  @Test
  fun handArcRunsFromTheHandToThePartAndPulses() {
    val from = Vec3(0f, 1.1f, -0.35f)
    val to = Vec3(-0.35f, 0.80f, -0.60f)
    val pts = HandArc.points(from, to)
    assertTrue(pts.size in 4..HandArc.MAX_DOTS)
    assertEquals(from, HandArc.point(from, to, 0f))
    assertTrue((HandArc.point(from, to, 1f) - to).length() < 1e-5f)
    // monotonic towards the part, lifted above the straight line in the middle, never ending on the part itself
    val d = pts.map { (it - to).length() }
    assertTrue(d.zipWithNext().all { (a, b) -> b < a })
    val mid = HandArc.point(from, to, 0.5f)
    assertTrue(mid.y > (from.y + to.y) / 2 + 0.015f)
    assertTrue(d.last() > 0.01f)
    // marching: the phase moves every dot towards the part
    val moved = HandArc.points(from, to, 0.5f)
    assertEquals(pts.size, moved.size)
    assertTrue(moved.zip(pts).all { (a, b) -> (a - to).length() < (b - to).length() })
    // the hand is at the part: no line
    assertEquals(0, HandArc.count(to + Vec3(0.05f, 0.03f, 0f), to))
    assertTrue(HandArc.points(to + Vec3(0.05f, 0.03f, 0f), to).isEmpty())
    // a long reach is capped
    assertEquals(HandArc.MAX_DOTS, HandArc.count(Vec3(0f, 1f, 0f), Vec3(1.2f, 0.8f, -0.9f)))
    // the pulse grows and fades over a second; still with reduced motion
    val (r0, a0) = HandArc.pulse(0.07f, 0.0f, false)
    val (r1, a1) = HandArc.pulse(0.07f, 0.5f, false)
    val (r2, a2) = HandArc.pulse(0.07f, 1.5f, false)
    assertEquals(0.07f, r0, 1e-6f)
    assertTrue(r1 > r0 && a1 < a0)
    assertEquals(r1, r2, 1e-5f)
    assertEquals(a1, a2, 1e-5f)
    assertEquals(HandArc.pulse(0.07f, 0.2f, true), HandArc.pulse(0.07f, 0.9f, true))
  }
}
