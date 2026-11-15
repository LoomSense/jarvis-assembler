// How a guide comes in on the build screen, without any markup: find it in the catalog by name, IKEA article
// number or product link (`/api/catalog`, `/api/catalog/open`), or send its PDF or photos of its pages
// (`POST /api/jobs`). build.ts draws the screen and calls these; a new layout can call them the same way.
import { apiFetch } from '../api'
import type { BuildWord } from './words'

export interface CatalogItem {
  product: string
  guide_id?: string | null
  article?: string | null
  job_id?: string | null
  steps?: number
  parts?: number
  created?: number | null
  source: 'built' | 'known'
  ready: boolean
  guide?: string
}

/** What came of an attempt: a job to follow or open, or the word for what went wrong. */
export type Outcome = { ok: true; job: string; ready: boolean; product?: string } | { ok: false; word: BuildWord }

const MAX_MB = 40 // the server's max_upload_mb
const MAX_PHOTOS = 60 // server/photos.py MAX_PHOTOS

/** The catalog's matches for what was typed (all of it for an empty query), ready guides first. */
export async function searchCatalog(q: string, key: string | null, signal?: AbortSignal): Promise<CatalogItem[]> {
  const res = await apiFetch(`/api/catalog?q=${encodeURIComponent(q.trim())}`, key, { signal })
  if (!res.ok) throw res.status
  return ((await res.json()) as { items?: CatalogItem[] }).items ?? []
}

/**
 * The query to send to `/api/catalog/open` for a chosen match: its article number or guide id say exactly which
 * guide it is; the product name alone may match others too.
 */
export const openQuery = (item: CatalogItem): string => item.article || item.guide_id || item.product

/** Open a guide by name, article number or IKEA product link: a built one at once, any other starts its build. */
export async function openCatalog(q: string, key: string | null, size?: string): Promise<Outcome> {
  const text = q.trim()
  if (!text) return { ok: false, word: 'notFound' }
  try {
    const res = await apiFetch('/api/catalog/open', key, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(size?.trim() ? { q: text, size: size.trim() } : { q: text }),
    })
    if (!res.ok) return { ok: false, word: openWord(res.status) }
    const body = (await res.json()) as { job_id: string; ready: boolean; product?: string }
    return { ok: true, job: body.job_id, ready: body.ready, product: body.product }
  } catch {
    return { ok: false, word: 'offline' }
  }
}

function openWord(status: number): BuildWord {
  if (status === 401) return 'noKey'
  if (status === 404 || status === 422) return 'notFound'
  if (status === 502) return 'shopDown'
  if (status === 413) return 'tooBig'
  return 'notOpened'
}

/** The guide as the person gave it: one PDF, or photos of its pages in the order they came. */
export type Chosen = { kind: 'pdf'; files: [File] } | { kind: 'photos'; files: File[] }

const isPdf = (f: File) => f.type === 'application/pdf' || /\.pdf$/i.test(f.name)
const isHeic = (f: File) => /image\/hei[cf]/i.test(f.type) || /\.hei[cf]$/i.test(f.name)
const isImage = (f: File) => f.type.startsWith('image/') || /\.(jpe?g|png|webp|hei[cf])$/i.test(f.name)

/**
 * Add newly chosen files to what was chosen before. Photos add up (a phone takes them one by one); a PDF stands
 * alone and replaces everything. Returns the new choice, or the word for why these files cannot be used.
 */
export function choose(before: Chosen | null, files: File[]): { chosen: Chosen } | { word: BuildWord } {
  if (!files.length) return before ? { chosen: before } : { word: 'notGuide' }
  const pdfs = files.filter(isPdf)
  if (pdfs.length) {
    if (pdfs.length > 1 || files.length > 1) return { word: 'onePdf' }
    return check({ kind: 'pdf', files: [pdfs[0]] })
  }
  if (!files.every(isImage)) return { word: 'notGuide' }
  const earlier = before?.kind === 'photos' ? before.files : []
  return check({ kind: 'photos', files: [...earlier, ...files] })
}

function check(c: Chosen): { chosen: Chosen } | { word: BuildWord } {
  if (c.files.length > MAX_PHOTOS) return { word: 'tooMany' }
  if (c.files.reduce((n, f) => n + f.size, 0) > MAX_MB * 1024 * 1024) return { word: 'tooBig' }
  return { chosen: c }
}

/** Send the guide to the server, which starts the build. `size`: the overall size the person typed, if any. */
export async function sendGuide(c: Chosen, size: string, key: string | null): Promise<Outcome> {
  const body = new FormData()
  if (c.kind === 'pdf') body.append('file', c.files[0], c.files[0].name)
  else for (const f of c.files) body.append('pages', f, f.name)
  if (size.trim()) body.append('size', size.trim())
  try {
    const res = await apiFetch('/api/jobs', key, { method: 'POST', body })
    if (!res.ok) {
      const word: BuildWord =
        res.status === 401 ? 'noKey'
        : res.status === 413 ? 'tooBig'
        : res.status === 415 ? (c.files.some(isHeic) ? 'heic' : 'notGuide')
        : res.status === 422 ? (c.files.length > MAX_PHOTOS ? 'tooMany' : 'onePdf')
        : 'failed'
      return { ok: false, word }
    }
    const { job_id } = (await res.json()) as { job_id: string }
    return { ok: true, job: job_id, ready: false }
  } catch {
    return { ok: false, word: 'offline' }
  }
}

/** Call `fn` once the calls have paused for `ms`. */
export function debounce<A extends unknown[]>(fn: (...a: A) => void, ms: number): (...a: A) => void {
  let t = 0
  return (...a: A) => {
    clearTimeout(t)
    t = window.setTimeout(() => fn(...a), ms)
  }
}
