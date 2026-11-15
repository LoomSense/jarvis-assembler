package com.agj.android.ui

import com.agj.android.guide.ActionKind
import com.agj.android.guide.Guide
import com.agj.android.guide.GuideAction
import java.util.Locale

/*
 * LIVE-COACH: what a person standing next to the wearer would say, the moment it happens. Pure Kotlin, JVM-tested
 * (LiveLinesTest); MainActivity picks the line (the `LIVE-COACH` hunks) and fetches every one of them ahead
 * ([LiveLines.all]) so none waits for the speech round trip.
 *
 *   the right part is in the hand (take → put)   "Σωστή βίδα! Βίδωσέ τη στην τρύπα που φωτίζεται."
 *   the hand works at the target for a while     "Ωραία, συνέχισε μέχρι να σφίξει."   (once per action, not for boards)
 *   the action is done (put → done)              "Μπράβο! Πάμε στην επόμενη, 2 από 4. Πάρε: Βίδα έκκεντρου."
 *                                                "Μπράβο, και οι τέσσερις μπήκαν. Επόμενο βήμα: πάρε το αριστερό πλαϊνό."
 *   a wrong board was lifted (ui/GrabJudge.kt)   "Αυτό είναι η πλάτη του συρταριού. Χρειάζεσαι το αριστερό πλαϊνό."
 *   the part is the wrong way round              "Προσοχή: " + the server's sentence
 *   no hand is seen, the coach goes by time      "Δεν βλέπω τα χέρια σου, οπότε προχωράω με τον χρόνο. …"   (once)
 *
 * One utterance per moment: the praise and the next action's "Πάρε: …" are one sentence group, so one speech request.
 * No line of the table is longer than [LiveLines.MAX] characters; names come from the manifest.
 */

/** What an action handles, for the choice of words. */
enum class CoachItem {
  BOARD,
  /** Turned in: a screw, a bolt. */
  SCREW,
  /** A cam lock: turned until tight. */
  CAM,
  /** Pushed in: a dowel, a pin, a peg. */
  PEG,
  OTHER,
}

/** One guided action, as the lines need it (from a [GuideAction]: [LiveLines.of]). */
data class LiveAct(
    val step: Int,
    val action: Int,
    val item: CoachItem,
    /** Hardware code or part id: the same id on the next action = "the next one of the same". */
    val id: String?,
    /** The manifest's name in the language, as on the label ("Βίδα έκκεντρου"); empty when the action has none. */
    val name: String,
    /** The manifest's instruction in the language. */
    val text: String,
    val ordinal: Int = 1,
    val sameCount: Int = 1,
    /** It has a "take" phase (false: a fastener already in its part, a turn). */
    val hasPick: Boolean = true,
)

/** What is said when an action ends. [entry]: it carries the next action's call to action, whose own entry line is then not said. */
data class DoneLine(val text: String, val entry: Boolean)

/** Never the same sentence twice in a row: [pick] gives the first candidate that is not what was said last. */
class NoRepeat {
  var last: String? = null
    private set

  fun pick(vararg lines: String?): String? {
    val line = lines.firstOrNull { !it.isNullOrBlank() && it != last } ?: return null
    last = line
    return line
  }

  /** Something else was said in between: the next line is not a repeat. */
  fun other() {
    last = null
  }
}

object LiveLines {
  /** No line of this table is longer (characters). */
  const val MAX = 90

  /** The instruction after the praise: the manifest's own text up to this length, else its imperative core. */
  private const val CORE_MAX = 72
  private const val CORE_MIN = 12

  private val SCREWS = setOf("screw", "bolt")
  private val CAMS = setOf("cam", "cam_lock", "camlock")
  private val PEGS = setOf("dowel", "pin", "peg", "plug", "nail", "cap", "rivet")
  private val elLocale = Locale.forLanguageTag("el")

  fun item(kind: ActionKind, hardwareKind: String?, hardware: Boolean): CoachItem {
    val k = hardwareKind?.lowercase(Locale.ROOT)
    return when {
      k in SCREWS -> CoachItem.SCREW
      k in CAMS -> CoachItem.CAM
      k in PEGS -> CoachItem.PEG
      kind == ActionKind.PLACE && !hardware -> CoachItem.BOARD
      else -> CoachItem.OTHER
    }
  }

  fun of(step: Int, a: GuideAction, lang: String): LiveAct {
    val l = a.label(lang)
    return LiveAct(step, a.index, item(a.kind, a.hardwareKind, a.code != null), a.code ?: a.partId, l?.name ?: "", a.text.get(lang), a.ordinal,
        a.sameCount, hasPick = l != null && !a.preinstalled && (a.kind == ActionKind.PLACE || a.kind == ActionKind.FASTEN))
  }

