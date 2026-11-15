// The build screen (`?mode=build`): find a guide by name, code or link, or drop a flat-pack guide (a PDF or
// photos of its pages), watch the agent turn it into the 3D guide, approve its plan, open the result. The same workshop at night as the guide page: one dark room, a single
// column of light words, the latest line brightest, two round controls at most.
//
//   ?mode=build                     find a guide (build/intake.ts), or drop a PDF or photos; the recent jobs below
//   ?mode=build&job=<id>            the feed of that job (live while it runs, the whole story once it is done)
//   &key=<demo key>                 sent on every request, never stored
//   &lang=en                        English (Greek by default)
//   &auto=1                         approve the plan by itself, and open the guide when it is ready (for a demo)
//   &transport=poll|sse             debug: force the long poll, or never leave the stream
//   &api=<url>                      dev only: the server to talk to (src/api.ts)
//
// Every sentence on screen is the server's own event, said in the page's language (build/words.ts). Nothing
// is invented here: no percentages, no estimated time left. Stage and elapsed time are what the events say.
import '../styles/page.css'
import '../styles/build.css'
import { apiFetch, apiUrl } from '../api'
import type { Lang } from '../types'
import { tr } from '../i18n'
import { WORDS } from '../guide/words'
import { follow, type AgentEvent, type Follow } from './stream'
import { STAGE, bw, fmtSec, say, thoughtPieces, type BuildWord, type Said } from './words'
import { choose, debounce, openCatalog, openQuery, searchCatalog, searchIkea, sendGuide, type CatalogItem, type Chosen } from './intake'

interface PlanSummary {
  title?: string
  parts?: { id: string; count?: number; short?: string; el?: string }[]
  hardware?: { code: string; count?: number; name?: string; short?: string; el?: string; kind?: string }[]
  steps?: unknown[]
  flags?: { ref?: string; kind?: string; message?: string }[]
}

interface JobRow {
  job_id: string
  stage?: string
  status?: string
  title?: string | null
  filename?: string | null
  created?: number | null
  last_seq?: number
}

const ICON = {
  up: '<path d="M12 18.5V6M6.5 11.5 12 6l5.5 5.5" />',
  check: '<path d="M5.5 12.5 10 17l8.5-9.5" />',
  next: '<path d="M9.5 5.5 16 12l-6.5 6.5" />',
  plus: '<path d="M12 6v12M6 12h12" />',
  cross: '<path d="M7.5 7.5l9 9M16.5 7.5l-9 9" />',
  camera: '<path d="M4.5 8.5h3l1.6-2.2h5.8l1.6 2.2h3v10h-15z" /><circle cx="12" cy="13.2" r="3.2" />',
}
const svg = (path: string, size = 22) =>
  `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${path}</svg>`

const el = <K extends keyof HTMLElementTagNameMap>(tag: K, cls = '', text = ''): HTMLElementTagNameMap[K] => {
  const node = document.createElement(tag)
  if (cls) node.className = cls
  if (text) node.textContent = text
  return node
}

const IMAGE = /\.(png|jpe?g|webp)$/i

