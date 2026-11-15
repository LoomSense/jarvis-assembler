// The guide page for phone and laptop: what the address opens when it names no `mode`.
// src/main.ts loads this file only then, and has already put the page's markup (the <template> of index.html)
// into the document, hidden. The styles of the page arrive with this file, so the markup is shown here.
import '../styles/page.css'
import { fetchManifest, fileUrl, readParams } from '../api'
import { startGuide, type Guide } from './guide'
import type { Watch } from './watch'
import { Stage } from './stage'
import { WORDS } from './words'
import { tr } from '../i18n'
import type { Lang, Manifest } from '../types'
import { loadGltf, prepareModel, type Model } from '../viewer/model'

const $ = <T extends Element>(id: string) => document.getElementById(id) as unknown as T

/** Read the address, show the loading line, load manifest and GLB (packed first), start the guide, own the language switch. */
export async function startGuidePage(): Promise<void> {
  const params = readParams()
  const query = new URLSearchParams(location.search)
  $<HTMLElement>('stage').hidden = false

  const now = $<HTMLParagraphElement>('now')
  const progress = $<HTMLElement>('progress')
  const langButton = $<HTMLButtonElement>('lang')
  let lang: Lang = params.lang ?? 'el'
  let guide: Guide | null = null
  let watch: Watch | null = null

  // ---- the one line of words, before the guide takes it over: loading, or what went wrong
  let said: () => void = () => {}
  const say = (
    text: () => string,
    opts: { quiet?: () => string; alert?: boolean; retry?: boolean; go?: { text: () => string; href: () => string } } = {},
  ) => {
    said = () => {
      now.replaceChildren()
      now.setAttribute('role', opts.alert ? 'alert' : 'status')
      const main = document.createElement('span')
      main.textContent = text()
      now.append(main)
      const quiet = opts.quiet?.()
      if (quiet) {
        const q = document.createElement('span')
        q.className = 'of'
        q.textContent = quiet
        now.append(q)
      }
      if (opts.retry) {
        const again = document.createElement('button')
        again.type = 'button'
        again.textContent = tr(lang, 'retry')
        again.addEventListener('click', () => location.reload())
        now.append(again)
      }
      if (opts.go) {
        const go = document.createElement('a')
        go.textContent = opts.go.text()
        go.href = opts.go.href()
        now.append(go)
      }
      now.classList.toggle('long', text().length > 24)
    }
    said()
  }

  // ---- the other language, two letters in the corner; the choice is kept in the address
  const setLang = (l: Lang) => {
    lang = l
    document.documentElement.lang = l
    document.title = tr(l, 'appTitle')
    langButton.textContent = WORDS[l].otherLanguageShort
    langButton.setAttribute('aria-label', WORDS[l].otherLanguage)
    langButton.lang = l === 'el' ? 'en' : 'el'
    if (guide) guide.setLang(l)
    else said()
    watch?.setLang(l)
  }
  langButton.addEventListener('click', () => {
    setLang(lang === 'el' ? 'en' : 'el')
    const url = new URL(location.href)
    url.searchParams.set('lang', lang)
    history.replaceState(null, '', url)
  })
  setLang(lang)

  if (!params.job) {
    // On the real server, a page without a guide offers to make one (the build screen, `?mode=build`).
    // A static host, or a page that cannot tell, keeps the plain line.
    const devApi = import.meta.env.DEV && query.get('api')
    if (devApi || import.meta.env.VITE_API_URL || (await serverIsHere())) {
      // nothing to step through: the line has the foot of the room to itself
      for (const id of ['prev', 'next', 'progress']) $<HTMLElement>(id).style.display = 'none'
      now.classList.add('bare')
      return say(() => WORDS[lang].noGuide, {
        go: {
          text: () => WORDS[lang].makeOne,
          href: () => {
            const url = new URL(location.pathname, location.href)
            url.searchParams.set('mode', 'build')
            if (params.key) url.searchParams.set('key', params.key)
            url.searchParams.set('lang', lang)
            if (devApi) url.searchParams.set('api', devApi)
            return url.toString()
          },
        },
      })
    }
    return say(() => tr(lang, 'noJob'), { alert: true })
  }
  const job = params.job

  let stage: Stage
  try {
    stage = new Stage($<HTMLCanvasElement>('view'))
  } catch (err) {
    console.error(err)
    return say(() => tr(lang, 'noWebgl'), { alert: true })
  }

  let fraction: number | null = null
  const loading = () =>
    say(() => tr(lang, 'loading'), { quiet: () => (fraction === null ? '' : `${Math.round(fraction * 100)}%`) })
  loading()

  let manifest: Manifest
  let model: Model | null = null
  try {
    manifest = await fetchManifest(job, params.key)
    // The packed GLB is a tenth of the size, and the page turns its packed coordinates back into metres
    // (guide/materials.ts), so it looks the same as the plain one. Use the plain one when the packed one fails to
    // load, or when it lacks a node or a clip the manifest names. `?glb=plain` asks for the plain one first (debug).
    const candidates = [manifest.glbPacked, manifest.glb].filter((p): p is string => !!p)
    if (query.get('glb') === 'plain') candidates.reverse()
    let lastError: unknown = null
    for (const path of candidates) {
      try {
        const gltf = await loadGltf(fileUrl(job, path, params.key), (f) => {
          fraction = f
          progress.style.setProperty('--p', String(f ?? 0))
          loading()
        })
        model = prepareModel(gltf, manifest)
        break
      } catch (err) {
        lastError = err
        console.warn(`could not use ${path}`, err)
      }
    }
    if (!model) throw lastError ?? new Error('no GLB in the manifest')
  } catch (err) {
    console.error(err)
    progress.style.setProperty('--p', '0')
    return say(() => tr(lang, 'loadFailed'), { alert: true, retry: true })
  }

  // `?at=<n>` opens with action n already done (0-based, debug); `?step=<n>` opens at the start of step n.
  const at = query.has('at') ? Number(query.get('at')) : null
  let openAt = at !== null && Number.isInteger(at) && at >= 0 ? at : null
  if (openAt === null && params.step) {
    const before = manifest.steps.slice(0, params.step - 1).reduce((n, s) => n + Math.max(1, s.actions?.length ?? 0), 0)
    if (before > 0) openAt = before - 1 // everything before that step is done; step 1 is the ordinary opening
  }

  now.replaceChildren()
  now.setAttribute('role', 'status')
  now.classList.remove('long')
  guide = startGuide({
    stage,
    manifest,
    model,
    lang,
    openAt,
    material: query.get('material'),
    page: {
      stock: $<HTMLUListElement>('stock'),
      now,
      prev: $<HTMLButtonElement>('prev'),
      next: $<HTMLButtonElement>('next'),
      progress,
      seat: document.querySelector<SVGGElement>('#seat g')!,
    },
  })
  document.documentElement.dataset.ready = 'true'
  // for the console and for scripts/shots.mjs
  ;(window as unknown as { __guide: unknown }).__guide = guide.debug

  // ---- live guidance: the camera watches, the server says what it sees (guide/watch.ts, src/assist).
  // The control is offered only where its socket can exist:
  //   - an API base was given (VITE_API_URL at build time, or `?api=` which is read in dev only, because it
  //     decides where camera frames go);
  //   - or this is the built page and the host it came from answers as the server (a static host does not);
  //   - or `?assist=1` forces it. `?assist=0` leaves it out anywhere.
  // The dev server has no such socket, so there the control is absent without one of the above.
  // Its code is loaded only when it is offered, and the camera and the socket open only on a tap.
  const apiBase = (import.meta.env.DEV && query.get('api')) || String(import.meta.env.VITE_API_URL ?? '')
  const asked = query.get('assist')
  const able = !!navigator.mediaDevices?.getUserMedia && 'WebSocket' in window
  let offer = asked === '1'
  if (!offer && asked !== '0' && able) offer = apiBase ? true : !import.meta.env.DEV && (await serverIsHere())
  if (offer) {
    try {
      const { startWatch } = await import('./watch')
      watch = startWatch({
        guide,
        job,
        key: params.key,
        apiBase,
        lang,
        invite: asked === '1',
        page: {
          stage: $<HTMLElement>('stage'),
          button: $<HTMLButtonElement>('watch'),
          word: $<HTMLElement>('watch-word'),
          now,
          hint: $<HTMLElement>('hint'),
          heard: $<HTMLElement>('heard'),
        },
      })
      ;(window as unknown as { __watch: unknown }).__watch = watch.debug
    } catch (err) {
      console.warn('live guidance could not be loaded; the guide works without it', err)
    }
  }
}

/** Does the host this page came from answer as the server (`GET /healthz` gives `{ok: true}`)? A static host does not. */
async function serverIsHere(): Promise<boolean> {
  const stop = new AbortController()
  const timer = window.setTimeout(() => stop.abort(), 4000)
  try {
    const res = await fetch('/healthz', { cache: 'no-store', signal: stop.signal })
    return res.ok && ((await res.json()) as { ok?: unknown } | null)?.ok === true
  } catch {
    return false // no answer, or a page instead of an answer: the guide works without live guidance
  } finally {
    clearTimeout(timer)
  }
}
