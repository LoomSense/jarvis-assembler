package com.agj.android.ui

import java.util.Locale

/**
 * UI copy in Greek and English. Coach and guide strings come from the WebXR i18n (client/src/xr/i18n.ts) and the
 * native spec's coach table (docs/design/XR_NATIVE_SPEC.md §5.4); step text comes from the manifest.
 */
object Strings {
  private val en =
      mapOf(
          "step" to "Step {n} of {total}",
          "page" to "Page {page}",
          "back" to "Back",
          "next" to "Next",
          "finish" to "Finish",
          "repeat" to "Repeat",
          "whereAmI" to "Where am I",
          "align" to "Re-align",
          "alignLook" to "Look at the part on the table.",
          "coachLookTitle" to "Look at the box or the furniture",
          "coachLookBody" to "Keep it about a metre away.",
          "coachIdTitle" to "Identifying…",
          "coachIdBody" to "Reading the label.",
          "coachFound" to "Found: {product}",
          "coachBuildTitle" to "Making the steps from the guide",
          "coachBuildBody" to "{stage}",
          "coachLocateTitle" to "Finding your step…",
          "coachLocated" to "You're at step {n} of {total}.",
          "coachLockTitle" to "Look down at the part",
          "coachLockBody" to "Hold that view for a few seconds.",
          "coachLocked" to "Locked on.",
          "coachLoading" to "Loading the guide…",
          "coachCards" to "{i} of 5",
          "noServer" to "No answer from the server. Retry.",
          "locateFailed" to "Could not tell the step. Use Back and Next.",
          "buildFailed" to "Could not make the guide",
          "notThis" to "Not this",
          "retry" to "Retry",
          "lost" to "Lost the drawer · show it again",
          "lostSay" to "I can't see the drawer. Look at it again.",
          "unsureSay" to "I can't see it clearly. Show me the part.",
          "correct" to "Right",
          "wrong" to "Wrong",
          "unsure" to "Not sure",
          "turnIt" to "Turn it",
          "allDone" to "Your drawer is done",
          "summary" to "{steps} steps · {actions} moves",
          "stageRead" to "Reading the guide",
          "stageWait" to "Waiting for approval",
          "stageParts" to "Making the parts",
          "stageSteps" to "Making the steps",
          "speak" to "Speak",
          "listening" to "Listening…",
          "thinking" to "One moment…",
          "heard" to "Heard: {t}",
          "voiceFailed" to "Didn't catch that. Use the buttons.",
          "take" to "Take",
          "wrongSay" to "That's not the part for this step. Check the drawing.",
          // held part: the verdict badge, the check action, the pick prompt (ui/HeldVerdict.kt)
          "heldRight" to "Right part",
          "heldWrong" to "Wrong part",
          "heldNeed" to "You need: {name}",
          "heldUnsure" to "Show me the part closer",
          "heldRightSay" to "Correct. {name}.",
          "heldRightSayPlain" to "Correct.",
          "heldUnsureSay" to "Show me the part closer.",
          "heldChecking" to "Checking…",
          "heldNoAnswer" to "No answer. Show it again.",
          "check" to "Check",
          "needN" to "You need {n}",
          "inViewN" to "{seen} of {n} in view",
          "pickSay" to "Take: {name}, {where}.",
          "pickSayPlain" to "Take: {name}.",
          "whereLeft" to "on your left",
          "whereAhead" to "in front of you",
          "whereRight" to "on your right",
          "cameraLive" to "camera live · {fps} fps",
          "cameraOff" to "camera · no frames",
          // ACTION-COACH: the two phases of an action (ui/ActionCoach.kt)
          "coachPick" to "Take: {name}",
          "coachPlace" to "Put it here",
          "coachPhasePut" to "Place",
          "coachGotIt" to "Got it",
      )

