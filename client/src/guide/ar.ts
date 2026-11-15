import '../styles/ar.css'
import { fetchManifest, fileUrl } from '../api'
import { appLink } from '../routes'

// The other ways to build this guide, in one quiet row beside the language switch of the steps' screen (#modes):
//
//   with the camera   src/live: the phone watches the real parts, a voice checks each step. On every device.
//   in the Quest      `?mode=xr`, where the browser can open an immersive session (or `?quest=1`).
//   on your table     on an iPhone or iPad the current step opens in AR Quick Look, life-size. The phone's ARKit
//                     (with the LiDAR scanner on a Pro) finds the table, keeps the model fixed to it while the
//                     person walks around, and hides it behind real things. The model is the step's own USDZ
//                     (pipeline/blender/export_usdz.py): the assembly as it stands, with this step's pieces
//                     moving into place in the accent colour.
//
// Each opens at the step the person is at, and each has its own way back to these steps. They sit in the corner
// and not over the room: the foot of the screen belongs to the piece to pick up.
//
// Safari opens Quick Look from an <a rel="ar"> whose only child is an <img>; anything else is a download.

const WORDS = {
  en: {
    nav: 'Other ways to build', live: 'With camera', liveLong: 'Build with live guidance', quest: 'Quest', questLong: 'Open in Quest',
    table: 'On your table', step: 'See this step on your table', done: 'See it finished on your table',
  },
  el: {
    nav: 'Άλλοι τρόποι', live: 'Με κάμερα', liveLong: 'Χτίσε με ζωντανή καθοδήγηση', quest: 'Quest', questLong: 'Άνοιξε στο Quest',
    table: 'Στο τραπέζι σου', step: 'Δες το βήμα στο τραπέζι σου', done: 'Δες το έτοιμο στο τραπέζι σου',
  },
}
const SVG = {
  // a phone on a stand; a headset
  live: '<rect x="7" y="2.5" width="10" height="15" rx="2"/><path d="M10 21.5h4M12 17.5v4"/>',
  quest: '<path d="M3.5 9.5c0-1.7 1.3-3 3-3h11c1.7 0 3 1.3 3 3v4.5c0 1.7-1.3 3-3 3h-2.6c-.8 0-1.4-.4-1.8-1.1l-.4-.8c-.4-.8-1.2-.8-1.6 0l-.4.8c-.4.7-1 1.1-1.8 1.1H6.5c-1.7 0-3-1.3-3-3z"/>',
}
// a cube standing on a surface, as a data: image (the anchor may hold nothing but an <img>)
const ICON = `data:image/svg+xml,${encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5 5.5 7v7.5L12 18l6.5-3.5V7L12 3.5Z"/><path d="M5.5 7 12 10.5 18.5 7M12 10.5V18M3 20.5h18"/></svg>',
)}`

export async function startTableAR(): Promise<void> {
  const q = new URLSearchParams(location.search)
  const job = q.get('job')
  const key = q.get('key')
  if (!job) return
  const words = () => WORDS[document.documentElement.lang === 'en' ? 'en' : 'el']
  /** the step the person is at, from 1 */
  let step = Math.max(1, Number(q.get('step') || 1))

  const bar = document.createElement('nav')
  bar.id = 'modes'
  const link = (id: string, icon: string) => {
    const a = document.createElement('a')
    a.id = id
    a.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${icon}</svg><span></span>`
    return a
  }
  const table = document.createElement('a') // filled below, when the guide has AR models and the device opens them
  table.id = 'table-ar'
  table.rel = 'ar'
  table.hidden = true
  const quest = link('table-quest', SVG.quest)
  quest.hidden = true
  const live = link('table-live', SVG.live)
  bar.append(table, quest, live)

  let paintTable = () => {}
  const paint = () => {
    const w = words()
    bar.setAttribute('aria-label', w.nav)
    live.querySelector('span')!.textContent = w.live
    live.setAttribute('aria-label', w.liveLong)
    live.title = w.liveLong
    live.href = appLink({ mode: 'live', job, step: String(step) })
    quest.querySelector('span')!.textContent = w.quest
    quest.setAttribute('aria-label', w.questLong)
    quest.title = w.questLong
    quest.href = appLink({ mode: 'xr', job })
    paintTable()
  }
  paint()
  ;(document.getElementById('stage') ?? document.body).append(bar)
  window.addEventListener('agj:step', (e) => {
    step = Math.max(1, (e as CustomEvent<{ step: number }>).detail.step)
    paint()
  })
  new MutationObserver(paint).observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] })

  // the Quest, where an immersive session can open
  const xr = (navigator as Navigator & { xr?: { isSessionSupported(m: string): Promise<boolean> } }).xr
  void (async () => {
    quest.hidden = !(q.get('quest') === '1' || (xr ? await xr.isSessionSupported('immersive-ar').catch(() => false) : false))
  })()

  // "on your table": `?ar=1` shows it on any device (a laptop then downloads the file), for checking the link
  const able = document.createElement('a').relList?.supports?.('ar') || q.get('ar') === '1'
  if (!able) return
  let manifest
  try {
    manifest = await fetchManifest(job, key)
  } catch {
    return
  }
  const steps = manifest.steps as { n: number; ar?: string }[]
  const finished = (manifest as { arFinished?: string }).arFinished
  if (!steps.some((s) => s.ar) && !finished) return // built before AR models were made

  const img = document.createElement('img')
  img.src = ICON
  img.width = 18
  img.height = 18
  table.append(img)
  paintTable = () => {
    const w = words()
    const at = steps[Math.min(step - 1, steps.length - 1)]
    const file = at?.ar ?? finished
    table.hidden = !file
    if (!file) return
    // #allowsContentScaling=0: life-size only, so the model matches the real parts
    table.href = `${fileUrl(job, file, key)}#allowsContentScaling=0`
    const label = at?.ar ? w.step : w.done
    table.setAttribute('aria-label', label)
    table.title = label
    table.dataset.label = w.table
    img.alt = label
  }
  paintTable()
}
