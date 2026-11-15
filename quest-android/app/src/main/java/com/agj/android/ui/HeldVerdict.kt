package com.agj.android.ui

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.guide.PartLabel
import com.agj.android.world.PickTarget
import kotlin.math.abs
import kotlin.math.atan2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * "Is the part I am holding the right one?" (XR_NATIVE_SPEC §4.6 pick, §4.8 wrong, §4.10 status). Pure Kotlin, JVM-tested:
 * the server events → [HeldEvent], the debounce / priority / once-per-pick rules ([HeldVerdictMachine]), where the
 * badge goes ([BadgePlacement]) and the "which part, where" line ([PickPrompt]). The scene side is ui/HeldPartGuide.kt.
 *
 * Server events (server/perception/README.md): `right_part` / `right_hardware` (the confirmation), `wrong_part` /
 * `wrong_hardware`, `unsure_show_part`, and `show_part_result` (the answer to the socket's `show_part` request, with
 * `match` true | false | null).
 */

enum class Verdict(val wire: String, val priority: Int) {
  RIGHT("right", 2),
  WRONG("wrong", 3),
  UNSURE("unsure", 1),
}

/**
 * How urgent a spoken line of this feature is. The activity maps it onto the voice (one place): today "interrupt" or
 * "only when nothing is being said"; with a speech queue, its priorities, most urgent first:
 *  - [ANSWER]: the reply to a check the person asked for ("είναι σωστό αυτό;"). Interrupts.
 *  - [WARNING]: the wrong part is in the hand (the server's reason). Interrupts.
 *  - [UNSURE]: "show me the part closer". Normal: may wait for the current sentence.
 *  - [CONFIRM]: "Σωστό." Low: never talks over anything; dropped if it cannot start at once (the chime carries it).
 *  - [PROMPT]: "Πάρε: …, αριστερά σου." Lowest: only when idle; dropped otherwise.
 */
enum class HeldSpeech {
  ANSWER,
  WARNING,
  UNSURE,
  CONFIRM,
  PROMPT;

  /** True for the lines that must never cut another sentence. */
  val quiet: Boolean
    get() = this == CONFIRM || this == PROMPT

  companion object {
    fun of(ev: HeldEvent): HeldSpeech =
        if (ev.source == HeldEvent.MANUAL) ANSWER
        else
            when (ev.verdict) {
              Verdict.RIGHT -> CONFIRM
              Verdict.WRONG -> WARNING
              Verdict.UNSURE -> UNSURE
            }
  }
}

