import '../styles/overview.css'
import type { AgentEvent } from './stream'

// The agent at work, on the build screen (`?mode=build&job=`). Two things, and no panel:
//
//   top left      a row of dots, one per stage, and the time so far (with how long it usually takes while the
//                 agent reads the guide, the long wait)
//   the centre    under build.ts's one picture of the work and its stage line: what the agent is doing right
//                 now, in plain words ("Reads page 8 of the guide", "Designs a part in 3D", "Compares each step
//                 with the guide's drawing"). It changes with the picture.
//
// No file names, no code, no model, no cost: those are for the builders, in the full feed (`&feed=1`).
// build.ts sends every event it applies as a window `agj:event`; nothing here talks to the server's feed itself.

type Lang = 'el' | 'en'
const W = {
  en: {
    usually: 'usually 7–10 min',
    page: (n: string, of?: string) => (of ? `Reads page ${n} of ${of} of the guide` : `Reads page ${n} of the guide`),
    looksAtModel: 'Looks at the 3D model it made',
    gettingReady: 'Gets ready to read the guide',
    studies: 'Studies how to make each part',
    thinking: 'Works out how the pieces go together',
    designs: (part: string) => (part ? `Designs the ${part} in 3D` : 'Designs a part in 3D'),
    redesigns: (part: string) => (part ? `Corrects the ${part}` : 'Corrects a part'),
    writesSteps: 'Writes down the steps of the assembly',
    fixesSteps: 'Corrects the steps',
    tryFit: 'Tries whether the parts fit together',
    fits: 'The parts fit together',
    noFit: 'Something does not fit yet. It is fixing it',
    compares: 'Compares each step with the guide’s drawing',
    matched: (n: string, of: string) => `${n} of ${of} steps match the guide`,
    approval: 'The plan is ready',
    built: (part: string) => (part ? `Made the ${part}` : 'Made a part'),
    place: 'Puts every piece in its place',
    steps: 'Animates each step',
    finish: 'Gets the guide ready for the phone and the Quest',
    done: '',
  },
  el: {
    usually: 'συνήθως 7–10 λεπτά',
    page: (n: string, of?: string) => (of ? `Διαβάζει τη σελίδα ${n} από ${of} του οδηγού` : `Διαβάζει τη σελίδα ${n} του οδηγού`),
    looksAtModel: 'Κοιτάζει το 3D μοντέλο που έφτιαξε',
    gettingReady: 'Ετοιμάζεται να διαβάσει τον οδηγό',
    studies: 'Μελετά πώς θα φτιάξει κάθε κομμάτι',
    thinking: 'Σκέφτεται πώς δένουν τα κομμάτια μεταξύ τους',
    // the part names are English ids until the guide is finished: in Greek the sentence stays general
    designs: () => 'Σχεδιάζει ένα κομμάτι σε 3D',
    redesigns: () => 'Διορθώνει ένα κομμάτι',
    writesSteps: 'Γράφει τα βήματα της συναρμολόγησης',
    fixesSteps: 'Διορθώνει τα βήματα',
    tryFit: 'Δοκιμάζει αν τα κομμάτια ταιριάζουν μεταξύ τους',
    fits: 'Τα κομμάτια ταιριάζουν',
    noFit: 'Κάτι δεν ταιριάζει ακόμα. Το διορθώνει',
    compares: 'Συγκρίνει κάθε βήμα με το σχέδιο του οδηγού',
    matched: (n: string, of: string) => `${n} από ${of} βήματα ταιριάζουν με τον οδηγό`,
    approval: 'Το σχέδιο είναι έτοιμο',
    built: () => 'Έφτιαξε ένα κομμάτι σε 3D',
    place: 'Βάζει κάθε κομμάτι στη θέση του',
    steps: 'Φτιάχνει την κίνηση κάθε βήματος',
    finish: 'Ετοιμάζει τον οδηγό για το κινητό και το Quest',
    done: '',
  },
} as const

const STAGES = ['ingest', 'extract_plan', 'await_approval', 'parts', 'place', 'steps', 'finish', 'done'] as const
type StageId = (typeof STAGES)[number]
const stageOf = (s: string): StageId | null =>
  s === 'refs' || s === 'export' ? 'finish' : (STAGES as readonly string[]).includes(s) ? (s as StageId) : null
