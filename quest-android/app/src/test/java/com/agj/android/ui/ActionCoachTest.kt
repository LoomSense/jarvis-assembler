package com.agj.android.ui

import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.PartLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACTION-COACH: the two phases of an action ("take this part" → "put it here") as a pure state machine: a board,
 * hardware with repeats, a turn, a wrong part, doubled and out-of-order events, Back and Repeat, the signals that
 * end "take" with and without hand tracking, and the copy in both languages.
 */
class ActionCoachTest {
  private val coach = ActionCoach()
  private var now = 100_000L
  private val changes = ArrayList<CoachChange>()

  private fun at(dtMs: Long): Long {
    now += dtMs
    return now
  }

  private fun keep(c: CoachChange?): CoachChange? = c?.also { changes += it }

  private fun board(last: Boolean = true, why: String = "next") =
      keep(coach.start(2, 0, ActionKind.PLACE, listOf("side_left"), last, now, why))!!

  private fun bolt(action: Int, last: Boolean, why: String = "next") =
      keep(coach.start(3, action, ActionKind.FASTEN, listOf("118331"), last, now, why))!!

  private val lay = Vec3(0.3f, 0.75f, -0.5f)
  private val wristAtPart = Vec3(0.32f, 0.85f, -0.42f)
  private val wristAway = Vec3(-0.4f, 1.1f, -0.2f)

  // ---- a board ----

  @Test
  fun boardGoesPickThenPlaceThenDone() {
    val c = board()
    assertNull("the first action has no phase before it", c.from)
    assertEquals(CoachPhase.PICK, c.to)
    assertTrue(coach.hasPick)
    val toPlace = keep(coach.rightPart(at(3000), "side_left"))!!
    assertEquals(CoachChange(2, 0, CoachPhase.PICK, CoachPhase.PLACE, "right_part"), toPlace)
    val done = keep(coach.stepComplete(at(5000)))!!
    assertEquals(CoachChange(2, 0, CoachPhase.PLACE, CoachPhase.DONE, "step_complete"), done)
    assertEquals(listOf("next", "right_part", "step_complete"), changes.map { it.why })
  }

  @Test
  fun nextMovesOnePhasePerPinch() {
    board()
    assertNull("the same pinch, 100 ms after the action appeared", coach.next(at(100)))
    assertEquals(CoachPhase.PLACE, coach.next(at(900))!!.to)
    assertNull("a doubled click does not run through the placing phase", coach.next(at(150)))
    assertEquals(CoachPhase.PLACE, coach.phase)
    val done = coach.next(at(800))!!
    assertEquals(CoachPhase.DONE, done.to)
    assertEquals("next", done.why)
    assertNull("nothing follows DONE until the next action starts", coach.next(at(2000)))
  }

  @Test
  fun voiceNextIsLoggedAsVoice() {
    board()
    assertEquals("voice", coach.next(at(1000), voice = true)!!.why)
    assertEquals("voice", coach.next(at(1000), voice = true)!!.why)
    assertEquals(CoachPhase.DONE, coach.phase)
  }

  @Test
  fun placeNeverEndsByATimerOrByThePartMoving() {
    board()
    coach.next(at(1000))
    val held = PickSight(emptyList(), wristAtPart, holding = true, holdingSinceMs = now - 60_000)
    repeat(200) { assertNull(coach.tick(at(500), held)) }
    assertNull(coach.partMoved(at(100), "side_left"))
    assertNull(coach.rightPart(at(100), "side_left"))
    assertEquals(CoachPhase.PLACE, coach.phase)
  }

  // ---- hardware with repeats: two cam bolts ----

