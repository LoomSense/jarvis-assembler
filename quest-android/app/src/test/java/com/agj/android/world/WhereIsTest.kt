package com.agj.android.world

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.ui.PickPrompt
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHERE-IS: the memory of where every loose item was last seen (boards by kind and index, every instance of a
 * hardware code), the eight directions and the distance in words in both languages, the sentences on the custom
 * drawer's names, and the "where is it?" question (with and without accents; "where does it go?" is another question).
 */
class WhereIsTest {
  private var now = 50_000L
  private val mem = WhereIs { now }
  private val head = Pose3(Vec3(0f, 1.6f, 0f), Quat.IDENTITY) // looks down −Z, +X is on the right
  private val bolt = listOf("cam_bolt", "118331")

  private fun target(id: String, pos: Vec3, key: Int, ageMs: Long = 0, tracked: Boolean = false, method: String = "") =
      PickTarget(id, "x", pos, 0.9, ageMs, method = method, key = key, tracked = tracked)

  /** One read of the pick targets, 300 ms after the last (the memory takes them 5 times a second at most). */
  private fun feed(vararg t: PickTarget) {
    now += 300
    mem.targets(t.toList())
  }

  /** [deg] clockwise from straight ahead, [m] along the floor, on the floor. */
  private fun at(deg: Double, m: Float = 1f): Vec3 {
    val a = Math.toRadians(deg)
    return Vec3((sin(a) * m).toFloat(), 0f, (-cos(a) * m).toFloat())
  }

  // ---- the direction and the distance ----

  @Test
  fun eightSectorsInBothLanguages() {
    val want =
        listOf(
            0.0 to Triple(Sector.AHEAD, "μπροστά σου", "in front of you"),
            45.0 to Triple(Sector.AHEAD_RIGHT, "μπροστά δεξιά σου", "ahead on your right"),
            90.0 to Triple(Sector.RIGHT, "δεξιά σου", "on your right"),
            135.0 to Triple(Sector.BEHIND_RIGHT, "πίσω δεξιά σου", "behind you on the right"),
            180.0 to Triple(Sector.BEHIND, "πίσω σου", "behind you"),
            225.0 to Triple(Sector.BEHIND_LEFT, "πίσω αριστερά σου", "behind you on the left"),
            270.0 to Triple(Sector.LEFT, "αριστερά σου", "on your left"),
            315.0 to Triple(Sector.AHEAD_LEFT, "μπροστά αριστερά σου", "ahead on your left"),
        )
    for ((deg, w) in want) {
      val got = WhereIs.where(head, at(deg))
      assertEquals("$deg°", w.first, got.sector)
      assertEquals(w.second, got.bearing("el"))
      assertEquals(w.third, got.bearing("en"))
      assertEquals("the distance is along the floor, whatever the head's height", 1f, got.distM, 1e-3f)
    }
    // each sector is 45° wide, centred on its direction
    assertEquals(Sector.AHEAD, WhereIs.where(head, at(20.0)).sector)
    assertEquals(Sector.AHEAD_RIGHT, WhereIs.where(head, at(25.0)).sector)
    assertEquals(Sector.AHEAD, WhereIs.where(head, at(-20.0)).sector)
    assertEquals(Sector.AHEAD_LEFT, WhereIs.where(head, at(-25.0)).sector)
    assertEquals(Sector.BEHIND, WhereIs.where(head, at(170.0)).sector)
    assertEquals(Sector.BEHIND, WhereIs.where(head, at(-170.0)).sector)
  }

