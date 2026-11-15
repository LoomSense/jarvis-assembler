// Every word of the build screen (`?mode=build`). BRAND.md §6: sentence case, informal singular in Greek,
// no exclamation marks, no em dashes. The agent's own sentences arrive in English from the server: the ones
// the pipeline always writes the same way are said here in both languages; the agent's free thoughts stay as
// it wrote them (marked lang="en").
import type { Lang } from '../types'

export const BUILD = {
  el: {
    title: 'Νέος οδηγός',
    drop: 'Ρίξε εδώ τον οδηγό',
    pick: 'Διάλεξε τον οδηγό',
    pdf: 'PDF',
    uploading: 'Ανεβαίνει',
    notPdf: 'Μόνο PDF',
    tooBig: 'Πολύ μεγάλο αρχείο',
    failed: 'Δεν ανέβηκε',
    noKey: 'Λείπει το κλειδί από τη διεύθυνση',
    offline: 'Ο server δεν απαντά',
    busy: 'δουλεύει',
    waiting: 'περιμένει εσένα',
    broken: 'σταμάτησε',
    queued: 'σε αναμονή',
    again: 'Νέος οδηγός',
    approve: 'Έγκρινε',
    approving: 'Εγκρίνεται',
    planReady: 'Το σχέδιο είναι έτοιμο',
    kinds: '{n} είδη κομματιών',
    kinds1: '1 είδος κομματιού',
    pieces: '{n} κομμάτια',
    pieces1: '1 κομμάτι',
    steps: '{n} βήματα',
    steps1: '1 βήμα',
    flags: 'Να ελέγξεις',
    open: 'Άνοιξε τον οδηγό',
    ready: 'Ο οδηγός είναι έτοιμος',
    stopped: 'Σταμάτησε',
    lost: 'Χάθηκε η σύνδεση, ξαναδοκιμάζω',
    noJob: 'Δεν βρέθηκε αυτή η δουλειά',
    find: 'όνομα, κωδικός ή link',
    finding: 'Ψάχνω τον οδηγό',
    notFound: 'Δεν βρέθηκε. Δοκίμασε τον κωδικό IKEA ή το link',
    shopDown: 'Το IKEA δεν απάντησε ή δεν έδωσε PDF. Δοκίμασε ξανά',
    notOpened: 'Δεν άνοιξε. Δοκίμασε ξανά',
    several: 'Ποιο από αυτά;',
    toBuild: 'θα χτιστεί',
    accepts: 'PDF ή φωτογραφίες',
    more: 'πρόσθεσε κι άλλες',
    pages: '{n} σελίδες',
    pages1: '1 σελίδα',
    camera: 'Φωτογράφισε μια σελίδα',
    clear: 'Από την αρχή',
    size: 'διαστάσεις, π.χ. 62 x 60 x 135 cm',
    build: 'Φτιάξε τον οδηγό',
    notGuide: 'Μόνο PDF ή φωτογραφίες',
    onePdf: 'Ένα PDF ή μόνο φωτογραφίες',
    tooMany: 'Έως 60 φωτογραφίες',
    heic: 'Στείλε τις φωτογραφίες ως JPEG',
  },
  en: {
    title: 'New guide',
    drop: 'Drop the guide here',
    pick: 'Choose the guide',
    pdf: 'PDF',
    uploading: 'Uploading',
    notPdf: 'PDF only',
    tooBig: 'The file is too large',
    failed: 'Upload failed',
    noKey: 'The key is missing from the address',
    offline: 'The server does not answer',
    busy: 'working',
    waiting: 'waiting for you',
    broken: 'stopped',
    queued: 'queued',
    again: 'New guide',
    approve: 'Approve',
    approving: 'Approving',
    planReady: 'The plan is ready',
    kinds: '{n} kinds of part',
    kinds1: '1 kind of part',
    pieces: '{n} pieces',
    pieces1: '1 piece',
    steps: '{n} steps',
    steps1: '1 step',
    flags: 'To check',
    open: 'Open the guide',
    ready: 'The guide is ready',
    stopped: 'Stopped',
    lost: 'Connection lost, trying again',
    noJob: 'No such job',
    find: 'name, code or link',
    finding: 'Finding the guide',
    notFound: 'Nothing found. Try the IKEA code or the link',
    shopDown: 'IKEA did not answer or gave no PDF. Try again',
    notOpened: 'Could not open it. Try again',
    several: 'Which one?',
    toBuild: 'to build',
    accepts: 'PDF or photos',
    more: 'add more',
    pages: '{n} pages',
    pages1: '1 page',
    camera: 'Photograph a page',
    clear: 'Start over',
    size: 'size, e.g. 62 x 60 x 135 cm',
    build: 'Build the guide',
    notGuide: 'A PDF or photos only',
    onePdf: 'One PDF, or photos only',
    tooMany: 'At most 60 photos',
    heic: 'Send the photos as JPEG',
  },
} as const

