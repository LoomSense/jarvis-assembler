// Every word the page shows or speaks, apart from the names and codes that come from the manifest.
// BRAND.md §6: sentence case, informal singular in Greek, no exclamation marks, no em dashes.
import type { Lang, Loc } from '../types'

export const WORDS = {
  el: {
    prev: 'Προηγούμενο',
    next: 'Επόμενο',
    stock: 'Μένουν στο κουτί',
    progress: 'Πρόοδος',
    hardware: '{name} {code}, {i} από {n}',
    done: 'Έτοιμο',
    turn: 'Γύρνα το',
    otherLanguage: 'English',
    otherLanguageShort: 'en',
  },
  en: {
    prev: 'Previous',
    next: 'Next',
    stock: 'Left in the box',
    progress: 'Progress',
    hardware: '{name} {code}, {i} of {n}',
    done: 'Done',
    turn: 'Turn it',
    otherLanguage: 'Ελληνικά',
    otherLanguageShort: 'ελ',
  },
} as const

// Live guidance (guide/watch.ts). The voice says the server's full sentence; the screen shows these few words.
// State words are lower-case and quiet, beside the camera's round picture. `{x}` is a code or a short name.
export const WATCH = {
  el: {
    control: 'Ζωντανός έλεγχος με την κάμερα',
    simulated: 'προσομοίωση',
    noVision: 'χωρίς εικόνα',
    cantSee: 'δεν βλέπω τώρα',
    reconnecting: 'ξανασυνδέομαι',
    stepUnwatched: 'αυτό το βήμα δεν ελέγχεται',
    refused: 'όχι σε αυτόν τον οδηγό',
    cameraDenied: 'δώσε άδεια για την κάμερα',
    cameraMissing: 'δεν βρέθηκε κάμερα',
    cameraEnded: 'η κάμερα σταμάτησε',
    cameraUnsupported: 'η κάμερα δεν ανοίγει εδώ',
    hold: 'Κρατάς {x}',
    needs: 'θέλει {x}',
    the: '', // Greek reads well without the article here, as the server's own sentences do

    notHere: 'Εδώ δεν θέλει {x}',
    notThis: 'Όχι αυτό',
    right: 'Σωστό',
    notYet: 'Όχι ακόμα',
    show: 'Δείξε το στην κάμερα',
    light: 'Θέλει περισσότερο φως',
    moved: 'Η κάμερα μετακινήθηκε',
    unsure: 'Δεν είμαι σίγουρος',
  },
  en: {
    control: 'Live check with the camera',
    simulated: 'simulated',
    noVision: 'no vision',
    cantSee: "can't see right now",
    reconnecting: 'reconnecting',
    stepUnwatched: 'this step is not checked',
    refused: 'not for this guide',
    cameraDenied: 'allow the camera',
    cameraMissing: 'no camera found',
    cameraEnded: 'the camera stopped',
    cameraUnsupported: "the camera can't open here",
    hold: 'You hold {x}',
    needs: 'it needs {x}',
    the: 'the ',
    notHere: 'No {x} in this step',
    notThis: 'Not this one',
    right: 'Right',
    notYet: 'Not yet',
    show: 'Show it to the camera',
    light: 'It needs more light',
    moved: 'The camera moved',
    unsure: 'Not sure',
  },
} as const

// A turn of the whole assembly in two or three words, by the pose the manifest says it ends in.
// These poses mean the same thing for any product; a pose that is not here falls back to the manifest's own sentence.
const POSES: Record<Lang, Record<string, string>> = {
  el: {
    upside_down: 'Γύρνα το ανάποδα',
    upright: 'Γύρνα το κανονικά',
    on_side: 'Γύρνα το στο πλάι',
    on_end: 'Στήσε το όρθιο',
    on_end_a: 'Στήσε το όρθιο',
    on_end_b: 'Στην άλλη άκρη',
    on_other_end: 'Στην άλλη άκρη',
  },
  en: {
    upside_down: 'Turn it over',
    upright: 'Set it upright',
    on_side: 'Lay it on its side',
    on_end: 'Stand it on end',
    on_end_a: 'Stand it on end',
    on_end_b: 'Onto the other end',
    on_other_end: 'Onto the other end',
  },
}

/** The first sentence of a manifest text, without its full stop. */
export function firstSentence(lang: Lang, text: Loc | undefined): string {
  const whole = (text?.[lang] ?? text?.en ?? text?.el ?? '').trim()
  const cut = whole.search(/[.;!·](\s|$)/u)
  return (cut < 0 ? whole : whole.slice(0, cut)).trim()
}

/**
 * What to show while the whole assembly turns: the short phrase for a pose the page knows, otherwise the first
 * clause of the manifest's own action text, otherwise two plain words.
 */
export function turnWords(lang: Lang, pose: string | undefined, text: Loc | undefined): string {
  const known = pose ? POSES[lang][pose] : undefined
  if (known) return known
  const sentence = firstSentence(lang, text)
  const clause = sentence.split(/,\s/u)[0]
  return clause || sentence || WORDS[lang].turn
}
