import type { Lang, Manifest } from './types'

// Same origin in production (PLAN D4). A mirror on another host sets VITE_API_URL at build time.
// In dev only, `?api=<url>` points the whole page at a server (its files, its jobs, its socket).
const API = String(
  (import.meta.env.DEV && new URLSearchParams(location.search).get('api')) || import.meta.env.VITE_API_URL || '',
).replace(/\/$/, '')

/** The server's address, '' for the same origin. */
export const apiBase = (): string => API

/** URL of a server route (`/api/...` or a `/files/...` path the server gave), with `?key=` for EventSource and <img>. */
export function apiUrl(path: string, key: string | null, query: Record<string, string | number> = {}): string {
  const url = new URL(`${API}${path.startsWith('/') ? '' : '/'}${path}`, location.href)
  for (const [k, v] of Object.entries(query)) url.searchParams.set(k, String(v))
  if (key) url.searchParams.set('key', key)
  return url.toString()
}

/** fetch() a server route with the demo key in its header. */
export function apiFetch(path: string, key: string | null, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (key) headers.set('X-Demo-Key', key)
  return fetch(apiUrl(path, null), { ...init, headers, cache: 'no-store' })
}

export interface Params {
  job: string | null
  /** demo key from the page URL; it is repeated on every /files/ request and never stored */
  key: string | null
  lang: Lang | null
  /** 1-based step to open at */
  step: number | null
  phase: string | null
  speed: number | null
}

export function readParams(): Params {
  const q = new URLSearchParams(location.search)
  const lang = q.get('lang')
  const step = Number(q.get('step'))
  const speed = Number(q.get('speed'))
  return {
    // the dev server opens local-fresh when the address names no job; `?job=` (empty) asks for no job at all
    job: q.has('job') ? q.get('job') || null : import.meta.env.DEV ? 'local-fresh' : null,
    key: q.get('key'),
    lang: lang === 'el' || lang === 'en' ? lang : null,
    step: Number.isInteger(step) && step > 0 ? step : null,
    phase: q.get('phase'),
    speed: speed > 0 && speed <= 4 ? speed : null,
  }
}

/** URL of a file in the job folder, e.g. fileUrl(job, 'renders/step_01.png', key). */
export function fileUrl(job: string, path: string, key: string | null): string {
  const clean = path.replace(/^\/+/, '').split('/').map(encodeURIComponent).join('/')
  const url = `${API}/files/${encodeURIComponent(job)}/${clean}`
  return key ? `${url}?key=${encodeURIComponent(key)}` : url
}

export async function fetchManifest(job: string, key: string | null): Promise<Manifest> {
  const res = await fetch(fileUrl(job, 'manifest.json', key), { cache: 'no-cache' })
  if (!res.ok) throw new Error(`manifest.json: HTTP ${res.status}`)
  const manifest = (await res.json()) as Manifest
  if (!manifest || !Array.isArray(manifest.steps) || !manifest.glb) throw new Error('manifest.json has no steps or no glb')
  manifest.parts ??= []
  manifest.hardware ??= []
  return manifest
}