export type BuildWord = keyof (typeof BUILD)['en']

export function bw(lang: Lang, key: BuildWord, vars: Record<string, string | number> = {}): string {
  const one = `${key}1` as BuildWord
  let s: string = vars.n === 1 && one in BUILD[lang] ? BUILD[lang][one] : BUILD[lang][key]
  for (const [k, v] of Object.entries(vars)) s = s.replaceAll(`{${k}}`, String(v))
  return s
}

/** What the agent is doing in each stage of the job, in a few words. */
export const STAGE: Record<Lang, Record<string, string>> = {
  el: {
    ingest: 'Διαβάζει τον οδηγό',
    extract_plan: 'Γράφει το σχέδιο',
    await_approval: 'Περιμένει εσένα',
    parts: 'Φτιάχνει τα κομμάτια',
    place: 'Τα τοποθετεί',
    steps: 'Κινεί τα βήματα',
    refs: 'Ετοιμάζει τον έλεγχο',
    export: 'Πακετάρει',
    done: 'Έτοιμο',
  },
  en: {
    ingest: 'Reading the guide',
    extract_plan: 'Writing the plan',
    await_approval: 'Waiting for you',
    parts: 'Making the parts',
    place: 'Placing them',
    steps: 'Animating the steps',
    refs: 'Preparing the checks',
    export: 'Packing',
    done: 'Ready',
  },
}

/**
 * One feed line in the page's language. `verb` is plain words, `code` a path or an id (shown in monospace),
 * `rest` more plain words after it. `own` is true when the words are the server's own English.
 */
export interface Said {
  verb: string
  code?: string
  rest?: string
  own?: boolean
}

/** pattern, words; `true` third: only for a tool call (an agent's thought may also begin with "Writing …") */
type Rule = [RegExp, (m: RegExpMatchArray) => Said, true?]

