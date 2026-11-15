import '../styles/ar.css'
import { fetchManifest, fileUrl } from '../api'
import { appLink } from '../routes'

// "See it on your table": on an iPhone or iPad the current step opens in AR Quick Look, life-size. The phone's
// ARKit (with the LiDAR scanner on a Pro) finds the table, keeps the model fixed to it while the person walks
// around, and hides it behind real things. The model is the step's own USDZ (pipeline/blender/export_usdz.py):
// the assembly as it stands, with this step's pieces moving into place in the accent colour.
//
// Safari opens Quick Look from an <a rel="ar"> whose only child is an <img>; anything else is a download.

const WORDS = {
  en: { step: 'See this step on your table', done: 'See it finished on your table', hint: 'iPhone or iPad', live: 'Build with live guidance' },
  el: { step: 'Δες το βήμα στο τραπέζι σου', done: 'Δες το έτοιμο στο τραπέζι σου', hint: 'iPhone ή iPad', live: 'Χτίσε με ζωντανή καθοδήγηση' },
}
// a cube standing on a surface, as a data: image (the anchor may hold nothing but an <img>)
const ICON = `data:image/svg+xml,${encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#000" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5 5.5 7v7.5L12 18l6.5-3.5V7L12 3.5Z"/><path d="M5.5 7 12 10.5 18.5 7M12 10.5V18M3 20.5h18"/></svg>',
)}`

export async function startTableAR(): Promise<void> {
  const q = new URLSearchParams(location.search)
  const job = q.get('job')
  const key = q.get('key')
  // Live guidance (src/live): the camera watches, a voice says what it sees and checks each step. On every device.
  const live = document.createElement('a')
  live.id = 'table-live'
  let liveStep = Math.max(1, Number(q.get('step') || 1))
  const paintLive = () => {
    if (!job) return
    live.textContent = WORDS[document.documentElement.lang === 'en' ? 'en' : 'el'].live
    live.href = appLink({ mode: 'live', job, step: String(liveStep) })
  }
  if (job) {
    paintLive()
    document.body.append(live)
    window.addEventListener('agj:step', (e) => {
      liveStep = Math.max(1, (e as CustomEvent<{ step: number }>).detail.step)
      paintLive()
    })
    new MutationObserver(paintLive).observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] })
  }
  const probe = document.createElement('a')
  // `?ar=1` shows the control on any device (a laptop then downloads the file): for checking the link
  const able = probe.relList?.supports?.('ar') || q.get('ar') === '1'
  if (!job || !able) return
  let manifest
  try {
    manifest = await fetchManifest(job, key)
  } catch {
    return
  }
  const steps = manifest.steps as { n: number; ar?: string }[]
  const finished = (manifest as { arFinished?: string }).arFinished
  if (!steps.some((s) => s.ar) && !finished) return // built before AR models were made

  const a = document.createElement('a')
  a.id = 'table-ar'
  a.rel = 'ar'
  const img = document.createElement('img')
  img.src = ICON
  img.width = 22
  img.height = 22
  a.append(img)
  document.body.append(a)
  document.documentElement.dataset.tableAr = '1' // the two pills stack

  let at = Math.max(0, Number(q.get('step') || 1) - 1)
  const paint = () => {
    const w = WORDS[document.documentElement.lang === 'en' ? 'en' : 'el']
    const step = steps[Math.min(at, steps.length - 1)]
    const file = step?.ar ?? finished
    a.hidden = !file
    if (!file) return
    // #allowsContentScaling=0: life-size only, so the model matches the real parts
    a.href = `${fileUrl(job, file, key)}#allowsContentScaling=0`
    const label = step?.ar ? w.step : w.done
    a.setAttribute('aria-label', label)
    a.dataset.label = label
    img.alt = label
  }
  paint()
  window.addEventListener('agj:step', (e) => {
    at = Math.max(0, (e as CustomEvent<{ step: number }>).detail.step - 1) // the step's number, from 1
    paint()
  })
  new MutationObserver(paint).observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] })
}