const IMAGE = /\.(png|jpe?g|webp)$/i
const clock = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`
/** "job/parts/side_panel.py" -> "side panel" (English only: the ids are English) */
const partName = (path: string) => (/parts\/([^/]+)\.py$/.exec(path)?.[1] ?? '').replace(/_\d+$/, '').replace(/_/g, ' ')

export function startOverview(): void {
  let root: HTMLElement | null = null
  const t = { first: 0, last: 0, ended: false }
  const seen = new Set<number>()
  let current: StageId | null = null
  /** the agent itself is at work (not a known guide's checked plan): the wait is long, say how long */
  let agent = false
  /** it has opened a page of the guide: a text file after that is its own tools, not the start */
  let readPage = false
  /** what it is doing, as a function of the language, so the switch rewrites it */
  let doing: (w: (typeof W)[Lang]) => string = () => ''
  let heard = Date.now()

  const lang = (): Lang => (document.documentElement.lang === 'en' ? 'en' : 'el')
  const $ = <T extends HTMLElement>(sel: string) => root!.querySelector<T>(sel)!

  const mount = () => {
    root = document.createElement('aside')
    root.id = 'agentview'
    root.innerHTML = `
      <section class="rail" aria-hidden="true">
        <ol class="stages">${STAGES.filter((s) => s !== 'done').map((s) => `<li data-stage="${s}"></li>`).join('')}</ol>
        <p class="sum"><span data-v="time">0:00</span><span data-v="usually" hidden></span></p>
      </section>`
    document.body.append(root)
    document.documentElement.dataset.agentview = '1'
    window.setInterval(() => {
      if (t.ended || !t.first) return
      // no file opened or written for a while while the agent reads the guide: it is reasoning about what it read
      if (current === 'extract_plan' && agent && Date.now() - heard > 6000) {
        doing = (w) => w.thinking
        paint()
      } else paintTime()
    }, 1000)
    new MutationObserver(paint).observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] })
  }

  const paintTime = () => {
    const end = t.ended ? t.last : Math.max(t.last, Date.now())
    $('[data-v="time"]').textContent = clock(Math.max(0, end - t.first))
    const u = $('[data-v="usually"]')
    u.hidden = !(agent && current === 'extract_plan' && !t.ended)
    u.textContent = W[lang()].usually
  }

  /** The plain-words line, under the build screen's picture and its stage line (#now, made by build.ts). */
  const line = (): HTMLParagraphElement | null => {
    const what = document.querySelector('#now .what')
    if (!what) return null
    let p = what.parentElement!.querySelector<HTMLParagraphElement>('p.doing')
    if (!p) {
      p = document.createElement('p')
      p.className = 'doing'
      p.setAttribute('aria-live', 'polite')
      what.after(p)
    }
    return p
  }

  const paint = () => {
    if (!root) return
    const at = current ? STAGES.indexOf(current) : -1
    root.querySelectorAll<HTMLLIElement>('.stages li').forEach((li, i) => {
      li.dataset.state = t.ended || i < at ? 'done' : i === at ? 'now' : 'todo'
    })
    const p = line()
    if (p) p.textContent = t.ended ? '' : doing(W[lang()])
    paintTime()
  }

  const take = (e: AgentEvent) => {
    if (seen.has(e.seq)) return
    seen.add(e.seq)
    if (!root) mount()
    heard = Date.now()
    if (!t.first) t.first = e.ts
    t.last = Math.max(t.last, e.ts)
    const text = e.text ?? ''

    const s = e.type === 'done' ? 'done' : stageOf(e.stage)
    if (s && !(e.type === 'progress' && / finished$/.test(text))) {
      if (current !== s) {
        // each stage starts with its own sentence, until the agent says something more precise
        doing = s === 'place' ? (w) => w.place : s === 'steps' ? (w) => w.steps : s === 'finish' ? (w) => w.finish : () => ''
        if (s === 'await_approval') doing = (w) => w.approval
      }
      current = s
    }
    if (e.type === 'done') t.ended = true

    const page = /^Read page (\d+) of (\d+)/.exec(text)
    if (page) doing = (w) => w.page(page[1], page[2])

    if (e.type === 'tool_call') {
      agent = true
      const read = /^Looking at (.+)$/.exec(text)
      const wrote = /^(Writing|Fixing) (.+)$/.exec(text)
      if (e.tool === 'read' && read) {
        const n = /page-0*(\d+)\./.exec(read[1])
        if (n) {
          readPage = true
          doing = (w) => w.page(n[1])
        } else if (IMAGE.test(read[1])) doing = (w) => w.looksAtModel
        else doing = (w) => (readPage ? w.studies : w.gettingReady)
      } else if ((e.tool === 'write' || e.tool === 'edit') && wrote) {
        const fix = wrote[1] === 'Fixing'
        if (/plan\.json$/.test(wrote[2])) doing = (w) => (fix ? w.fixesSteps : w.writesSteps)
        else {
          const part = partName(wrote[2])
          doing = (w) => (fix ? w.redesigns(part) : w.designs(part))
        }
      } else if (e.tool === 'build') doing = (w) => w.tryFit
      else if (e.tool === 'check') doing = (w) => w.compares
    } else if (e.type === 'tool_result' && e.tool === 'build') {
      doing = (w) => (e.level === 'success' ? w.fits : w.noFit)
    } else if (e.type === 'tool_result' && e.tool === 'check') {
      const m = /(\d+) of (\d+) steps match/.exec(text)
      if (m) doing = (w) => w.matched(m[1], m[2])
    } else if (e.type === 'part_status' && e.level !== 'error' && e.partId) {
      const part = e.partId.replace(/_/g, ' ')
      doing = (w) => w.built(/^\d+$/.test(part) ? '' : part)
    } else if (e.type === 'error') {
      doing = () => '' // build.ts says what went wrong, in the line under this one
    }
    paint()
  }

  window.addEventListener('agj:event', (ev) => take((ev as CustomEvent<AgentEvent>).detail))
}
