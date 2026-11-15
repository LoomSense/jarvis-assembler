// The module's own sentences, for what the server cannot say (no socket, no camera) and for the states it
// reports without a sentence. BRAND.md §6: short, plain, no blame, informal singular in Greek. The
// "unavailable" and "paused" lines are the server's own wording (server/perception/phrases.py).
import type { Lang } from './protocol'

export type Tone = 'off' | 'watching' | 'right' | 'wrong' | 'check' | 'unsure' | 'paused' | 'unavailable'

interface Words {
  simulated: string
  /** set before a sentence that came from the debug script */
  simTag: string
  unavailable: string
  paused: string
  reconnecting: string
  /** the socket never opened in this run */
  cannotConnect: string
  refused: string
  /** the server has no questions for this step, so it looks at no frame of it */
  stepUnwatched: string
  cameraDenied: string
  cameraMissing: string
  cameraEnded: string
  cameraUnsupported: string
  /** the word of each ring state, for screen readers (BRAND.md §3) */
  tone: Record<Tone, string>
}

export const WORDS: Record<Lang, Words> = {
  el: {
    simulated: 'Προσομοίωση. Οι απαντήσεις δεν έρχονται από την κάμερα.',
    simTag: 'προσομοίωση',
    unavailable: 'Δεν έχω εικόνα. Μπορώ όμως να σου λέω τα βήματα.',
    paused: 'Δεν βλέπω αυτή τη στιγμή. Θα σου πω μόλις ξαναδώ.',
    reconnecting: 'Η σύνδεση χάθηκε. Ξαναδοκιμάζω.',
    cannotConnect: 'Δεν έχω σύνδεση για ζωντανό έλεγχο. Ξαναδοκιμάζω.',
    refused: 'Αυτός ο οδηγός δεν είναι έτοιμος για ζωντανό έλεγχο.',
    stepUnwatched: 'Αυτό το βήμα δεν μπορώ να το ελέγξω. Ακολούθησε το σχέδιο.',
    cameraDenied: 'Η κάμερα είναι κλειστή. Δώσε άδεια στον περιηγητή για να βλέπω.',
    cameraMissing: 'Δεν βρέθηκε κάμερα.',
    cameraEnded: 'Η κάμερα σταμάτησε. Ξεκίνα ξανά τον έλεγχο.',
    cameraUnsupported: 'Αυτή η σελίδα δεν μπορεί να ανοίξει την κάμερα.',
    tone: {
      off: 'Κάμερα κλειστή',
      watching: 'Η κάμερα βλέπει',
      right: 'Σωστό',
      wrong: 'Λάθος',
      check: 'Έλεγξε',
      unsure: 'Δεν είμαι σίγουρος',
      paused: 'Η εικόνα σταμάτησε',
      unavailable: 'Χωρίς εικόνα',
    },
  },
  en: {
    simulated: 'Simulation. These answers do not come from the camera.',
    simTag: 'simulated',
    unavailable: 'Vision is not available. I can still take you through the steps.',
    paused: "I can't see right now. I'll tell you when I can again.",
    reconnecting: 'Connection lost. Trying again.',
    cannotConnect: 'No connection for live checks. Trying again.',
    refused: 'This guide is not ready for live checks.',
    stepUnwatched: "I can't check this step. Follow the drawing.",
    cameraDenied: 'The camera is blocked. Allow it in the browser so I can see.',
    cameraMissing: 'No camera found.',
    cameraEnded: 'The camera stopped. Start the check again.',
    cameraUnsupported: 'This page cannot open the camera.',
    tone: {
      off: 'Camera off',
      watching: 'Camera on',
      right: 'Right',
      wrong: 'Wrong',
      check: 'Check',
      unsure: 'Not sure',
      paused: 'Vision paused',
      unavailable: 'No vision',
    },
  },
}
