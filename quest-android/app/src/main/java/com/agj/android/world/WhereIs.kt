package com.agj.android.world

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.ui.LiveLines
import com.agj.android.ui.PickPrompt
import com.agj.android.ui.Placement
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

/*
 * WHERE-IS: "where is it?" answered from what the headset itself has seen. Pure Kotlin, JVM-tested (WhereIsTest).
 *
 *  - [WhereIs]: the last known WORLD place of every loose item, for the whole session: boards by kind and index
 *    (world/FlatParts.kt feeds its live labelled rectangles once per detector pass) and every instance of a hardware
 *    code or server-located part (world/WorldModule.kt feeds the pick targets as they are read). A newer sighting
 *    replaces the entry; a board placed by an earlier action is dropped; when an action that used an item is done,
 *    the instance nearest to where it was taken is dropped.
 *  - [WhereIs.where]: one of eight directions about where the wearer faces on the floor plane (the convention of
 *    `PickPrompt.bearing`), and the distance along the floor, in words.
 *  - [WhereLines]: the take line for a part that is far away or out of view, and the answer to "πού είναι;".
 *  - [WhereAsk]: is a transcript that question (and not "πού πάει;", which asks where the part goes).
 *
 * Remembered places are never pick targets: the action coach reads a vanished target as "the part was taken".
 */

/** The eight directions about where the wearer faces, clockwise from straight ahead (45° each). */
enum class Sector(val wire: String, val el: String, val en: String) {
  AHEAD("ahead", "μπροστά σου", "in front of you"),
  AHEAD_RIGHT("ahead_right", "μπροστά δεξιά σου", "ahead on your right"),
  RIGHT("right", "δεξιά σου", "on your right"),
  BEHIND_RIGHT("behind_right", "πίσω δεξιά σου", "behind you on the right"),
  BEHIND("behind", "πίσω σου", "behind you"),
  BEHIND_LEFT("behind_left", "πίσω αριστερά σου", "behind you on the left"),
  LEFT("left", "αριστερά σου", "on your left"),
  AHEAD_LEFT("ahead_left", "μπροστά αριστερά σου", "ahead on your left");

  fun words(lang: String): String = if (lang == "el") el else en
}

/** Where something is for the wearer: the direction, and the distance along the floor. */
data class Where(val sector: Sector, val distM: Float) {
  fun bearing(lang: String): String = sector.words(lang)

  fun distance(lang: String): String = WhereIs.distance(lang, distM)
}

