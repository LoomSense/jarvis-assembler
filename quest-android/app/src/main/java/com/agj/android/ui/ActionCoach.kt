package com.agj.android.ui

import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.PartLabel

/*
 * ACTION-COACH: every action of the guide in two phases, "take this part" (PICK) then "put it here" (PLACE), one
 * instruction on screen at a time. Pure Kotlin, JVM-tested (ActionCoachTest); MainActivity feeds it and draws the
 * result (the `ACTION-COACH` hunks), logging AGQ `coach_phase` on every change.
 *
 *   PICK  → PLACE  the server confirms the right part in the hand ([ActionCoach.rightPart]); the part to take is no
 *                  longer where it lay ([ActionCoach.partMoved], or [ActionCoach.tick]: a hand closed at the part
 *                  and carried it off, or its pick target moved or vanished); Next or "επόμενο"
 *                  ([ActionCoach.next]); the hand has been closed for 12 s (`timer`).
 *   PLACE → DONE   the server's `step_complete` on the step's last action; the hand was at the target and then left
 *                  it, or went for the next part (`placed`, [ActionCoach.tick]); Next or "επόμενο". With a hand seen
 *                  during the action it is never a bare timer: only that hand, the server or the wearer ends it.
 *                  With no hand seen at all during the action (the headset gives none): `timer`, how `no_hands`,
 *                  once the phase has lasted the time its kind needs and the part taken has not been back where it
 *                  lay for 4 s ([ActionCoach.Config.noHandsGoneMs], [ActionCoach.noHandsMs]).
 *   PICK  → DONE   only the server's `step_complete` on the step's last action (the step advances once).
 *   A turn has nothing to take: it starts at PLACE. Back and Repeat start the action again at PICK ([ActionCoach.start]).
 *   A wrong part in the hand ([ActionCoach.wrongPart]) never moves the phase and holds the automatic signals back.
 */

enum class CoachPhase(val wire: String) {
  PICK("pick"),
  PLACE("place"),
  DONE("done"),
}

/** One phase change, for the log and the screen. [from] is null on the very first action. */
data class CoachChange(val step: Int, val action: Int, val from: CoachPhase?, val to: CoachPhase, val why: String)

/** One instance of the item to take, where the world module has it now (`PickTarget.key`, `worldPos`). */
data class PickSpot(val key: Int, val pos: Vec3)

/** What is known this frame about the part to take and the hand. */
data class PickSight(
    /** Every live pick target of the current action's item. */
    val spots: List<PickSpot> = emptyList(),
    /** The wrist in WORLD; null = no hand data now (hand tracking off, or no hand in view). */
    val hand: Vec3? = null,
    /** A hand is closed on something (`HeldState.holding`). */
    val holding: Boolean = false,
    /** The clock time at which [holding] took its value (`HeldState.sinceMs`). */
    val holdingSinceMs: Long = 0L,
    /** The wearer faces where the part lay: a part that is merely out of view is not "gone". */
    val looking: Boolean = true,
    /** Where the wrist was when the grip that is closed now began; null = not holding, or not known. */
    val gripStart: Vec3? = null,
    /** PLACE: a tracked hand (wrist or a fingertip) is at the target now. */
    val atTarget: Boolean = false,
    /** Some hand is tracked now (false: nothing is known about the hands this frame). */
    val tracked: Boolean = hand != null,
    /** PLACE: where the next action's part lies (its pick targets), for "the hand went for the next part". */
    val nextSpots: List<Vec3> = emptyList(),
    /**
     * PLACE: working at the target long enough ends the action without the hand having to leave it: a fastener
     * turned with a tool, or a board that a hand keeps steadying while the next fastener goes in (the drawer's side
     * panels and their cam locks: the hand never goes "away" between them).
     */
    val dwellEnds: Boolean = false,
)

/**
 * The phase of the action shown now. One clock for every call (`SystemClock.elapsedRealtime()` in the app). Each
 * entry point returns the change it made, or null when it made none. Not thread-safe (main thread).
 */