const clean = (s: string) => s.replace(/`/g, '').trim()
/** 1 βήμα, 8 βήματα */
const pl = (n: string, one: string, many: string) => `${n} ${n === '1' ? one : many}`

const RULES: Record<Lang, Rule[]> = {
  el: [
    [/^Looking at (.+)$/, (m) => ({ verb: 'Κοιτάζει', code: m[1] }), true],
    [/^Writing (.+)$/, (m) => ({ verb: 'Γράφει', code: m[1] }), true],
    [/^Fixing (.+)$/, (m) => ({ verb: 'Διορθώνει', code: m[1] }), true],
    [/^Building the parts and checking the fit \(run 1\)$/, () => ({ verb: 'Χτίζει τα κομμάτια και ελέγχει αν ταιριάζουν' })],
    [/^Building the parts and checking the fit \(run (\d+)\)$/, (m) => ({ verb: `Ξαναχτίζει · δοκιμή ${m[1]}` })],
    [/^The build passes/, () => ({ verb: 'Περνάει: όλα χτίζονται και ταιριάζουν' }), true],
    [/^Not yet: (.*)$/s, (m) => ({ verb: 'Απορρίφθηκε', code: m[1] }), true],
    [/^Built (\S+): (\d+) mate points?$/, (m) => ({ verb: 'Έτοιμο', code: m[1], rest: `${m[2]} σημεία σύνδεσης` })],
    [/^Read page (\d+) of (\d+)$/, (m) => ({ verb: `Σελίδα ${m[1]} από ${m[2]}` })],
    [/^Guide read: (\d+) pages$/, (m) => ({ verb: `Διάβασε τον οδηγό · ${m[1]} σελίδες` })],
    [/^Reading the guide and writing the plan and the parts \((.+)\)\. This takes several minutes\.$/, (m) => ({ verb: 'Διαβάζει τον οδηγό και γράφει το σχέδιο και τα κομμάτια. Θέλει λίγα λεπτά.', code: m[1] })],
    [/^Plan: (\d+) part types, (\d+) hardware types, (\d+) steps, (\d+) flags? for a human to check\.$/, (m) => ({ verb: `Σχέδιο: ${pl(m[1], 'είδος κομματιού', 'είδη κομματιών')}, ${pl(m[2], 'είδος βίδας', 'είδη βιδών')}, ${pl(m[3], 'βήμα', 'βήματα')}, ${pl(m[4], 'σημείο', 'σημεία')} να ελέγξεις` })],
    [/^Plan and parts written and checked: (\d+) s, (\S+) turns, (\d+) builds?(?:, \$([\d.]+))?\.$/, (m) => ({ verb: `Σχέδιο και κομμάτια έτοιμα και ελεγμένα · ${fmtSec(+m[1])} · ${pl(m[2], 'γύρος', 'γύροι')} · ${pl(m[3], 'χτίσιμο', 'χτισίματα')}${m[4] ? ` · $${m[4]}` : ''}` })],
    [/^This guide is a known one: using its hand-checked plan \((.+)\), not a fresh extraction\.$/, (m) => ({ verb: 'Γνωστός οδηγός: παίρνει το ελεγμένο σχέδιο', code: m[1] })],
    [/^Using the hand-checked part scripts for (\d+) parts? \((.+)\)\.$/, (m) => ({ verb: `Παίρνει τα ελεγμένα κομμάτια · ${m[1]}`, code: m[2] })],
    [/^(\d+) of (\d+) part scripts are already written and checked by the build agent\.$/, (m) => ({ verb: `${m[1]} από ${m[2]} κομμάτια είναι ήδη γραμμένα και ελεγμένα` })],
    [/^Placed (\d+) pieces; the worst mate is off by ([\d.]+) mm$/, (m) => ({ verb: `Τοποθέτησε ${m[1]} κομμάτια · η χειρότερη σύνδεση απέχει ${m[2]} mm` })],
    [/^Assembled (\d+) pieces and animated (\d+) steps as (\d+) actions/, (m) => ({ verb: `Συναρμολόγησε ${m[1]} κομμάτια · ${pl(m[2], 'βήμα', 'βήματα')} σε ${m[3]} κινήσεις` })],
    [/^Rendered (\d+) part pictures and (\d+) reference sheets/, (m) => ({ verb: `${m[1]} εικόνες κομματιών και ${pl(m[2], 'φύλλο', 'φύλλα')} για τον ζωντανό έλεγχο` })],
    [/^Compressed the model from (.+) to (.+)$/, (m) => ({ verb: `Συμπίεσε το μοντέλο από ${m[1]} σε ${m[2]}` })],
    [/^Cut (\d+) step drawings from the guide/, (m) => ({ verb: m[1] === '1' ? 'Έκοψε 1 σχέδιο βήματος από τον οδηγό · θέλει μια ματιά' : `Έκοψε ${m[1]} σχέδια βημάτων από τον οδηγό · θέλουν μια ματιά` })],
    [/^Plan approved/, () => ({ verb: 'Εγκρίθηκε' })],
    [/^The agent session ended early \((.+)\)\.$/, (m) => ({ verb: 'Η συνεδρία του πράκτορα έκλεισε νωρίς', code: m[1] })],
    [/^The agent was stopped after (\d+) minutes\.$/, (m) => ({ verb: `Ο πράκτορας σταμάτησε στα ${m[1]} λεπτά` })],
    [/^Retrying from `?([a-z_]+)`?$/, (m) => ({ verb: `Ξανά από το «${STAGE.el[m[1]] ?? m[1]}»` })],
    [/^Walkthrough ready$/, () => ({ verb: 'Ο οδηγός είναι έτοιμος' })],
  ],
  en: [
    [/^Not yet: (.*)$/s, (m) => ({ verb: 'Rejected', code: m[1] }), true],
    [/^Building the parts and checking the fit \(run (\d+)\)$/, (m) => ({ verb: m[1] === '1' ? 'Building the parts and checking the fit' : `Rebuilding · run ${m[1]}` })],
    [/^(Looking at|Writing|Fixing) (.+)$/, (m) => ({ verb: m[1], code: m[2] }), true],
    [/^Built (\S+): (\d+) mate points?$/, (m) => ({ verb: 'Built', code: m[1], rest: `${m[2]} mate points` })],
    [/^Reading the guide and writing the plan and the parts \((.+)\)\. This takes several minutes\.$/, (m) => ({ verb: 'Reading the guide and writing the plan and the parts. This takes a few minutes.', code: m[1] })],
    [/^Plan and parts written and checked: (\d+) s, (\S+) turns, (\d+) builds?(?:, \$([\d.]+))?\.$/, (m) => ({ verb: `Plan and parts written and checked · ${fmtSec(+m[1])} · ${m[2]} turns · ${m[3]} ${m[3] === '1' ? 'build' : 'builds'}${m[4] ? ` · $${m[4]}` : ''}` })],
    [/^This guide is a known one: using its hand-checked plan \((.+)\), not a fresh extraction\.$/, (m) => ({ verb: 'A known guide: using its hand-checked plan', code: m[1] })],
    [/^The agent session ended early \((.+)\)\.$/, (m) => ({ verb: 'The agent session ended early', code: m[1] })],
    [/^Retrying from `?([a-z_]+)`?$/, (m) => ({ verb: `Again from “${STAGE.en[m[1]] ?? m[1]}”` })],
    [/^Plan approved/, () => ({ verb: 'Approved' })],
  ],
}

/** The feed line for a server sentence. Unknown sentences come back as they are, marked as the server's own. */
export function say(lang: Lang, text: string, tool = false): Said {
  const t = text.trim()
  for (const [re, make, toolOnly] of RULES[lang]) {
    if (toolOnly && !tool) continue
    const m = t.match(re)
    if (m) return make(m)
  }
  return { verb: clean(t), own: lang !== 'en' }
}

/**
 * An agent's thought as plain words: its Markdown marks dropped, its lines run together. Returns the pieces in
 * order, `code: true` for what it wrote between backticks.
 */
export function thoughtPieces(text: string): { text: string; code: boolean }[] {
  const plain = text
    .replace(/\*\*|__/g, '')
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s*[-*]\s+/gm, '· ')
    .replace(/\s*\n+\s*/g, ' ')
    .trim()
  return plain.split(/(`[^`]+`)/).filter(Boolean).map((p) => (p.startsWith('`') && p.endsWith('`') && p.length > 2 ? { text: p.slice(1, -1), code: true } : { text: p, code: false }))
}

/** 7 → 0:07, 392 → 6:32, 3725 → 1:02:05 */
export function fmtSec(total: number): string {
  const s = Math.max(0, Math.floor(total))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}