/** The session's memory of where every loose item was last seen. Thread-safe. */
class WhereIs(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
  /** One labelled board on the floor in one detector pass. [which]: the board the cue says it is (a part id), if it says. */
  class BoardSight(val kind: String, val index: Int, val pos: Vec3, val which: String? = null)

  /** A remembered place. [atMs]: when it was last seen there, on this memory's clock. */
  class Seen(val pos: Vec3, val atMs: Long, val which: String? = null, internal val key: Int = 0)

  private val boards = HashMap<String, HashMap<Int, Seen>>() // kind name ("a+b") → index → last sighting
  private val items = HashMap<String, ArrayList<Seen>>() // part id / hardware code → its instances
  private val usedAt = HashMap<String, Long>() // id → when an action last used one of it
  private var fedMs = FAR_PAST

  /**
   * FlatParts, once per detector pass: every live labelled rectangle. [loose]: per kind name, how many of its boards
   * no earlier action has placed; [placed]: the part ids of the boards that earlier actions have placed. A kind keeps
   * no more places than it has loose boards: first the ones told to be a placed board go, then the oldest.
   */
  @Synchronized
  fun boards(seen: List<BoardSight>, loose: Map<String, Int>, placed: Set<String> = emptySet(), nowMs: Long = clockMs()) {
    for (s in seen) boards.getOrPut(s.kind) { HashMap() }[s.index] = Seen(s.pos, nowMs, s.which)
    val it = boards.entries.iterator()
    while (it.hasNext()) {
      val e = it.next()
      val n = loose[e.key] ?: 0 // a kind the guide no longer has: nothing of it is loose
      val m = e.value
      m.entries.removeAll { x -> x.value.which != null && x.value.which in placed }
      while (m.size > n) m.remove(m.minByOrNull { x -> x.value.atMs }!!.key)
      if (m.isEmpty()) it.remove()
    }
  }

  /**
   * The pick targets, whenever they are read (several times a frame: taken in every [FEED_MS]). Every instance the
   * server located is kept under its id; the recognised rectangle is not ([boards] has it). A target followed in the
   * picture is seen now, another when the server last saw it. The same thing again (its key, else a place within
   * [SAME_M]) replaces its entry when the sighting is newer.
   */
  @Synchronized
  fun targets(all: List<PickTarget>, nowMs: Long = clockMs()) {
    if (nowMs - fedMs < FEED_MS) return
    fedMs = nowMs
    val claimed = HashSet<Seen>()
    for (t in all) {
      if (t.method == FlatParts.METHOD || t.id.isBlank()) continue
      val at = if (t.tracked) nowMs else nowMs - t.ageMs.coerceAtLeast(0L)
      val l = items.getOrPut(t.id) { ArrayList() }
      val old =
          l.firstOrNull { it.key == t.key && t.key != 0 }
              ?: l.filter { it !in claimed && flat(it.pos, t.worldPos) <= SAME_M }.minByOrNull { flat(it.pos, t.worldPos) }
      if (old != null) {
        if (old.atMs > at) {
          claimed += old
          continue
        }
        l.remove(old)
      } else if (at <= (usedAt[t.id] ?: FAR_PAST)) continue // seen before one of it was used: that one does not come back
      val fresh = Seen(t.worldPos, at, null, t.key)
      l += fresh
      claimed += fresh
      while (l.size > MAX_PER_ID) l.remove(l.minByOrNull { it.atMs }!!)
    }
    items.entries.removeAll { it.value.isEmpty() }
  }

  /**
   * An action that used one [id] is done: that one is no longer loose. Every instance that [inPlace] says lies where
   * the action put it goes (the tracker followed it there); when there is none, the instance nearest to [near]
   * (where it was taken from), or the oldest when [near] is not known. A sighting from before this moment does not
   * bring it back.
   */
  @Synchronized
  fun used(id: String, near: Vec3?, nowMs: Long = clockMs(), inPlace: (Vec3) -> Boolean = { false }) {
    usedAt[id] = nowMs
    val l = items[id] ?: return
    if (!l.removeAll { inPlace(it.pos) }) {
      val gone = if (near != null) l.minByOrNull { flat(it.pos, near) } else l.minByOrNull { it.atMs }
      if (gone != null) l.remove(gone)
    }
    if (l.isEmpty()) items.remove(id)
  }

  /**
   * The last known place of the item with one of [ids] (a part id, a hardware code); null when it was never seen.
   * Among several (four bolts; two boards of one size) the ones seen within [SAME_SIGHT_MS] of the newest count as
   * equally fresh, and the nearest to [head] is given (the newest without [head]). A rectangle told to be this
   * board is the one; a rectangle told to be another board never is.
   */
  @Synchronized
  fun find(ids: Collection<String>, head: Vec3? = null): Seen? {
    val c = ArrayList<Seen>()
    for ((kind, m) in boards) {
      val parts = kind.split('+')
      val id = ids.firstOrNull { it in parts } ?: continue
      c += m.values.filter { it.which == id }.ifEmpty { m.values.filter { it.which == null } }
    }
    for (id in ids) items[id]?.let { c += it }
    val newest = c.maxOfOrNull { it.atMs } ?: return null
    val fresh = c.filter { newest - it.atMs <= SAME_SIGHT_MS }
    return if (head == null) fresh.maxByOrNull { it.atMs } else fresh.minByOrNull { flat(it.pos, head) }
  }

  /** Every remembered place of [id]'s instances (server-located), for the log and the tests. */
  @Synchronized fun instances(id: String): List<Vec3> = items[id]?.map { it.pos } ?: emptyList()

  /** Another guide: nothing of the last one is remembered. */
  @Synchronized
  fun clear() {
    boards.clear()
    items.clear()
    usedAt.clear()
    fedMs = FAR_PAST
  }

  companion object {
    private const val FAR_PAST = Long.MIN_VALUE / 2

    /** The pick targets are taken into the memory this often at most. */
    const val FEED_MS = 200L

    /** A located instance this close to a remembered one is that same thing. */
    const val SAME_M = 0.10f

    /** Sightings this close in time are equally fresh (one server answer; the tracker's follow-up). */
    const val SAME_SIGHT_MS = 5_000L
    const val MAX_PER_ID = 12

    /** Under this the distance is "close to you", with no number. */
    const val NEAR_M = 0.6f

    private fun flat(a: Vec3, b: Vec3): Float = sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

    /**
     * Where [pos] is for the wearer: the direction in eight sectors about the flat gaze (forward and right exactly as
     * in `PickPrompt.bearing`: forward is the head's −Z laid on the floor plane) and the distance along the floor.
     */
    fun where(head: Pose3, pos: Vec3): Where {
      val f = Placement.flatForward(head)
      val r = Vec3(-f.z, 0f, f.x)
      val d = pos - head.t
      val right = d.dot(r)
      val ahead = d.dot(f)
      val deg = Math.toDegrees(atan2(right.toDouble(), ahead.toDouble())) // 0 = ahead, +90 = right, ±180 = behind
      val i = Math.floorMod(Math.round(deg / 45.0).toInt(), 8)
      return Where(Sector.values()[i], sqrt(right * right + ahead * ahead))
    }

    /** "κοντά σου" under [NEAR_M]; else to the half metre, "περίπου ενάμισι μέτρο"; beyond 2.5 m "πάνω από δύο μέτρα". */
    fun distance(lang: String, m: Float): String {
      val el = lang == "el"
      if (m < NEAR_M) return if (el) "κοντά σου" else "close to you"
      if (m > 2.5f) return if (el) "πάνω από δύο μέτρα" else "more than two metres away"
      return when (Math.round(m * 2f)) {
        1 -> if (el) "περίπου μισό μέτρο" else "about half a metre away"
        2 -> if (el) "περίπου ένα μέτρο" else "about one metre away"
        3 -> if (el) "περίπου ενάμισι μέτρο" else "about one and a half metres away"
        4 -> if (el) "περίπου δύο μέτρα" else "about two metres away"
        else -> if (el) "περίπου δυόμισι μέτρα" else "about two and a half metres away"
      }
    }
  }
}

