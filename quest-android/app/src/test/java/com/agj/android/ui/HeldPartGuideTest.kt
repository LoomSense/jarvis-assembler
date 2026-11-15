package com.agj.android.ui

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.GuideAction
import com.agj.android.guide.GuideStep
import com.agj.android.guide.HandArc
import com.agj.android.guide.HardwareQty
import com.agj.android.guide.HeldCues
import com.agj.android.guide.LocalizedText
import com.agj.android.guide.PartLabel
import com.agj.android.world.PickTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held-part guide without a scene (no panel entities): what the badge, the ΠΑΡΕ card and the pick pill say, what
 * is spoken and played, what is logged, and where the cues point, for right / wrong / unsure, a step change, the
 * manual check and the pick prompt.
 */
class HeldPartGuideTest {
  private var now = 10_000L
  private val head = Pose3(Vec3(0f, 1.6f, 0f), Quat.IDENTITY) // looks down −Z
  private val inHand = Vec3(0.05f, 1.25f, -0.40f)
  private val said = ArrayList<Pair<String, HeldSpeech>>()
  private val fetched = ArrayList<String>()
  private val played = ArrayList<Sounds.Cue>()
  private val asked = ArrayList<String?>()
  private val lines = ArrayList<JsonObject>()
  private var wrists: List<Vec3> = emptyList()
  private var targets: List<PickTarget> = emptyList()
  private var speaking = false
  private var session = true
  private val stepPanel = StepPanelModel()
  private val side = SidePanelModel()

  private class Cues : HeldCues {
    override var reducedMotion = false
    var target: Vec3? = null
    var from: Vec3? = null

    override fun update(target: Vec3?, radiusM: Float, from: Vec3?, dtS: Float) {
      this.target = target
      this.from = from
    }
  }

  private val cues = Cues()
  private val names = mapOf("118331" to PartLabel("Βίδα έκκεντρου", "118331", null), "101345" to PartLabel("Ξύλινη καβίλια", "101345", null))

  private val guide =
      HeldPartGuide(
          lang = { "el" },
          say = { t, k -> said += t to k },
          prefetch = { fetched += it },
          speaking = { speaking },
          play = { played += it },
          stepPanel = stepPanel,
          side = side,
          wrists = { wrists },
          pickTargets = { targets },
          window = { Vec3(0.4f, 1.3f, -0.8f) to head.t },
          showPart = { e -> if (session) asked.add(e) else false },
          nameOf = { names[it] },
          cues = cues,
          clockMs = { now },
          log = AgqLog({ now }, { lines += Json.parseToJsonElement(it).jsonObject }),
      )

  private fun action(kind: ActionKind, partId: String?, code: String?, name: String) =
      GuideAction(0, kind, LocalizedText("x", "χ"), "node", partId, code, null, null, null, null,
          mapOf("el" to PartLabel(name, code, "1 από 4"), "en" to PartLabel(name, code, "1 of 4")), 1, 4)

  private fun step(n: Int, act: GuideAction) = GuideStep(n, "t", 3, null, listOf(HardwareQty("118331", 4), HardwareQty("101345", 2)), Pose3.IDENTITY, listOf(act))

  private val bolt = action(ActionKind.FASTEN, "cam_bolt", "118331", "Βίδα έκκεντρου")

  private fun start(act: GuideAction = bolt, n: Int = 2) {
    side.part = PartCard("ΠΑΡΕ", "x", act.code, null, FloatArray(0))
    guide.onAction(n, 0, act, step(n, act))
    tick(0)
  }

  private fun tick(ms: Long) {
    now += ms
    guide.tick(0.014f, head, true)
  }

  private fun event(name: String, expected: String?, observed: String?, step: Int = 2, extra: String = "") =
      guide.onServerEvent(
          name,
          Json.parseToJsonElement(
                  """{"type":"event","event":"$name","step":$step,"expected_id":${expected?.let { "\"$it\"" }},"observed_id":${observed?.let { "\"$it\"" }},
                  "p":0.91,"say":{"en":"en reason","el":"Κρατάς ξύλινη καβίλια, 101345. Το βήμα 2 θέλει βίδα έκκεντρου, 118331."}$extra}""")
              .jsonObject,
      )

  private fun logged(ev: String) = lines.filter { it["ev"]?.jsonPrimitive?.content == ev }

  private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