/** One verdict about what is in the hand, as the server said it. */
data class HeldEvent(
    val verdict: Verdict,
    /** What the action needs: a part id or a hardware code (null on a step that needs no hardware). */
    val expectedId: String?,
    /** What the server saw in the hand (null when it could not tell). */
    val seenId: String?,
    /** The server's display strings (`"long screw 108363"`); the UI prefers the manifest's names by id. */
    val expectedText: String? = null,
    val seenText: String? = null,
    val p: Double? = null,
    /** The ready sentence in the app language (`say.{el,en}`), null when the event has none. */
    val say: String? = null,
    /** `socket` (the server decided to speak) or `manual` (the answer to "is this right?"). */
    val source: String = SOCKET,
    val event: String = "",
    val step: Int? = null,
) {
  /** Same thing shown twice = same key: a repeat does not flip, re-sound or re-speak. */
  val key: String
    get() = "${verdict.wire}|${expectedId ?: ""}|${seenId ?: ""}"

  companion object {
    const val SOCKET = "socket"
    const val MANUAL = "manual"
    val EVENTS = setOf("right_part", "right_hardware", "wrong_part", "wrong_hardware", "unsure_show_part", "show_part_result")

    /** A server `event` message → a verdict, or null for an event that is not about the held part. */
    fun parse(event: String, raw: JsonObject, lang: String): HeldEvent? {
      fun str(k: String) = (raw[k] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != "null" }?.contentOrNull
      val match = (raw["match"] as? JsonPrimitive)?.booleanOrNull
      val verdict =
          when (event) {
            "right_part", "right_hardware" -> Verdict.RIGHT
            "wrong_part", "wrong_hardware" -> Verdict.WRONG
            "unsure_show_part" -> Verdict.UNSURE
            "show_part_result" -> if (match == true) Verdict.RIGHT else if (match == false) Verdict.WRONG else Verdict.UNSURE
            else -> return null
          }
      return HeldEvent(
          verdict = verdict,
          expectedId = str("expected_id"),
          seenId = str("observed_id"),
          expectedText = str("expected"),
          seenText = str("observed"),
          p = (raw["p"] as? JsonPrimitive)?.doubleOrNull,
          say = sayOf(raw, lang),
          source = if (event == "show_part_result") MANUAL else SOCKET,
          event = event,
          step = (raw["step"] as? JsonPrimitive)?.intOrNull,
      )
    }

    /** `say` is `{en, el}` (or a plain string). */
    fun sayOf(raw: JsonObject, lang: String): String? {
      val s = raw["say"] ?: return null
      (s as? JsonPrimitive)?.let { return it.contentOrNull?.takeIf { t -> t.isNotBlank() && t != "null" } }
      val o = s as? JsonObject ?: return null
      fun get(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
      return get(if (lang == "el") "el" else "en") ?: get("en")
    }
  }
}

/**
 * One badge at a time. Rules:
 *  - a different verdict is never shown sooner than [minFlipMs] after the last one: it waits ([pending]); while it
 *    waits, a higher [Verdict.priority] replaces it (wrong > right > unsure), an equal one replaces it by being newer;
 *  - the same verdict about the same thing does not flip: a repeated "wrong" only keeps the badge up;
 *  - right fades after [rightMs], wrong after [wrongMs], unsure stays until the next verdict;
 *  - speech: "right" aloud once per pick (re-armed by a wrong verdict, as on the server), "wrong" once per wrong thing
 *    (again after [repeatWrongMs]), "unsure" once per unsure spell; an answer the person asked for (`manual`) is
 *    always spoken;
 *  - [reset] on every step or action change clears all of it.
 * Not thread-safe (main thread).
 */
class HeldVerdictMachine(
    val minFlipMs: Long = 1500,
    val rightMs: Long = 2000,
    val wrongMs: Long = 7000,
    val fadeMs: Long = 300,
    val repeatWrongMs: Long = 20_000,
) {
  data class Shown(val ev: HeldEvent, val atMs: Long, val speak: Boolean, val sound: Boolean)

  var shown: Shown? = null
    private set

  var pending: HeldEvent? = null
    private set

  private var lastFlipMs = Long.MIN_VALUE / 2
  private val confirmed = HashSet<String>()
  private var unsureSpoken = false
  private var lastWrongSeen: String? = null
  private var lastWrongMs = Long.MIN_VALUE / 2

  /** A verdict arrived. Returns the badge when it is shown now, null when it was merged or has to wait. */
  fun offer(ev: HeldEvent, nowMs: Long): Shown? {
    expire(nowMs)
    val cur = shown
    if (cur != null && cur.ev.key == ev.key) {
      // the same thing again: a wrong part still in the hand keeps its badge; right fades once; nothing is re-said
      if (ev.verdict == Verdict.WRONG) shown = cur.copy(atMs = nowMs, speak = false, sound = false)
      if (pending?.key != ev.key) pending = null // what was waiting is older than this confirmation of the shown one
      return null
    }
    if (cur != null && nowMs - lastFlipMs < minFlipMs) {
      val p = pending
      if (p == null || ev.verdict.priority >= p.verdict.priority) pending = ev
      return null
    }
    return show(ev, nowMs)
  }

  /** Every frame: fades, and releases a verdict that waited. Returns it when it is shown now. */
  fun tick(nowMs: Long): Shown? {
    expire(nowMs)
    val p = pending ?: return null
    if (shown != null && nowMs - lastFlipMs < minFlipMs) return null
    return show(p, nowMs)
  }

  /** 0..1: fades in over 150 ms and out over the last [fadeMs]. */
  fun alpha(nowMs: Long): Float {
    val s = shown ?: return 0f
    val age = nowMs - s.atMs
    val inA = (age / 150f).coerceIn(0f, 1f)
    val life = lifeMs(s.ev.verdict) ?: return inA
    val outA = ((life - age).toFloat() / fadeMs).coerceIn(0f, 1f)
    return minOf(inA, outA)
  }

  /**
   * The person asked for a check: the badge comes down now and nothing waits, so the answer shows the moment it
   * arrives. What was already said aloud stays remembered (unlike [reset]).
   */
  fun dismiss() {
    shown = null
    pending = null
    lastFlipMs = Long.MIN_VALUE / 2
  }

  /** Step or action change: nothing shown, nothing waiting, every pick may be confirmed again. */
  fun reset() {
    shown = null
    pending = null
    confirmed.clear()
    unsureSpoken = false
    lastWrongSeen = null
    lastWrongMs = Long.MIN_VALUE / 2
    lastFlipMs = Long.MIN_VALUE / 2
  }

  private fun lifeMs(v: Verdict): Long? =
      when (v) {
        Verdict.RIGHT -> rightMs
        Verdict.WRONG -> wrongMs
        Verdict.UNSURE -> null
      }

  private fun expire(nowMs: Long) {
    val s = shown ?: return
    val life = lifeMs(s.ev.verdict) ?: return
    if (nowMs - s.atMs >= life) shown = null
  }

  private fun show(ev: HeldEvent, nowMs: Long): Shown {
    val manual = ev.source == HeldEvent.MANUAL
    val speak: Boolean
    val sound: Boolean
    when (ev.verdict) {
      Verdict.RIGHT -> {
        val first = confirmed.add(ev.expectedId ?: ev.seenId ?: "")
        speak = first || manual
        sound = speak
        unsureSpoken = false
      }
      Verdict.WRONG -> {
        confirmed.clear() // after a correction the next right pick is confirmed again
        speak = manual || ev.seenId != lastWrongSeen || nowMs - lastWrongMs >= repeatWrongMs
        sound = true
        if (speak) {
          lastWrongSeen = ev.seenId
          lastWrongMs = nowMs
        }
        unsureSpoken = false
      }
      Verdict.UNSURE -> {
        speak = manual || !unsureSpoken
        sound = speak
        unsureSpoken = true
      }
    }
    lastFlipMs = nowMs
    pending = null
    return Shown(ev, nowMs, speak, sound).also { shown = it }
  }
}

/**
 * Where the verdict badge goes (WORLD, metres). Never head-locked: beside the wrist that holds the part, or, with no
 * usable wrist pose, just above the step window.
 *
 * A wrist is used only when the wearer can see a badge beside it: 0.12–0.95 m from the head and within 50° of the
 * gaze (the headset shows about 48° below the gaze). This device's hand source gives wrists only and may give none (or
 * a stale pose), so anything else falls back to the window: the badge goes to the hand when the wearer looks at the
 * hand, else to where the instructions are.
 */
object BadgePlacement {
  const val WRIST = "wrist"
  const val WINDOW = "window"
  const val UP_M = 0.10f
  const val TOWARD_M = 0.06f
  const val MIN_HEAD_M = 0.28f
  const val MIN_REACH_M = 0.12f
  const val MAX_REACH_M = 0.95f
  const val MIN_COS = 0.6428f // 50° from the gaze
  /** Badge centre above the step panel's centre: half the panel + half the badge + 1 cm. */
  const val ABOVE_WINDOW_M = STEP_PANEL_H / 2 + VERDICT_PANEL_H / 2 + 0.01f

  data class Placed(val pos: Vec3, val where: String)

  /** The wrist the wearer is looking at (smallest angle to the gaze), or null when none is plausible. */
  fun pickWrist(wrists: List<Vec3>, head: Pose3): Vec3? {
    val fwd = head.q.rotate(Vec3(0f, 0f, -1f))
    return wrists
        .mapNotNull { w ->
          val d = w - head.t
          val len = d.length()
          if (len < MIN_REACH_M || len > MAX_REACH_M) return@mapNotNull null
          val cos = d.dot(fwd) / len
          if (cos < MIN_COS) null else w to cos
        }
        .maxByOrNull { it.second }
        ?.first
  }

  /** 10 cm above the wrist and 6 cm towards the eyes (clear of the part in the fingers), never nearer than 28 cm. */
  fun atWrist(wrist: Vec3, head: Vec3): Vec3 {
    val to = head - wrist
    val len = to.length()
    val dir = if (len < 1e-4f) Vec3(0f, 0f, 1f) else to * (1f / len)
    val p = wrist + Vec3(0f, UP_M, 0f) + dir * TOWARD_M
    val back = p - head
    val dist = back.length()
    return if (dist >= MIN_HEAD_M || dist < 1e-4f) p else head + back * (MIN_HEAD_M / dist)
  }

  /** Just above the step window's top edge, 1.5 cm nearer the wearer than the window (no z-fight with it). */
  fun atWindow(windowPos: Vec3, windowHead: Vec3?): Vec3 {
    val up = windowPos + Vec3(0f, ABOVE_WINDOW_M, 0f)
    val h = windowHead ?: return up
    val flat = Vec3(h.x - windowPos.x, 0f, h.z - windowPos.z)
    val n = flat.length()
    return if (n < 1e-3f) up else up + flat * (0.015f / n)
  }

  /**
   * The wrist the badge sits beside, one frame later: the tracked wrist nearest to where it was ([prev]), so the badge
   * does not hop to the other hand; null when that hand is no longer tracked (the badge then stays where it is).
   */
  fun follow(prev: Vec3, wrists: List<Vec3>, maxJumpM: Float = 0.25f): Vec3? =
      wrists.minByOrNull { (it - prev).length() }?.takeIf { (it - prev).length() <= maxJumpM }

  /** The wrist if one is usable, else the window, else null (nothing to anchor to yet). */
  fun place(wrists: List<Vec3>, head: Pose3?, windowPos: Vec3?, windowHead: Vec3?): Placed? {
    val w = head?.let { pickWrist(wrists, it) }
    if (w != null && head != null) return Placed(atWrist(w, head.t), WRIST)
    return windowPos?.let { Placed(atWindow(it, windowHead), WINDOW) }
  }

  /** Panel scale that keeps the text at the step window's body size or larger (in degrees) from 0.45 m outwards. */
  fun scale(distM: Float): Float = (distM / 0.75f).coerceIn(0.6f, 1.5f)
}

/** "Which part, and where" (the spoken line, the ΠΑΡΕ card, the pill on the pick marker). Pure. */
object PickPrompt {
  const val LEFT = "whereLeft"
  const val AHEAD = "whereAhead"
  const val RIGHT = "whereRight"

  /**
   * The wanted item's instances among `world.pickTargets` (every instance of every item of the step is in there):
   * the one easiest to pick up first (instance 0), then by confidence.
   */
  fun matches(targets: List<PickTarget>, wanted: Collection<String>): List<PickTarget> =
      targets.filter { it.id in wanted }.sortedWith(compareBy({ it.instance }, { -it.confidence }))

  /** The pill above the pick marker: the name the card and the window use, and "seen/needed" as its chip. */
  fun pill(t: PickTarget, label: PartLabel): PartLabel {
    val seen = t.countSeen
    val need = t.countNeeded
    return PartLabel(label.name, null, if (seen != null && need != null && need > 0) "$seen/$need" else null)
  }

  /** The server's count sentence for [t] in [lang] ("φαίνονται 1 από 2"), null when the step does not say how many. */
  fun countText(t: PickTarget?, lang: String): String? =
      t?.countText?.let { m -> m[if (lang == "el") "el" else "en"] ?: m["en"] }?.takeIf { it.isNotBlank() }

  /** Where [pos] is for the wearer now: the [Strings] key for left / ahead / right (±15° about the flat gaze). */
  fun bearing(head: Pose3, pos: Vec3): String {
    val f = Placement.flatForward(head)
    val r = Vec3(-f.z, 0f, f.x)
    val d = pos - head.t
    val deg = Math.toDegrees(atan2(d.dot(r).toDouble(), d.dot(f).toDouble()))
    return if (abs(deg) <= 15.0) AHEAD else if (deg < 0) LEFT else RIGHT
  }

  /** "Πάρε: Βίδα έκκεντρου, αριστερά σου." / "Take: Cam bolt, on your left." ([where] null → the plain name). */
  fun line(lang: String, name: String, where: String?): String =
      if (where.isNullOrBlank()) Strings.t(lang, "pickSayPlain", "name" to name)
      else Strings.t(lang, "pickSay", "name" to name, "where" to where.trim().trimEnd('.'))

  /** The card's count line: the server's `count_text` when known, else "Χρειάζεσαι N". */
  fun countLine(lang: String, needed: Int?, countText: String?, seen: Int?): String? =
      when {
        !countText.isNullOrBlank() -> countText
        needed != null && seen != null -> Strings.t(lang, "inViewN", "seen" to seen, "n" to needed)
        needed != null && needed > 0 -> Strings.t(lang, "needN", "n" to needed)
        else -> null
      }
}

/** What the badge says and what is spoken, per verdict (pure; the copy is in [Strings]). */
object HeldCopy {
  /** "Βίδα έκκεντρου 118331": the name, then the hardware code when there is one. */
  fun nameLine(l: PartLabel?): String = l?.let { listOfNotNull(it.name.takeIf { n -> n.isNotBlank() }, it.code).joinToString(" ") } ?: ""

  /**
   * ✓ "Σωστό" + the part; ✕ "Λάθος εξάρτημα" + "Χρειάζεσαι: <the needed part>"; ? "Δείξε μου το εξάρτημα πιο κοντά".
   * [need] is the needed part's [nameLine] (empty: no detail line).
   */
  fun badge(lang: String, verdict: Verdict, need: String): VerdictBadgeState =
      when (verdict) {
        Verdict.RIGHT -> VerdictBadgeState(StatusKind.SUCCESS, Strings.t(lang, "heldRight"), need.ifBlank { null })
        Verdict.WRONG ->
            VerdictBadgeState(StatusKind.ERROR, Strings.t(lang, "heldWrong"), need.takeIf { it.isNotBlank() }?.let { Strings.t(lang, "heldNeed", "name" to it) })
        Verdict.UNSURE -> VerdictBadgeState(StatusKind.UNSURE, Strings.t(lang, "heldUnsure"))
      }

  /** While a check the person asked for is being answered. */
  fun checking(lang: String): VerdictBadgeState = VerdictBadgeState(StatusKind.INFO, Strings.t(lang, "heldChecking"))

  /** "Σωστό. Βίδα έκκεντρου." (no code aloud); the plain "Σωστό." without a name. */
  fun rightSay(lang: String, name: String?): String =
      if (name.isNullOrBlank()) Strings.t(lang, "heldRightSayPlain") else Strings.t(lang, "heldRightSay", "name" to name.trim())

  /** The spoken line: a short "Σωστό. <name>.", the server's reason for a wrong part, the ask to show it closer. */
  fun spoken(lang: String, ev: HeldEvent, name: String? = null): String =
      when (ev.verdict) {
        Verdict.RIGHT -> rightSay(lang, name)
        Verdict.WRONG -> ev.say ?: Strings.t(lang, "wrongSay")
        Verdict.UNSURE -> if (ev.event == TIMEOUT) ev.say ?: Strings.t(lang, "heldNoAnswer") else Strings.t(lang, "heldUnsureSay")
      }

  /** The local "no answer came" verdict of a manual check (no `show_part_result` in time, or no session). */
  const val TIMEOUT = "show_part_timeout"
}

/** "Is this right?" said into the mic: the transcript (any intent) or the server's `identify` intent. */
object CheckPhrase {
  private val patterns =
      listOf(
          Regex("σωστ[οόήηάα]"), // είναι σωστό αυτό; / το σωστό; / σωστή βίδα;
          Regex("(έλεγξ|ελεγξ|τσέκαρ|τσεκαρ)"),
          Regex("\\b(is|am|are)\\b.*\\b(right|correct)\\b"),
          Regex("\\b(right|correct)\\s+(one|part|screw|piece)\\b"),
          Regex("\\bcheck\\s+(this|it|the part)\\b"),
      )

  /** "Which is the right screw?" is a question for the guide, not a check of the hand. */
  private val whQuestion = Regex("^\\s*(ποι|τι\\s|πού|που\\s|πόσ|ποσ|which|what|where|how)")

  fun matches(transcript: String, intent: String): Boolean {
    if (intent == "identify") return true
    if (intent in setOf("next", "back", "repeat", "where_am_i")) return false
    val t = transcript.lowercase()
    if (whQuestion.containsMatchIn(t)) return false
    return patterns.any { it.containsMatchIn(t) }
  }
}