/** The sentences (pure). Names are the action's label ("Αριστερό πλαϊνό", "Βίδα έκκεντρου", "Πάτο του συρταριού"). */
object WhereLines {
  /** Farther than this along the floor, the take line says where the part is and asks to bring it close. */
  const val FAR_M = 1.2f

  private val elLocale = Locale.forLanguageTag("el")
  private const val KEEPS_N = "αάεέηήιίοόυύωώκπτξψ"

  private fun firstWord(name: String) = name.trim().lowercase(elLocale).substringBefore(' ')

  /** A masculine name given in the nominative ("Πάτος του συρταριού"): LiveLines' helpers expect "Πάρε: …" names ("Πάτο …"). */
  private fun mascNom(name: String): Boolean = firstWord(name).let { w -> w.length > 3 && listOf("ος", "ός", "ας", "άς", "ης", "ής").any { w.endsWith(it) } }

  private fun gender(name: String): LiveLines.El.Gender? = if (mascNom(name)) LiveLines.El.Gender.M else LiveLines.El.gender(name)

  /** "το αριστερό πλαϊνό", "η βίδα έκκεντρου", "ο πάτος του συρταριού"; null when the article cannot be told. */
  private fun nom(name: String): String? = if (mascNom(name)) "ο " + LiveLines.lower(name, elLocale) else LiveLines.El.nom(name)

  /** "το αριστερό πλαϊνό", "τη βίδα έκκεντρου", "τον πάτο του συρταριού". */
  private fun acc(name: String): String? {
    if (!mascNom(name)) return LiveLines.El.acc(name)
    val n = LiveLines.lower(name, elLocale)
    val w = n.substringBefore(' ')
    return "τον " + w.dropLast(1) + n.substring(w.length)
  }