  @Test
  fun rightPartGreenBadgeAtTheHandChimeAndOneShortLine() {
    start()
    assertEquals(listOf("Σωστό. Βίδα έκκεντρου."), fetched) // ready before it is needed
    assertEquals("Έλεγχος", side.checkLabel)
    wrists = listOf(inHand)
    assertTrue(event("right_hardware", "118331", "118331"))
    assertEquals(VerdictBadgeState(StatusKind.SUCCESS, "Σωστό", "Βίδα έκκεντρου 118331"), guide.badge.state)
    assertEquals(listOf(Sounds.Cue.SUCCESS), played)
    assertEquals(listOf("Σωστό. Βίδα έκκεντρου." to HeldSpeech.CONFIRM), said)
    assertNull("at the hand: the card keeps its header", side.verdict)
    val v = logged("held_verdict").single()
    assertEquals("right", v.str("verdict"))
    assertEquals("118331", v.str("expected"))
    assertEquals("118331", v.str("seen"))
    assertEquals("0.91", v.str("p"))
    assertEquals("socket", v.str("source"))
    assertEquals("wrist", v.str("where"))
    tick(1000)
    assertEquals(1f, guide.badge.alpha, 1e-6f)
    tick(1000)
    assertNull("fades after about 2 s", guide.badge.state)
    // the same pick confirmed again: the badge, no second chime, no second sentence
    event("right_hardware", "118331", "118331")
    assertEquals(StatusKind.SUCCESS, guide.badge.state!!.kind)
    assertEquals(1, played.size)
    assertEquals(1, said.size)
    assertNull("no cue points anywhere on a right part", cues.target)
  }

  @Test
  fun wrongPartRedBadgeNamesWhatIsNeededAndPointsAtIt() {
    start()
    wrists = listOf(inHand)
    val onTable = Vec3(-0.35f, 0.80f, -0.60f)
    targets = listOf(PickTarget("101345", "x", Vec3(0.3f, 0.8f, -0.5f), 0.9, 0), PickTarget("118331", "x", onTable, 0.8, 0, radiusM = 0.04f))
    event("wrong_hardware", "118331", "101345")
    assertEquals(VerdictBadgeState(StatusKind.ERROR, "Λάθος εξάρτημα", "Χρειάζεσαι: Βίδα έκκεντρου 118331"), guide.badge.state)
    assertEquals(listOf(Sounds.Cue.WARNING), played)
    assertEquals(listOf("Κρατάς ξύλινη καβίλια, 101345. Το βήμα 2 θέλει βίδα έκκεντρου, 118331." to HeldSpeech.WARNING), said)
    tick(100)
    assertEquals("the right part's marker pulses", onTable, cues.target)
    assertEquals("the arc starts at the hand", inHand, cues.from)
    assertEquals("wrong", logged("held_verdict").single().str("verdict"))
    assertEquals("101345", logged("held_verdict").single().str("seen"))
    // the hand moves: the arc follows that wrist; the badge stays one badge
    wrists = listOf(Vec3(0.02f, 1.2f, -0.42f))
    tick(100)
    assertEquals(wrists[0], cues.from)
    // still held 5 s later: the badge stays, nothing is said again
    now += 5000
    event("wrong_hardware", "118331", "101345")
    tick(0)
    assertEquals(1, said.size)
    assertEquals(StatusKind.ERROR, guide.badge.state!!.kind)
    // the right part is not on the table (or no hand pose): no arc is invented
    targets = emptyList()
    tick(100)
    assertNull(cues.target)
    tick(7000)
    assertNull("gone 7 s after the last report", guide.badge.state)
  }

  @Test
  fun withoutAWristTheBadgeGoesAboveTheWindowAndOntoTheCard() {
    start()
    targets = listOf(PickTarget("118331", "x", Vec3(-0.35f, 0.80f, -0.60f), 0.8, 0))
    event("wrong_hardware", "118331", "101345")
    assertEquals("window", logged("held_verdict").single().str("where"))
    assertEquals(guide.badge.state, side.verdict)
    // no scene in this test, so no badge panel: the window's status line carries the word until the badge clears
    assertEquals("ΛΑΘΟΣ ΕΞΑΡΤΗΜΑ", stepPanel.state.statusText)
    assertEquals(StatusKind.ERROR, stepPanel.state.status)
    tick(100)
    assertEquals("the right part still pulses", Vec3(-0.35f, 0.80f, -0.60f), cues.target)
    assertNull("no hand pose: no arc", cues.from)
    tick(7000)
    assertNull(side.verdict)
    assertEquals("", stepPanel.state.statusText)
    assertEquals(StatusKind.NONE, stepPanel.state.status)
  }

