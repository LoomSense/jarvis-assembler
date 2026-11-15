import type { Lang, Loc } from './types'

// UI copy. Rules from BRAND.md §6: sentence case, verbs on buttons, informal singular in Greek,
// no exclamation marks, no em dashes, label parts joined with a middle dot.
const STRINGS = {
  el: {
    appTitle: 'Jarvis Assembler',
    stepOf: 'Βήμα {n} από {m}',
    phaseGather: 'Μάζεψε',
    phaseBuild: 'Χτίσε',
    phaseCheck: 'Έλεγξε',
    phaseDone: 'έγινε',
    phaseNow: 'τώρα',
    gatherHint: 'Βρες αυτά και βάλε τα δίπλα σου.',
    gotThem: 'Τα έχω',
    next: 'Επόμενο',
    back: 'Πίσω',
    replay: 'Ξανά',
    playStep: 'Όλο το βήμα',
    playStepShort: 'Όλο',
    pause: 'Παύση',
    nextStep: 'Επόμενο βήμα',
    toFinish: 'Τέλος',
    restart: 'Από την αρχή',
    recenter: 'Κεντράρισμα',
    moveOf: 'Κίνηση {i} από {n}',
    screwOf: 'Βίδα {i} από {n}',
    asItStands: 'Έτσι είναι τώρα η κατασκευή. Πάτα Επόμενο για την πρώτη κίνηση.',
    checkLead: 'Σύγκρινέ το με το δικό σου.',
    checkHint: 'Γύρισέ το με το δάχτυλο και δες το από κάθε πλευρά.',
    openDrawing: 'Άνοιξε το σχέδιο του οδηγού',
    guidePage: 'Οδηγός · σελ. {p}',
    guideDrawing: 'Το σχέδιο του οδηγού',
    flagUnclear: 'Το σχέδιο εδώ δεν είναι σαφές. Δες και τον οδηγό.',
    statusCheck: 'Έλεγξε',
    left: 'Μένουν',
    leftAria: 'Απομένουν {n} από τον κωδικό {code}',
    dragToTurn: 'Σύρε για να το γυρίσεις',
    compareTitle: 'Ποια βίδα είναι',
    compareNote: 'ίδια κλίμακα · μήκος κατ’ εκτίμηση',
    coin: 'Κέρμα 1 €',
    coinSize: '23,25 mm',
    approxMm: '≈ {mm} mm',
    estimate: 'εκτίμηση',
    thisOne: 'Αυτή',
    notThisOne: 'Όχι αυτή',
    finishTitle: 'Έτοιμο',
    finishLine: 'Δεν πρέπει να περισσεύει καμία βίδα. Αν περισσεύει, γύρνα πίσω και έλεγξε τα βήματα.',
    loading: 'Φόρτωση μοντέλου',
    loadFailed: 'Το μοντέλο δεν φορτώθηκε.',
    retry: 'Δοκίμασε ξανά',
    noJob: 'Δεν δόθηκε οδηγός. Άνοιξε τη σελίδα με ?job= στη διεύθυνση.',
    noWebgl: 'Αυτός ο browser δεν μπορεί να δείξει 3D.',
    close: 'Κλείσιμο',
    language: 'Γλώσσα',
    piece: 'κομμάτι',
  },
  en: {
    appTitle: 'Jarvis Assembler',
    stepOf: 'Step {n} of {m}',
    phaseGather: 'Gather',
    phaseBuild: 'Build',
    phaseCheck: 'Check',
    phaseDone: 'done',
    phaseNow: 'now',
    gatherHint: 'Find these and put them next to you.',
    gotThem: 'I have them',
    next: 'Next',
    back: 'Back',
    replay: 'Replay',
    playStep: 'Whole step',
    playStepShort: 'All',
    pause: 'Pause',
    nextStep: 'Next step',
    toFinish: 'Finish',
    restart: 'Start again',
    recenter: 'Re-centre',
    moveOf: 'Move {i} of {n}',
    screwOf: 'Screw {i} of {n}',
    asItStands: 'This is how the assembly stands now. Press Next for the first move.',
    checkLead: 'Compare it with yours.',
    checkHint: 'Turn it with your finger and look at it from every side.',
    openDrawing: 'Open the guide drawing',
    guidePage: 'Guide · p. {p}',
    guideDrawing: 'The guide drawing',
    flagUnclear: 'The drawing is unclear here. Look at the guide too.',
    statusCheck: 'Check',
    left: 'Left',
    leftAria: '{n} left of code {code}',
    dragToTurn: 'Drag to turn it',
    compareTitle: 'Which screw it is',
    compareNote: 'same scale · length is an estimate',
    coin: '1 € coin',
    coinSize: '23.25 mm',
    approxMm: '≈ {mm} mm',
    estimate: 'estimate',
    thisOne: 'This one',
    notThisOne: 'Not this one',
    finishTitle: 'Done',
    finishLine: 'No screw should be left over. If one is, go back and check the steps.',
    loading: 'Loading model',
    loadFailed: 'Model failed to load.',
    retry: 'Retry',
    noJob: 'No guide was given. Open the page with ?job= in the address.',
    noWebgl: 'This browser cannot show 3D.',
    close: 'Close',
    language: 'Language',
    piece: 'piece',
  },
} as const

