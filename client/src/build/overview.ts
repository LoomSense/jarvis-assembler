import '../styles/overview.css'
import { apiUrl, fileUrl } from '../api'
import type { AgentEvent } from './stream'

// The agent at work, beside the feed of `?mode=build&job=`: which stage the job is in and what the agent is doing
// in it right now, how much it has read, written, built and checked, and what it is looking at or has just
// written (the guide page, its own render next to the guide's drawing, the code of a part).
// build.ts sends every event it applies as a window `agj:event`; nothing here talks to the server's feed itself.

type Lang = 'el' | 'en'
const W = {
  en: {
    title: 'The agent at work', sees: 'What the agent sees', wrote: 'The agent wrote', compare: 'Checking against the guide',
    guide: 'The guide', ours: 'Its 3D model', alike: 'alike',
    ingest: 'Reads the guide', extract_plan: 'Writes the plan and the parts', await_approval: 'Your approval',
    parts: 'Builds every part', place: 'Fits the parts together', steps: 'Animates each step', finish: 'Finishes the guide', done: 'Ready',
    time: 'Time', read: 'Files read', written: 'Files written', builds: 'Builds', checks: 'Checks', cost: 'Cost', turns: 'Turns',
    reading: 'Reading', writing: 'Writing', fixing: 'Fixing', building: 'Building and testing the fit', checking: 'Comparing with the guide’s drawings',
    thinking: 'Thinking', passed: 'The parts fit', failed: 'Something does not fit yet', page: 'page', nothing: 'Nothing yet',
  },
  el: {
    title: 'Ο πράκτορας δουλεύει', sees: 'Τι βλέπει ο πράκτορας', wrote: 'Ο πράκτορας έγραψε', compare: 'Έλεγχος με τον οδηγό',
    guide: 'Ο οδηγός', ours: 'Το 3D μοντέλο του', alike: 'ομοιότητα',
    ingest: 'Διαβάζει τον οδηγό', extract_plan: 'Γράφει το σχέδιο και τα κομμάτια', await_approval: 'Η έγκρισή σου',
    parts: 'Φτιάχνει κάθε κομμάτι', place: 'Ταιριάζει τα κομμάτια', steps: 'Κινεί κάθε βήμα', finish: 'Ολοκληρώνει τον οδηγό', done: 'Έτοιμος',
    time: 'Χρόνος', read: 'Αρχεία που διάβασε', written: 'Αρχεία που έγραψε', builds: 'Δοκιμές', checks: 'Έλεγχοι', cost: 'Κόστος', turns: 'Γύροι',
    reading: 'Διαβάζει', writing: 'Γράφει', fixing: 'Διορθώνει', building: 'Χτίζει και δοκιμάζει αν ταιριάζουν', checking: 'Συγκρίνει με τα σχέδια του οδηγού',
    thinking: 'Σκέφτεται', passed: 'Τα κομμάτια ταιριάζουν', failed: 'Κάτι δεν ταιριάζει ακόμα', page: 'σελίδα', nothing: 'Τίποτα ακόμα',
  },
} as const

const STAGES = ['ingest', 'extract_plan', 'await_approval', 'parts', 'place', 'steps', 'finish', 'done'] as const
type StageId = (typeof STAGES)[number]
const stageOf = (s: string): StageId | null =>
  s === 'refs' || s === 'export' ? 'finish' : (STAGES as readonly string[]).includes(s) ? (s as StageId) : null