  /** The weak pronoun for the named thing before a word that starts with [next]: "το", "τον", "τη" / "την". */
  private fun pron(name: String, next: Char): String =
      when (gender(name)) {
        LiveLines.El.Gender.M -> "τον"
        LiveLines.El.Gender.F -> if (next in KEEPS_N) "την" else "τη"
        else -> "το"
      }

  private fun cap(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(elLocale) else it.toString() }

  /** Seen now, but far: "Πάρε: Αριστερό πλαϊνό. Είναι πίσω σου, περίπου δύο μέτρα. Φέρ' το κοντά σου." */
  fun takeFar(lang: String, name: String, w: Where): String =
      if (lang == "el") "Πάρε: $name. Είναι ${w.bearing(lang)}, ${w.distance(lang)}. Φέρ' ${pron(name, 'κ')} κοντά σου."
      else "Take: $name. It is ${w.bearing(lang)}, ${w.distance(lang)}. Bring it close to you."

  /** Not in view, remembered: "Πάρε: Βίδα έκκεντρου. Την είδα τελευταία πίσω αριστερά σου, περίπου ένα μέτρο." */
  fun takeRemembered(lang: String, name: String, w: Where): String =
      if (lang == "el") "Πάρε: $name. ${cap(pron(name, 'ε'))} είδα τελευταία ${w.bearing(lang)}, ${w.distance(lang)}."
      else "Take: $name. I last saw it ${w.bearing(lang)}, ${w.distance(lang)}."

  /**
   * Is a remembered place worth a sentence of its own in the take line? Not when the wearer already faces it from
   * within [FAR_M]: the part is then about to be found again and lit, and the plain "Πάρε: …" says enough. (The
   * question "πού είναι;" is answered from the memory all the same.)
   */
  fun outOfSight(w: Where): Boolean = w.sector != Sector.AHEAD || w.distM > FAR_M

  /**
   * The take line. [live]: where the part is seen now, with [near] its left / ahead / right words (the existing line,
   * kept while the part is within [FAR_M]); [remembered]: where it was last seen, when it is not in view.
   */
  fun take(lang: String, name: String, live: Where?, near: String?, remembered: Where?): String =
      when {
        live != null && live.distM > FAR_M -> takeFar(lang, name, live)
        live != null -> PickPrompt.line(lang, name, near)
        remembered != null -> takeRemembered(lang, name, remembered)
        else -> PickPrompt.line(lang, name, null)
      }

  /**
   * The answer to "where is it?". [w] null: never seen. [live]: it is seen now (else [w] is where it was last seen);
   * [lit]: its marker is shown now, so the answer may say so.
   */
  fun answer(lang: String, name: String, w: Where?, live: Boolean, lit: Boolean = true): String {
    val el = lang == "el"
    if (w == null)
        return if (el) "Δεν ${pron(name, 'β')} βλέπω ακόμα. Κοίτα γύρω σου στο πάτωμα και θα ${pron(name, 'φ')} φωτίσω μόλις ${pron(name, 'δ')} δω."
        else "I can't see it yet. Look around you on the floor and I will light it up as soon as I see it."
    val at = "${w.bearing(lang)}, ${w.distance(lang)}"
    if (!el) {
      val the = "the ${LiveLines.lower(name, Locale.ENGLISH)}"
      return if (live) "${the.replaceFirstChar { it.uppercase() }} is $at." + (if (lit) " I am lighting it up." else "") else "I last saw $the $at."
    }
    if (live) {
      val head = nom(name)?.let { "${cap(it)} είναι" } ?: "$name: είναι"
      return "$head $at." + (if (lit) " ${cap(pron(name, 'φ'))} φωτίζω." else "")
    }
    // the thing seen is the object here: "Τη βίδα έκκεντρου την είδα τελευταία …"
    val head = acc(name)?.let { "${cap(it)} ${pron(name, 'ε')}" } ?: "$name: το"
    return "$head είδα τελευταία $at."
  }
}

/** "Where is it?" / "I cannot find it" said into the mic, about the part to take. Pure. */
object WhereAsk {
  private val elLocale = Locale.forLanguageTag("el")
  private const val PRON = "(το|τη|την|τον|τα|τις|τους)"

