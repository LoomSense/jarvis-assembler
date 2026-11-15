import '../styles/landing.css'
import { appLink } from '../routes'
import { bw } from '../build/words'
import { apiFetch, fileUrl } from '../api'
import { choose, sendGuide, type Chosen } from '../build/intake'

// The landing page (no mode, no job): a workshop at night with the boxes on the table, one clear action
// (assemble: it opens the scanner), three pictures of what happens next, and the guides that exist. The 3D workbench that used to be the home screen is at `?mode=shelf`;
// a finished guide still opens on its "ready" screen (`?mode=ready&job=`, src/home/home.ts).

type Lang = 'el' | 'en'
interface JobRow { job_id: string; status: string; stage: string; title: string | null; filename: string | null; created?: number }

const WORDS = {
  en: {
    brand: 'Jarvis Assembler', guides: 'My guides', add: 'New guide', other: 'ελ', otherAria: 'Ελληνικά',
    eyebrow: 'From the first piece to the final fit',
    h1: 'The box is here.<br>Let us build it.', cta: 'Assemble',
    lead: 'Scan the box, photograph it or type its name. An agent finds the guide and builds it in 3D, one screw at a time.',
    scan: 'Scan the box', upload: 'Upload a guide', find: 'Find it by name or code', drop: 'or drop a PDF or photos anywhere on this page',
    home: 'Home', boxes: 'Guides on the shelf',
    dropping: 'Drop it to start', sending: 'Sending the guide',
    how: 'How it works',
    s1: 'Give a guide', s2: 'Built in 3D', s3: 'You build',
    yours: 'My guides', empty: 'No guides yet. Upload the first one.', loading: 'Loading the guides',
    ready: 'Ready', building: 'Building', approve: 'Waiting for your approval', failed: 'Stopped', open: 'Open', watch: 'See progress',
    keyMissing: 'This address has no access key', keyWrong: 'The access key in this address is not accepted',
    keyHelp: 'Paste the key your team gave you.', keyField: 'Access key', keyGo: 'Continue',
    offline: 'The server did not answer. Try again in a moment.', retry: 'Try again',
    notGuide: 'That is not a PDF or a photo.', tooBig: 'The files are too large.', failedSend: 'The guide could not be sent.',
  },
  el: {
    brand: 'Jarvis Assembler', guides: 'Οι οδηγοί μου', add: 'Νέος οδηγός', other: 'en', otherAria: 'English',
    eyebrow: 'Από το πρώτο κομμάτι μέχρι την τελευταία βίδα',
    h1: 'Το κουτί ήρθε.<br>Ας το στήσουμε.', cta: 'Συναρμολόγησε',
    lead: 'Σκάναρε το κουτί, φωτογράφισέ το ή γράψε το όνομά του. Ένας πράκτορας βρίσκει τον οδηγό και τον χτίζει σε 3D, βίδα βίδα.',
    scan: 'Σκάναρε το κουτί', upload: 'Ανέβασε έναν οδηγό', find: 'Βρες το με όνομα ή κωδικό', drop: 'ή άφησε ένα PDF ή φωτογραφίες οπουδήποτε στη σελίδα',
    home: 'Αρχική', boxes: 'Οδηγοί στο ράφι',
    dropping: 'Άφησέ το για να ξεκινήσει', sending: 'Στέλνω τον οδηγό',
    how: 'Πώς δουλεύει',
    s1: 'Δίνεις οδηγό', s2: 'Χτίζεται 3D', s3: 'Στήνεις',
    yours: 'Οι οδηγοί μου', empty: 'Δεν υπάρχει ακόμα οδηγός. Ανέβασε τον πρώτο.', loading: 'Φορτώνω τους οδηγούς',
    ready: 'Έτοιμος', building: 'Χτίζεται', approve: 'Περιμένει την έγκρισή σου', failed: 'Σταμάτησε', open: 'Άνοιξε', watch: 'Δες την πρόοδο',
    keyMissing: 'Η διεύθυνση δεν έχει κλειδί πρόσβασης', keyWrong: 'Το κλειδί πρόσβασης στη διεύθυνση δεν έγινε δεκτό',
    keyHelp: 'Βάλε το κλειδί που σου έδωσε η ομάδα σου.', keyField: 'Κλειδί πρόσβασης', keyGo: 'Συνέχεια',
    offline: 'Ο server δεν απάντησε. Δοκίμασε ξανά σε λίγο.', retry: 'Δοκίμασε ξανά',
    notGuide: 'Αυτό δεν είναι PDF ή φωτογραφία.', tooBig: 'Τα αρχεία είναι πολύ μεγάλα.', failedSend: 'Ο οδηγός δεν στάλθηκε.',
  },
} as const