  @Test
  fun twoCamBoltsEachGoPickPlaceDone() {
    bolt(0, last = false)
    assertEquals(CoachPhase.PLACE, keep(coach.rightPart(at(2500), "118331"))!!.to)
    assertNull("step_complete cannot skip the second bolt", coach.stepComplete(at(1500)))
    assertEquals(CoachPhase.DONE, keep(coach.next(at(1500)))!!.to)
    val second = bolt(1, last = true)
    assertEquals(CoachPhase.DONE, second.from)
    assertEquals(CoachPhase.PICK, second.to)
    assertEquals(CoachPhase.PLACE, keep(coach.next(at(2000)))!!.to)
    assertEquals(CoachPhase.DONE, keep(coach.stepComplete(at(4000)))!!.to)
    assertEquals(
        listOf("0:pick", "0:place", "0:done", "1:pick", "1:place", "1:done"),
        changes.map { "${it.action}:${it.to.wire}" },
    )
  }

  @Test
  fun aConfirmationLeftOverFromTheBoltBeforeDoesNotEndTheNewPick() {
    bolt(0, last = false)
    coach.rightPart(at(2000), "118331")
    coach.next(at(1000))
    bolt(1, last = true)
    assertNull("still in flight from the first bolt", coach.rightPart(at(200), "118331"))
    assertEquals(CoachPhase.PICK, coach.phase)
    assertEquals("a fresh one, once the hand had time to take the second", CoachPhase.PLACE, coach.rightPart(at(1500), "118331")!!.to)
  }

  @Test
  fun aConfirmationAboutAnotherItemIsNotThisPick() {
    bolt(0, last = false)
    assertNull(coach.rightPart(at(2000), "101345"))
    assertNull(coach.partMoved(at(100), "side_left"))
    assertEquals(CoachPhase.PICK, coach.phase)
    assertEquals("an event that names no item is about the action shown", CoachPhase.PLACE, coach.rightPart(at(100), null)!!.to)
  }

  // ---- a turn ----

  @Test
  fun aTurnHasNothingToTake() {
    val c = coach.start(4, 0, ActionKind.TURN, emptyList(), true, now, "next")
    assertEquals(CoachPhase.PLACE, c.to)
    assertFalse(coach.hasPick)
    assertNull(coach.rightPart(at(2000), null))
    assertNull(coach.tick(at(20_000), PickSight(holding = true, holdingSinceMs = now - 30_000)))
    assertEquals(CoachPhase.DONE, coach.next(at(1000))!!.to)
  }

  @Test
  fun anActionWithoutAPartStartsAtPlace() {
    assertEquals(CoachPhase.PLACE, coach.start(5, 1, ActionKind.FASTEN, emptyList(), false, now, "next").to)
    assertEquals(CoachPhase.PLACE, coach.start(5, 2, ActionKind.PRESS, listOf("drawer_bottom"), false, now, "next").to)
  }

  // ---- a wrong part ----

  @Test
  fun aWrongPartKeepsThePhaseInPickAndInPlace() {
    board()
    coach.wrongPart(at(2000))
    assertEquals(CoachPhase.PICK, coach.phase)
    assertTrue(coach.wrongFresh(now))
    // the hand is closed on the wrong board: neither the timer nor "it moved" may read that as the right pick
    val sight = PickSight(emptyList(), wristAtPart, holding = true, holdingSinceMs = now - 20_000)
    assertNull(coach.tick(at(1000), sight))
    assertNull(coach.partMoved(at(1000), "side_left"))
    coach.wrongPart(at(1000)) // said again while it is still held
    repeat(20) { assertNull(coach.tick(at(500), sight.copy(holdingSinceMs = 0))) }
    assertEquals(CoachPhase.PICK, coach.phase)
    // the right one is confirmed: on to placing; a wrong verdict there holds the phase too
    assertEquals(CoachPhase.PLACE, coach.rightPart(at(500), "side_left")!!.to)
    coach.wrongPart(at(2000))
    assertEquals(CoachPhase.PLACE, coach.phase)
    assertNull(coach.tick(at(30_000), sight))
  }

  @Test
  fun theTimerStartsAgainAfterAWrongPart() {
    board()
    val sight = PickSight(emptyList(), wristAway, holding = true, holdingSinceMs = now)
    assertNull(coach.tick(at(8000), sight))
    coach.wrongPart(now)
    assertNull("12 s since the hand closed, but only 5 s since the wrong verdict", coach.tick(at(5000), sight))
    assertEquals("timer", coach.tick(at(7100), sight)!!.why)
  }

