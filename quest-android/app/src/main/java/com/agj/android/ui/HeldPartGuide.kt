package com.agj.android.ui

import android.os.SystemClock
import com.agj.android.core.Agq
import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.GhostState
import com.agj.android.guide.GuideAction
import com.agj.android.guide.GuideStep
import com.agj.android.guide.HeldCues
import com.agj.android.guide.LabelInfo
import com.agj.android.guide.PartLabel
import com.agj.android.guide.facingPose
import com.agj.android.world.PickCue
import com.agj.android.world.PickTarget
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import kotlin.math.exp
import kotlinx.serialization.json.JsonObject

/**
 * "Which part do I take, and is the one in my hand right?" in the scene. One object so MainActivity only needs hooks:
 *
 *  - [onAction] from `showStep` (every step / action change clears everything);
 *  - [onServerEvent] from the perception listener (`right_part`, `right_hardware`, `wrong_part`, `wrong_hardware`,
 *    `unsure_show_part`, `show_part_result`), main thread;
 *  - [check] from the "Έλεγχος" button on the ΠΑΡΕ card and from the voice reply ([onVoice]): sends the socket's
 *    `show_part` and shows its `show_part_result`;
 *  - [tick] every render frame; [onSceneReady] once.
 *
 * What it shows (one signal at a time, world-placed, never head-locked):
 *  - the verdict badge ([VerdictBadge]) beside the wrist that holds the part when the wearer is looking at that hand,
 *    else above the step window (then the ΠΑΡΕ card's header carries it too);
 *  - on "wrong": a pulse on the right part's pick marker and a dotted arc from the hand to it ([HeldCues]);
 *  - a name pill above the pick marker of the part to take, the card's count line, and the spoken "which, where".
 *
 * AGQ: `held_verdict`, `held_check`, `pick_prompt`. Rules, maths and copy are in ui/HeldVerdict.kt (tested); this
 * class is tested without a scene (HeldPartGuideTest): the models, the speech, the sounds, the log, the cues.
 * Every lambda is first called from [tick] or an event, so this can be a field of the activity.
 */