const ICON = {
  mark: '<path d="M4 8.5 12 4l8 4.5v7L12 20l-8-4.5v-7Z"/><path d="M4 8.5 12 13l8-4.5M12 13v7"/>',
  up: '<path d="M12 16V5M7.5 9.5 12 5l4.5 4.5M5 19h14"/>',
  search: '<circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/>',
  plus: '<path d="M12 5.5v13M5.5 12h13"/>',
  arrow: '<path d="M5 12h14M13.5 6.5 19 12l-5.5 5.5"/>',
  scan: '<path d="M4 8V5.5C4 4.7 4.7 4 5.5 4H8M16 4h2.5c.8 0 1.5.7 1.5 1.5V8M20 16v2.5c0 .8-.7 1.5-1.5 1.5H16M8 20H5.5C4.7 20 4 19.3 4 18.5V16M7 12h10"/>',
  home: '<path d="M4.5 11 12 4.5 19.5 11M6.5 9.5V19h11V9.5"/>',
  shelf: '<rect x="4" y="4.5" width="7" height="6" rx="1.2"/><rect x="13" y="4.5" width="7" height="6" rx="1.2"/><rect x="4" y="13.5" width="7" height="6" rx="1.2"/><rect x="13" y="13.5" width="7" height="6" rx="1.2"/>',
  scanbox: '<path d="M4 8V5.5C4 4.7 4.7 4 5.5 4H8M16 4h2.5c.8 0 1.5.7 1.5 1.5V8M20 16v2.5c0 .8-.7 1.5-1.5 1.5H16M8 20H5.5C4.7 20 4 19.3 4 18.5V16"/><path d="m12 8 4 2v4l-4 2-4-2v-4zM8 10l4 2 4-2M12 12v4"/>',
  steps: '<rect x="7" y="2.5" width="10" height="19" rx="2.4"/><path d="m10.2 12 1.4 1.5 2.4-3"/>',
}
const svg = (d: string) => `<svg viewBox="0 0 24 24" aria-hidden="true">${d}</svg>`
const FILL = 'fill="rgba(240,178,90,.18)"'
const CUBE = `<svg viewBox="0 0 48 48" aria-hidden="true"><path ${FILL} d="M24 10l14 7v16l-14 7-14-7V17z"/><path d="M10 17l14 7 14-7M24 24v16"/></svg>`
const HALF = `<svg viewBox="0 0 48 48" aria-hidden="true"><path d="M10 33l14-7 14 7-14 7z"/><path ${FILL} d="M10 17l14-7v16l-14 7zM24 10l14 7v16l-14-7z"/></svg>`
/** The mark: a cardboard box, and above it the dashed ghost of the piece that goes on next. */
const LOGO = `<svg class="logo" viewBox="0 0 48 48" aria-hidden="true"><path fill="#c59a68" d="M8 29l16 7v10L8 39z"/><path fill="#8f6639" d="M24 36l16-7v10l-16 7z"/><path fill="#dcb585" d="M24 22l16 7-16 7-16-7z"/><path stroke="#f0b25a" stroke-width="1.2" stroke-dasharray="2 3" opacity=".6" d="M8 13v12M40 13v12"/><path fill="rgba(240,178,90,.2)" stroke="#f0b25a" stroke-width="2" stroke-dasharray="4 3" stroke-linejoin="round" d="M24 3l16 7-16 7-16-7z"/></svg>`
/** One cardboard box on the table: its lid lifts, a glowing piece rises out of it, and it closes again. */
const BOX = (n: number, name: string) => `<a class="bx c${n}"><i class="sh"></i><i class="hole"></i><i class="lid"></i><span class="pop">${CUBE}</span><i class="side"></i><span class="face"><i class="tape"></i><b>3D</b><em>${name}</em></span></a>`
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)