  // ---- doubled and out-of-order events ----

  @Test
  fun doubledEventsAdvanceOnce() {
    board()
    assertEquals(CoachPhase.PLACE, coach.rightPart(at(2000), "side_left")!!.to)
    assertNull("step_complete right behind the confirmation: one event, one phase", coach.stepComplete(at(100)))
    assertNull(coach.rightPart(at(10), "side_left"))
    assertNull(coach.rightPart(at(3000), "side_left"))
    assertEquals(CoachPhase.PLACE, coach.phase)
    assertEquals(CoachPhase.DONE, coach.stepComplete(at(2000))!!.to)
    assertNull(coach.stepComplete(at(10)))
    assertNull(coach.stepComplete(at(5000)))
    assertNull(coach.actionComplete(at(10)))
  }

  @Test
  fun stepCompleteDuringPickOfTheLastActionAdvancesTheStepOnce() {
    board(last = true)
    val c = coach.stepComplete(at(4000))!!
    assertEquals(CoachChange(2, 0, CoachPhase.PICK, CoachPhase.DONE, "step_complete"), c)
    // what arrives late about the action that is over changes nothing
    assertNull(coach.rightPart(at(50), "side_left"))
    assertNull(coach.partMoved(at(50), "side_left"))
    assertNull(coach.next(at(1000)))
    assertNull(coach.tick(at(20_000), PickSight(holding = true, holdingSinceMs = 0)))
    assertEquals(CoachPhase.DONE, coach.phase)
  }

  @Test
  fun stepCompleteOnAnEarlierActionIsIgnoredInBothPhases() {
    board(last = false)
    assertNull(coach.stepComplete(at(4000)))
    coach.next(at(100))
    assertNull(coach.stepComplete(at(4000)))
    assertEquals(CoachPhase.PLACE, coach.phase)
  }

  @Test
  fun aLateStepCompleteDoesNotEndTheNextStepsFirstAction() {
    board(last = true)
    coach.stepComplete(at(4000))
    coach.start(3, 0, ActionKind.PLACE, listOf("back_panel"), true, at(1400), "step_complete")
    assertNull("the same step_complete, sent twice", coach.stepComplete(at(200)))
    assertEquals(CoachPhase.PICK, coach.phase)
  }

  // ---- Back and Repeat ----

  @Test
  fun backAndRepeatRestartTheActionAtPick() {
    board()
    coach.next(at(1000))
    assertEquals(CoachPhase.PLACE, coach.phase)
    val again = coach.start(2, 0, ActionKind.PLACE, listOf("side_left"), true, at(500), "repeat")
    assertEquals(CoachChange(2, 0, CoachPhase.PLACE, CoachPhase.PICK, "repeat"), again)
    coach.next(at(1000))
    coach.next(at(1000))
    assertEquals(CoachPhase.DONE, coach.phase)
    val back = coach.start(2, 0, ActionKind.PLACE, listOf("side_left"), true, at(500), "back")
    assertEquals(CoachPhase.DONE, back.from)
    assertEquals(CoachPhase.PICK, back.to)
    assertEquals("back", back.why)
    assertNull("a pinch still down from Back", coach.next(at(100)))
  }

  @Test
  fun aRestartForgetsTheWrongVerdictAndWhereThePartLay() {
    board()
    coach.wrongPart(at(1000))
    coach.tick(at(100), PickSight(listOf(PickSpot(7, lay)), wristAway))
    board(why = "repeat")
    assertFalse(coach.wrongFresh(now))
    // the part was taken away before the repeat: nothing lay there in this pick, so nothing is "gone"
    repeat(10) { assertNull(coach.tick(at(500), PickSight(emptyList(), null))) }
  }

  // ---- the part leaves where it lay ----