class ActionCoach(private val cfg: Config = Config()) {
  class Config(
      /** After a phase starts, the server and the sensors cannot move it for this long (stale and doubled events). */
      val settleMs: Long = 600,
      /** A second Next within this of a phase change is the same pinch. */
      val tapMs: Long = 400,
      /** PICK ends by itself when a hand has been closed this long. */
      val holdMs: Long = 12_000,
      /** With no hand data: the part must be gone for this long. */
      val vanishMs: Long = 1_500,
      /** With a hand closed or near: the part must be gone or moved for this long (one noisy frame is not enough). */
      val confirmMs: Long = 300,
      /** "Moved clearly from where it lay", metres. */
      val movedM: Float = 0.12f,
      /** A new track this close to where a part lay is that same part. */
      val sameM: Float = 0.06f,
      /** The wrist counts as near the part within this (the wrist is 10–18 cm behind the fingertips). */
      val nearM: Float = 0.30f,
      /** After a wrong part is reported, the automatic signals wait this long. */
      val wrongMs: Long = 6_000,
      /** PICK: a hand that closed at the part and has moved this far since has taken it. */
      val carryM: Float = 0.20f,
      /** PLACE: a hand must have been at the target this long in all… */
      val dwellMs: Long = 1_200,
      /** …and, where [PickSight.dwellEnds] is set, this long at the target ends the action by itself. */
      val dwellEndsMs: Long = 4_500,
      /** …and then every tracked hand away from it this long (the step's last action waits [awayLastMs]). */
      val awayMs: Long = 2_000,
      /** On the step's last action the server's `step_complete` gets this long to say it first. */
      val awayLastMs: Long = 4_000,
      /** No hand seen during the action: PLACE lasts at least this for a bolt, a screw, a cam… */
      val noHandsTurnMs: Long = 9_000,
      /** … this for what is pushed in (a dowel, a pin, a peg)… */
      val noHandsPushMs: Long = 6_000,
      /** … and this for a board, and for an action with nothing to take (a fastener already in its part, a turn). */
      val noHandsBoardMs: Long = 12_000,
      /** No hand seen: the part taken must not have been back where it lay for this long. */
      val noHandsGoneMs: Long = 4_000,
  )

  var step = 0
    private set

  var action = 0
    private set

  /** DONE until the first [start]. */
  var phase = CoachPhase.DONE
    private set

  /** The action has a part to take (a board to place, hardware to fasten); false: it started at PLACE. */
  var hasPick = false
    private set

  var lastOfStep = false
    private set

  /**
   * What the last change saw (for the log): from [tick] `grab`, `moved`, `gone`, `held`, `away`, `next_pick`,
   * `no_hands`; from [rightPart] `grab_rect` when the device itself saw the board lifted.
   */
  var how: String? = null
    private set

  private var started = false
  private var tickMs = 0L
  private var dwellMs = 0L // PLACE: time a hand has been at the target, in all
  private var awayMs = 0L // PLACE: time since then with tracked hands and none at the target
  private var leftAtMs: Long? = null
  private var wanted: Set<String> = emptySet()
  private var sinceMs = FAR_PAST
  private var wrongAtMs = FAR_PAST
  private val rest = LinkedHashMap<Int, Vec3>() // where each instance lay when it was first seen
  private var leftSinceMs: Long? = null
  private var handSeen = false // a hand was tracked at some time during this action
  private var noHandsMinMs = 0L // no hand seen: how long PLACE must last for this action
  private var backAtMs = FAR_PAST // no hand seen: when every instance was last where it lay (nothing taken, or put back)