export type StrKey = keyof (typeof STRINGS)['en']

export function tr(lang: Lang, key: StrKey, vars?: Record<string, string | number>): string {
  let s: string = STRINGS[lang][key] ?? STRINGS.en[key] ?? key
  if (vars) for (const [k, v] of Object.entries(vars)) s = s.replaceAll(`{${k}}`, String(v))
  return s
}

// WORKAROUND, remove when the manifest carries Greek names and titles (see README "Known gaps").
// The manifest gives part names, hardware names and step titles in English only. The Greek words below are
// the ones the manifest's own Greek action texts use for the same things. Keys are lower-case English.
const EL_FALLBACK: Record<string, string> = {
  'bottom panel': 'Κάτω πάνελ',
  'bottom panel (pre-assembled)': 'Κάτω πάνελ',
  'side panel': 'Πλαϊνό πάνελ',
  'long side panel (pre-assembled)': 'Πλαϊνό πάνελ',
  'plain slat': 'Απλή σανίδα',
  'end slat': 'Απλή σανίδα',
  'slat with the handle cut-out': 'Σανίδα με τη λαβή',
  'end slat with handle cut-out': 'Σανίδα με τη λαβή',
  'long screw': 'Μακριά βίδα',
  'short screw': 'Κοντή βίδα',
  'stand a side panel against the end of the bottom panel': 'Στήσε ένα πλαϊνό πάνελ στην άκρη του κάτω πάνελ',
  'fix it with two long screws': 'Στερέωσέ το με δύο μακριές βίδες',
  'turn it over and add the second side panel': 'Γύρισέ το ανάποδα και βάλε το δεύτερο πλαϊνό πάνελ',
  'stand the box on end and screw on two end slats': 'Στήσε το κουτί όρθιο και βίδωσε δύο σανίδες στην άκρη',
  'screw on the handle slat': 'Βίδωσε τη σανίδα με τη λαβή',
  'turn the box over and screw two end slats onto the other end': 'Γύρισε το κουτί και βίδωσε δύο σανίδες στην άλλη άκρη',
  'screw on the second handle slat': 'Βίδωσε τη δεύτερη σανίδα με τη λαβή',
}

const capitalise = (s: string) => (s ? s[0].toLocaleUpperCase() + s.slice(1) : s)

/**
 * A manifest text in the wanted language. Accepts a `{en, el}` object (action texts today, hopefully names and
 * titles later) or a plain English string, for which Greek comes from the fallback table when it has the entry.
 */
export function localise(lang: Lang, value: string | Loc | null | undefined): string {
  if (!value) return ''
  if (typeof value === 'object') return value[lang] ?? value.en ?? value.el ?? ''
  if (lang === 'el') return EL_FALLBACK[value.trim().toLowerCase()] ?? value
  return value
}

/** Short display name of a part or a screw type: the manifest's `short` name, else its `name`. */
export function displayName(lang: Lang, item: { name?: string | Loc; short?: string | Loc; names?: Loc }): string {
  // `names` ({en, el}, written by the build agent since f6c34ee) beats the English-only `short` and the fallback table
  const own = item.names?.[lang]
  return capitalise(own || localise(lang, item.short ?? item.name))
}

// The manifest's fasten texts end with "Screw 3 of 8." The page shows that count as its own chip,
// so the sentence is cut from the text shown under it. If the wording changes, nothing is cut.
const COUNTER_TAIL: Record<Lang, RegExp> = {
  el: /\s*Βίδα\s+\d+\s+από\s+\d+\.?\s*$/u,
  en: /\s*Screw\s+\d+\s+of\s+\d+\.?\s*$/u,
}

export function withoutCounter(lang: Lang, text: string): string {
  return text.replace(COUNTER_TAIL[lang], '')
}