export function startLanding(app: HTMLElement): void {
  const q = new URLSearchParams(location.search)
  const key = q.get('key')
  const lang: Lang = q.get('lang') === 'en' ? 'en' : 'el'
  const w = WORDS[lang]
  document.documentElement.lang = lang
  document.title = w.brand

  const link = (params: Record<string, string>, withKey: string | null = key): string => {
    return appLink({ ...params, key: withKey, lang: params.lang ?? lang })
  }

  app.innerHTML = `
  <div id="landing">
    <header class="bar" id="top">
      <a class="brand" href="${esc(link({}))}">${LOGO}<span>${w.brand}</span></a>
      <nav aria-label="${w.guides}">
        <a class="navlink" href="#guides" aria-label="${w.guides}">${svg(ICON.mark)}<span>${w.guides}</span></a>
        <a class="navadd" href="${esc(link({ mode: 'build' }))}">${svg(ICON.plus)}<span>${w.add}</span></a>
        <a class="lang" href="${esc(link({ lang: lang === 'en' ? 'el' : 'en' }))}" lang="${lang === 'en' ? 'el' : 'en'}" aria-label="${w.otherAria}">${w.other}</a>
      </nav>
    </header>

    <main>
      <section class="hero ws">
        <div class="scene" id="boxes" role="group" aria-label="${w.boxes}">
          <i class="glow"></i><i class="cord"></i><i class="shade"></i><i class="bulb"></i><i class="cone"></i><i class="table"></i><i class="edge"></i>
          <div class="row">${BOX(1, 'KNAGGLIG')}${BOX(2, 'MARKUS')}</div>
        </div>
        <div class="copy">
          <h1>${w.h1}</h1>
          <div class="actions">
            <a class="primary" href="${esc(link({ mode: 'shelf', scan: '1' }))}"><span>${w.cta}</span>${svg(ICON.arrow)}</a>
          </div>
          <ol class="steps3" aria-label="${w.how}">
            <li class="t">${svg(ICON.scanbox)}<span>${w.s1}</span></li>
            <li class="ar" aria-hidden="true">${svg(ICON.arrow)}</li>
            <li class="t">${HALF}<span>${w.s2}</span></li>
            <li class="ar" aria-hidden="true">${svg(ICON.arrow)}</li>
            <li class="t">${CUBE}<span>${w.s3}</span></li>
          </ol>
          <p class="hint" id="hint" role="status"></p>
          <input id="file" type="file" accept="application/pdf,image/*" multiple hidden />
        </div>
      </section>

      <section class="guides" id="guides" aria-labelledby="guides-h">
        <h2 id="guides-h">${w.yours}</h2>
        <div id="list" class="grid" aria-live="polite"><p class="quiet">${w.loading}</p></div>
      </section>
    </main>
    <nav class="tabs" aria-label="${w.brand}">
      <a class="tab on" href="#top">${svg(ICON.home)}<span>${w.home}</span></a>
      <a class="tab scan" href="${esc(link({ mode: 'shelf', scan: '1' }))}" aria-label="${w.scan}"><i>${svg(ICON.scan)}</i></a>
      <a class="tab" href="#guides">${svg(ICON.shelf)}<span>${w.guides}</span></a>
    </nav>
    <div class="veil" id="veil" hidden><p>${w.dropping}</p></div>
  </div>`

  const $ = <T extends HTMLElement>(sel: string) => app.querySelector<T>(sel)!
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')?.setAttribute('content', '#0b0907')

  const list = $('#list')
  const hint = $('#hint')
  app.querySelectorAll<HTMLAnchorElement>('#boxes .bx').forEach((bx) => (bx.href = link({ mode: 'shelf', scan: '1' })))
  const tabs = app.querySelectorAll<HTMLElement>('.tabs .tab:not(.scan)')
  new IntersectionObserver(([e]) => {
    tabs[0]!.classList.toggle('on', !e!.isIntersecting)
    tabs[1]!.classList.toggle('on', e!.isIntersecting)
  }, { rootMargin: '-45% 0px -45% 0px' }).observe($('#guides'))

  // ---- the access key: say which problem it is, and take the key here instead of asking for an edited address
  const askForKey = () => {
    if ($('.hero').querySelector('.keybox')) return
    $<HTMLElement>('.hero .actions').hidden = true
    hint.hidden = true
    list.innerHTML = ''
    $<HTMLElement>('.guides').hidden = true
    const box = document.createElement('form')
    box.className = 'keybox'
    box.innerHTML = `<h2>${key ? w.keyWrong : w.keyMissing}</h2><p>${w.keyHelp}</p>
      <div class="row"><input name="key" type="text" autocomplete="off" spellcheck="false" aria-label="${w.keyField}" placeholder="${w.keyField}" required />
      <button class="primary" type="submit"><span>${w.keyGo}</span>${svg(ICON.arrow)}</button></div>`
    box.addEventListener('submit', (e) => {
      e.preventDefault()
      const v = String(new FormData(box).get('key') ?? '').trim()
      if (v) location.assign(link({}, v))
    })
    $('.hero').append(box)
    if (location.hash !== '#guides') box.querySelector('input')!.focus()
  }

  // ---- the guides
  const state = (j: JobRow): { word: string; cls: string; open: string; action: string } => {
    if (j.status === 'done') return { word: w.ready, cls: 'ok', open: link({ mode: 'ready', job: j.job_id }), action: w.open }
    if (j.status === 'waiting_approval') return { word: w.approve, cls: 'ask', open: link({ mode: 'build', job: j.job_id }), action: w.watch }
    if (j.status === 'failed' || j.status === 'error') return { word: w.failed, cls: 'bad', open: link({ mode: 'build', job: j.job_id }), action: w.watch }
    return { word: w.building, cls: 'run', open: link({ mode: 'build', job: j.job_id }), action: w.watch }
  }
  const name = (j: JobRow) => j.title || (j.filename ?? '').replace(/__.*$|\.pdf$/gi, '').replace(/[-_]+/g, ' ') || j.job_id

  const draw = (jobs: JobRow[]) => {
    // one card per product: the newest finished build of it, else its newest job
    const by = new Map<string, JobRow>()
    for (const j of [...jobs].sort((a, b) => (b.created ?? 0) - (a.created ?? 0))) {
      const k = (j.filename || name(j)).toLowerCase()
      const have = by.get(k)
      if (!have || (have.status !== 'done' && j.status === 'done')) by.set(k, j)
    }
    const rows = [...by.values()]
    // the boxes of the hero carry the names of real guides and open them; with fewer than two, the rest go to the scanner
    const done = rows.filter((j) => j.status === 'done')
    app.querySelectorAll<HTMLAnchorElement>('#boxes .bx').forEach((bx, i) => {
      const j = done[i]
      bx.href = j ? link({ mode: 'ready', job: j.job_id }) : link({ mode: 'shelf', scan: '1' })
      // the label carries the product's own word: "KNAGGLIG box" -> KNAGGLIG, "IKEA side table" -> SIDE TABLE
      if (j) bx.querySelector('em')!.textContent = (name(j).replace(/^ikea\s+/i, '').match(/^\S+(\s+(table|chair|box|shelf|stool|desk))?/i)?.[0] ?? name(j)).toUpperCase().slice(0, 13)
      bx.setAttribute('aria-label', j ? name(j) : w.scan)
    })
    if (!rows.length) {
      list.innerHTML = `<p class="quiet">${w.empty}</p>`
      return
    }
    list.replaceChildren(
      ...rows.map((j) => {
        const s = state(j)
        const a = document.createElement('a')
        a.className = 'card'
        a.href = s.open
        const pic = j.status === 'done' ? `<img src="${esc(fileUrl(j.job_id, 'renders/cover.png', key))}" alt="" loading="lazy" />` : `<span class="wait" aria-hidden="true">${svg(ICON.mark)}</span>`
        a.innerHTML = `<div class="pic ${s.cls}">${pic}</div>
          <div class="meta"><h3>${esc(name(j))}</h3>
          <p><span class="state ${s.cls}">${s.word}</span><span class="go">${s.action}${svg(ICON.arrow)}</span></p></div>`
        a.querySelector('img')?.addEventListener('error', (e) => (e.target as HTMLElement).remove())
        return a
      }),
    )
  }

  let timer = 0
  const load = async () => {
    try {
      const res = await apiFetch('/api/jobs', key)
      if (res.status === 401) return askForKey()
      if (!res.ok) throw new Error(String(res.status))
      const { jobs } = (await res.json()) as { jobs: JobRow[] }
      draw(jobs)
      // a guide still being built: look again, so its card turns to "Ready" by itself
      clearTimeout(timer)
      if (jobs.some((j) => j.status !== 'done' && j.status !== 'failed' && j.status !== 'error')) timer = window.setTimeout(load, 5000)
    } catch {
      list.innerHTML = `<p class="quiet">${w.offline}</p><button class="ghost" type="button" id="again">${w.retry}</button>`
      $('#again').addEventListener('click', () => void load())
    }
  }
  void load().then(() => {
    if (location.hash === '#guides') $('#guides').scrollIntoView()
  })

  // ---- adding a guide from here: a PDF or photos dropped anywhere on the page (the scanner screen has the buttons)
  let sending = false
  const send = async (files: File[]) => {
    if (!files.length || sending) return
    const picked = choose(null, files)
    if ('word' in picked) {
      hint.textContent = bw(lang, picked.word)
      hint.className = 'hint bad'
      return
    }
    sending = true
    $('.hero').setAttribute('aria-busy', 'true')
    hint.textContent = w.sending
    hint.className = 'hint busy'
    const out = await sendGuide(picked.chosen as Chosen, '', key)
    sending = false
    $('.hero').removeAttribute('aria-busy')
    if (out.ok) location.assign(link({ mode: 'build', job: out.job }))
    else if (out.word === 'noKey') askForKey()
    else {
      hint.textContent = bw(lang, out.word)
      hint.className = 'hint bad'
    }
  }
  const file = $<HTMLInputElement>('#file')
  file.addEventListener('change', () => {
    const files = [...(file.files ?? [])]
    file.value = ''
    void send(files)
  })
  const veil = $('#veil')
  let depth = 0
  const hasFiles = (e: DragEvent) => [...(e.dataTransfer?.types ?? [])].includes('Files')
  window.addEventListener('dragenter', (e) => { if (hasFiles(e)) { depth++; veil.hidden = false } })
  window.addEventListener('dragleave', () => { depth = Math.max(0, depth - 1); if (!depth) veil.hidden = true })
  window.addEventListener('dragover', (e) => { if (hasFiles(e)) e.preventDefault() })
  window.addEventListener('drop', (e) => {
    if (!hasFiles(e)) return
    e.preventDefault()
    depth = 0
    veil.hidden = true
    void send([...(e.dataTransfer?.files ?? [])])
  })
}