  /**
   * The action shown now: PICK when it has a part to take ([kind] place / fasten and [ids] not empty), else PLACE.
   * Called for every step or action change, and again for Back and Repeat, which is how those restart at PICK.
   */
  fun start(
      step: Int,
      action: Int,
      kind: ActionKind?,
      ids: Collection<String>,
      lastOfStep: Boolean,
      nowMs: Long,
      why: String,
      /** Manifest `hardware[].kind` of the fastener (bolt, cam, dowel…), for [noHandsMs]; null for a board. */
      hardwareKind: String? = null,
  ): CoachChange {
    val from = if (started) phase else null
    started = true
    this.step = step
    this.action = action
    this.lastOfStep = lastOfStep
    wanted = ids.filter { it.isNotBlank() }.toSet()
    hasPick = (kind == ActionKind.PLACE || kind == ActionKind.FASTEN) && wanted.isNotEmpty()
    phase = if (hasPick) CoachPhase.PICK else CoachPhase.PLACE
    sinceMs = nowMs
    wrongAtMs = FAR_PAST
    rest.clear()
    leftSinceMs = null
    dwellMs = 0
    awayMs = 0
    leftAtMs = null
    how = null
    handSeen = false
    backAtMs = FAR_PAST
    noHandsMinMs = noHandsMs(kind, hardwareKind, hasPick, cfg)
    return CoachChange(step, action, from, phase, why)
  }

  /**
   * The right part is in the hand: the server saw it (`right_part`, `right_hardware`, a `show_part_result` that
   * matched), or the device did ([how] `grab_rect`: the hand lifted the board's own rectangle, ui/GrabJudge.kt).
   */
  fun rightPart(nowMs: Long, id: String? = null, how: String? = null): CoachChange? {
    if (phase != CoachPhase.PICK || !settled(nowMs) || !mine(id)) return null
    wrongAtMs = FAR_PAST // the wrong part was put down
    return move(CoachPhase.PLACE, "right_part", nowMs, how)
  }

  /** The server saw a wrong part in the hand: the phase stays, and `part_moved` / `timer` wait [Config.wrongMs]. */
  fun wrongPart(nowMs: Long) {
    if (phase != CoachPhase.DONE) wrongAtMs = nowMs
  }

  /** A wrong part was reported less than [Config.wrongMs] ago (the right part's marker stays up meanwhile). */
  fun wrongFresh(nowMs: Long): Boolean = phase != CoachPhase.DONE && nowMs - wrongAtMs < cfg.wrongMs

  /** The Next button, or "επόμενο" said aloud ([voice]): one phase forward, never more. */
  fun next(nowMs: Long, voice: Boolean = false): CoachChange? {
    if (phase == CoachPhase.DONE || nowMs - sinceMs < cfg.tapMs) return null
    return move(if (phase == CoachPhase.PICK) CoachPhase.PLACE else CoachPhase.DONE, if (voice) "voice" else "next", nowMs)
  }

  /**
   * The server's `step_complete`. It ends the action only on the step's last action (in either phase, once); on
   * any earlier action it is ignored, so it cannot skip the actions still to be done.
   */
  fun stepComplete(nowMs: Long): CoachChange? {
    if (phase == CoachPhase.DONE || !lastOfStep || !settled(nowMs)) return null
    return move(CoachPhase.DONE, "step_complete", nowMs)
  }

  /** A server `action_complete` (not sent today): this one action is done. */
  fun actionComplete(nowMs: Long): CoachChange? {
    if (phase == CoachPhase.DONE || !settled(nowMs)) return null
    return move(CoachPhase.DONE, "action_complete", nowMs)
  }

  /**
   * "The part to pick is no longer where it lay", said by whoever watches the real part ([id]: its part id or
   * hardware code, null = the current action's). This is the hook for the on-device rectangles.
   */
  fun partMoved(nowMs: Long, id: String? = null): CoachChange? {
    if (phase != CoachPhase.PICK || !settled(nowMs) || wrongFresh(nowMs) || !mine(id)) return null
    return move(CoachPhase.PLACE, "part_moved", nowMs)
  }