class HeldPartGuide(
    private val lang: () -> String,
    /** Speak a line; the [HeldSpeech] says how urgent it is (the activity maps it onto the voice). */
    private val say: (String, HeldSpeech) -> Unit,
    /** Fetch a line ahead of time so it starts without the synthesis round trip. */
    private val prefetch: (String) -> Unit,
    private val speaking: () -> Boolean,
    private val play: (Sounds.Cue) -> Unit,
    private val stepPanel: StepPanelModel,
    private val side: SidePanelModel,
    /** Tracked wrists in WORLD (0, 1 or 2); empty when the hand source gives nothing. */
    private val wrists: () -> List<Vec3>,
    private val pickTargets: () -> List<PickTarget>,
    /** The step window's position and the head position it faces (WORLD). */
    private val window: () -> Pair<Vec3?, Vec3?>,
    /** Sends `{type:"show_part", expected}` on the perception socket; false when there is no session. */
    private val showPart: (String?) -> Boolean,
    /** Manifest name of a part id / hardware code in the app language. */
    private val nameOf: (String) -> PartLabel?,
    /** The pulse on the right part and the arc from the hand (guide/VerdictCues.kt). */
    private val cues: HeldCues,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val log: AgqLog = Agq.log,
) {
  val badge = VerdictBadgeModel()
  val pickLabel = LabelModel()
  var reducedMotion = false
    set(v) {
      field = v
      cues.reducedMotion = v
    }

  private val machine = HeldVerdictMachine()
  private var badgeE: Entity? = null
  private var pickE: Entity? = null
  private var badgeShown = false
  private var pickShown = false

  // the action shown now
  private var step = 0
  private var action = 0
  private var wanted: List<String> = emptyList()
  private var expected: String? = null
  private var label: PartLabel? = null
  private var needed: Int? = null
  private var actionAtMs = 0L
  private var prompted = true
  private var rightSeen = false

  // the badge
  private var checkingSince: Long? = null
  private var where = BadgePlacement.WINDOW
  private var pos: Vec3? = null
  private var followed: Vec3? = null // the wrist the badge sits beside
  private var rightId: String? = null // on "wrong": the part to point at
  private var head: Pose3? = null
  private var active = false
  private var statusText: String? = null
  private var noteAtMs = 0L
  private var tickErrors = 0

  private fun s(key: String, vararg v: Pair<String, Any?>) = Strings.t(lang(), key, *v)

  /** After the scene exists: the two panels (registered by the activity with these ids), hidden. */
  @Synchronized
  fun onSceneReady(badgePanelId: Int, pickLabelPanelId: Int) {
    try {
      badgeE = Entity.createPanelEntity(badgePanelId, Transform(Pose(Vector3(0f, -10f, 0f)))).also { it.setComponent(Visible(false)) }
      pickE = Entity.createPanelEntity(pickLabelPanelId, Transform(Pose(Vector3(0f, -10f, 0f)))).also { it.setComponent(Visible(false)) }
    } catch (e: Throwable) {
      log.error("held_part", "panels: ${e.message}")
    }
  }

  /** The action shown now (`showStep`). Clears the badge, the waiting verdict and the once-per-pick memory. */
  @Synchronized
  fun onAction(step: Int, action: Int, act: GuideAction?, st: GuideStep?) {
    machine.reset()
    checkingSince = null
    clearBadge()
    this.step = step
    this.action = action
    // only something that is picked up can be right or wrong in the hand: a part to place, hardware to fasten
    val a = act?.takeIf { it.kind == ActionKind.PLACE || it.kind == ActionKind.FASTEN }
    wanted = listOfNotNull(a?.partId, a?.code)
    expected = if (a?.kind == ActionKind.FASTEN) a.code ?: a.partId else a?.partId ?: a?.code
    label = a?.label(lang())
    // how many the step uses: the hardware's quantity, else the step's actions with this same part
    needed = a?.let { x -> x.code?.let { c -> st?.hardware?.firstOrNull { it.code == c }?.qty } ?: x.sameCount.takeIf { it > 0 } }
    actionAtMs = clockMs()
    prompted = label == null
    rightSeen = false
    pickLabel.lang = lang()
    side.checkLabel = if (expected != null && label != null) s("check") else null
    noteAtMs = 0L
    // the confirmation names the part: fetched now, so it is heard the moment the part is recognised
    label?.let { l -> runCatching { prefetch(HeldCopy.rightSay(lang(), l.name)) } }
  }

  /** A perception `event` (main thread). Returns true when it was one about the held part (shown, merged or stale). */
  @Synchronized
  fun onServerEvent(event: String, raw: JsonObject): Boolean {
    val ev = HeldEvent.parse(event, raw, lang()) ?: return false
    if (!active || (ev.step != null && ev.step != step)) return true
    if (ev.source == HeldEvent.MANUAL) checkingSince = null
    machine.offer(ev, clockMs())?.let { present(it) }
    return true
  }

  /** "Is this right?" from the mic: true when it was taken as a check (the verdict then speaks, not the answer). */
  fun onVoice(transcript: String, intent: String): Boolean {
    if (!active || expected == null || !CheckPhrase.matches(transcript, intent)) return false
    check("voice")
    return true
  }

  /**
   * Check the part in the hand now: one close look at the next camera frame (the socket's `show_part`). The badge
   * says "Ελέγχω…" until `show_part_result` arrives; no answer in [CHECK_TIMEOUT_MS] reads as "show it again".
   */
  @Synchronized
  fun check(by: String) {
    if (!active || expected == null) return
    val sent = runCatching { showPart(expected) }.getOrDefault(false)
    log.event("held_check", mapOf("by" to by, "expected" to expected, "sent" to sent, "step" to step, "action" to action))
    machine.dismiss() // the answer was asked for: it shows the moment it arrives
    if (sent) {
      checkingSince = clockMs()
      anchor()
      rightId = null
      side.verdict = null
      val state = HeldCopy.checking(lang())
      badge.state = state
      badge.alpha = 1f
      if (badgeE == null) setStatus(state)
    } else machine.offer(noAnswer(), clockMs())?.let { present(it) }
  }

  private fun noAnswer() =
      HeldEvent(Verdict.UNSURE, expected, null, say = s("heldNoAnswer"), source = HeldEvent.MANUAL, event = HeldCopy.TIMEOUT, step = step)

  /** Every render frame. [headPose] WORLD; [guiding] = the guide stage is running and not finished. */
  @Synchronized
  fun tick(dtS: Float, headPose: Pose3?, guiding: Boolean) {
    try {
      head = headPose
      if (!guiding) {
        if (active) {
          active = false
          machine.reset()
          checkingSince = null
          clearBadge()
          cues.update(null, 0f, null, dtS)
          showPick(null, headPose)
          side.checkLabel = null
        }
        return
      }
      active = true
      val now = clockMs()
      checkingSince?.let {
        if (now - it > CHECK_TIMEOUT_MS) {
          checkingSince = null
          machine.offer(noAnswer(), now)?.let { sh -> present(sh) }
        }
      }
      machine.tick(now)?.let { present(it) }
      val sh = machine.shown
      val ws = wrists()
      when {
        sh != null -> {
          // above the window the badge fades with it while the window relocates
          badge.alpha = machine.alpha(now) * (if (where == BadgePlacement.WINDOW) stepPanel.alpha else 1f)
          followBadge(ws, headPose, dtS)
        }
        checkingSince != null -> followBadge(ws, headPose, dtS) // "Ελέγχω…"
        badge.state != null || badgeShown -> clearBadge()
      }
      val targets = if (wanted.isEmpty()) emptyList() else pickTargets().filter { PickCue.opacity(it) > 0f }
      val match = PickPrompt.matches(targets, wanted)
      val best = match.firstOrNull()
      // "wrong": pulse the right part where it lies and draw the way from the hand to it
      val right = if (sh?.ev?.verdict == Verdict.WRONG) rightId?.let { id -> PickPrompt.matches(targets, listOf(id)).firstOrNull() } ?: best else null
      cues.update(right?.worldPos, right?.radiusM ?: 0.05f, if (where == BadgePlacement.WRIST) followed else null, dtS)
      showPick(best, headPose)
      prompt(best, match.size, headPose, now)
      if (now - noteAtMs >= 500) {
        noteAtMs = now
        cardNote(best)
      }
    } catch (e: Throwable) {
      if (tickErrors++ < 3) log.error("held_part", e.message)
    }
  }

  // ---- the badge ----

  /** The name of what the action needs, for the badge: the action's own label, else the manifest's name for the id. */
  private fun expectedLabel(ev: HeldEvent): PartLabel? {
    val id = ev.expectedId
    if (id == null || id in wanted) return label ?: id?.let { nameOf(it) }
    return nameOf(id) ?: ev.expectedText?.let { PartLabel(it, null, null) } ?: label
  }

  /** Decide where the badge goes now: beside the wrist the wearer looks at, else above the step window. */
  private fun anchor() {
    val (wPos, wHead) = window()
    val ws = wrists()
    val placed = BadgePlacement.place(ws, head, wPos, wHead)
    where = placed?.where ?: BadgePlacement.WINDOW
    pos = placed?.pos
    followed = if (where == BadgePlacement.WRIST) head?.let { BadgePlacement.pickWrist(ws, it) } else null
  }

  private fun present(sh: HeldVerdictMachine.Shown) {
    val ev = sh.ev
    checkingSince = null // any verdict ends "Ελέγχω…"; the check's own answer still arrives and is weighed as usual
    anchor()
    val need = expectedLabel(ev)
    val state = HeldCopy.badge(lang(), ev.verdict, HeldCopy.nameLine(need))
    badge.state = state
    badge.alpha = 0f
    side.verdict = if (where == BadgePlacement.WINDOW) state else null
    rightId = if (ev.verdict == Verdict.WRONG) ev.expectedId ?: expected else null
    if (badgeE == null) setStatus(state) // no badge panel (scene not ready): the window's status line carries it
    if (ev.verdict == Verdict.RIGHT) rightSeen = true
    if (sh.sound)
        play(
            when (ev.verdict) {
              Verdict.RIGHT -> Sounds.Cue.SUCCESS
              Verdict.WRONG -> Sounds.Cue.WARNING
              Verdict.UNSURE -> Sounds.Cue.UNSURE
            })
    if (sh.speak) say(HeldCopy.spoken(lang(), ev, need?.name), HeldSpeech.of(ev))
    log.event(
        "held_verdict",
        mapOf(
            "verdict" to ev.verdict.wire,
            "expected" to (ev.expectedId ?: expected),
            "seen" to ev.seenId,
            "p" to ev.p,
            "source" to ev.source,
            "where" to where,
            "event" to ev.event,
            "spoken" to sh.speak,
            "step" to step,
            "action" to action,
        ),
    )
  }

  private fun followBadge(ws: List<Vec3>, headPose: Pose3?, dtS: Float) {
    val (wPos, wHead) = window()
    val target =
        if (where == BadgePlacement.WRIST) {
          // the hand moves: follow that same wrist softly; when it is lost the badge stays where it was (world-locked)
          val w = followed?.let { BadgePlacement.follow(it, ws) }
          if (w != null) followed = w
          if (w != null && headPose != null) BadgePlacement.atWrist(w, headPose.t) else pos
        } else wPos?.let { BadgePlacement.atWindow(it, wHead) }
    val cur = pos
    val p =
        when {
          target == null -> cur
          cur == null || where == BadgePlacement.WINDOW -> target
          (target - cur).length() < 0.008f -> cur // dead band: no shimmer from tracking noise
          else -> cur.lerp(target, 1f - exp(-dtS / 0.12f))
        }
    pos = p
    val e = badgeE ?: return
    val facing = headPose?.t ?: wHead
    if (p == null || facing == null) return
    e.setComponent(Transform(facingPose(p, facing)))
    e.setComponent(Scale(Vector3(BadgePlacement.scale((p - facing).length()))))
    if (!badgeShown) {
      badgeShown = true
      e.setComponent(Visible(true))
    }
  }

  private fun clearBadge() {
    badge.state = null
    side.verdict = null
    pos = null
    followed = null
    rightId = null
    if (badgeShown) {
      badgeShown = false
      badgeE?.setComponent(Visible(false))
    }
    // take our word off the step window's status line, if we had to put it there and it is still ours
    val t = statusText
    statusText = null
    if (t != null && stepPanel.state.statusText == t) stepPanel.state = stepPanel.state.copy(status = StatusKind.NONE, statusText = "")
  }

  private fun setStatus(state: VerdictBadgeState) {
    val t = Strings.upper(lang(), state.title)
    statusText = t
    stepPanel.state = stepPanel.state.copy(status = state.kind, statusText = t)
  }

  // ---- which part to take ----

  /** The name pill above the pick marker of the part to take (above the chevron), turned to the wearer. */
  private fun showPick(t: PickTarget?, headPose: Pose3?) {
    val l = label
    val info = if (t != null && l != null) LabelInfo(PickPrompt.pill(t, l), GhostState.NEXT, false) else null
    if (pickLabel.info != info) pickLabel.info = info
    val e = pickE ?: return
    if (t == null || info == null || headPose == null) {
      if (pickShown) {
        pickShown = false
        e.setComponent(Visible(false))
      }
      return
    }
    val scale = ((headPose.t - t.worldPos).length() / 0.8f).coerceIn(0.75f, 1.5f)
    val p = t.worldPos + Vec3(0f, PICK_PILL_LIFT_M * scale, 0f)
    e.setComponent(Transform(facingPose(p, headPose.t)))
    e.setComponent(Scale(Vector3(scale)))
    if (!pickShown) {
      pickShown = true
      e.setComponent(Visible(true))
    }
  }

  /**
   * Once per action, after the step's own line and only when nothing else is being said or shown: "Πάρε: <name>,
   * <where>." when the part was found on the table. The step's line and the card name the part from the start, so
   * when it is not found within [PROMPT_GIVE_UP_MS] nothing more is said.
   */
  private fun prompt(best: PickTarget?, inView: Int, headPose: Pose3?, now: Long) {
    if (prompted) return
    val l = label ?: return
    val age = now - actionAtMs
    if (rightSeen) {
      prompted = true // already in the hand: nothing to point at
      return
    }
    if (best != null && headPose != null && age >= PROMPT_AFTER_MS && !speaking() && machine.shown == null && checkingSince == null) {
      prompted = true
      val bearing = PickPrompt.bearing(headPose, best.worldPos)
      val whereText = s(bearing)
      say(PickPrompt.line(lang(), l.name, whereText), HeldSpeech.PROMPT)
      log.event(
          "pick_prompt",
          mapOf("part" to expected, "name" to l.name, "position" to whereText, "bearing" to bearing.removePrefix("where").lowercase(),
              "in_view" to (best.countSeen ?: inView), "needed" to (best.countNeeded ?: needed), "spoken" to true,
              "step" to step, "action" to action),
      )
    } else if (age >= PROMPT_GIVE_UP_MS) {
      prompted = true
      log.event(
          "pick_prompt",
          mapOf("part" to expected, "name" to l.name, "position" to null, "bearing" to null, "in_view" to 0, "needed" to needed,
              "spoken" to false, "step" to step, "action" to action),
      )
    }
  }

  /** The card's third line: the server's count sentence ("φαίνονται 1 από 2") when it has one, else "Χρειάζεσαι N". */
  private fun cardNote(best: PickTarget?) {
    val card = side.part ?: return
    val note = if (label == null) null else PickPrompt.countLine(lang(), best?.countNeeded ?: needed, PickPrompt.countText(best, lang()), null)
    if (card.note != note) side.part = card.copy(note = note)
  }

  companion object {
    /** The server waits 8 s for a frame and a request takes 1–3 s. */
    const val CHECK_TIMEOUT_MS = 10_000L
    const val PROMPT_AFTER_MS = 3000L
    const val PROMPT_GIVE_UP_MS = 15_000L
    /** Pill centre above the part: chevron (6 cm + 1 cm bob) + 2 cm + half the pill panel. */
    const val PICK_PILL_LIFT_M = 0.105f
  }
}