  /** The guide's guided actions in order (`Guide.flatten`). */
  fun of(g: Guide, lang: String): List<LiveAct> = g.steps.flatMap { s -> s.guided.map { of(s.n, it, lang) } }

  // ---- take → put ----

  /** The right part is in the hand: the praise, then what to do with it. */
  fun rightInHand(lang: String, a: LiveAct): String {
    val el = lang == "el"
    val praise =
        when (a.item) {
          CoachItem.SCREW -> if (el) "Σωστή βίδα!" else "Right screw!"
          CoachItem.CAM -> if (el) "Ωραία." else "Good."
          else -> if (el) "Σωστό κομμάτι!" else "Right part!"
        }
    return "$praise ${instruction(lang, a)}"
  }

  /**
   * What to do with the part, as a coach says it: one fixed line for hardware; for a board the manifest's own text
   * when it is short, else its imperative core ([core]), else "put it where it is lit".
   */
  fun instruction(lang: String, a: LiveAct): String {
    val el = lang == "el"
    return when (a.item) {
      CoachItem.SCREW -> if (el) "Βίδωσέ τη στην τρύπα που φωτίζεται." else "Screw it into the hole that is lit."
      CoachItem.CAM -> if (el) "Γύρισέ τη με το κατσαβίδι μέχρι να σφίξει." else "Turn it with the screwdriver until it is tight."
      CoachItem.PEG -> if (el) "Σπρώξ' το στην τρύπα που φωτίζεται, μέχρι τέρμα." else "Push it into the hole that is lit, all the way."
      else ->
          a.text.trim().takeIf { it.length in CORE_MIN..CORE_MAX }
              ?: core(lang, a.text)
              ?: if (el) "Βάλ' το στη θέση που φωτίζεται." else "Put it in the place that is lit."
    }
  }

