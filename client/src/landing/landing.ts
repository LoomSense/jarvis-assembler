import '../styles/landing.css'
import { apiFetch, fileUrl } from '../api'
import { choose, sendGuide, type Chosen } from '../build/intake'

// The landing page (no mode, no job): what the app does, one clear action (add a guide), and the guides that
// exist, each with its picture and state. The 3D workbench that used to be the home screen is at `?mode=shelf`;
// a finished guide still opens on its "ready" screen (`?mode=ready&job=`, src/home/home.ts).

type Lang = 'el' | 'en'
interface JobRow { job_id: string; status: string; stage: string; title: string | null; filename: string | null; created?: number }

const WORDS = {
  en: {
    brand: 'Assembly Guide', guides: 'My guides', add: 'New guide', other: 'ελ', otherAria: 'Ελληνικά',
    h1: 'Any assembly guide, as a 3D walkthrough',
    lead: 'Add the PDF, or photos of the pages. An agent reads the guide, builds every part in 3D and shows each step in the guide’s own order, one screw at a time.',
    upload: 'Upload a guide', find: 'Find a product by name or code', drop: 'or drop a PDF or photos anywhere on this page',
    dropping: 'Drop it to start', sending: 'Sending the guide',
    how: 'How it works',
    s1: 'Add the guide', s1d: 'A PDF from the maker’s site, or photos of the paper booklet.',
    s2: 'The agent builds it', s2d: 'It models the parts, fits them together and checks each step against the guide’s drawing. A few minutes.',
    s3: 'Follow the steps', s3d: 'On a phone or laptop in 3D, or in a headset that watches you build and speaks.',
    yours: 'My guides', empty: 'No guides yet. Upload the first one.', loading: 'Loading the guides',
    ready: 'Ready', building: 'Building', approve: 'Waiting for your approval', failed: 'Stopped', open: 'Open', watch: 'See progress',
    keyMissing: 'This address has no access key', keyWrong: 'The access key in this address is not accepted',
    keyHelp: 'Paste the key your team gave you.', keyField: 'Access key', keyGo: 'Continue',
    offline: 'The server did not answer. Try again in a moment.', retry: 'Try again',
    notGuide: 'That is not a PDF or a photo.', tooBig: 'The files are too large.', failedSend: 'The guide could not be sent.',
  },
  el: {
    brand: 'Οδηγός συναρμολόγησης', guides: 'Οι οδηγοί μου', add: 'Νέος οδηγός', other: 'en', otherAria: 'English',
    h1: 'Κάθε οδηγός συναρμολόγησης, βήμα βήμα σε 3D',
    lead: 'Βάλε το PDF ή φωτογραφίες των σελίδων. Ένας πράκτορας διαβάζει τον οδηγό, φτιάχνει κάθε κομμάτι σε 3D και δείχνει κάθε βήμα με τη σειρά του οδηγού, βίδα βίδα.',
    upload: 'Ανέβασε έναν οδηγό', find: 'Βρες ένα προϊόν με όνομα ή κωδικό', drop: 'ή άφησε ένα PDF ή φωτογραφίες οπουδήποτε στη σελίδα',
    dropping: 'Άφησέ το για να ξεκινήσει', sending: 'Στέλνω τον οδηγό',
    how: 'Πώς δουλεύει',
    s1: 'Βάλε τον οδηγό', s1d: 'Ένα PDF από τη σελίδα του κατασκευαστή ή φωτογραφίες από το χάρτινο φυλλάδιο.',
    s2: 'Ο πράκτορας τον χτίζει', s2d: 'Φτιάχνει τα κομμάτια, τα ταιριάζει και ελέγχει κάθε βήμα με το σχέδιο του οδηγού. Λίγα λεπτά.',
    s3: 'Ακολούθησε τα βήματα', s3d: 'Σε κινητό ή laptop σε 3D, ή με headset που σε βλέπει να χτίζεις και σου μιλάει.',
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
}
const svg = (d: string) => `<svg viewBox="0 0 24 24" aria-hidden="true">${d}</svg>`
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)