  @Test
  fun thePartGoneWithAHandNearEndsPick() {
    board()
    assertNull(coach.tick(at(1000), PickSight(listOf(PickSpot(7, lay)), wristAway)))
    assertNull("gone, the hand is at it: one frame is not enough", coach.tick(at(100), PickSight(emptyList(), wristAtPart)))
    val c = coach.tick(at(350), PickSight(emptyList(), wristAtPart))!!
    assertEquals(CoachChange(2, 0, CoachPhase.PICK, CoachPhase.PLACE, "part_moved"), c)
  }

  @Test
  fun thePartMovedClearlyWithAClosedHandEndsPick() {
    board()
    coach.tick(at(1000), PickSight(listOf(PickSpot(7, lay)), wristAway))
    val nudged = PickSight(listOf(PickSpot(7, lay + Vec3(0.05f, 0f, 0f))), wristAway, holding = true, holdingSinceMs = now)
    repeat(5) { assertNull("5 cm is not clearly moved", coach.tick(at(200), nudged)) }
    val lifted = nudged.copy(spots = listOf(PickSpot(7, lay + Vec3(0.1f, 0.2f, 0.1f))))
    assertNull(coach.tick(at(100), lifted))
    assertEquals("part_moved", coach.tick(at(350), lifted)!!.why)
  }

  @Test
  fun aPartThatMovesWithNoHandNearItIsNotAPick() {
    board()
    coach.tick(at(1000), PickSight(listOf(PickSpot(7, lay)), wristAway))
    val drifted = PickSight(listOf(PickSpot(7, lay + Vec3(0.4f, 0f, 0f))), wristAway)
    repeat(20) { assertNull(coach.tick(at(500), drifted)) }
    assertEquals(CoachPhase.PICK, coach.phase)
  }

  @Test
  fun withoutHandTrackingThePartMustBeGoneForOneAndAHalfSeconds() {
    board()
    coach.tick(at(1000), PickSight(listOf(PickSpot(7, lay)), null))
    assertNull(coach.tick(at(100), PickSight(emptyList(), null)))
    assertNull(coach.tick(at(1300), PickSight(emptyList(), null)))
    assertEquals("part_moved", coach.tick(at(200), PickSight(emptyList(), null))!!.why)
  }

  @Test
  fun withoutHandTrackingABlinkOrALookAwayOrAMoveIsNotAPick() {
    board()
    val there = PickSight(listOf(PickSpot(7, lay)), null)
    coach.tick(at(1000), there)
    // a blink: gone for a second, then seen again where it lay under a new track key
    assertNull(coach.tick(at(1000), PickSight(emptyList(), null)))
    assertNull(coach.tick(at(100), PickSight(listOf(PickSpot(8, lay + Vec3(0.02f, 0f, 0.01f))), null)))
    assertNull(coach.tick(at(3000), PickSight(listOf(PickSpot(8, lay + Vec3(0.02f, 0f, 0.01f))), null)))
    // the wearer looks at the furniture: the part is out of the picture, not taken
    repeat(10) { assertNull(coach.tick(at(500), PickSight(emptyList(), null, looking = false))) }
    // a tracked point that wanders, with no hand to explain it
    coach.tick(at(100), there)
    repeat(10) { assertNull(coach.tick(at(500), PickSight(listOf(PickSpot(7, lay + Vec3(0.3f, 0f, 0f))), null))) }
    assertEquals(CoachPhase.PICK, coach.phase)
  }

  @Test
  fun oneOfSeveralScrewsTakenEndsPick() {
    bolt(0, last = false)
    val pile = listOf(PickSpot(1, lay), PickSpot(2, lay + Vec3(0.09f, 0f, 0f)), PickSpot(3, lay + Vec3(0f, 0f, 0.09f)))
    coach.tick(at(1000), PickSight(pile, wristAway))
    val taken = PickSight(pile.drop(1), wristAtPart, holding = true, holdingSinceMs = now)
    assertNull(coach.tick(at(100), taken))
    assertEquals("part_moved", coach.tick(at(350), taken)!!.why)
  }