  @Test
  fun leftAndRightAreTheWearersAsInThePickPrompt() {
    // +X is the right of a head that looks down −Z: swapped signs would fail every line here
    assertEquals(Sector.RIGHT, WhereIs.where(head, Vec3(1f, 0f, 0f)).sector)
    assertEquals(Sector.LEFT, WhereIs.where(head, Vec3(-1f, 0f, 0f)).sector)
    assertEquals(Sector.AHEAD_RIGHT, WhereIs.where(head, Vec3(0.4f, 0.8f, -0.5f)).sector)
    assertEquals(Sector.AHEAD_LEFT, WhereIs.where(head, Vec3(-0.4f, 0.8f, -0.5f)).sector)
    // the same points as HeldVerdictTest: the three-way prompt and the eight sectors agree on the side
    assertEquals(PickPrompt.RIGHT, PickPrompt.bearing(head, Vec3(0.4f, 0.8f, -0.5f)))
    assertEquals(PickPrompt.LEFT, PickPrompt.bearing(head, Vec3(-0.4f, 0.8f, -0.5f)))
    // turned 90° to the left (looking down −X): what was ahead is now on the right, what was on the left is ahead
    val turned = Pose3(head.t, Quat.yaw(Math.toRadians(90.0).toFloat()))
    assertEquals(Sector.RIGHT, WhereIs.where(turned, Vec3(0f, 0.8f, -0.6f)).sector)
    assertEquals(Sector.AHEAD, WhereIs.where(turned, Vec3(-0.6f, 0.8f, 0f)).sector)
    assertEquals(Sector.LEFT, WhereIs.where(turned, Vec3(0f, 0.8f, 0.6f)).sector)
    assertEquals(Sector.BEHIND, WhereIs.where(turned, Vec3(0.6f, 0.8f, 0f)).sector)
    // the head's position counts, not the world's origin
    val moved = Pose3(Vec3(2f, 1.6f, 2f), Quat.IDENTITY)
    assertEquals(Sector.LEFT, WhereIs.where(moved, Vec3(1f, 0f, 2f)).sector)
    assertEquals(Sector.BEHIND_RIGHT, WhereIs.where(moved, Vec3(3f, 0f, 3f)).sector)
  }

  @Test
  fun distanceInWords() {
    val want =
        listOf(
            0.3f to ("κοντά σου" to "close to you"),
            0.59f to ("κοντά σου" to "close to you"),
            0.7f to ("περίπου μισό μέτρο" to "about half a metre away"),
            1.0f to ("περίπου ένα μέτρο" to "about one metre away"),
            1.2f to ("περίπου ένα μέτρο" to "about one metre away"),
            1.4f to ("περίπου ενάμισι μέτρο" to "about one and a half metres away"),
            2.0f to ("περίπου δύο μέτρα" to "about two metres away"),
            2.4f to ("περίπου δυόμισι μέτρα" to "about two and a half metres away"),
            2.6f to ("πάνω από δύο μέτρα" to "more than two metres away"),
            7f to ("πάνω από δύο μέτρα" to "more than two metres away"),
        )
    for ((m, w) in want) {
      assertEquals("$m m", w.first, WhereIs.distance("el", m))
      assertEquals("$m m", w.second, WhereIs.distance("en", m))
    }
    assertEquals("περίπου δύο μέτρα", WhereIs.where(head, at(180.0, 2.1f)).distance("el"))
  }

  // ---- the sentences, on the drawer's names ----

  private val farLeft = Where(Sector.LEFT, 2f)
  private val lastSeen = Where(Sector.BEHIND_LEFT, 1f)