  @Test
  fun unsureAmberPromptStaysUntilTheNextVerdictAndNeverFlipsFast() {
    start()
    wrists = listOf(inHand)
    event("unsure_show_part", "118331", null)
    assertEquals(VerdictBadgeState(StatusKind.UNSURE, "Δείξε μου το εξάρτημα πιο κοντά"), guide.badge.state)
    assertEquals(listOf(Sounds.Cue.UNSURE), played)
    assertEquals(listOf("Δείξε μου το εξάρτημα πιο κοντά." to HeldSpeech.UNSURE), said)
    tick(60_000)
    assertEquals("still there a minute later", StatusKind.UNSURE, guide.badge.state!!.kind)
    assertEquals(1f, guide.badge.alpha, 1e-6f)
    // right, then wrong 0.5 s later: one badge, the flip waits until 1.5 s have passed
    event("right_hardware", "118331", "118331")
    assertEquals(StatusKind.SUCCESS, guide.badge.state!!.kind)
    now += 500
    event("wrong_hardware", "118331", "101345")
    assertEquals(StatusKind.SUCCESS, guide.badge.state!!.kind)
    tick(900)
    assertEquals(StatusKind.SUCCESS, guide.badge.state!!.kind)
    tick(100)
    assertEquals(StatusKind.ERROR, guide.badge.state!!.kind)
    assertEquals(listOf("unsure", "right", "wrong"), logged("held_verdict").map { it.str("verdict") })
  }

  @Test
  fun stepChangeClearsEverythingAndStaleEventsAreDropped() {
    start()
    event("wrong_hardware", "118331", "101345")
    now += 300
    event("right_hardware", "118331", "118331") // waiting behind the debounce
    val dowel = action(ActionKind.FASTEN, "dowel", "101345", "Ξύλινη καβίλια")
    guide.onAction(3, 0, dowel, step(3, dowel))
    assertNull(guide.badge.state)
    assertNull(side.verdict)
    assertEquals("", stepPanel.state.statusText)
    tick(5000)
    assertNull("what waited is gone too", guide.badge.state)
    assertNull(cues.target)
    // an answer about the old step arrives late: ignored
    assertTrue(event("right_hardware", "118331", "118331", step = 2))
    assertNull(guide.badge.state)
    // the new step's first verdict shows at once, spoken, about the new part
    event("right_hardware", "101345", "101345", step = 3)
    assertEquals(VerdictBadgeState(StatusKind.SUCCESS, "Σωστό", "Ξύλινη καβίλια 101345"), guide.badge.state)
    assertEquals("Σωστό. Ξύλινη καβίλια." to HeldSpeech.CONFIRM, said.last())
    // an action with nothing to pick (a turn): no check button, nothing to check by voice
    val turn = action(ActionKind.TURN, null, null, "x")
    guide.onAction(3, 1, turn, step(3, turn))
    tick(0)
    assertNull(side.checkLabel)
    assertFalse(guide.onVoice("είναι σωστό αυτό;", "ask"))
    // events that are not about the hand are not taken
    assertFalse(guide.onServerEvent("step_complete", JsonObject(emptyMap())))
    // the guide ended: everything off
    guide.tick(0.014f, head, false)
    assertNull(guide.badge.state)
    assertTrue(event("wrong_hardware", "101345", "118331", step = 3))
    assertNull("not guiding: verdicts are dropped", guide.badge.state)
  }