  @Test
  fun aHandClosedForTwelveSecondsEndsPick() {
    board()
    // the hand was already closed when the action appeared: the 12 s count from the action
    val sight = PickSight(emptyList(), wristAway, holding = true, holdingSinceMs = now - 30_000)
    assertNull(coach.tick(at(11_900), sight))
    assertEquals(CoachChange(2, 0, CoachPhase.PICK, CoachPhase.PLACE, "timer"), coach.tick(at(200), sight))
  }

  @Test
  fun anOpenHandNeverTimesOut() {
    board()
    repeat(100) { assertNull(coach.tick(at(1000), PickSight(emptyList(), wristAway, holding = false))) }
    // closed just now: 12 s from then, not from the action
    val closedAt = now
    assertNull(coach.tick(at(11_000), PickSight(emptyList(), wristAway, holding = true, holdingSinceMs = closedAt)))
    assertEquals("timer", coach.tick(at(1100), PickSight(emptyList(), wristAway, holding = true, holdingSinceMs = closedAt))!!.why)
  }

  @Test
  fun theHookForTheRectanglesEndsPickOnce() {
    board()
    assertNull("the rectangle flickers as the action appears", coach.partMoved(at(200), "side_left"))
    val c = coach.partMoved(at(1000), "side_left")!!
    assertEquals(CoachChange(2, 0, CoachPhase.PICK, CoachPhase.PLACE, "part_moved"), c)
    assertNull(coach.partMoved(at(100), "side_left"))
    assertNull(coach.partMoved(at(5000), null))
    assertEquals(CoachPhase.PLACE, coach.phase)
  }

  // ---- hands only: the phases move without Next ----

  @Test
  fun aHandThatClosedAtThePartAndCarriedItOffEndsPick() {
    board()
    coach.tick(at(1000), PickSight(listOf(PickSpot(7, lay)), wristAway))
    // the server still lists the part where it lay (its next look is seconds away): the hand is the evidence
    val closed = PickSight(listOf(PickSpot(7, lay)), wristAtPart, holding = true, holdingSinceMs = now, gripStart = wristAtPart)
    assertNull("closed at the part, not yet moved", coach.tick(at(300), closed))
    val c = coach.tick(at(300), closed.copy(hand = wristAtPart + Vec3(-0.15f, 0.15f, 0.1f)))!!
    assertEquals(CoachChange(2, 0, CoachPhase.PICK, CoachPhase.PLACE, "part_moved"), c)
    assertEquals("grab", coach.how)
  }

  @Test
  fun aClosedHandThatOnlyPassesThePartIsNotAPick() {
    board()
    coach.tick(at(1000), PickSight(listOf(PickSpot(7, lay)), wristAway))
    // the grip began far from the part (a screwdriver in the hand) and then crosses over it
    val passing = PickSight(listOf(PickSpot(7, lay)), wristAtPart, holding = true, holdingSinceMs = now, gripStart = wristAway)
    repeat(10) { assertNull(coach.tick(at(300), passing)) }
    assertEquals(CoachPhase.PICK, coach.phase)
  }

  private fun toPlace(last: Boolean = false) {
    board(last = last)
    coach.next(at(1000))
    assertEquals(CoachPhase.PLACE, coach.phase)
  }

  /** [ms] of frames, 50 ms apart, all seeing [sight]: the first change, or null. */
  private fun frames(ms: Long, sight: PickSight): CoachChange? {
    var t = 0L
    while (t < ms) {
      t += 50
      coach.tick(at(50), sight)?.let { return it }
    }
    return null
  }

  @Test
  fun aHandAtTheTargetThatThenLeavesEndsPlace() {
    toPlace()
    assertNull(frames(1500, PickSight(hand = wristAway, holding = true, atTarget = true)))
    assertNull("just left: it may come back", frames(1900, PickSight(hand = wristAway)))
    val c = frames(300, PickSight(hand = wristAway))!!
    assertEquals(CoachChange(2, 0, CoachPhase.PLACE, CoachPhase.DONE, "placed"), c)
    assertEquals("away", coach.how)
  }