  /**
   * Every frame while guiding. In PICK: `part_moved` when a pick target of the item has moved more than
   * [Config.movedM] from where it lay, or is gone, while a hand is closed or near it; with no hand data, when it
   * has been gone for [Config.vanishMs] while the wearer faces the spot. `timer` when a hand has been closed for
   * [Config.holdMs] in this phase. In PLACE: see [placed], and [noHands] while no hand has been seen in this action.
   */
  fun tick(nowMs: Long, sight: PickSight): CoachChange? {
    val dt = (nowMs - tickMs).coerceIn(0L, 100L) // a gap between ticks is not time spent at the target
    tickMs = nowMs
    if (sight.tracked || sight.hand != null || sight.holding) handSeen = true
    if (phase == CoachPhase.PLACE) return placed(nowMs, dt, sight)
    if (phase != CoachPhase.PICK) return null
    for (s in sight.spots) if (s.key !in rest && rest.values.none { (it - s.pos).length() < cfg.sameM }) rest[s.key] = s.pos
    val hand = sight.hand
    // the hand closed where the part lay and has carried it off: nothing more to wait for
    val grip = sight.gripStart
    if (sight.holding && hand != null && grip != null && (hand - grip).length() > cfg.carryM &&
        rest.values.any { (grip - it).length() < cfg.nearM } && settled(nowMs) && !wrongFresh(nowMs))
        return move(CoachPhase.PLACE, "part_moved", nowMs, "grab")
    var left = false
    var goneNow = false
    var handThere = sight.holding
    for ((key, at) in rest) {
      val cur = sight.spots.firstOrNull { it.key == key }
      val gone = cur == null && sight.looking && sight.spots.none { (it.pos - at).length() < cfg.sameM }
      val moved = cur != null && (cur.pos - at).length() > cfg.movedM
      if (!gone && !(moved && hand != null)) continue // with no hand data only "gone" counts
      left = true
      if (gone) goneNow = true
      if (hand != null && ((hand - at).length() < cfg.nearM || (cur != null && (hand - cur.pos).length() < cfg.nearM))) handThere = true
    }
    if (left && (hand == null || handThere)) {
      val since = leftSinceMs ?: nowMs.also { leftSinceMs = it }
      val need = if (hand == null) cfg.vanishMs else cfg.confirmMs
      if (nowMs - since >= need && settled(nowMs) && !wrongFresh(nowMs))
          return move(CoachPhase.PLACE, "part_moved", nowMs, if (goneNow) "gone" else "moved")
    } else leftSinceMs = null
    if (sight.holding && nowMs - maxOf(sight.holdingSinceMs, sinceMs, wrongAtMs) >= cfg.holdMs)
        return move(CoachPhase.PLACE, "timer", nowMs, "held")
    return null
  }

  /**
   * PLACE, every frame: `placed` once a hand has been at the target for [Config.dwellMs] in all and then either
   * every tracked hand has been away from it for [Config.awayMs], or a hand closed anew where the next part lies.
   * Frames without a tracked hand count for nothing (a hand hidden behind the furniture has not left it).
   */
  private fun placed(nowMs: Long, dt: Long, sight: PickSight): CoachChange? {
    if (!handSeen) return noHands(nowMs, sight)
    if (sight.atTarget) {
      dwellMs += dt
      awayMs = 0
      leftAtMs = null
    } else if (sight.tracked) {
      awayMs += dt
      if (leftAtMs == null) leftAtMs = nowMs
    }
    if (sight.dwellEnds && dwellMs >= cfg.dwellEndsMs && !wrongFresh(nowMs)) return move(CoachPhase.DONE, "placed", nowMs, "dwell")
    val leftAt = leftAtMs
    if (dwellMs < cfg.dwellMs || leftAt == null || wrongFresh(nowMs)) return null
    if (awayMs >= (if (lastOfStep) cfg.awayLastMs else cfg.awayMs)) return move(CoachPhase.DONE, "placed", nowMs, "away")
    val hand = sight.hand
    if (sight.holding && sight.holdingSinceMs > leftAt && hand != null && sight.nextSpots.any { (hand - it).length() < cfg.nearM })
        return move(CoachPhase.DONE, "placed", nowMs, "next_pick")
    return null
  }