export function startLanding(app: HTMLElement): void {
  const q = new URLSearchParams(location.search)
  const key = q.get('key')
  const lang: Lang = q.get('lang') === 'en' ? 'en' : 'el'
  const w = WORDS[lang]
  document.documentElement.lang = lang
  document.title = w.brand

  const link = (params: Record<string, string>, withKey: string | null = key): string => {
    const url = new URL(location.pathname, location.href)
    if (withKey) url.searchParams.set('key', withKey)
    url.searchParams.set('lang', params.lang ?? lang)
    for (const [k, v] of Object.entries(params)) if (k !== 'lang') url.searchParams.set(k, v)
    return url.toString()
  }

  app.innerHTML = `
  <div id="landing">
    <header class="bar">
      <a class="brand" href="${esc(link({}))}">${svg(ICON.mark)}<span>${w.brand}</span></a>
      <nav aria-label="${w.guides}">
        <a class="navlink" href="#guides">${w.guides}</a>
        <a class="navadd" href="${esc(link({ mode: 'build' }))}">${svg(ICON.plus)}<span>${w.add}</span></a>
        <a class="lang" href="${esc(link({ lang: lang === 'en' ? 'el' : 'en' }))}" lang="${lang === 'en' ? 'el' : 'en'}" aria-label="${w.otherAria}">${w.other}</a>
      </nav>
    </header>

    <main>
      <section class="hero">
        <h1>${w.h1}</h1>
        <p class="lead">${w.lead}</p>
        <div class="actions">
          <button class="primary" type="button" id="pick">${svg(ICON.up)}<span>${w.upload}</span></button>
          <a class="ghost" href="${esc(link({ mode: 'build' }))}">${svg(ICON.search)}<span>${w.find}</span></a>
        </div>
        <p class="hint" id="hint" role="status">${w.drop}</p>
        <input id="file" type="file" accept="application/pdf,image/*" multiple hidden />
      </section>

      <section class="how" aria-labelledby="how-h">
        <h2 id="how-h">${w.how}</h2>
        <ol>
          <li><b>1</b><div><h3>${w.s1}</h3><p>${w.s1d}</p></div></li>
          <li><b>2</b><div><h3>${w.s2}</h3><p>${w.s2d}</p></div></li>
          <li><b>3</b><div><h3>${w.s3}</h3><p>${w.s3d}</p></div></li>
        </ol>
      </section>

      <section class="guides" id="guides" aria-labelledby="guides-h">
        <h2 id="guides-h">${w.yours}</h2>
        <div id="list" class="grid" aria-live="polite"><p class="quiet">${w.loading}</p></div>
      </section>
    </main>
    <div class="veil" id="veil" hidden><p>${w.dropping}</p></div>
  </div>`

  const $ = <T extends HTMLElement>(sel: string) => app.querySelector<T>(sel)!
  const list = $('#list')
  const hint = $('#hint')

  // ---- the access key: say which problem it is, and take the key here instead of asking for an edited address
  const askForKey = () => {
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
    box.querySelector('input')!.focus()
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
        const pic = j.status === 'done' ? `<img src="${esc(fileUrl(j.job_id, 'renders/cover.png', key))}" alt="" loading="lazy" />` : `<span class="spin" aria-hidden="true"></span>`
        a.innerHTML = `<div class="pic ${s.cls}">${pic}</div>
          <div class="meta"><h3>${esc(name(j))}</h3>
          <p><span class="chip ${s.cls}"><i></i>${s.word}</span><span class="go">${s.action}${svg(ICON.arrow)}</span></p></div>`
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
  void load()

  // ---- adding a guide from here: the button, or a drop anywhere on the page
  const send = async (files: File[]) => {
    if (!files.length) return
    const picked = choose(null, files)
    if ('word' in picked) {
      hint.textContent = picked.word === 'tooBig' || picked.word === 'tooMany' ? w.tooBig : w.notGuide
      hint.className = 'hint bad'
      return
    }
    hint.textContent = w.sending
    hint.className = 'hint busy'
    const out = await sendGuide(picked.chosen as Chosen, '', key)
    if (out.ok) location.assign(link({ mode: 'build', job: out.job }))
    else if (out.word === 'noKey') askForKey()
    else {
      hint.textContent = out.word === 'offline' ? w.offline : out.word === 'tooBig' ? w.tooBig : out.word === 'notGuide' ? w.notGuide : w.failedSend
      hint.className = 'hint bad'
    }
  }
  const file = $<HTMLInputElement>('#file')
  $('#pick').addEventListener('click', () => file.click())
  file.addEventListener('change', () => void send([...(file.files ?? [])]))
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
