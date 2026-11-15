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
          "coachLookTitle" to "Look at the box or the furniture",
          "coachLookBody" to "Keep it about a metre away.",
          "coachIdTitle" to "Identifying…",
          "coachIdBody" to "Reading the label.",
          "coachFound" to "Found: {product}",
          "coachBuildTitle" to "Making the steps from the guide",
          "coachBuildBody" to "{stage}",
          "coachLocateTitle" to "Finding your step…",
          "coachLocated" to "You're at step {n} of {total}.",
          "coachLockTitle" to "Hold still for a moment",
          "coachLockBody" to "Look at the drawer front and its marker.",
          "coachLocked" to "Locked on.",
          "coachLoading" to "Loading the guide…",
          "coachCards" to "{i} of 5",
          "noServer" to "No answer from the server. Retry.",
          "locateFailed" to "Could not tell the step. Use Back and Next.",
          "buildFailed" to "Could not make the guide",
          "notThis" to "Not this",
          "retry" to "Retry",
          "lost" to "Lost the drawer · show it again",
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
          "coachLookTitle" to "Κοίτα το κουτί ή το έπιπλο",
          "coachLookBody" to "Κράτα το περίπου ένα μέτρο μακριά.",
          "coachIdTitle" to "Αναγνωρίζω…",
          "coachIdBody" to "Διαβάζω την ετικέτα.",
          "coachFound" to "Βρήκα: {product}",
          "coachBuildTitle" to "Φτιάχνω τα βήματα από τον οδηγό",
          "coachBuildBody" to "{stage}",
          "coachLocateTitle" to "Βρίσκω σε ποιο βήμα είσαι…",
          "coachLocated" to "Είσαι στο βήμα {n} από {total}.",
          "coachLockTitle" to "Μείνε ακίνητος για λίγο",
          "coachLockBody" to "Κοίτα την πρόσοψη του συρταριού και το σημάδι της.",
          "coachLocked" to "Κλείδωσε.",
          "coachLoading" to "Φορτώνω τον οδηγό…",
          "coachCards" to "{i} από 5",
          "noServer" to "Δεν απαντά ο server. Ξανά.",
          "locateFailed" to "Δεν βρήκα το βήμα. Πήγαινε με Πίσω και Επόμενο.",
          "buildFailed" to "Ο οδηγός δεν έγινε",
          "notThis" to "Όχι αυτό",
          "retry" to "Ξανά",
          "lost" to "Χάθηκε το συρτάρι · δείξ' το ξανά",
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
      )

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