  /**
   * The imperative core of a manifest instruction: its first sentence without brackets, without the leading
   * "Πάρε … και" / "Take … and" (the part is already in the hand), up to a colon, cut at a clause boundary so that it
   * stays within [CORE_MAX]. Null when nothing usable is left.
   */
  fun core(lang: String, text: String): String? {
    val el = lang == "el"
    var s = text.trim().replace(Regex("\\s*\\([^)]*\\)"), "")
    Regex("[.;!](\\s|$)").find(s)?.let { s = s.substring(0, it.range.first) }
    s = s.replaceFirst(if (el) Regex("^Πάρε\\s.+?\\sκαι\\s+") else Regex("^Take\\s.+?\\sand\\s+", RegexOption.IGNORE_CASE), "")
    s = s.substringBefore(":").trim()
    if (s.length > CORE_MAX) {
      val marks = if (el) listOf(", ", " με ", " ώστε ", " μέχρι ") else listOf(", ", " with ", " so that ", " until ")
      val cut = marks.flatMap { m -> Regex(Regex.escape(m)).findAll(s).map { it.range.first }.toList() }.filter { it in CORE_MIN..CORE_MAX }.maxOrNull() ?: return null
      s = s.substring(0, cut)
    }
    s = s.trim().trimEnd(',', ';', ':').trim()
    if (s.length < CORE_MIN) return null
    return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(if (el) elLocale else Locale.ENGLISH) else it.toString() } + "."
  }

  // ---- while putting it in ----

  /** The hand has been working at the target for a while: one quiet encouragement; null for boards. */
  fun keepGoing(lang: String, item: CoachItem): String? {
    val el = lang == "el"
    return when (item) {
      CoachItem.SCREW, CoachItem.CAM -> if (el) "Ωραία, συνέχισε μέχρι να σφίξει." else "Good, keep going until it is tight."
      CoachItem.PEG -> if (el) "Ωραία, σπρώξε μέχρι τέρμα." else "Good, push it all the way in."
      else -> null
    }
  }

  // ---- put → done ----

  /**
   * The action [cur] is done and [next] comes: the praise and what comes next, as one utterance. Another of the
   * same: "Μπράβο! Πάμε στην επόμενη, 2 από 4. Πάρε: Βίδα έκκεντρου."; the last of several: "Μπράβο, και οι
   * τέσσερις μπήκαν."; a new part: "Τώρα πάρε το αριστερό πλαϊνό."; a new step: "Επόμενο βήμα: …". Null at the
   * guide's end (it has its own line).
   */
  fun done(lang: String, cur: LiveAct, next: LiveAct?): DoneLine? {
    next ?: return null
    val el = lang == "el"
    val sameStep = next.step == cur.step
    if (sameStep && cur.id != null && cur.id == next.id && next.sameCount > 1) {
      val on =
          if (el) "Μπράβο! Πάμε ${El.toNext(cur.name)}, ${next.ordinal} από ${next.sameCount}."
          else "Well done! On to the next one, ${next.ordinal} of ${next.sameCount}."
      return DoneLine(if (next.hasPick) "$on ${PickPrompt.line(lang, next.name, null)}" else on, true)
    }
    val head =
        when {
          cur.sameCount > 1 && cur.ordinal >= cur.sameCount -> allIn(lang, cur)
          sameStep -> if (el) "Μπράβο!" else "Well done!"
          else -> if (el) "Τέλειο." else "Great."
        }
    // nothing to take next (a fastener already in its part, a turn): its own instruction is its entry line
    if (!next.hasPick || next.name.isBlank()) return DoneLine(head, false)
    val count = if (next.sameCount > 1) (if (el) ", ${next.ordinal} από ${next.sameCount}" else ", ${next.ordinal} of ${next.sameCount}") else ""
    val what = if (el) El.acc(next.name) else "the ${lower(next.name, Locale.ENGLISH)}"
    val tail =
        when {
          what == null -> (if (sameStep) "" else "Επόμενο βήμα. ") + PickPrompt.line(lang, next.name, null)
          sameStep -> if (el) "Τώρα πάρε $what$count." else "Now take $what$count."
          else -> if (el) "Επόμενο βήμα: πάρε $what$count." else "Next step: take $what$count."
        }
    val line = "$head $tail"
    return if (line.length <= MAX) DoneLine(line, true) else DoneLine(head, false)
  }

  /** "Μπράβο, και οι τέσσερις μπήκαν." */
  private fun allIn(lang: String, a: LiveAct): String {
    val n = a.sameCount
    if (lang != "el") {
      val state = if (a.item == CoachItem.CAM) "tight" else "in"
      val all = when (n) {
        2 -> "both"
        3 -> "all three"
        4 -> "all four"
        5 -> "all five"
        6 -> "all six"
        else -> "all of them"
      }
      return "Well done, $all are $state."
    }
    val verb = if (a.item == CoachItem.CAM) "έσφιξαν" else "μπήκαν"
    val g = El.gender(a.name)
    val neuter = g == null || g == El.Gender.N
    val word =
        when (n) {
          2 -> "δύο"
          3 -> if (neuter) "τρία" else "τρεις"
          4 -> if (neuter) "τέσσερα" else "τέσσερις"
          5 -> "πέντε"
          6 -> "έξι"
          else -> null
        }
    if (word != null) return "Μπράβο, και ${if (neuter) "τα" else "οι"} $word $verb."
    return "Μπράβο, $verb ${if (neuter) "όλα" else if (g == El.Gender.F) "όλες" else "όλοι"}."
  }

  // ---- wrong ----

  /** A wrong board was lifted: what it is, and what is needed. [seen] and [needed] are the manifest's names. */
  fun wrongBoard(lang: String, seen: String, needed: String): String {
    if (lang != "el") return "That is the ${lower(seen, Locale.ENGLISH)}. You need the ${lower(needed, Locale.ENGLISH)}."
    val s = El.nom(seen)
    val n = El.acc(needed)
    return if (s != null && n != null) "Αυτό είναι $s. Χρειάζεσαι $n." else "Λάθος κομμάτι: $seen. Χρειάζεσαι: $needed."
  }

  /** The verdict badge's first line for a wrong board: what the hand took. */
  fun wrongTitle(lang: String, seen: String): String = if (lang == "el") "Αυτό είναι: $seen" else "This is: $seen"

  /** The part is the wrong way round: the server's own sentence [say], announced. */
  fun wrongWay(lang: String, say: String?): String {
    val el = lang == "el"
    val lead = if (el) "Προσοχή" else "Careful"
    val s = say?.trim().orEmpty()
    return when {
      s.isEmpty() -> if (el) "Προσοχή: είναι ανάποδα. Γύρισέ το." else "Careful: it is the wrong way round. Turn it over."
      s.startsWith(lead, ignoreCase = true) -> s
      else -> "$lead: $s"
    }
  }

  /** Said once in a session, the first time an action ends by time because the headset gives no hands. */
  fun noHands(lang: String): String =
      if (lang == "el") "Δεν βλέπω τα χέρια σου, οπότε προχωράω με τον χρόνο. Πες «πίσω» αν βιάστηκα."
      else "I can't see your hands, so I am going by time. Say \"back\" if I went too fast."

  // ---- the prefetch ----

  /**
   * Every sentence the guide can need from this table (and each action's plain "Πάρε: …"), in the guide's order,
   * each once. [kinds]: the boards that look the same from above, as part ids (ui/GrabJudge.kt): a wrong-board line
   * is made for each board action and each other kind that can still lie loose then.
   */
  fun all(lang: String, acts: List<LiveAct>, kinds: List<List<String>> = emptyList()): List<String> {
    val out = LinkedHashSet<String>()
    out += noHands(lang)
    out += wrongWay(lang, null)
    for ((i, a) in acts.withIndex()) {
      if (a.hasPick) {
        out += PickPrompt.line(lang, a.name, null)
        out += rightInHand(lang, a)
      }
      keepGoing(lang, a.item)?.let { out += it }
      done(lang, a, acts.getOrNull(i + 1))?.let { out += it.text }
      if (a.item == CoachItem.BOARD && a.hasPick && a.id != null) {
        val placed = acts.take(i).mapNotNull { it.id }.toSet()
        for (k in kinds) {
          if (a.id in k) continue
          val seen = k.firstOrNull { it !in placed } ?: continue
          val name = acts.firstOrNull { it.id == seen }?.name?.takeIf { it.isNotBlank() } ?: continue
          out += wrongBoard(lang, name, a.name)
        }
      }
    }
    return out.toList()
  }

  /** "Αριστερό πλαϊνό" → "αριστερό πλαϊνό" inside a sentence; a code in capitals stays. */
  internal fun lower(name: String, locale: Locale): String {
    val n = name.trim()
    val first = n.substringBefore(' ')
    return if (first.length > 1 && first == first.uppercase(locale)) n else n.replaceFirstChar { it.lowercase(locale) }
  }

  /**
   * Greek names come from the manifest without an article, as the object of "Πάρε" (accusative: "πάτο του
   * συρταριού"). The article is guessed from the first word's ending; null when it cannot be (the caller then uses
   * a form without one).
   */
  internal object El {
    enum class Gender {
      M,
      F,
      N,
    }

    /** Masculine nouns in the accusative look like neuters ("πάτο"): the ones furniture guides use. */
    private val masculine = setOf("πάτο", "πείρο", "πίρο", "σύνδεσμο", "οδηγό", "γάντζο", "τάκο", "δακτύλιο", "μεντεσέ", "σύρτη", "κοχλία", "πύρο")
    private const val VOWELS = "αάεέηήιίοόυύωώ"

    private fun first(name: String) = name.trim().lowercase(elLocale).substringBefore(' ')

    fun gender(name: String): Gender? {
      val w = first(name)
      return when {
        w.isEmpty() -> null
        w in masculine -> Gender.M
        w.endsWith("μα") -> Gender.N
        w.last() in "ηήαά" -> Gender.F
        w.last() in "οόιί" -> Gender.N
        else -> null
      }
    }

    /** "το αριστερό πλαϊνό", "την πλάτη του συρταριού", "τη βίδα έκκεντρου", "τον πάτο του συρταριού". */
    fun acc(name: String): String? {
      val n = lower(name, elLocale)
      return when (gender(name) ?: return null) {
        Gender.M -> "τον $n"
        Gender.N -> "το $n"
        Gender.F -> {
          // the final ν stays before a vowel and κ, π, τ, ξ, ψ (and μπ, ντ, γκ)
          val w = first(name)
          val keeps = w[0] in VOWELS || w[0] in "κπτξψ" || w.startsWith("μπ") || w.startsWith("ντ") || w.startsWith("γκ")
          (if (keeps) "την " else "τη ") + n
        }
      }
    }

    /** "η πλάτη του συρταριού", "το αριστερό πλαϊνό", "ο πάτος του συρταριού". */
    fun nom(name: String): String? {
      val n = lower(name, elLocale)
      return when (gender(name) ?: return null) {
        Gender.F -> "η $n"
        Gender.N -> "το $n"
        Gender.M -> "ο " + n.substringBefore(' ') + "ς" + n.substring(n.substringBefore(' ').length)
      }
    }

    /** "στην επόμενη" (βίδα), "στον επόμενο" (πείρο), "στο επόμενο". */
    fun toNext(name: String): String =
        when (gender(name)) {
          Gender.F -> "στην επόμενη"
          Gender.M -> "στον επόμενο"
          else -> "στο επόμενο"
        }
  }
}