  private val asks =
      listOf(
          Regex(" που (ειναι|ναι|βρισκεται|βρισκονται) "), // πού είναι η βίδα;
          Regex(" δεν? ($PRON )?βρισκω "), // δεν βρίσκω τη βίδα / δεν την βρίσκω
          Regex(" δεν? μπορω να ($PRON )?βρω "),
          Regex(" που (θα )?($PRON )?(βρισκω|βρω) "), // πού το βρίσκω;
          Regex(" where (is|s|are) "),
          Regex(" wheres "),
          Regex(" (can t|cant|cannot|can not) find "),
      )

  /** Where the part GOES is a question for the guide: "πού πάει;", "πού μπαίνει;", "where does it go?". */
  private val goes =
      listOf(
          Regex(" που (θα )?($PRON )?(παει|πανε|μπαινει|μπαινουν|βαζω|βαζουμε|βαλω|βιδωνω|βιδωνει|βιδωνεται|τοποθετω|τοποθετειται|κουμπωνει|κολλαει) "),
          Regex(" where (does|do|should|shall|must|to) "),
          Regex(" (it|this|that|one) (goes|go) "),
      )

  /** Words that name nothing: with only these the question is about "it", the part to take. */
  private val filler =
      setOf(
          "το", "τη", "την", "τον", "τα", "τις", "τους", "η", "ο", "οι", "ενα", "μια", "ενας", "ενα", "αυτο", "αυτη", "αυτος", "αυτα", "εκεινο", "μου", "μας",
          "σου", "τωρα", "ρε", "λοιπον", "και", "αλλα", "μα", "εγω", "δε", "δεν", "που", "ειναι", "ναι", "βρισκω", "βρισκεται", "βρισκονται", "μπορω", "να",
          "βρω", "θα", "παρακαλω", "ακομα", "πια", "αλλο", "αλλη", "αλλος", "επομενο", "επομενη", "επομενος", "κομματι", "εξαρτημα", "πραγμα", "ε", "α",
          "the", "a", "an", "it", "is", "are", "s", "t", "where", "wheres", "can", "cant", "cannot", "not", "find", "i", "my", "this", "that", "one",
          "please", "now", "next", "other", "at", "all", "still", "anymore", "part", "piece", "thing", "them", "these", "those", "do", "so", "but", "and",
      )

  /** Lower case, no accents, letters and digits only, one space between words and one at each end. */
  fun normal(text: String): String {
    val sb = StringBuilder(" ")
    for (ch in text.lowercase(elLocale)) {
      val c =
          when (ch) {
            'ά' -> 'α'
            'έ' -> 'ε'
            'ή' -> 'η'
            'ί', 'ϊ', 'ΐ' -> 'ι'
            'ό' -> 'ο'
            'ύ', 'ϋ', 'ΰ' -> 'υ'
            'ώ' -> 'ω'
            else -> ch
          }
      if (c.isLetterOrDigit()) sb.append(c) else if (sb.last() != ' ') sb.append(' ')
    }
    if (sb.last() != ' ') sb.append(' ')
    return sb.toString()
  }

  /** Two words name the same thing: they share their first three letters and all but the last letter of the shorter. */
  private fun same(a: String, b: String): Boolean {
    var n = 0
    while (n < a.length && n < b.length && a[n] == b[n]) n++
    return n >= maxOf(3, minOf(a.length, b.length) - 1)
  }

  /**
   * True when [transcript] asks where the part to take is. Never for a command ([intent] next / back / repeat /
   * where_am_i), never for "where does it go". [names]: the part's names (any language); when the question names
   * anything else ("πού είναι η τρύπα;", "where is the screwdriver?") it is not about this part.
   */
  fun matches(transcript: String, intent: String = "ask", names: Collection<String> = emptyList()): Boolean {
    if (intent in setOf("next", "back", "repeat", "where_am_i")) return false
    val t = normal(transcript)
    if (asks.none { it.containsMatchIn(t) } || goes.any { it.containsMatchIn(t) }) return false
    val said = t.trim().split(' ').filter { it.length > 1 && it !in filler }
    if (said.isEmpty() || names.isEmpty()) return true
    val mine = names.flatMap { normal(it).trim().split(' ') }.filter { it.length > 2 }
    return said.all { w -> mine.any { same(w, it) } }
  }
}