  @Test
  fun checkThisPartAsksTheSocketAndShowsTheAnswer() {
    start()
    wrists = listOf(inHand)
    guide.check("card")
    assertEquals(listOf<String?>("118331"), asked) // FASTEN: the hardware code is what is expected
    assertEquals(VerdictBadgeState(StatusKind.INFO, "Ελέγχω…"), guide.badge.state)
    assertEquals("card", logged("held_check").single().str("by"))
    tick(1200)
    event("show_part_result", "118331", "101345", extra = ""","match":false""")
    assertEquals(VerdictBadgeState(StatusKind.ERROR, "Λάθος εξάρτημα", "Χρειάζεσαι: Βίδα έκκεντρου 118331"), guide.badge.state)
    assertEquals(HeldSpeech.ANSWER, said.single().second)
    assertEquals("manual", logged("held_verdict").single().str("source"))
    // by voice, 0.4 s later: the badge comes down at once and the answer shows the moment it arrives
    now += 400
    assertTrue(guide.onVoice("είναι σωστό αυτό;", "ask"))
    assertEquals(StatusKind.INFO, guide.badge.state!!.kind)
    now += 300
    event("show_part_result", "118331", "118331", extra = ""","match":true""")
    assertEquals(StatusKind.SUCCESS, guide.badge.state!!.kind)
    assertEquals("Σωστό. Βίδα έκκεντρου." to HeldSpeech.ANSWER, said.last())
    assertFalse("a command is not a check", guide.onVoice("επόμενο", "next"))
    // no answer in 10 s: asks to show it again
    tick(3000)
    guide.check("card")
    tick(10_001)
    assertEquals(StatusKind.UNSURE, guide.badge.state!!.kind)
    assertEquals("Δεν πήρα απάντηση. Δείξε το ξανά." to HeldSpeech.ANSWER, said.last())
    // no session to ask: the same, at once
    tick(3000)
    session = false
    guide.check("card")
    assertEquals(StatusKind.UNSURE, guide.badge.state!!.kind)
    assertEquals("show_part_timeout", logged("held_verdict").last().str("event"))
  }

  @Test
  fun whichPartIsSaidOnceAndShownOnTheCardAndTheMarker() {
    start()
    tick(1000)
    assertEquals("before the part is found: how many the step needs", "Χρειάζεσαι 4", side.part!!.note)
    assertNull(guide.pickLabel.info)
    // step mode: every instance; another item of the step is not this action's
    val count = mapOf("en" to "3 of 4 in view", "el" to "φαίνονται 3 από 4")
    targets =
        listOf(
            PickTarget("101345", "x", Vec3(0.4f, 0.8f, -0.5f), 0.99, 0, current = false),
            PickTarget("118331", "x", Vec3(-0.1f, 0.8f, -0.5f), 0.6, 0, instance = 1, countText = count, countSeen = 3, countNeeded = 4),
            PickTarget("118331", "x", Vec3(-0.4f, 0.8f, -0.5f), 0.9, 0, instance = 0, countText = count, countSeen = 3, countNeeded = 4),
        )
    speaking = true // the step's own line is still being read
    tick(1000)
    assertEquals("φαίνονται 3 από 4", side.part!!.note)
    assertEquals(PartLabel("Βίδα έκκεντρου", null, "3/4"), guide.pickLabel.info!!.label)
    tick(2000)
    assertTrue("never over the step's line", said.isEmpty())
    speaking = false
    tick(100)
    assertEquals(listOf("Πάρε: Βίδα έκκεντρου, αριστερά σου." to HeldSpeech.PROMPT), said)
    tick(5000)
    assertEquals("once per action", 1, said.size)
    val p = logged("pick_prompt").single()
    assertEquals("118331", p.str("part"))
    assertEquals("αριστερά σου", p.str("position"))
    assertEquals("3", p.str("in_view"))
    // the next action with the part never found: nothing more is said, and the log says so
    targets = emptyList()
    guide.onAction(2, 1, bolt, step(2, bolt))
    tick(15_000)
    assertEquals(1, said.size)
    assertEquals("false", logged("pick_prompt").last().str("spoken"))
    assertNull(logged("pick_prompt").last().str("position"))
    assertNull(guide.pickLabel.info)
    // already holding the right one before it was found on the table: no prompt for it
    guide.onAction(2, 2, bolt, step(2, bolt))
    tick(100)
    event("right_hardware", "118331", "118331")
    targets = listOf(PickTarget("118331", "x", Vec3(-0.4f, 0.8f, -0.5f), 0.9, 0))
    tick(6000)
    assertEquals("still only the first action's prompt", 1, said.count { it.second == HeldSpeech.PROMPT })
    // the arc lands beside the part on the hand's side, not on the chevron or the pill above it
    val land = HandArc.landing(inHand, Vec3(-0.4f, 0.8f, -0.5f), 0.05f)
    assertEquals(0.82f, land.y, 1e-5f)
    assertEquals(0.11f, Math.hypot((land.x + 0.4f).toDouble(), (land.z + 0.5f).toDouble()).toFloat(), 1e-4f)
    assertTrue("on the hand's side", land.x > -0.4f)
  }
}
