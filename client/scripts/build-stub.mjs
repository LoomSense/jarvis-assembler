// A tiny stand-in for the server's job API that replays a recorded events.jsonl: no agent, no cost.
// For the build screen (?mode=build) and its check (scripts/build-shots.mjs). Dev only.
// Also the guide catalog (GET /api/catalog, POST /api/catalog/open) and uploads of photos (`pages`) with a `size`.
// Article 999.999.99 answers 502 (IKEA unreachable), another unknown article starts a build as if fetched,
// any other text that matches nothing answers 404. GET /stub/last says what the last upload carried.
//
//   node scripts/build-stub.mjs     env: PORT=8788 RECORD=<job folder> SPEED=0.12 MAXGAP=2200 BUFFER_SSE=1 FIX=1
//   then open http://127.0.0.1:5173/?mode=build&api=http://127.0.0.1:8788&key=stub
//
// Key: "stub" (header X-Demo-Key or ?key=). BUFFER_SSE=1 makes the SSE route send its first line and then
// nothing, like a cloudflared quick tunnel. FIX=1 injects a rejected build and a fix after build run 1
// (a rendering test only; the recorded run had none).
import { createServer } from 'node:http'
import { createReadStream, readFileSync, statSync } from 'node:fs'
import { dirname, extname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const PORT = Number(process.env.PORT ?? 8788)
const RECORD = resolve(process.env.RECORD ?? join(dirname(fileURLToPath(import.meta.url)), '../../jobs/1115-0022-2d4ab6'))
const SPEED = Number(process.env.SPEED ?? 0.12)
const MAXGAP = Number(process.env.MAXGAP ?? 2200)
const BUFFER = process.env.BUFFER_SSE === '1'
const FIX = process.env.FIX === '1'
const KEY = 'stub'
const recId = RECORD.split(sep).pop()
const recorded = readFileSync(join(RECORD, 'events.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
const plan = JSON.parse(readFileSync(join(RECORD, 'plan.json'), 'utf8'))
const status0 = JSON.parse(readFileSync(join(RECORD, 'status.json'), 'utf8'))

const jobs = new Map()
// the catalog: the recorded job is built; two guides are known but not built yet (one with an accent, to test folding)
const KNOWN = [
  { product: 'MARKUS office chair', guide_id: 'AA-251870-22', article: '90289172', guide: 'eval/guides/ikea/markus-office-chair__AA-251870-22.pdf' },
  { product: 'BEKVÄM step stool', guide_id: 'AA-498351-9', article: '40290421', guide: 'eval/guides/ikea/bekvaem-step-stool__AA-498351-9.pdf' },
]
const fold = (t) => (t ?? '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
function article(q) {
  const t = (q ?? '').trim()
  const url = /^https?:\/\/(?:www\.)?ikea\.com\/[a-z]{2}\/[a-z]{2}\/p\/[a-z0-9-]*?-(s?\d{8})\/?(?:[?#].*)?$/i.exec(t)
  if (url) return url[1].toLowerCase()
  const m = /^(s?)\s*(\d{3})[\s.-]?(\d{3})[\s.-]?(\d{2})$/i.exec(t)
  return m ? m[1].toLowerCase() + m[2] + m[3] + m[4] : null
}
function catalog() {
  const built = { product: plan.title, guide_id: plan.guide_id, article: null, job_id: recId, steps: plan.steps.length, parts: plan.parts.length, created: (T0 - 3600_000) / 1000, source: 'built', ready: true }
  return [built, ...KNOWN.map((k) => ({ ...k, job_id: null, source: 'known', ready: false }))]
}
function search(q) {
  const a = article(q)
  return catalog().filter((i) => {
    if (!q.trim()) return true
    if (a && i.article === a) return true
    const hay = fold([i.product, i.guide_id, i.article].filter(Boolean).join(' '))
    return fold(q).split(/\s+/).filter(Boolean).every((w) => hay.includes(w))
  })
}
let lastUpload = null
const readBody = async (req) => {
  const parts = []
  for await (const c of req) parts.push(c)
  return Buffer.concat(parts)
}
/** The fields of a multipart body, in order: name, filename, content type, size (enough for a stub). */
function multipart(buf, type) {
  const b = /boundary=(?:"([^"]+)"|([^;]+))/.exec(type ?? '')
  if (!b) return []
  const sep = Buffer.from(`--${b[1] ?? b[2]}`)
  const out = []
  let at = buf.indexOf(sep)
  while (at >= 0) {
    const next = buf.indexOf(sep, at + sep.length)
    if (next < 0) break
    const part = buf.subarray(at + sep.length + 2, next - 2)
    const head = part.subarray(0, part.indexOf('\r\n\r\n')).toString()
    const data = part.subarray(part.indexOf('\r\n\r\n') + 4)
    out.push({
      name: /name="([^"]*)"/.exec(head)?.[1],
      filename: /filename="([^"]*)"/.exec(head)?.[1] ?? null,
      type: /content-type:\s*([^\r\n]+)/i.exec(head)?.[1] ?? null,
      size: data.length,
      text: /filename=/.test(head) ? null : data.toString(),
    })
    at = next
  }
  return out
}
const T0 = Date.now()
function makeJob(id, done) {
  const job = { id, events: [], waiters: new Set(), status: 'queued', stage: 'ingest', title: null, filename: status0.filename, created: Date.now(), approved: false, cursor: 0, timer: null }
  jobs.set(id, job)
  if (done) {
    for (const e of recorded) push(job, e, recorded[0].ts - 3600_000 + (e.ts - recorded[0].ts))
    job.status = 'done'
    job.stage = 'done'
    job.title = plan.title
    job.created = T0 - 3600_000
  }
  return job
}
function rewrite(e, job) {
  const out = { ...e, jobId: job.id }
  for (const k of ['image_url', 'glb_url', 'manifest_url', 'plan_url']) if (out[k]) out[k] = out[k].replace(recId, job.id)
  return out
}
function push(job, e, ts = Date.now()) {
  const ev = { ...rewrite(e, job), seq: job.events.length + 1, ts }
  job.events.push(ev)
  if (ev.stage) job.stage = ev.stage
  for (const w of job.waiters) w(ev)
}
function script() {
  const list = []
  for (const e of recorded) {
    if (e.type === 'progress' && e.text === 'Plan approved') continue // said when the stub is approved
    list.push(e)
    if (FIX && e.type === 'tool_call' && /\(run 1\)/.test(e.text)) {
      // the real success comes after the fix
      list.push({ __hold: true })
    }
  }
  if (FIX) {
    const i = list.findIndex((e) => e.__hold)
    const success = list[i + 1]
    list.splice(i, 2,
      { ...success, type: 'tool_result', level: 'warn', tool: 'build', text: 'Not yet: FIT PROBLEMS (2); end_slat#2.post_a is 3.2 mm from long_side_panel#1.slot_a; screw 108560#7 runs into end_slat#2' },
      { ...success, type: 'thought', level: 'info', text: 'The end slats sit one slot too low. Moving their mate frames up by the slat width and rebuilding.' },
      { ...success, type: 'tool_call', level: 'info', tool: 'edit', text: 'Fixing job/parts/end_slat.py' },
      { ...success, type: 'tool_call', level: 'info', tool: 'build', text: 'Building the parts and checking the fit (run 2)' },
      success)
  }
  return list
}
function run(job) {
  const list = script()
  job.status = 'running'
  const step = () => {
    if (job.cursor >= list.length) return
    const e = list[job.cursor]
    if (e.type === 'approval_required' && !job.approved) {
      push(job, e)
      job.status = 'waiting_approval'
      job.cursor++
      return // resumes on approve
    }
    if (e.type === 'thought' && /^Plan:/.test(e.text)) job.title = plan.title
    push(job, e)
    if (e.type === 'done') job.status = 'done'
    job.cursor++
    const next = list[job.cursor]
    if (!next) return
    const real = Math.max(0, (next.ts ?? e.ts) - e.ts)
    job.timer = setTimeout(step, Math.min(MAXGAP, Math.max(90, real * SPEED)))
  }
  job.timer = setTimeout(step, 300)
}

makeJob(recId, true)

const TYPES = { '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.glb': 'model/gltf-binary', '.webp': 'image/webp' }
const send = (res, code, body, type = 'application/json') => {
  res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-cache' })
  res.end(typeof body === 'string' ? body : JSON.stringify(body))
}
const summary = (j) => ({ job_id: j.id, stage: j.stage, status: j.status, progress: 0, last_seq: j.events.length, title: j.title, filename: j.filename, created: j.created })

createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Headers', 'X-Demo-Key, Content-Type, Last-Event-ID')
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
  if (req.method === 'OPTIONS') return send(res, 204, '')
  const url = new URL(req.url, 'http://x')
  const p = url.pathname
  if (p === '/healthz') return send(res, 200, { ok: true })
  if ((req.headers['x-demo-key'] ?? url.searchParams.get('key')) !== KEY) return send(res, 401, { detail: 'key' })
  let m
  if (p === '/api/jobs' && req.method === 'GET') return send(res, 200, { jobs: [...jobs.values()].reverse().map(summary) })
  if (p === '/api/jobs' && req.method === 'POST') {
    const body = await readBody(req)
    const fields = multipart(body, req.headers['content-type'])
    const files = fields.filter((f) => f.name === 'file' || f.name === 'pages')
    const pages = fields.filter((f) => f.name === 'pages')
    const size = fields.find((f) => f.name === 'size')?.text ?? null
    if (!files.length) return send(res, 422, { detail: 'Send the guide as a PDF in `file` or photos of its pages in `pages`' })
    if (files.some((f) => /hei[cf]/i.test(f.type ?? '') || /\.hei[cf]$/i.test(f.filename ?? ''))) return send(res, 415, { detail: 'HEIC, which this server cannot read' })
    if (files.length > 60) return send(res, 422, { detail: 'Send at most 60 photos' })
    const id = `stub-${Date.now().toString(36)}`
    const job = makeJob(id, false)
    job.filename = pages.length ? (pages.length === 1 ? pages[0].filename : `${pages.length} photos`) : 'lack-side-table__AA-2606170-1.pdf'
    lastUpload = { job_id: id, source: pages.length ? 'photos' : 'pdf', files: files.map((f) => ({ field: f.name, filename: f.filename, type: f.type, size: f.size })), size }
    run(job)
    console.log('created', id, body.length, 'bytes', lastUpload.source, files.length, 'files', size ? `size ${size}` : '')
    return send(res, 200, { job_id: id, stage: 'ingesting' })
  }
  if (p === '/stub/last') return send(res, 200, lastUpload ?? {})
  if (p === '/api/catalog' && req.method === 'GET') return send(res, 200, { items: search((url.searchParams.get('q') ?? '').slice(0, 500)) })
  if (p === '/api/catalog/open' && req.method === 'POST') {
    let body = {}
    try {
      body = JSON.parse((await readBody(req)).toString() || '{}')
    } catch {}
    const q = String(body.q ?? '').trim()
    if (!q) return send(res, 422, { detail: 'Type a product name, an article number or a product page URL' })
    const found = search(q)
    const ready = found.find((i) => i.ready)
    if (ready) return send(res, 200, { job_id: ready.job_id, ready: true, product: ready.product })
    const known = found.find((i) => i.source === 'known')
    const a = article(q)
    if (!known && !a) return send(res, 404, { detail: `No guide matches “${q.slice(0, 80)}”. Try the IKEA article number (e.g. 902.891.72) or paste the product page URL.` })
    if (!known && a === '99999999') return send(res, 502, { detail: 'Could not reach IKEA (ConnectError)' })
    await new Promise((r) => setTimeout(r, known ? 200 : 900)) // the PDF from the known guides, or from IKEA
    const id = `stub-${Date.now().toString(36)}`
    const job = makeJob(id, false)
    job.filename = known ? known.guide.split('/').pop() : `item-${a}.pdf`
    run(job)
    console.log('catalog build', id, q, body.size ? `size ${body.size}` : '')
    return send(res, 200, { job_id: id, ready: false, product: known?.product ?? `Item ${a}` })
  }
  if ((m = p.match(/^\/api\/jobs\/([^/]+)(\/.*)?$/))) {
    const job = jobs.get(decodeURIComponent(m[1]))
    if (!job) return send(res, 404, { detail: 'No such job' })
    const rest = m[2] ?? ''
    if (!rest) return send(res, 200, summary(job))
    if (rest === '/plan') return send(res, 200, { job_id: job.id, approved: job.approved, plan, crops: [], pages: [] })
    if (rest === '/plan/approve' && req.method === 'POST') {
      if (job.status !== 'waiting_approval') return send(res, 409, { detail: 'not waiting' })
      job.approved = true
      job.status = 'running'
      push(job, { stage: 'await_approval', type: 'progress', level: 'success', text: 'Plan approved' })
      setTimeout(() => run2(job), 400)
      return send(res, 200, { stage: 'building' })
    }
    if (rest === '/events') {
      const since = Number(url.searchParams.get('since') ?? req.headers['last-event-id'] ?? 0)
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
      res.write('retry: 2000\n\n')
      if (BUFFER) return req.on('close', () => {}) // a buffering proxy: nothing more ever arrives
      const write = (e) => res.write(`id: ${e.seq}\ndata: ${JSON.stringify(e)}\n\n`)
      for (const e of job.events) if (e.seq > since) write(e)
      job.waiters.add(write)
      const ping = setInterval(() => res.write(': ping\n\n'), 15000)
      req.on('close', () => {
        job.waiters.delete(write)
        clearInterval(ping)
      })
      return
    }
    if (rest === '/events/poll') {
      const since = Number(url.searchParams.get('since') ?? 0)
      const wait = Math.min(25, Number(url.searchParams.get('wait') ?? 20))
      const now = job.events.filter((e) => e.seq > since)
      if (now.length || wait <= 0) return send(res, 200, { events: now, last_seq: job.events.length })
      const w = () => {
        job.waiters.delete(w)
        clearTimeout(t)
        send(res, 200, { events: job.events.filter((e) => e.seq > since), last_seq: job.events.length })
      }
      const t = setTimeout(() => {
        job.waiters.delete(w)
        send(res, 200, { events: [], last_seq: job.events.length })
      }, wait * 1000)
      job.waiters.add(w)
      return
    }
  }
  if ((m = p.match(/^\/files\/([^/]+)\/(.+)$/))) {
    const file = resolve(RECORD, decodeURIComponent(m[2]))
    if (!file.startsWith(RECORD + sep)) return send(res, 403, 'no', 'text/plain')
    try {
      const st = statSync(file)
      res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream', 'Content-Length': st.size, 'Cache-Control': 'no-cache' })
      return createReadStream(file).pipe(res)
    } catch {
      return send(res, 404, 'none', 'text/plain')
    }
  }
  send(res, 404, { detail: 'Not found' })
}).listen(PORT, '127.0.0.1', () => console.log(`stub on ${PORT} replaying ${recId} speed ${SPEED} buffer ${BUFFER} fix ${FIX}`))

function run2(job) {
  // continue the script after approval_required
  const list = script()
  const step = () => {
    if (job.cursor >= list.length) return
    const e = list[job.cursor]
    push(job, e)
    if (e.type === 'done') job.status = 'done'
    job.cursor++
    const next = list[job.cursor]
    if (!next) return
    job.timer = setTimeout(step, Math.min(MAXGAP, Math.max(90, (next.ts - e.ts) * SPEED)))
  }
  step()
}