export function startBuild(app: HTMLElement): void {
  const q = new URLSearchParams(location.search)
  const key = q.get('key')
  const auto = q.get('auto') === '1'
  let lang: Lang = q.get('lang') === 'en' ? 'en' : 'el'
  let job = q.get('job')

  // ---- the room
  const root = el('main')
  root.id = 'build'
  root.innerHTML = `
    <section id="drop">
      <form class="find" role="search" autocomplete="off">
        <input type="search" name="q" enterkeyhint="go" autocomplete="off" autocapitalize="off" spellcheck="false" />
        <p class="said" role="status"></p>
        <ul class="matches" hidden></ul>
      </form>
      <div class="put">
        <div class="disc">
          <label class="target">
            <input type="file" accept="application/pdf,image/*" multiple />
            <span class="ring">${svg(ICON.up, 30)}</span>
            <span class="word"></span>
            <span class="quiet"></span>
          </label>
          <label class="camera" hidden><input type="file" accept="image/*" capture="environment" />${svg(ICON.camera, 20)}</label>
          <button class="clear" type="button" hidden>${svg(ICON.cross, 18)}</button>
        </div>
        <span class="pages" hidden></span>
        <div class="send" hidden>
          <input class="size" type="text" name="size" autocomplete="off" enterkeyhint="go" maxlength="120" />
          <button class="go" type="button"><span class="round">${svg(ICON.next)}</span><span class="word"></span></button>
        </div>
      </div>
      <p class="note" role="alert"></p>
      <ul id="recent"></ul>
    </section>
    <section id="work" hidden>
      <p id="state" role="status"><i class="dot" aria-hidden="true"></i><span class="stage"></span><span class="clock"></span></p>
      <div id="feed"><ol class="rows" aria-live="polite"></ol></div>
      <button id="act" type="button" hidden><span class="round"></span><span class="word"></span></button>
    </section>
    <button id="again" type="button" hidden>${svg(ICON.plus, 20)}</button>
    <button id="lang" type="button"></button>`
  app.append(root)
  const $ = <T extends Element>(sel: string) => root.querySelector(sel) as T
  const findForm = $<HTMLFormElement>('.find')
  const findInput = $<HTMLInputElement>('.find input')
  const findSaid = $<HTMLParagraphElement>('.find .said')
  const matches = $<HTMLUListElement>('.find .matches')
  const target = $<HTMLLabelElement>('.target')
  const input = $<HTMLInputElement>('.target input')
  const targetWord = $<HTMLSpanElement>('.target .word')
  const targetQuiet = $<HTMLSpanElement>('.target .quiet')
  const camera = $<HTMLLabelElement>('.camera')
  const cameraInput = $<HTMLInputElement>('.camera input')
  const clearButton = $<HTMLButtonElement>('.disc .clear')
  const pagesStrip = $<HTMLSpanElement>('.put .pages')
  const send = $<HTMLDivElement>('.put .send')
  const sizeInput = $<HTMLInputElement>('.send .size')
  const goButton = $<HTMLButtonElement>('.send .go')
  const goWord = $<HTMLSpanElement>('.send .go .word')
  const note = $<HTMLParagraphElement>('#drop .note')
  const recent = $<HTMLUListElement>('#recent')
  const work = $<HTMLElement>('#work')
  const stateStage = $<HTMLSpanElement>('#state .stage')
  const stateClock = $<HTMLSpanElement>('#state .clock')
  const feed = $<HTMLDivElement>('#feed')
  const rows = $<HTMLOListElement>('#feed .rows')
  const act = $<HTMLButtonElement>('#act')
  const actButton = act
  const actRound = $<HTMLSpanElement>('#act .round')
  const actWord = $<HTMLSpanElement>('#act .word')
  const again = $<HTMLButtonElement>('#again')
  const langButton = $<HTMLButtonElement>('#lang')
  const touch = matchMedia('(hover: none) and (pointer: coarse)').matches
  const reduced = matchMedia('(prefers-reduced-motion: reduce)')
  const setState = (s: string) => (document.documentElement.dataset.state = s)

  // The feed keeps its newest line in view while it grows (pictures arriving, the control at the foot taking
  // its room), unless the person has scrolled up to read; scrolling back down picks it up again.
  // Only the person's own scrolling (wheel, touch, keys) decides; the page's scrolls and pictures arriving do not.
  let stick = true
  let touched = -Infinity
  const toEnd = () => {
    if (stick) feed.scrollTop = feed.scrollHeight
  }
  for (const type of ['wheel', 'touchmove', 'keydown', 'pointerdown'])
    feed.addEventListener(type, () => (touched = performance.now()), { passive: true })
  feed.addEventListener(
    'scroll',
    () => {
      if (performance.now() - touched < 1200) stick = feed.scrollTop + feed.clientHeight >= feed.scrollHeight - 48
    },
    { passive: true },
  )
  new ResizeObserver(toEnd).observe(feed)
  new ResizeObserver(toEnd).observe(rows)

  const link = (params: Record<string, string | null>): string => {
    const url = new URL(location.pathname, location.href)
    for (const [k, v] of Object.entries(params)) if (v) url.searchParams.set(k, v)
    if (key) url.searchParams.set('key', key)
    url.searchParams.set('lang', lang)
    if (import.meta.env.DEV && q.get('api')) url.searchParams.set('api', q.get('api')!)
    return url.toString()
  }
  // a finished guide opens on the "ready" screen (?mode=ready, src/home/), which leads on to the guide page;
  // while a build of the client has no such screen, an unknown mode falls through to the guide page itself
  const guideLink = (id: string) => link({ mode: 'ready', job: id })

  // ------------------------------------------------------------------ language
  let refreshWords: () => void = () => {}
  const setLang = (l: Lang) => {
    lang = l
    document.documentElement.lang = l
    document.title = job ? tr(l, 'appTitle') : bw(l, 'title')
    langButton.textContent = WORDS[l].otherLanguageShort
    langButton.setAttribute('aria-label', WORDS[l].otherLanguage)
    langButton.lang = l === 'el' ? 'en' : 'el'
    again.setAttribute('aria-label', bw(l, 'again'))
    findInput.placeholder = bw(l, 'find')
    findInput.setAttribute('aria-label', bw(l, 'find'))
    sizeInput.placeholder = bw(l, 'size')
    sizeInput.setAttribute('aria-label', bw(l, 'size'))
    cameraInput.setAttribute('aria-label', bw(l, 'camera'))
    clearButton.setAttribute('aria-label', bw(l, 'clear'))
    refreshWords()
  }
  langButton.addEventListener('click', () => {
    setLang(lang === 'el' ? 'en' : 'el')
    const url = new URL(location.href)
    url.searchParams.set('lang', lang)
    history.replaceState(null, '', url)
  })
  again.addEventListener('click', () => location.assign(link({ mode: 'build' })))

  // ------------------------------------------------------------------ 1. drop or pick
  let noteKey: BuildWord | null = null
  const showNote = (k: typeof noteKey) => {
    noteKey = k
    note.textContent = k ? bw(lang, k) : ''
  }
  let recentJobs: JobRow[] = []

  const drawRecent = () => {
    recent.replaceChildren()
    for (const j of recentJobs) {
      const li = el('li')
      const a = el('a')
      a.href = j.status === 'done' ? guideLink(j.job_id) : link({ mode: 'build', job: j.job_id })
      const photos = /^(\d+) photos$/.exec(j.filename ?? '') // the server names an upload of photos "<n> photos"
      a.append(el('span', 'name', j.title || (photos ? bw(lang, 'pages', { n: +photos[1] }) : prettyName(j.filename)) || j.job_id))
      const word =
        j.status === 'running' ? bw(lang, 'busy')
        : j.status === 'waiting_approval' ? bw(lang, 'waiting')
        : j.status === 'error' ? bw(lang, 'broken')
        : j.status === 'queued' ? bw(lang, 'queued')
        : ''
      if (word) a.append(el('span', `how ${j.status}`, word))
      li.append(a)
      recent.append(li)
    }
  }

  const loadRecent = async () => {
    try {
      const res = await apiFetch('/api/jobs', key)
      if (res.status === 401) return showNote('noKey')
      if (!res.ok) return showNote('offline')
      const all = ((await res.json()) as { jobs?: JobRow[] }).jobs ?? []
      // one row per guide: its newest finished build, else its newest attempt
      const byName = new Map<string, JobRow>()
      for (const j of all) {
        const name = (j.title || prettyName(j.filename) || j.job_id).toLowerCase()
        const had = byName.get(name)
        if (!had || (had.status !== 'done' && j.status === 'done')) byName.set(name, j)
      }
      recentJobs = [...byName.values()].sort((a, b) => (b.created ?? 0) - (a.created ?? 0)).slice(0, 5)
      drawRecent()
    } catch {
      showNote('offline')
    }
  }

  // ---- a job to follow: from an upload, or from a catalog guide that is not built yet
  const followJob = (id: string) => {
    history.replaceState(null, '', link({ mode: 'build', job: id, auto: auto ? '1' : null }))
    job = id
    openJob(id)
  }

  // ---- find a guide: name, IKEA article number or product link
  let found: CatalogItem[] = []
  let asked = 0
  let opening = false
  let saidKey: BuildWord | null = null
  let saidWarn = false
  const sayFind = (k: BuildWord | null, warn = true) => {
    saidKey = k
    saidWarn = warn
    findSaid.textContent = k ? bw(lang, k) : ''
    findSaid.classList.toggle('warn', !!k && warn)
  }

  const drawMatches = () => {
    matches.replaceChildren()
    for (const item of found.slice(0, 5)) {
      const li = el('li')
      const quiet = item.ready ? (item.steps ? bw(lang, 'steps', { n: item.steps }) : '') : bw(lang, 'toBuild')
      const pick = item.ready && item.job_id ? el('a') : el('button')
      if (pick instanceof HTMLAnchorElement) pick.href = guideLink(item.job_id!)
      else {
        pick.type = 'button'
        pick.addEventListener('click', () => void openGuide(openQuery(item)))
      }
      pick.append(el('span', 'name', item.product))
      if (quiet) pick.append(el('span', `how${item.ready ? '' : ' later'}`, quiet))
      li.append(pick)
      matches.append(li)
    }
    const showing = found.length > 0 && !!findInput.value.trim() && !opening
    matches.hidden = !showing
    root.classList.toggle('finding', showing)
  }

  const search = debounce(async (q: string) => {
    const mine = ++asked
    if (!q.trim()) {
      found = []
      return drawMatches()
    }
    try {
      const items = await searchCatalog(q, key)
      if (mine !== asked) return
      found = items
      if (items.length < 5) {
        drawMatches() // our own guides at once; IKEA's products follow when they come
        const more = await searchIkea(q, key, items)
        if (mine !== asked) return
        found = [...items, ...more]
      }
    } catch {
      if (mine !== asked) return
      found = []
    }
    drawMatches()
  }, 180)

  const openGuide = async (q: string) => {
    if (opening || !q.trim()) return
    opening = true
    asked++ // a search still on its way is no longer wanted
    drawMatches()
    findForm.classList.add('busy')
    findInput.readOnly = true
    sayFind('finding', false)
    const got = await openCatalog(q, key)
    opening = false
    findForm.classList.remove('busy')
    findInput.readOnly = false
    if (!got.ok) {
      sayFind(got.word, !got.items?.length)
      found = got.items ?? []
      drawMatches()
      findInput.focus()
      return
    }
    sayFind(null)
    if (got.ready) return location.assign(guideLink(got.job))
    followJob(got.job)
  }

  findInput.addEventListener('input', () => {
    if (saidKey && saidWarn) sayFind(null)
    search(findInput.value)
  })
  findForm.addEventListener('submit', (e) => {
    e.preventDefault()
    void openGuide(findInput.value)
  })
  findInput.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown' && !matches.hidden) {
      e.preventDefault()
      ;(matches.querySelector('a, button') as HTMLElement | null)?.focus()
    } else if (e.key === 'Escape') {
      findInput.value = ''
      sayFind(null)
      search('')
    }
  })
  matches.addEventListener('keydown', (e) => {
    const all = [...matches.querySelectorAll<HTMLElement>('a, button')]
    const at = all.indexOf(document.activeElement as HTMLElement)
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const next = at + (e.key === 'ArrowDown' ? 1 : -1)
      ;(next < 0 ? findInput : all[Math.min(next, all.length - 1)]).focus()
    } else if (e.key === 'Escape') findInput.focus()
  })

  // ---- give the guide: one PDF, or photos of its pages in order, and the size if the person knows it
  let chosen: Chosen | null = null
  let thumbs: string[] = []
  let sending = false

  const drawChosen = () => {
    const has = !!chosen
    root.classList.toggle('chosen', has)
    send.hidden = !has
    clearButton.hidden = !has || sending
    pagesStrip.hidden = chosen?.kind !== 'photos'
    target.classList.toggle('busy', sending)
    goButton.disabled = sending
    goWord.textContent = bw(lang, sending ? 'uploading' : 'build')
    if (!chosen) {
      targetWord.textContent = bw(lang, touch ? 'pick' : 'drop')
      targetQuiet.textContent = bw(lang, 'accepts')
    } else if (chosen.kind === 'pdf') {
      targetWord.textContent = prettyName(chosen.files[0].name) || chosen.files[0].name
      targetQuiet.textContent = bw(lang, 'pdf')
    } else {
      targetWord.textContent = bw(lang, 'pages', { n: chosen.files.length })
      targetQuiet.textContent = bw(lang, 'more')
    }
  }

  const drawPages = () => {
    for (const u of thumbs) URL.revokeObjectURL(u)
    thumbs = []
    pagesStrip.replaceChildren()
    if (chosen?.kind !== 'photos') return
    const SHOW = 12
    for (const f of chosen.files.slice(0, SHOW)) {
      const url = URL.createObjectURL(f)
      thumbs.push(url)
      const img = el('img')
      img.alt = ''
      img.decoding = 'async'
      img.src = url
      img.addEventListener('load', () => img.classList.add('in'))
      img.addEventListener('error', () => img.replaceWith(el('i', 'nopic')))
      pagesStrip.append(img)
    }
    if (chosen.files.length > SHOW) pagesStrip.append(el('span', 'more', `+${chosen.files.length - SHOW}`))
  }

  const takeFiles = (files: File[]) => {
    if (sending || !files.length) return
    const got = choose(chosen, files)
    if ('word' in got) return showNote(got.word)
    showNote(null)
    const wasChosen = !!chosen
    chosen = got.chosen
    drawChosen()
    drawPages()
    if (!wasChosen && !touch) sizeInput.focus({ preventScroll: true })
  }

  const clearChosen = () => {
    chosen = null
    sizeInput.value = ''
    showNote(null)
    drawChosen()
    drawPages()
  }

  const sendChosen = async () => {
    if (!chosen || sending) return
    sending = true
    showNote(null)
    drawChosen()
    const got = await sendGuide(chosen, sizeInput.value, key)
    sending = false
    if (got.ok) {
      for (const u of thumbs) URL.revokeObjectURL(u)
      return followJob(got.job)
    }
    drawChosen()
    showNote(got.word)
  }

  input.addEventListener('change', () => {
    takeFiles([...(input.files ?? [])])
    input.value = '' // the same photo chosen again still counts
  })
  cameraInput.addEventListener('change', () => {
    takeFiles([...(cameraInput.files ?? [])])
    cameraInput.value = ''
  })
  camera.hidden = !touch
  clearButton.addEventListener('click', clearChosen)
  goButton.addEventListener('click', () => void sendChosen())
  sizeInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      void sendChosen()
    }
  })

  let depth = 0
  addEventListener('dragenter', (e) => {
    e.preventDefault()
    if (job) return
    depth++
    root.classList.add('over')
  })
  addEventListener('dragleave', () => {
    if (--depth <= 0) {
      depth = 0
      root.classList.remove('over')
    }
  })
  addEventListener('dragover', (e) => e.preventDefault())
  addEventListener('drop', (e) => {
    e.preventDefault()
    depth = 0
    root.classList.remove('over')
    if (!job) takeFiles([...(e.dataTransfer?.files ?? [])])
  })

  // ------------------------------------------------------------------ 2. the agent at work
  function openJob(id: string) {
    $<HTMLElement>('#drop').hidden = true
    work.hidden = false
    again.hidden = false
    document.title = tr(lang, 'appTitle')
    setState('running')

    const events: AgentEvent[] = []
    const queue: AgentEvent[] = []
    let first = 0
    let lastTs = 0
    let stage = 'ingest'
    let ended: 'done' | 'error' | null = null
    let startedDone = false
    let lost = false
    let pendingApproval = false
    let plan: PlanSummary | null = null
    let planRow: HTMLLIElement | null = null
    let stream: Follow | null = null
    const stageRows = new Map<string, HTMLLIElement>()

    // ---- the line at the top: what stage, how long
    const drawState = () => {
      const word = ended === 'error' ? bw(lang, 'stopped') : lost ? bw(lang, 'lost') : (STAGE[lang][stage] ?? stage)
      stateStage.textContent = word
      root.dataset.ended = ended ?? ''
      root.classList.toggle('lost', lost && !ended)
    }
    const tick = () => {
      if (!first) return (stateClock.textContent = '')
      const end = ended ? lastTs : Date.now()
      stateClock.textContent = fmtSec((end - first) / 1000)
    }
    const clock = window.setInterval(tick, 1000)

    // ---- one event into the feed
    const latest = () => rows.lastElementChild as HTMLLIElement | null

    const sentence = (s: Said, into: HTMLElement) => {
      const words = el('span', 'w', s.verb)
      if (s.own) words.lang = 'en'
      into.append(words)
      if (s.code) into.append(el('code', '', shortPath(s.code)))
      if (s.rest) into.append(el('span', 'w rest', s.rest))
    }

    const thumb = (url: string, alt: string) => {
      const img = el('img')
      img.alt = alt
      img.decoding = 'async'
      img.loading = 'eager'
      img.src = url
      img.addEventListener('load', () => img.classList.add('in'))
      img.addEventListener('error', () => img.replaceWith(el('code', '', shortPath(alt))))
      return img
    }

    const imageOf = (e: AgentEvent): string | null => {
      if (e.image_url) return apiUrl(e.image_url, key, { v: e.seq })
      // what the agent opened in its workspace (<job>/agent/): the guide pages and its own renders
      const m = e.type === 'tool_call' && e.tool === 'read' ? /^Looking at (.+)$/.exec(e.text ?? '') : null
      if (m && IMAGE.test(m[1]) && !m[1].startsWith('/') && !m[1].includes('..'))
        return apiUrl(`/files/${encodeURIComponent(id)}/agent/${m[1]}`, key, { v: e.seq })
      return null
    }

    const add = (e: AgentEvent, animate: boolean) => {
      const text = e.text ?? ''
      const prev = latest()
      const row = (kind: string) => {
        const li = el('li', kind)
        li.dataset.seq = String(e.seq)
        if (e.level && e.level !== 'info') li.dataset.level = e.level
        if (animate && !reduced.matches) li.classList.add('rise')
        rows.append(li)
        return li
      }

      if (e.type === 'progress') {
        const m = /^Stage `?([a-z_]+)`? (started|finished)$/.exec(text)
        if (m) {
          if (m[2] === 'started') {
            const li = row('stage')
            li.dataset.stage = m[1]
            li.append(el('span', 'w', STAGE[lang][m[1]] ?? m[1]))
            stageRows.set(m[1], li)
          } else if (e.ms) {
            const li = stageRows.get(m[1])
            if (li && !li.querySelector('.took')) li.append(el('span', 'took', fmtSec(e.ms / 1000)))
          }
          return
        }
        // "Read page 1 of 8", "Read page 5 of 8": one line that counts up
        if (prev?.classList.contains('note') && /^Read page/.test(text) && prev.dataset.kind === 'pages') {
          prev.replaceChildren()
          sentence(say(lang, text), prev)
          return
        }
        const li = row('tool note')
        if (/^Read page/.test(text)) li.dataset.kind = 'pages'
        sentence(say(lang, text), li)
        return
      }

      if (e.type === 'tool_call') {
        const img = imageOf(e)
        const group = img ? 'look' : `tool-${e.tool ?? ''}`
        const said = say(lang, text, true)
        if (prev && prev.dataset.group === group && e.tool !== 'build') {
          const name = shortPath(said.code ?? text)
          const codes = prev.querySelector('.codes')
          if (img) prev.querySelector('.strip')!.append(thumb(img, said.code ?? text))
          else if (codes && ![...codes.children].some((c) => c.textContent === name)) codes.append(el('code', '', name))
          prev.dataset.seq = String(e.seq)
          prev.classList.remove('rise')
          void prev.offsetWidth
          if (animate && !reduced.matches) prev.classList.add('pulse')
          return
        }
        const li = row(img ? 'tool look' : 'tool')
        li.dataset.group = group
        li.append(el('span', 'w', said.verb))
        if (img) {
          const strip = el('span', 'strip')
          strip.append(thumb(img, said.code ?? text))
          li.append(strip)
        } else if (said.code) {
          const codes = el('span', 'codes')
          codes.append(el('code', '', shortPath(said.code)))
          li.append(codes)
        }
        return
      }

      if (e.type === 'tool_result') {
        const li = row(e.level === 'success' ? 'tool ok' : 'tool fix')
        li.append(mark(e.level === 'success' ? 'ok' : 'fix'))
        const said = say(lang, text, true)
        li.append(el('span', 'w', said.verb))
        if (said.code) li.append(el('code', 'detail', said.code))
        return
      }

      if (e.type === 'part_status') {
        const said = say(lang, text)
        if (prev?.dataset.group === 'parts') {
          prev.querySelector('.codes')!.append(el('code', '', said.code ?? e.partId ?? ''))
          return
        }
        const li = row(e.level === 'error' ? 'tool fix' : 'tool')
        li.dataset.group = e.level === 'error' ? '' : 'parts'
        li.append(el('span', 'w', said.verb))
        const codes = el('span', 'codes')
        codes.append(el('code', '', said.code ?? e.partId ?? ''))
        li.append(codes)
        return
      }

      if (e.type === 'step_ready') {
        const url = imageOf(e)
        let li = prev?.dataset.group === 'steps' ? prev : null
        if (!li) {
          li = row('steps')
          li.dataset.group = 'steps'
          li.append(el('span', 'strip'), el('span', 'caption'))
        }
        if (url) li.querySelector('.strip')!.append(thumb(url, `${e.stepN ?? ''}`))
        const cap = li.querySelector('.caption')!
        cap.replaceChildren(el('b', '', String(e.stepN ?? '')), el('span', '', text))
        cap.lastElementChild!.setAttribute('lang', 'en')
        return
      }

      if (e.type === 'image') {
        const li = row('picture')
        const url = imageOf(e)
        if (url) li.append(thumb(url, text))
        const cap = el('span', 'caption')
        sentence(say(lang, text), cap)
        li.append(cap)
        return
      }

      if (e.type === 'error') {
        const li = row('fault')
        li.append(mark('fix'))
        li.append(el('span', 'w', text.split('\n')[0].slice(0, 280)))
        li.lastElementChild!.setAttribute('lang', 'en')
        return
      }

      if (e.type === 'done') {
        const li = row('end')
        li.append(mark('ok'), el('span', 'w', bw(lang, 'ready')))
        return
      }

      if (e.type === 'approval_required') return // the plan itself is shown when it is really waiting

      // a thought, or a type this page does not know yet: one line of words
      const li = row('thought')
      const said = say(lang, text)
      if (!said.own && said.verb !== text.replace(/`/g, '').trim()) return sentence(said, li)
      // the agent's own words, as it wrote them
      const words = el('span', 'w own')
      words.lang = 'en'
      for (const piece of thoughtPieces(text)) words.append(piece.code ? el('code', '', piece.text) : document.createTextNode(piece.text))
      li.append(words)
    }

    // ---- the agent's state, as the events say it
    const apply = (e: AgentEvent) => {
      window.dispatchEvent(new CustomEvent('agj:event', { detail: e })) // for the panels beside the feed (overview.ts)
      if (!first) first = e.ts
      lastTs = e.ts
      if (e.stage && !(e.type === 'progress' && / finished$/.test(e.text ?? ''))) stage = e.stage
      if (e.type === 'approval_required') pendingApproval = true
      else if (pendingApproval && (e.stage !== 'await_approval' || /^Plan approved/.test(e.text ?? ''))) {
        pendingApproval = false
        closeApproval()
      }
      if (e.type === 'error') {
        ended = 'error'
        setState('error')
      } else if (ended === 'error' && e.type === 'progress' && /started|Retrying/.test(e.text ?? '')) {
        ended = null // a retry from the server: the job runs again
        setState('running')
      }
      if (e.type === 'done') {
        ended = 'done'
        stage = 'done'
        finished()
      }
    }

    // ---- rows arrive one by one: a backlog appears at once, live events rise in turn
    let pumping = 0
    const pump = () => {
      pumping = 0
      if (!queue.length) return
      const backlog = queue.length > 8
      const take = backlog ? queue.splice(0, queue.length) : [queue.shift()!]
      for (const e of take) {
        add(e, !backlog)
        apply(e)
      }
      toEnd()
      drawState()
      tick()
      if (queue.length) pumping = window.setTimeout(pump, 160)
      else if (pendingApproval) void openApproval()
    }

    const rebuild = () => {
      rows.replaceChildren()
      stageRows.clear()
      planRow = null
      for (const e of events) if (!queue.includes(e)) add(e, false)
      if (plan && !act.hidden && root.dataset.act === 'approve') showPlan(plan)
      toEnd()
    }

    // ---- 3. approval
    const openApproval = async () => {
      try {
        const res = await apiFetch(`/api/jobs/${encodeURIComponent(id)}`, key)
        const s = res.ok ? ((await res.json()) as JobRow) : null
        if (!pendingApproval || s?.status !== 'waiting_approval') return
        const planRes = await apiFetch(`/api/jobs/${encodeURIComponent(id)}/plan`, key)
        if (!planRes.ok || !pendingApproval) return
        plan = ((await planRes.json()) as { plan?: PlanSummary }).plan ?? {}
        showPlan(plan)
        setState('approval')
        showAct('approve')
        if (auto) window.setTimeout(() => pendingApproval && approve(), 1800)
      } catch {
        /* the button comes with the next event, or a reload */
      }
    }

    const showPlan = (p: PlanSummary) => {
      planRow?.remove()
      const li = el('li', 'plan')
      if (!reduced.matches) li.classList.add('rise')
      li.append(el('span', 'lead', bw(lang, 'planReady')))
      if (p.title) li.append(el('span', 'title', p.title))
      const kinds = p.parts?.length ?? 0
      const pieces = (p.parts ?? []).reduce((n, x) => n + (x.count ?? 1), 0)
      li.append(el('span', 'facts', [bw(lang, 'kinds', { n: kinds }), bw(lang, 'pieces', { n: pieces }), bw(lang, 'steps', { n: p.steps?.length ?? 0 })].join(' · ')))
      const hw = el('span', 'hardware')
      for (const h of p.hardware ?? []) {
        const one = el('span', 'hw')
        one.append(el('code', '', h.code), el('b', '', `×${h.count ?? 1}`), el('span', 'w', (lang === 'el' ? h.el : h.short) || h.name || ''))
        hw.append(one)
      }
      if (hw.childElementCount) li.append(hw)
      if (p.flags?.length) {
        const fl = el('span', 'flags')
        fl.append(el('span', 'lead', `${bw(lang, 'flags')} · ${p.flags.length}`))
        for (const f of p.flags.slice(0, 4)) {
          const one = el('span', 'flag')
          one.append(mark('check'))
          const t = el('span', 'w', firstSentence(f.message ?? f.kind ?? ''))
          t.lang = 'en'
          one.append(t)
          fl.append(one)
        }
        li.append(fl)
      }
      rows.append(li)
      planRow = li
      toEnd()
    }

    const approve = async () => {
      if (actButton.disabled) return
      actButton.disabled = true
      actWord.textContent = bw(lang, 'approving')
      try {
        const res = await apiFetch(`/api/jobs/${encodeURIComponent(id)}/plan/approve`, key, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: '{}',
        })
        if (res.ok || res.status === 409) {
          pendingApproval = false
          closeApproval()
          return
        }
      } catch {
        /* offer the button again */
      }
      actButton.disabled = false
      actWord.textContent = bw(lang, 'approve')
    }

    const closeApproval = () => {
      if (root.dataset.act === 'approve') hideAct()
      if (!ended) setState('running')
    }

    // ---- the one control at the foot: approve, or open the guide
    const showAct = (what: 'approve' | 'open') => {
      root.dataset.act = what
      act.hidden = false
      actButton.disabled = false
      actRound.innerHTML = svg(what === 'approve' ? ICON.check : ICON.next)
      actWord.textContent = bw(lang, what === 'approve' ? 'approve' : 'open')
    }
    const hideAct = () => {
      root.dataset.act = ''
      act.hidden = true
    }
    actButton.addEventListener('click', () => {
      if (root.dataset.act === 'approve') void approve()
      else if (root.dataset.act === 'open') leave()
    })

    // ---- 4. done
    const leave = () => {
      const to = guideLink(id)
      if (reduced.matches) return location.assign(to)
      root.classList.add('leaving')
      window.setTimeout(() => location.assign(to), 650)
    }
    const finished = () => {
      stream?.stop()
      setState('done')
      showAct('open')
      if (auto && !startedDone) window.setTimeout(leave, 2600)
    }

    refreshWords = () => {
      drawState()
      rebuild()
      if (root.dataset.act) showAct(root.dataset.act as 'approve' | 'open')
    }

    // ---- start: the job's state, then its events from the first one
    const start = async () => {
      try {
        const res = await apiFetch(`/api/jobs/${encodeURIComponent(id)}`, key)
        if (res.status === 404) {
          stateStage.textContent = bw(lang, 'noJob')
          root.dataset.ended = 'error'
          return
        }
        if (res.status === 401) {
          stateStage.textContent = bw(lang, 'noKey')
          root.dataset.ended = 'error'
          return
        }
        if (res.ok) startedDone = ((await res.json()) as JobRow).status === 'done'
      } catch {
        /* the stream will say whether the server is there */
      }
      stream = follow(
        id,
        key,
        (fresh) => {
          events.push(...fresh)
          queue.push(...fresh)
          if (!pumping) pump()
        },
        (isLost) => {
          if (lost === isLost) return
          lost = isLost
          drawState()
        },
        q.get('transport'),
      )
      ;(window as unknown as { __build: unknown }).__build = {
        events,
        get transport() {
          return stream?.transport
        },
        stop: () => stream?.stop(),
      }
    }
    drawState()
    void start()
    window.addEventListener('pagehide', () => {
      stream?.stop()
      clearInterval(clock)
    })
  }

  // ------------------------------------------------------------------ open
  setLang(lang)
  if (job) {
    openJob(job)
  } else {
    setState('drop')
    void loadRecent()
    drawChosen()
    refreshWords = () => {
      drawRecent()
      drawMatches()
      drawChosen()
      if (noteKey) showNote(noteKey)
      if (saidKey) sayFind(saidKey, saidWarn)
    }
  }
  document.documentElement.dataset.ready = 'true'
}

/** A small status shape: ok (check), fix (cross), check (dot in a ring). */
function mark(kind: 'ok' | 'fix' | 'check'): SVGSVGElement {
  const paths = {
    ok: '<path d="M5.5 12.5 10 17l8.5-9.5" />',
    fix: '<path d="M7 7l10 10M17 7 7 17" />',
    check: '<circle cx="12" cy="12" r="6.5" /><circle cx="12" cy="12" r="1.2" fill="currentColor" />',
  }
  const t = document.createElement('template')
  t.innerHTML = `<svg class="mark ${kind}" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[kind]}</svg>`
  return t.content.firstElementChild as SVGSVGElement
}

/** job/parts/bottom_panel.py → parts/bottom_panel.py: the agent's workspace prefix says nothing. */
function shortPath(p: string): string {
  return p.replace(/^job\//, '')
}

/** knagglig-box__AA-1159337-7.pdf → knagglig box */
function prettyName(file: string | null | undefined): string {
  if (!file) return ''
  return file.replace(/\.pdf$/i, '').replace(/__.*$/, '').replace(/[-_]+/g, ' ').trim()
}

function firstSentence(text: string): string {
  const t = text.trim()
  const cut = t.search(/[.;](\s|$)/)
  const s = cut < 0 ? t : t.slice(0, cut)
  return s.length > 120 ? `${s.slice(0, 118).trimEnd()}…` : s
}