  private val el =
      mapOf(
          "step" to "Βήμα {n} από {total}",
          "page" to "Σελίδα {page}",
          "back" to "Πίσω",
          "next" to "Επόμενο",
          "finish" to "Τέλος",
          "repeat" to "Ξανά",
          "whereAmI" to "Πού είμαι;",
          "align" to "Ευθυγράμμιση",
          "alignLook" to "Κοίτα το κομμάτι πάνω στο τραπέζι.",
          "coachLookTitle" to "Κοίτα το κουτί ή το έπιπλο",
          "coachLookBody" to "Κράτα το περίπου ένα μέτρο μακριά.",
          "coachIdTitle" to "Αναγνωρίζω…",
          "coachIdBody" to "Διαβάζω την ετικέτα.",
          "coachFound" to "Βρήκα: {product}",
          "coachBuildTitle" to "Φτιάχνω τα βήματα από τον οδηγό",
          "coachBuildBody" to "{stage}",
          "coachLocateTitle" to "Βρίσκω σε ποιο βήμα είσαι…",
          "coachLocated" to "Είσαι στο βήμα {n} από {total}.",
          "coachLockTitle" to "Κοίτα το κομμάτι από πάνω",
          "coachLockBody" to "Μείνε έτσι για λίγα δευτερόλεπτα.",
          "coachLocked" to "Κλείδωσε.",
          "coachLoading" to "Φορτώνω τον οδηγό…",
          "coachCards" to "{i} από 5",
          "noServer" to "Δεν απαντά ο server. Ξανά.",
          "locateFailed" to "Δεν βρήκα το βήμα. Πήγαινε με Πίσω και Επόμενο.",
          "buildFailed" to "Ο οδηγός δεν έγινε",
          "notThis" to "Όχι αυτό",
          "retry" to "Ξανά",
          "lost" to "Χάθηκε το συρτάρι · δείξ' το ξανά",
          "lostSay" to "Δεν βλέπω το συρτάρι. Κοίτα το ξανά.",
          "unsureSay" to "Δεν το βλέπω καθαρά. Δείξε μου το κομμάτι.",
          "correct" to "Σωστά",
          "wrong" to "Λάθος",
          "unsure" to "Δεν είμαι σίγουρος",
          "turnIt" to "Γύρισμα",
          "allDone" to "Το συρτάρι είναι έτοιμο",
          "summary" to "{steps} βήματα · {actions} κινήσεις",
          "stageRead" to "Διαβάζω τον οδηγό",
          "stageWait" to "Περιμένει έγκριση",
          "stageParts" to "Φτιάχνω τα κομμάτια",
          "stageSteps" to "Φτιάχνω τα βήματα",
          "speak" to "Μίλα",
          "listening" to "Ακούω…",
          "thinking" to "Μια στιγμή…",
          "heard" to "Άκουσα: {t}",
          "voiceFailed" to "Δεν το έπιασα. Πάτα τα κουμπιά.",
          "take" to "Πάρε",
          "wrongSay" to "Αυτό δεν είναι το κομμάτι του βήματος. Δες το σχέδιο.",
          // κρατημένο εξάρτημα: σήμα ετυμηγορίας, έλεγχος, «ποιο να πάρεις» (ui/HeldVerdict.kt)
          "heldRight" to "Σωστό",
          "heldWrong" to "Λάθος εξάρτημα",
          "heldNeed" to "Χρειάζεσαι: {name}",
          "heldUnsure" to "Δείξε μου το εξάρτημα πιο κοντά",
          "heldRightSay" to "Σωστό. {name}.",
          "heldRightSayPlain" to "Σωστό.",
          "heldUnsureSay" to "Δείξε μου το εξάρτημα πιο κοντά.",
          "heldChecking" to "Ελέγχω…",
          "heldNoAnswer" to "Δεν πήρα απάντηση. Δείξε το ξανά.",
          "check" to "Έλεγχος",
          "needN" to "Χρειάζεσαι {n}",
          "inViewN" to "φαίνονται {seen} από {n}",
          "pickSay" to "Πάρε: {name}, {where}.",
          "pickSayPlain" to "Πάρε: {name}.",
          "whereLeft" to "αριστερά σου",
          "whereAhead" to "μπροστά σου",
          "whereRight" to "δεξιά σου",
          "cameraLive" to "κάμερα ζωντανά · {fps} fps",
          "cameraOff" to "κάμερα · χωρίς εικόνα",
          // ACTION-COACH: οι δύο φάσεις μιας κίνησης (ui/ActionCoach.kt)
          "coachPick" to "Πάρε: {name}",
          "coachPlace" to "Βάλ' το εδώ",
          "coachPhasePut" to "Βάλε",
          "coachGotIt" to "Το πήρα",
      )

  /** Every key of a language's table (tests: both languages carry the same keys). */
  internal fun keys(lang: String): Set<String> = if (lang == "el") el.keys else en.keys

  fun t(lang: String, key: String, vararg vars: Pair<String, Any?>): String {
    val raw = (if (lang == "el") el[key] else en[key]) ?: en[key] ?: key
    return vars.fold(raw) { s, (k, v) -> s.replace("{$k}", v?.toString() ?: "") }
  }

  /** Uppercase for labels; Greek loses its tonos in all caps ("ΒΗΜΑ", not "ΒΉΜΑ"), spec §3.2. */
  fun upper(lang: String, s: String): String {
    if (lang != "el") return s.uppercase(Locale.ENGLISH)
    val stripped = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace("́", "").replace("̈́", "̈")
    return java.text.Normalizer.normalize(stripped.uppercase(Locale.forLanguageTag("el")), java.text.Normalizer.Form.NFC)
  }
}