  @Test
  fun placeDoesNotEndWithoutAHandSeenAtTheTarget() {
    toPlace()
    assertNull("hands in view, never at the target", frames(20_000, PickSight(hand = wristAway)))
    assertNull("no hand data at all", frames(20_000, PickSight()))
    assertNull("a touch in passing", frames(600, PickSight(hand = wristAway, atTarget = true)))
    assertNull(frames(20_000, PickSight(hand = wristAway)))
    assertEquals(CoachPhase.PLACE, coach.phase)
  }

  @Test
  fun aHandHiddenBehindTheFurnitureHasNotLeftTheTarget() {
    toPlace()
    frames(1500, PickSight(hand = wristAway, atTarget = true))
    assertNull("tracking lost at the target", frames(10_000, PickSight()))
    assertNull(frames(1500, PickSight(hand = wristAway)))
    frames(500, PickSight(hand = wristAway, atTarget = true)) // back at it: the away clock starts again
    assertNull(frames(1900, PickSight(hand = wristAway)))
    assertEquals("placed", frames(200, PickSight(hand = wristAway))!!.why)
  }

  @Test
  fun theHandGoingForTheNextPartEndsPlaceAtOnce() {
    toPlace()
    val pile = listOf(lay)
    val firstGrip = now - 5000
    frames(1500, PickSight(hand = wristAway, holding = true, holdingSinceMs = firstGrip, atTarget = true, nextSpots = pile))
    // still the grip that placed the part, now over the pile: not a new pick
    assertNull(frames(500, PickSight(hand = wristAtPart, holding = true, holdingSinceMs = firstGrip, nextSpots = pile)))
    // the hand opens, then closes on the next one
    assertNull(frames(200, PickSight(hand = wristAtPart, nextSpots = pile)))
    val c = coach.tick(at(50), PickSight(hand = wristAtPart, holding = true, holdingSinceMs = now, nextSpots = pile))!!
    assertEquals(CoachChange(2, 0, CoachPhase.PLACE, CoachPhase.DONE, "placed"), c)
    assertEquals("next_pick", coach.how)
  }

  @Test
  fun theStepsLastActionWaitsLongerForTheServer() {
    toPlace(last = true)
    frames(1500, PickSight(hand = wristAway, atTarget = true))
    assertNull(frames(3900, PickSight(hand = wristAway)))
    assertEquals("placed", frames(200, PickSight(hand = wristAway))!!.why)
  }

  @Test
  fun aWrongPartHoldsPlacedBack() {
    toPlace()
    frames(1500, PickSight(hand = wristAway, atTarget = true))
    coach.wrongPart(now)
    assertNull(frames(5900, PickSight(hand = wristAway)))
    assertEquals("placed", frames(200, PickSight(hand = wristAway))!!.why)
  }

  @Test
  fun twoBoltsWithHandsOnlyNeverNeedNext() {
    // bolt 1: taken from the pile, put in its hole, the hand goes back for bolt 2
    bolt(0, last = false)
    val pile = listOf(PickSpot(1, lay), PickSpot(2, lay + Vec3(0.09f, 0f, 0f)))
    val hole = Vec3(-0.2f, 0.8f, -0.45f)
    coach.tick(at(1000), PickSight(pile, wristAway))
    var grip = now
    keep(coach.tick(at(400), PickSight(pile, hole, holding = true, holdingSinceMs = grip, gripStart = wristAtPart)))
    assertEquals(CoachPhase.PLACE, coach.phase)
    assertNull(frames(1500, PickSight(pile, hole, holding = true, holdingSinceMs = grip, atTarget = true, nextSpots = pile.map { it.pos })))
    assertNull(frames(300, PickSight(pile, wristAtPart, nextSpots = pile.map { it.pos })))
    grip = now
    keep(coach.tick(at(50), PickSight(pile, wristAtPart, holding = true, holdingSinceMs = grip, nextSpots = pile.map { it.pos })))
    assertEquals(CoachPhase.DONE, coach.phase)
    // bolt 2: the same grip carries it to the second hole; the hand then rests away from it
    bolt(1, last = true, why = "placed")
    assertNull("the action has just appeared", coach.tick(at(100), PickSight(pile, hole, holding = true, holdingSinceMs = grip, gripStart = wristAtPart)))
    keep(coach.tick(at(600), PickSight(pile, hole, holding = true, holdingSinceMs = grip, gripStart = wristAtPart)))
    assertEquals(CoachPhase.PLACE, coach.phase)
    assertNull(frames(1500, PickSight(pile, hole, holding = true, holdingSinceMs = grip, atTarget = true)))
    keep(frames(4100, PickSight(pile, wristAway)))
    assertEquals(CoachPhase.DONE, coach.phase)
    assertEquals(
        listOf("0:pick:next", "0:place:part_moved", "0:done:placed", "1:pick:placed", "1:place:part_moved", "1:done:placed"),
        changes.map { "${it.action}:${it.to.wire}:${it.why}" },
    )
  }