  @Test
  fun takeLines() {
    // in view and within 1.2 m: the line as it was
    assertEquals("Πάρε: Βίδα έκκεντρου, αριστερά σου.", WhereLines.take("el", "Βίδα έκκεντρου", Where(Sector.LEFT, 0.8f), "αριστερά σου", null))
    assertEquals("Πάρε: Αριστερό πλαϊνό, μπροστά σου.", WhereLines.take("el", "Αριστερό πλαϊνό", Where(Sector.AHEAD, 1.2f), "μπροστά σου", null))
    // in view, farther
    assertEquals("Πάρε: Αριστερό πλαϊνό. Είναι αριστερά σου, περίπου δύο μέτρα. Φέρ' το κοντά σου.", WhereLines.take("el", "Αριστερό πλαϊνό", farLeft, "αριστερά σου", null))
    assertEquals("Πάρε: Βίδα έκκεντρου. Είναι αριστερά σου, περίπου δύο μέτρα. Φέρ' την κοντά σου.", WhereLines.take("el", "Βίδα έκκεντρου", farLeft, "αριστερά σου", null))
    assertEquals("Πάρε: Πάτο του συρταριού. Είναι αριστερά σου, περίπου δύο μέτρα. Φέρ' τον κοντά σου.", WhereLines.take("el", "Πάτο του συρταριού", farLeft, null, null))
    assertEquals("Take: Cam bolt. It is on your left, about two metres away. Bring it close to you.", WhereLines.take("en", "Cam bolt", farLeft, "on your left", null))
    // not in view, remembered
    assertEquals("Πάρε: Αριστερό πλαϊνό. Το είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.take("el", "Αριστερό πλαϊνό", null, null, lastSeen))
    assertEquals("Πάρε: Βίδα έκκεντρου. Την είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.take("el", "Βίδα έκκεντρου", null, null, lastSeen))
    assertEquals("Πάρε: Πάτο του συρταριού. Τον είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.take("el", "Πάτο του συρταριού", null, null, lastSeen))
    assertEquals("Take: Left side panel. I last saw it behind you on the left, about one metre away.", WhereLines.take("en", "Left side panel", null, null, lastSeen))
    // a remembered place the wearer already faces from close by is not worth a sentence (it is about to be lit again)
    assertTrue(WhereLines.outOfSight(lastSeen))
    assertTrue(WhereLines.outOfSight(Where(Sector.AHEAD_LEFT, 0.5f)))
    assertTrue(WhereLines.outOfSight(Where(Sector.AHEAD, 1.5f)))
    assertFalse(WhereLines.outOfSight(Where(Sector.AHEAD, 0.8f)))
    // seen now wins over a remembered place; nothing known: the plain line
    assertEquals("Πάρε: Βίδα έκκεντρου, αριστερά σου.", WhereLines.take("el", "Βίδα έκκεντρου", Where(Sector.LEFT, 0.8f), "αριστερά σου", lastSeen))
    assertEquals("Πάρε: Βίδα έκκεντρου.", WhereLines.take("el", "Βίδα έκκεντρου", null, null, null))
  }

  @Test
  fun answers() {
    // seen now
    assertEquals("Το αριστερό πλαϊνό είναι αριστερά σου, περίπου δύο μέτρα. Το φωτίζω.", WhereLines.answer("el", "Αριστερό πλαϊνό", farLeft, live = true))
    assertEquals("Η βίδα έκκεντρου είναι αριστερά σου, περίπου δύο μέτρα. Τη φωτίζω.", WhereLines.answer("el", "Βίδα έκκεντρου", farLeft, live = true))
    assertEquals("Ο πάτος του συρταριού είναι μπροστά σου, κοντά σου. Τον φωτίζω.", WhereLines.answer("el", "Πάτο του συρταριού", Where(Sector.AHEAD, 0.4f), live = true))
    assertEquals("Ο πάτος του συρταριού είναι μπροστά σου, κοντά σου. Τον φωτίζω.", WhereLines.answer("el", "Πάτος του συρταριού", Where(Sector.AHEAD, 0.4f), live = true))
    assertEquals("The cam bolt is on your left, about two metres away. I am lighting it up.", WhereLines.answer("en", "Cam bolt", farLeft, live = true))
    // its marker is not shown (the right part is already in the hand): no promise to light it
    assertEquals("Η βίδα έκκεντρου είναι αριστερά σου, περίπου δύο μέτρα.", WhereLines.answer("el", "Βίδα έκκεντρου", farLeft, live = true, lit = false))
    // remembered
    assertEquals("Το αριστερό πλαϊνό το είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.answer("el", "Αριστερό πλαϊνό", lastSeen, live = false))
    assertEquals("Τη βίδα έκκεντρου την είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.answer("el", "Βίδα έκκεντρου", lastSeen, live = false))
    assertEquals("Τον πάτο του συρταριού τον είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.answer("el", "Πάτο του συρταριού", lastSeen, live = false))
    assertEquals("Τον πάτο του συρταριού τον είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.answer("el", "Πάτος του συρταριού", lastSeen, live = false))
    assertEquals("Την πλάτη του συρταριού την είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.answer("el", "Πλάτη του συρταριού", lastSeen, live = false))
    assertEquals("I last saw the left side panel behind you on the left, about one metre away.", WhereLines.answer("en", "Left side panel", lastSeen, live = false))
    // never seen
    assertEquals("Δεν το βλέπω ακόμα. Κοίτα γύρω σου στο πάτωμα και θα το φωτίσω μόλις το δω.", WhereLines.answer("el", "Αριστερό πλαϊνό", null, live = false))
    assertEquals("Δεν τη βλέπω ακόμα. Κοίτα γύρω σου στο πάτωμα και θα τη φωτίσω μόλις τη δω.", WhereLines.answer("el", "Βίδα έκκεντρου", null, live = false))
    assertEquals("I can't see it yet. Look around you on the floor and I will light it up as soon as I see it.", WhereLines.answer("en", "Cam bolt", null, live = false))
    // a name whose article cannot be told: no article, the neuter pronoun
    assertEquals("XJ-9: είναι αριστερά σου, περίπου δύο μέτρα. Το φωτίζω.", WhereLines.answer("el", "XJ-9", farLeft, live = true))
    assertEquals("XJ-9: το είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο.", WhereLines.answer("el", "XJ-9", lastSeen, live = false))
  }

  // ---- the memory ----

  @Test
  fun hardwareKeepsEveryInstanceAndTheNewestSighting() {
    assertNull("never seen", mem.find(bolt, head.t))
    feed(target("118331", Vec3(1f, 0f, -1f), 1), target("118331", Vec3(1.2f, 0f, -1f), 2), target("118331", Vec3(1.4f, 0f, -1f), 3),
        target("118331", Vec3(1.6f, 0f, -1f), 4), target("101345", Vec3(-1f, 0f, 0f), 5))
    assertEquals(4, mem.instances("118331").size)
    assertEquals("equally fresh: the nearest to the wearer", Vec3(1f, 0f, -1f), mem.find(bolt, head.t)!!.pos)
    assertEquals(Vec3(-1f, 0f, 0f), mem.find(listOf("101345"), head.t)!!.pos)
    // the targets are gone (the wearer looked away, the phase moved on): the places stay, for the whole session
    feed()
    now += 600_000
    feed()
    assertEquals(4, mem.instances("118331").size)
    assertEquals(Vec3(1f, 0f, -1f), mem.find(bolt, head.t)!!.pos)
    // seen again, moved: the newer sighting replaces the entry of the same thing (its key)
    feed(target("118331", Vec3(0.5f, 0f, 2f), 1))
    assertEquals(4, mem.instances("118331").size)
    assertFalse(Vec3(1f, 0f, -1f) in mem.instances("118331"))
    assertEquals("the newest sighting", Vec3(0.5f, 0f, 2f), mem.find(bolt, head.t)!!.pos)
    // found again under another key at nearly the same place: still that one, not a fifth
    feed(target("118331", Vec3(1.23f, 0f, -1.02f), 77))
    assertEquals(4, mem.instances("118331").size)
    assertTrue(Vec3(1.23f, 0f, -1.02f) in mem.instances("118331"))
    // a target that only stands where the server last saw it (not followed) is no newer sighting
    feed(target("118331", Vec3(1.41f, 0f, -1f), 3, ageMs = 900_000))
    assertTrue("the remembered place is the newer one", Vec3(1.4f, 0f, -1f) in mem.instances("118331"))
    // the recognised rectangle of a board is not an instance here (FlatParts feeds the boards)
    feed(target("side_left", Vec3(0f, 0f, -1f), 1_000_001, method = FlatParts.METHOD))
    assertTrue(mem.instances("side_left").isEmpty())
    mem.clear()
    assertNull(mem.find(bolt, head.t))
  }

  @Test
  fun theMemoryIsFedFiveTimesASecondAtMost() {
    feed(target("118331", Vec3(1f, 0f, -1f), 1))
    now += 50
    mem.targets(listOf(target("118331", Vec3(2f, 0f, -1f), 1)))
    assertEquals(listOf(Vec3(1f, 0f, -1f)), mem.instances("118331"))
    now += 200
    mem.targets(listOf(target("118331", Vec3(2f, 0f, -1f), 1)))
    assertEquals(listOf(Vec3(2f, 0f, -1f)), mem.instances("118331"))
  }

  @Test
  fun aUsedInstanceIsDroppedAndAnOldSightingDoesNotBringItBack() {
    val a = Vec3(1f, 0f, -1f)
    val b = Vec3(1.3f, 0f, -1f)
    val c = Vec3(1.6f, 0f, -1f)
    feed(target("118331", a, 1), target("118331", b, 2), target("118331", c, 3))
    // the action that used a bolt is done: the one nearest to where it was taken from goes
    now += 20_000
    mem.used("118331", Vec3(1.33f, 0.1f, -0.95f))
    assertEquals(listOf(a, c), mem.instances("118331"))
    // its track still stands where it lay, last seen by the server before it was used: it stays dropped
    feed(target("118331", a, 1, ageMs = 25_000), target("118331", b, 2, ageMs = 25_000), target("118331", c, 3, ageMs = 25_000))
    assertEquals(listOf(a, c), mem.instances("118331"))
    // a bolt the server sees there after that is a bolt that lies there
    feed(target("118331", b, 9, ageMs = 0))
    assertEquals(3, mem.instances("118331").size)
    // where it was taken from is not known: the oldest sighting goes
    mem.used("118331", null)
    assertEquals(2, mem.instances("118331").size)
    assertTrue("the newest stays", b in mem.instances("118331"))
    // the tracker followed the bolt into its hole: the instance at the target goes, not the one near the pick spot
    val hole = Vec3(0f, 0.1f, -0.4f)
    feed(target("118331", hole, 3, tracked = true))
    mem.used("118331", b) { p -> (p - hole).length() < 0.1f }
    assertFalse(hole in mem.instances("118331"))
    assertTrue(b in mem.instances("118331"))
    mem.used("118331", b)
    mem.used("118331", b)
    assertNull("all used", mem.find(bolt, head.t))
    mem.used("118331", b) // nothing left: no harm
  }

  @Test
  fun boardsByKindAndIndexAndPlacedBoardsAreDropped() {
    val sides = "side_left+side_right"
    val loose = mapOf(sides to 2, "bottom" to 1)
    val s0 = Vec3(-1f, 0f, -1f)
    val s1 = Vec3(0.2f, 0f, -0.5f)
    mem.boards(listOf(WhereIs.BoardSight(sides, 0, s0), WhereIs.BoardSight(sides, 1, s1), WhereIs.BoardSight("bottom", 0, Vec3(2f, 0f, 2f))), loose)
    assertEquals("two boards of one size, equally fresh: the nearest", s1, mem.find(listOf("side_left"), head.t)!!.pos)
    assertEquals(s1, mem.find(listOf("side_right"), head.t)!!.pos)
    assertEquals(Vec3(2f, 0f, 2f), mem.find(listOf("bottom"), head.t)!!.pos)
    assertNull(mem.find(listOf("back"), head.t))
    // the cue tells them apart: the rectangle told to be the right side is never the left one
    now += 400
    mem.boards(listOf(WhereIs.BoardSight(sides, 0, s0), WhereIs.BoardSight(sides, 1, s1, which = "side_right")), loose)
    assertEquals(s0, mem.find(listOf("side_left"), head.t)!!.pos)
    assertEquals(s1, mem.find(listOf("side_right"), head.t)!!.pos)
    // out of view for a long time: remembered; a newer sighting of the same index replaces it
    now += 300_000
    mem.boards(emptyList(), loose)
    assertEquals(s0, mem.find(listOf("side_left"), head.t)!!.pos)
    val moved = Vec3(-0.5f, 0f, -0.3f)
    mem.boards(listOf(WhereIs.BoardSight(sides, 0, moved)), loose)
    assertEquals(moved, mem.find(listOf("side_left"), head.t)!!.pos)
    // an earlier action has placed the right side: the rectangle told to be it is dropped, the left one stays
    mem.boards(emptyList(), mapOf(sides to 1, "bottom" to 1), placed = setOf("side_right"))
    assertEquals(moved, mem.find(listOf("side_left", "x"), head.t)!!.pos)
    // both placed: nothing of the kind is loose
    mem.boards(emptyList(), mapOf(sides to 0, "bottom" to 1), placed = setOf("side_left", "side_right"))
    assertNull(mem.find(listOf("side_left"), head.t))
    assertEquals(Vec3(2f, 0f, 2f), mem.find(listOf("bottom"), head.t)!!.pos)
  }

  @Test
  fun withNothingToldApartThePlaceNotSeenForLongestGoesWhenABoardIsPlaced() {
    val sides = "side_left+side_right"
    val taken = Vec3(-1f, 0f, -1f)
    val stays = Vec3(0.2f, 0f, -0.5f)
    mem.boards(listOf(WhereIs.BoardSight(sides, 0, taken), WhereIs.BoardSight(sides, 1, stays)), mapOf(sides to 2))
    // the left side is lifted and put in place; the other one is still seen on the floor meanwhile
    now += 8_000
    mem.boards(listOf(WhereIs.BoardSight(sides, 1, stays)), mapOf(sides to 2))
    now += 8_000
    mem.boards(emptyList(), mapOf(sides to 1), placed = setOf("side_left"))
    assertEquals(stays, mem.find(listOf("side_right"), head.t)!!.pos)
    assertEquals("one loose board, one place", stays, mem.find(listOf("side_right"), Vec3(-1f, 1.6f, -1f))!!.pos)
  }

  // ---- the question ----

  private val names = listOf("Βίδα έκκεντρου", "Cam bolt")

  @Test
  fun whereIsItWithAndWithoutAccents() {
    for (q in
        listOf(
            "Πού είναι η βίδα;", "που ειναι η βιδα", "ΠΟΥ ΕΙΝΑΙ Η ΒΙΔΑ", "Πού είναι;", "Πού είναι οι βίδες;", "Πού είναι η βίδα έκκεντρου;",
            "Δεν βρίσκω την βίδα.", "δεν βρισκω τη βιδα", "Δεν το βρίσκω.", "Δεν τη βρίσκω", "Δεν την βρίσκω", "δεν την βρισκω", "Πού το βρίσκω;",
            "που το βρισκω", "Δεν μπορώ να τη βρω", "Where is it?", "where is the cam bolt", "Where's the bolt?", "I can't find it", "I cannot find the bolt",
        )) {
      assertTrue(q, WhereAsk.matches(q, "ask", names))
      assertTrue("$q, whatever the part is called", WhereAsk.matches(q, "ask"))
    }
    assertTrue("a board", WhereAsk.matches("Πού είναι το αριστερό πλαϊνό;", "ask", listOf("Αριστερό πλαϊνό", "Left side panel")))
    assertTrue(WhereAsk.matches("Δεν βρίσκω το πλαϊνό", "none", listOf("Αριστερό πλαϊνό", "Left side panel")))
  }

  @Test
  fun whereItGoesAndOtherQuestionsStayWithTheServer() {
    for (q in
        listOf(
            "Πού πάει;", "Πού πάει η βίδα;", "που παει η βιδα", "Πού μπαίνει;", "Πού μπαίνει η βίδα;", "που μπαινει", "Πού τη βάζω;", "Where does it go?",
            "where does the bolt go", "Where do I put it?", "Πού είναι η τρύπα που μπαίνει η βίδα;", "Είναι σωστό αυτό;", "επόμενο", "Πού είμαι;", "",
            "Πόσες βίδες χρειάζομαι;",
        )) assertFalse(q, WhereAsk.matches(q, "ask", names))
    // it names something that is not the part to take
    assertFalse(WhereAsk.matches("Πού είναι η τρύπα;", "ask", names))
    assertFalse(WhereAsk.matches("Where is the screwdriver?", "ask", names))
    assertFalse("another board", WhereAsk.matches("Πού είναι η πλάτη;", "ask", listOf("Αριστερό πλαϊνό", "Left side panel")))
    assertFalse("the other side", WhereAsk.matches("Πού είναι το δεξί πλαϊνό;", "ask", listOf("Αριστερό πλαϊνό", "Left side panel")))
    // a command is a command
    for (intent in listOf("next", "back", "repeat", "where_am_i")) assertFalse(intent, WhereAsk.matches("Πού είναι η βίδα;", intent, names))
    assertEquals(" που ειναι η βιδα ", WhereAsk.normal("  Πού  είναι η ΒΊΔΑ;! "))
  }
}