  /**
   * PLACE with no hand seen at all during this action (the headset gives no hands: nothing can show the work being
   * done): `timer`, how `no_hands`, once the phase has lasted [noHandsMs] and, for an item that was taken, no
   * instance of it has been back where it lay for [Config.noHandsGoneMs] (every instance where it lay = nothing was
   * taken, or it was put back). Never within [Config.wrongMs] of a wrong verdict. A hand seen once switches this
   * off for the action ([placed] decides then); Back and Repeat start it again ([start]).
   */
  private fun noHands(nowMs: Long, sight: PickSight): CoachChange? {
    if (rest.isNotEmpty() && rest.values.all { at -> sight.spots.any { (it.pos - at).length() < cfg.sameM } }) backAtMs = nowMs
    if (nowMs - sinceMs < noHandsMinMs || nowMs - maxOf(backAtMs, sinceMs) < cfg.noHandsGoneMs || wrongFresh(nowMs)) return null
    return move(CoachPhase.DONE, "timer", nowMs, "no_hands")
  }

  private fun mine(id: String?) = id == null || wanted.isEmpty() || id in wanted

  private fun settled(nowMs: Long) = nowMs - sinceMs >= cfg.settleMs

  private fun move(to: CoachPhase, why: String, nowMs: Long, how: String? = null): CoachChange {
    val c = CoachChange(step, action, phase, to, why)
    phase = to
    sinceMs = nowMs
    leftSinceMs = null
    dwellMs = 0
    awayMs = 0
    leftAtMs = null
    this.how = how
    return c
  }

  companion object {
    private const val FAR_PAST = Long.MIN_VALUE / 2

    /** Hardware that is pushed in, not turned (the same kinds as `Guidance.PUSH_KINDS`). */
    private val PUSHED = setOf("dowel", "pin", "nail", "plug", "peg", "cap", "rivet")

    /**
     * With no hand seen: how long PLACE must have lasted before it may end by itself. A board 12 s; what is turned
     * in (bolt, screw, cam) 9 s; what is pushed in (dowel, pin, peg) 6 s; an action with nothing to take (a fastener
     * already in its part, a turn) 12 s.
     */
    fun noHandsMs(kind: ActionKind?, hardwareKind: String?, hasPick: Boolean, cfg: Config = Config()): Long =
        when {
          !hasPick || kind != ActionKind.FASTEN -> cfg.noHandsBoardMs
          hardwareKind?.lowercase() in PUSHED -> cfg.noHandsPushMs
          else -> cfg.noHandsTurnMs
        }
  }
}

/** What the step window shows for a phase: the two-dot indicator, one title, one body line, the primary button. */
data class CoachView(
    /** 1 = "Πάρε" is the lit dot, 2 = "Βάλε". */
    val dot: Int,
    val title: String,
    val body: String,
    /** "1 από 2" beside the dots while placing (in PICK the body line carries it). */
    val count: String,
    val next: String,
)

/** The copy of the two phases (pure; the strings are in [Strings]). */
object CoachLines {
  /**
   * PICK: "Πάρε: Βίδα έκκεντρου" over "118331 · 1 από 2", button "Το πήρα". PLACE: "Βάλ' το εδώ" over the guide's
   * own instruction [text], the count beside the dots, button Next (or Finish on the guide's [last] action).
   */
  fun view(lang: String, phase: CoachPhase, label: PartLabel, text: String, last: Boolean): CoachView =
      if (phase == CoachPhase.PICK)
          CoachView(1, Strings.t(lang, "coachPick", "name" to label.name), listOfNotNull(label.code, label.count).joinToString(" · "), "",
              Strings.t(lang, "coachGotIt"))
      else CoachView(2, Strings.t(lang, "coachPlace"), text, label.count ?: "", Strings.t(lang, if (last) "finish" else "next"))

  /** Spoken on entering PICK: "Πάρε: Βίδα έκκεντρου, αριστερά σου." ([where] null: the part is not found yet). */
  fun pickSay(lang: String, label: PartLabel, where: String?): String = PickPrompt.line(lang, label.name, where)
}