  // ---- the copy ----

  @Test
  fun theWindowShowsOneInstructionPerPhase() {
    val bolt = PartLabel("Βίδα έκκεντρου", "118331", "1 από 2")
    val text = "Βίδωσε τη βίδα έκκεντρου στην τρύπα."
    val pick = CoachLines.view("el", CoachPhase.PICK, bolt, text, last = false)
    assertEquals(CoachView(1, "Πάρε: Βίδα έκκεντρου", "118331 · 1 από 2", "", "Το πήρα"), pick)
    val place = CoachLines.view("el", CoachPhase.PLACE, bolt, text, last = false)
    assertEquals(CoachView(2, "Βάλ' το εδώ", text, "1 από 2", "Επόμενο"), place)
    assertFalse("the placing text is not shown while taking the part", pick.body.contains("Βίδωσε"))

    val side = PartLabel("Left side", null, null)
    val take = CoachLines.view("en", CoachPhase.PICK, side, "Stand the left side on the front.", last = true)
    assertEquals(CoachView(1, "Take: Left side", "", "", "Got it"), take)
    val put = CoachLines.view("en", CoachPhase.PLACE, side, "Stand the left side on the front.", last = true)
    assertEquals(CoachView(2, "Put it here", "Stand the left side on the front.", "", "Finish"), put)
  }

  @Test
  fun theSpokenPickLineNamesThePartAndWhereItIs() {
    val bolt = PartLabel("Βίδα έκκεντρου", "118331", "1 από 2")
    assertEquals("Πάρε: Βίδα έκκεντρου.", CoachLines.pickSay("el", bolt, null))
    assertEquals("Πάρε: Βίδα έκκεντρου, αριστερά σου.", CoachLines.pickSay("el", bolt, Strings.t("el", "whereLeft")))
    assertEquals("Take: Cam bolt, on your right.", CoachLines.pickSay("en", PartLabel("Cam bolt", "118331", null), Strings.t("en", "whereRight")))
  }

  @Test
  fun stringsAreCompleteInBothLanguages() {
    assertEquals(Strings.keys("en"), Strings.keys("el"))
    for (key in listOf("coachPick", "coachPlace", "coachPhasePut", "coachGotIt", "take")) {
      for (lang in listOf("el", "en")) {
        val text = Strings.t(lang, key, "name" to "X")
        assertTrue("$lang $key", text.isNotBlank() && text != key && !text.contains("{"))
      }
      assertFalse("$key is translated", Strings.t("el", key, "name" to "X") == Strings.t("en", key, "name" to "X"))
    }
    assertEquals("Πάρε: Αριστερό πλαϊνό", Strings.t("el", "coachPick", "name" to "Αριστερό πλαϊνό"))
    assertEquals("Βάλ' το εδώ", Strings.t("el", "coachPlace"))
    // the dots' words in capitals lose the tonos like every label
    assertEquals("ΠΑΡΕ", Strings.upper("el", Strings.t("el", "take")))
    assertEquals("ΒΑΛΕ", Strings.upper("el", Strings.t("el", "coachPhasePut")))
    assertEquals(listOf("pick", "place", "done"), CoachPhase.entries.map { it.wire })
  }
}