const IMAGE = /\.(png|jpe?g|webp)$/i
const clock = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`
const base = (p: string) => p.split('/').pop() ?? p

export function startOverview(): void {
  const q = new URLSearchParams(location.search)
  const key = q.get('key')
  let root: HTMLElement | null = null
  let job = ''
  const t = { first: 0, last: 0, ended: false }
  const n = { read: 0, written: 0, builds: 0, checks: 0 }
  const seen = new Set<number>()
  const reached = new Map<StageId, number>() // stage -> ts it started
  let current: StageId | null = null
  let doing = ''
  let cost = ''
  let turns = ''
  let model = ''
  let codeFor = ''
  let heard = Date.now()

  const w = () => W[(document.documentElement.lang === 'en' ? 'en' : 'el') as Lang]
  const $ = <T extends HTMLElement>(sel: string) => root!.querySelector<T>(sel)!

  const mount = () => {
    root = document.createElement('aside')
    root.id = 'agentview'
    root.innerHTML = `
      <section class="rail">
        <h2><span class="t"></span><code class="model" hidden></code></h2>
        <ol class="stages">${STAGES.map((s) => `<li data-stage="${s}"><i></i><div><b></b><small></small></div><time></time></li>`).join('')}</ol>
        <dl class="stats">
          <div><dt data-k="time"></dt><dd data-v="time">0:00</dd></div>
          <div><dt data-k="read"></dt><dd data-v="read">0</dd></div>
          <div><dt data-k="written"></dt><dd data-v="written">0</dd></div>
          <div><dt data-k="builds"></dt><dd data-v="builds">0</dd></div>
          <div><dt data-k="checks"></dt><dd data-v="checks">0</dd></div>
          <div><dt data-k="cost"></dt><dd data-v="cost">–</dd></div>
        </dl>
      </section>
      <section class="eye">
        <h2></h2>
        <div class="show"><p class="none"></p></div>
        <p class="cap"></p>
      </section>`
    document.body.append(root)
    document.documentElement.dataset.agentview = '1'
    window.setInterval(() => {
      if (t.ended || !t.first) return
      // no file opened or written for a while inside the agent's stage: it is reasoning about what it read
      if (current === 'extract_plan' && Date.now() - heard > 6000 && !/^(Building|Χτίζει|Comparing|Συγκρίνει)/.test(doing)) {
        doing = w().thinking
        paint()
      } else paintStats()
    }, 1000)
    new MutationObserver(paint).observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] })
  }

  const paintStats = () => {
    const end = t.ended ? t.last : Math.max(t.last, Date.now())
    $('[data-v="time"]').textContent = clock(Math.max(0, end - t.first))
    for (const k of ['read', 'written', 'builds', 'checks'] as const) $(`[data-v="${k}"]`).textContent = String(n[k])
    $('[data-v="cost"]').textContent = cost || '–'
  }

  const paint = () => {
    if (!root) return
    const words = w()
    $('.rail h2 .t').textContent = words.title
    const m = $('.model')
    m.hidden = !model
    m.textContent = model
    for (const k of ['time', 'read', 'written', 'builds', 'checks', 'cost'] as const) $(`[data-k="${k}"]`).textContent = words[k]
    const at = current ? STAGES.indexOf(current) : -1
    root.querySelectorAll<HTMLLIElement>('.stages li').forEach((li, i) => {
      const id = li.dataset.stage as StageId
      li.dataset.state = t.ended && id === 'done' ? 'done' : i < at ? 'done' : i === at ? 'now' : 'todo'
      li.querySelector('b')!.textContent = words[id]
      li.querySelector('small')!.textContent = i === at && !t.ended ? doing : ''
      const from = reached.get(id)
      const next = STAGES.slice(i + 1).map((s) => reached.get(s)).find((v) => v) ?? (i === at ? (t.ended ? t.last : 0) : 0)
      li.querySelector('time')!.textContent = from && next && next > from ? clock(next - from) : ''
    })
    if (!$('.eye .show').querySelector('img, pre')) {
      $('.eye h2').textContent = words.sees
      $('.eye .none').textContent = words.nothing
    }
    paintStats()
  }

  // ---- the right-hand panel
  const showImage = (url: string, caption: string) => {
    codeFor = '' // a file still being fetched must not replace this
    $('.eye h2').textContent = w().sees
    $('.eye .show').innerHTML = `<img alt="" />`
    $<HTMLImageElement>('.eye img').src = url
    $('.eye .cap').textContent = caption
  }
  const showPair = (guide: string, ours: string, score: number | null, caption: string, level: string) => {
    const words = w()
    codeFor = ''
    $('.eye h2').textContent = words.compare
    $('.eye .show').innerHTML = `<div class="pair"><figure><img alt="" /><figcaption>${words.guide}</figcaption></figure>
      <figure><img alt="" /><figcaption>${words.ours}</figcaption></figure></div>
      ${score === null ? '' : `<p class="score" data-level="${level}">${Math.round(score * 100)}% ${words.alike}</p>`}`
    const [a, b] = [...root!.querySelectorAll<HTMLImageElement>('.eye .pair img')]
    a.src = guide
    b.src = ours
    a.addEventListener('error', () => a.closest('figure')!.remove())
    $('.eye .cap').textContent = caption
  }
  const showCode = async (path: string, tries = 6): Promise<void> => {
    codeFor = path
    try {
      const res = await fetch(fileUrl(job, `agent/${path}`, key), { cache: 'no-store' })
      if (!res.ok) throw new Error(String(res.status))
      const text = await res.text()
      if (codeFor !== path) return
      const lines = text.split('\n')
      $('.eye h2').textContent = w().wrote
      $('.eye .show').innerHTML = '<pre></pre>'
      $('.eye pre').textContent = lines.slice(0, 44).join('\n') + (lines.length > 44 ? '\n…' : '')
      $('.eye .cap').textContent = `${base(path)} · ${lines.length} lines`
    } catch {
      // the event comes as the agent starts to write; the file is there a moment later
      if (tries > 0 && codeFor === path) window.setTimeout(() => void showCode(path, tries - 1), 1500)
    }
  }

  const take = (e: AgentEvent & { score?: number }) => {
    if (seen.has(e.seq)) return
    seen.add(e.seq)
    if (!root) mount()
    heard = Date.now()
    job = e.jobId || job || q.get('job') || ''
    if (!t.first) t.first = e.ts
    t.last = Math.max(t.last, e.ts)
    const words = w()
    const text = e.text ?? ''

    const s = e.type === 'done' ? 'done' : stageOf(e.stage)
    if (s && !reached.has(s)) reached.set(s, e.ts)
    if (s && !(e.type === 'progress' && / finished$/.test(text))) {
      if (current !== s) doing = ''
      current = s
    }
    if (e.type === 'done') t.ended = true
    if (e.type === 'error') doing = text.slice(0, 90)

    const mm = /\((claude-[a-z0-9-]+)\)/.exec(text)
    if (mm) model = mm[1]
    const sum = /(\d+) turns.*?\$([0-9]+(?:\.[0-9]+)?)/.exec(text)
    if (sum) { turns = sum[1]; cost = `$${sum[2]}` }
    void turns

    if (e.type === 'tool_call') {
      const read = /^Looking at (.+)$/.exec(text)
      const wrote = /^(Writing|Fixing) (.+)$/.exec(text)
      if (e.tool === 'read' && read) {
        n.read++
        doing = `${words.reading} ${base(read[1])}`
        if (IMAGE.test(read[1]) && !read[1].startsWith('/') && !read[1].includes('..')) {
          const page = /page-(\d+)\./.exec(read[1])
          showImage(apiUrl(`/files/${encodeURIComponent(job)}/agent/${read[1]}`, key, { v: e.seq }), page ? `${words.guide} · ${words.page} ${page[1]}` : base(read[1]))
        }
      } else if ((e.tool === 'write' || e.tool === 'edit') && wrote) {
        if (e.tool === 'write') n.written++
        doing = `${e.tool === 'write' ? words.writing : words.fixing} ${base(wrote[2])}`
        if (!wrote[2].startsWith('/') && !wrote[2].includes('..')) void showCode(wrote[2])
      } else if (e.tool === 'build') {
        n.builds++
        doing = words.building
      } else if (e.tool === 'check') {
        n.checks++
        doing = words.checking
      }
    } else if (e.type === 'tool_result' && e.tool === 'build') {
      doing = e.level === 'success' ? words.passed : words.failed
    } else if (e.type === 'thought' && current === 'extract_plan' && !doing) {
      doing = words.thinking
    } else if (e.type === 'image' && e.image_url) {
      const step = e.stepN ?? null
      if (step && typeof e.score === 'number') {
        const nn = String(step).padStart(2, '0')
        showPair(fileUrl(job, `crops/step-${nn}.png`, key), apiUrl(e.image_url, key, { v: e.seq }), e.score, text, e.level ?? 'info')
      } else showImage(apiUrl(e.image_url, key, { v: e.seq }), text)
    } else if (e.type === 'progress' && current !== 'extract_plan' && text && !/^Stage /.test(text)) {
      doing = text.slice(0, 90)
    }
    paint()
  }

  window.addEventListener('agj:event', (ev) => take((ev as CustomEvent<AgentEvent>).detail))
}
