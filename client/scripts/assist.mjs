// Check live guidance on the guide page against a server that answers from its simulated script.
//
//   node scripts/assist.mjs [outDir] [pageUrl] [apiBase] [job] [--novision=<url>]
//
//   outDir    where the screenshots and report.json go   (default: client/.shots/assist, ignored by git)
//   pageUrl   the guide page                              (default: http://127.0.0.1:5173/)
//   apiBase   the server with the perception socket       (default, or "": the page's own origin)
//   job       a job built by the server                   (default: 1115-0022-2d4ab6)
//   --novision=<url>   a second server with no vision at all (no key, no simulation), for the "unavailable" state
//
// Start the server first, from the repo root, with scripted answers and a quiet log (the demo key travels in URLs):
//   PERCEPTION_SIMULATE=true .venv/bin/python -m uvicorn server.app:create_app --factory --host 127.0.0.1 --port 8137 --log-level warning
// The demo key is read from DEMO_KEY in the environment or in <repo>/.env. It is never printed or written.
//
// Two ways to run it:
//   the built page, served by the server itself:  node scripts/assist.mjs out http://127.0.0.1:8137/ "" <job> --novision=http://127.0.0.1:8138
//   the dev page, with the server as API base:     node scripts/assist.mjs out http://127.0.0.1:5173/ http://127.0.0.1:8137 <job> --novision=http://127.0.0.1:8138
// (`?api=` is read by the dev page only. With the built page the second server must serve the page too.)
//
// Chrome runs headless with a fake camera. Speech is set to volume 0 (the browser's mute flag does not reach the
// system voice) and what is handed to the voice is recorded.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2).filter((a) => !a.startsWith('--'))
const flag = (name) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split('=').slice(1).join('=') ?? null
const OUT = resolve(args[0] ?? join(here, '..', '.shots', 'assist'))
const PAGE = (args[1] ?? 'http://127.0.0.1:5173/').replace(/\/?$/, '/')
const API = args[2] ?? ''
const JOB = args[3] ?? '1115-0022-2d4ab6'
const NO_VISION = flag('novision')
mkdirSync(OUT, { recursive: true })

const envFile = join(here, '..', '..', '.env')
const KEY = (process.env.DEMO_KEY ?? (existsSync(envFile) ? (readFileSync(envFile, 'utf8').match(/^DEMO_KEY=(.*)$/m)?.[1] ?? '') : '')).trim().replace(/^["']|["']$/g, '')
if (!KEY) {
  console.error('DEMO_KEY is not set (environment or <repo>/.env).')
  process.exit(2)
}
/** Anything printed goes through this: the key never reaches the terminal or the report. */
const hide = (text) => String(text).split(KEY).join('<key>')

const CHROME =
  process.env.CHROME ??
  ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'].find(
    (p) => existsSync(p),
  )
if (!CHROME) {
  console.error('No Chrome found. Set CHROME to the browser binary.')
  process.exit(2)
}

const VIEWS = {
  laptop: { width: 1512, height: 864, deviceScaleFactor: 2 },
  phone: { width: 390, height: 844, deviceScaleFactor: 3, isMobile: true, hasTouch: true },
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const problems = []
const report = { page: PAGE, api: API || '(the page origin)', job: JOB, views: {} }
const fail = (where, text) => {
  problems.push(`[${where}] ${hide(text)}`)
  console.log(`  FAIL [${where}] ${hide(text)}`)
}
const ok = (where, test, text) => {
  if (!test) fail(where, text)
  return !!test
}

const launch = (fakeCamera = true) =>
  puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    args: [
      '--use-angle=metal',
      '--ignore-gpu-blocklist',
      '--enable-webgl',
      '--hide-scrollbars',
      '--mute-audio',
      ...(fakeCamera ? ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] : []),
    ],
  })

function address(query = {}, base = PAGE) {
  const q = new URLSearchParams({ job: JOB, key: KEY })
  if (API) q.set('api', API)
  for (const [k, v] of Object.entries(query)) v === null ? q.delete(k) : q.set(k, String(v))
  return `${base}?${q}`
}

async function open(browser, view, where, query = {}, { reduced = false, allow = [], wait = true, base = PAGE, dead = false } = {}) {
  const page = await browser.newPage()
  await page.setViewport(VIEWS[view])
  if (reduced) await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }])
  await page.evaluateOnNewDocument((dead) => {
    const Real = window.WebSocket
    const probe = (window.__probe = { sockets: 0, open: 0, frames: 0, sent: [], cameras: 0, spoken: [] })
    // The perception socket is real and counted. The dev server's hot-reload socket is cut off, so a file saved by
    // someone else does not reset the run. `dead` sends the perception socket to an address where nothing listens.
    function Socket(url, protocols) {
      if (!String(url).includes('/ws/perception/')) return { addEventListener() {}, removeEventListener() {}, send() {}, close() {}, readyState: 3 }
      probe.sockets++
      if (dead) url = 'ws://127.0.0.1:9/ws/perception/nobody'
      const ws = protocols === undefined ? new Real(url) : new Real(url, protocols)
      ws.addEventListener('open', () => probe.open++)
      ws.addEventListener('close', () => probe.open--)
      const send = ws.send.bind(ws)
      ws.send = (data) => {
        if (typeof data !== 'string') probe.frames++
        else {
          try {
            const m = JSON.parse(data)
            probe.sent.push(m.type === 'start' ? `start ${m.step}/${m.action ?? '-'}` : m.type === 'step' ? `step ${m.n}/${m.action ?? '-'}` : m.type === 'action' ? `action ${m.step ?? '?'}/${m.index}` : m.type)
          } catch {
            // not ours to judge
          }
        }
        return send(data)
      }
      return ws
    }
    Object.assign(Socket, { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 })
    window.WebSocket = Socket
    if (window.speechSynthesis) {
      const speak = speechSynthesis.speak.bind(speechSynthesis)
      speechSynthesis.speak = (u) => {
        u.volume = 0
        if (u.text.trim()) probe.spoken.push(u.text)
        return speak(u)
      }
    }
    const md = navigator.mediaDevices
    if (md?.getUserMedia) {
      const gum = md.getUserMedia.bind(md)
      md.getUserMedia = (c) => {
        probe.cameras++
        return gum(c).then((stream) => ((probe.stream = stream), stream))
      }
    }
  }, dead)
  const expected = (t) => allow.some((p) => p.test(t))
  page.on('console', (m) => {
    if (['error', 'warn', 'warning'].includes(m.type()) && !expected(m.text())) fail(where, `console ${m.type()}: ${m.text()}`)
  })
  page.on('pageerror', (e) => fail(where, `page error: ${e.message}`))
  page.on('requestfailed', (r) => {
    if (!expected(r.url())) fail(where, `request failed: ${r.url()}`)
  })
  await page.goto(address(query, base), { waitUntil: 'load' })
  if (wait) {
    await page.waitForFunction(() => document.documentElement.dataset.ready === 'true', { timeout: 30000 })
    await page.evaluate(() => document.fonts.ready)
  }
  return page
}

const shot = async (page, name) => {
  const file = join(OUT, `${name}.png`)
  await page.screenshot({ path: file })
  console.log(`  saved ${file}`)
}
const until = (page, fn, timeout = 15000, arg) => page.waitForFunction(fn, { timeout, polling: 50 }, arg)
const idle = (page) => until(page, () => window.__guide.phase === 'idle')
const offered = (page) => page.waitForFunction(() => window.__watch, { timeout: 10000 })
const opened = async (page) => {
  await offered(page)
  await until(page, () => window.__guide.cur === 0 && window.__guide.phase === 'idle')
}
/** The page and the server agree on the step and the action. */
const synced = (page) =>
  until(page, () => {
    const s = window.__watch.status
    return !!s.server && s.server.step === s.step && s.server.action === s.action
  })
const sim = (page, script) => page.evaluate((x) => window.__watch.assist.debug.send({ type: 'sim', ...x }), script)
const eventSeen = (page, name, timeout = 15000) => until(page, (n) => window.__watch.assist.events.some((e) => e.event === n), timeout, name)

/** What the page shows, what the server believes and what left the browser. */
const look = (page) =>
  page.evaluate(() => {
    const w = window.__watch
    const g = window.__guide
    const s = w?.status ?? null
    const button = document.getElementById('watch')
    const eye = document.querySelector('#watch .assist-eye')
    const probe = window.__probe
    return {
      offered: !!button && !button.hidden,
      pressed: button?.getAttribute('aria-pressed') ?? null,
      label: button?.getAttribute('aria-label') ?? null,
      word: document.getElementById('watch-word')?.textContent ?? '',
      hint: document.querySelector('#stage.hinted #hint')?.textContent ?? '',
      mark: document.querySelector('#stage.hinted #hint')?.dataset.mark ?? null,
      heard: document.querySelector('#heard .assist-words')?.textContent ?? '',
      now: [...document.querySelectorAll('#now > span[aria-hidden]')].map((e) => e.textContent).join(' '),
      rowMarks: [...document.querySelectorAll('#stock li[data-not]')].map((li) => li.getAttribute('aria-label')),
      cue: document.getElementById('next').classList.contains('cue'),
      cueAnimation: getComputedStyle(document.getElementById('next'), '::after').animationName,
      ring: eye?.dataset.tone ?? null,
      hatched: eye?.dataset.sim ?? null,
      state: document.getElementById('watch-state')?.textContent ?? '',
      beat: g.cur,
      lamp: +g.stage.crispLevel.toFixed(2),
      status: s && {
        running: s.running,
        link: s.link,
        camera: s.camera,
        vision: s.vision,
        why: s.why,
        simulated: s.simulated,
        place: `${s.step}/${s.action}`,
        server: `${s.server?.step ?? '-'}/${s.server?.action ?? '-'}`,
        state: s.state,
        holding: s.holding.id,
        verdict: s.verdict,
        sent: s.frames.sent,
        acked: s.frames.acked,
        hz: s.frames.hz,
      },
      events: (w?.assist?.events ?? []).map((e) => e.event),
      sockets: probe.sockets,
      socketsOpen: probe.open,
      cameras: probe.cameras,
      tracks: probe.stream ? probe.stream.getTracks().map((t) => t.readyState) : [],
      frames: probe.frames,
      sent: probe.sent.filter((m) => m !== 'sim'),
      spoken: probe.spoken,
    }
  })

// ------------------------------------------------------------------ 1. the whole exchange, with scripted answers

async function exchange(browser, view) {
  const where = `${view} guidance`
  console.log(`\n${view}: start, follow, wrong screw, correction, step complete, stop`)
  const out = (report.views[view] = {})

  // ---- the control is there and nobody touches it: the page is the page
  let page = await open(browser, view, `${view} untouched`)
  await opened(page)
  await shot(page, `${view}-1-control-off`)
  for (let i = 0; i < 3; i++) {
    await page.click('#next')
    await idle(page)
  }
  let s = await look(page)
  out.untouched = { offered: s.offered, beat: s.beat, sockets: s.sockets, cameras: s.cameras, spoken: s.spoken.length, moduleMade: await page.evaluate(() => window.__watch.assist !== null) }
  console.log(`  never touched: 3 actions on, ${s.sockets} sockets, ${s.cameras} cameras opened, ${s.spoken.length} sentences spoken`)
  ok(where, s.offered && s.pressed === 'false', 'the control is not offered, or is on before a tap (with the dev page, give the API base)')
  ok(where, s.beat === 3 && s.sockets === 0 && s.cameras === 0 && s.spoken.length === 0 && !out.untouched.moduleMade, 'the page did something for live guidance before the control was touched')
  await page.close()

  // ---- one tap starts it
  page = await open(browser, view, where)
  await opened(page)
  const plan = await page.evaluate(() => window.__guide.beats.map((b) => ({ step: b.step, type: b.a ? b.a.type : null, code: b.a?.raw.code ?? null })))
  const codes = await page.evaluate(() => window.__guide.kinds.filter((k) => k.hardware).map((k) => k.key))
  const screw = plan.findIndex((b) => b.type === 'fasten')
  const needed = plan[screw]?.code
  const other = codes.find((c) => c !== needed)
  if (screw < 0 || !needed || !other) {
    fail(where, 'this guide has no screw action with a second hardware code to mistake it for')
    return page.close()
  }

  const t0 = Date.now()
  await page.click('#watch')
  await until(page, () => ['live', 'simulated', 'unavailable'].includes(window.__watch.status?.vision), 20000)
  s = await look(page)
  if (!s.status.simulated) {
    fail(where, `the server is not in simulated mode (vision: ${s.status.vision}); this check sends scripted answers and stops here`)
    return page.close()
  }
  await until(page, () => window.__watch.status.frames.acked >= 3)
  const startedMs = Date.now() - t0
  await sim(page, { reset: true }) // the server keeps one script per job
  const f0 = await look(page)
  await sleep(3000)
  s = await look(page)
  out.start = { msToThreeAcks: startedMs, framesIn3s: s.status.sent - f0.status.sent, ackedIn3s: s.status.acked - f0.status.acked, firstMessage: s.sent[0], word: s.word, hatched: s.hatched, state: s.state, spokenOnce: s.spoken.filter((t) => t === s.spoken[0]).length }
  console.log(`  tap: first three frames acked after ${startedMs} ms; then ${out.start.framesIn3s} frames sent and ${out.start.ackedIn3s} acked in 3 s`)
  console.log(`  simulated mode on screen: word "${s.word}", picture hatched ${s.hatched}, for a screen reader "${s.state}", spoken once: "${s.spoken[0] ?? ''}"`)
  ok(where, s.pressed === 'true' && s.cameras === 1 && s.sockets === 1 && s.tracks.includes('live'), 'the tap did not open one camera and one socket')
  ok(where, out.start.framesIn3s >= 3 && out.start.ackedIn3s >= 3, 'frames are not flowing')
  ok(where, s.word !== '' && s.hatched === 'true' && s.spoken.length === 1, 'simulated answers are not marked on screen and said once')
  await shot(page, `${view}-2-watching`)

  // ---- the step and the action follow Next and Previous
  const trail = []
  const go = async (button) => {
    await page.click(button)
    await synced(page)
    await idle(page)
    const now = await look(page)
    trail.push(`${button === '#next' ? 'Next' : 'Previous'}: page ${now.status.place}, server ${now.status.server}`)
    ok(where, now.status.place === now.status.server, `after ${button} the server is on ${now.status.server}, the page on ${now.status.place}`)
  }
  while ((await look(page)).beat < screw) await go('#next')
  await go('#prev')
  await go('#next')
  out.follow = { trail, sent: (await look(page)).sent }
  console.log(`  ${trail.join('; ')}`)
  console.log(`  messages sent: ${out.follow.sent.join(', ')}`)

  // ---- a wrong screw: the cue and the words, once
  const before = await look(page)
  const t1 = Date.now()
  await sim(page, { holding: other })
  await eventSeen(page, 'wrong_hardware')
  const wrongMs = Date.now() - t1
  await sleep(700)
  s = await look(page)
  await shot(page, `${view}-3-correction`)
  await sleep(7000)
  const later = await look(page)
  const saidWrong = later.spoken.slice(before.spoken.length)
  out.wrong = { ms: wrongMs, line: s.now, hint: s.hint, mark: s.mark, rowMarks: s.rowMarks, ring: s.ring, lamp: s.lamp, heard: s.heard, spoken: saidWrong, eventsAfter7s: later.events.filter((e) => e === 'wrong_hardware').length }
  console.log(`  wrong screw (${other} for ${needed}): event after ${wrongMs} ms`)
  console.log(`    on screen: "${s.now}" and under it "${s.hint}" with the ${s.mark} mark; in the row: ${s.rowMarks.join(', ') || 'nothing'}; ring ${s.ring}; lamp x${s.lamp}`)
  console.log(`    spoken: ${saidWrong.map((t) => `"${t}"`).join(' ') || 'nothing'}; for a screen reader: "${s.heard}"`)
  console.log(`    seven seconds on: ${out.wrong.eventsAfter7s} event, ${saidWrong.length} sentence, hint still "${later.hint}"`)
  ok(where, s.hint.includes(other) && s.mark === 'wrong' && s.ring === 'wrong', 'the wrong screw is not named on screen with the mark for wrong')
  ok(where, s.rowMarks.length === 1 && s.rowMarks[0].includes(other), 'the kind in the hand is not marked in the row')
  ok(where, s.lamp > 1.3, 'the lamp over the piece to pick up did not come up')
  ok(where, saidWrong.length === 1 && out.wrong.eventsAfter7s === 1, 'the correction was not given exactly once')
  ok(where, !/\d{5,}/.test(saidWrong.join(' ')), 'a hardware code was read aloud')
  ok(where, s.heard.includes(other), 'the full sentence is not there for a screen reader')
  ok(where, later.beat === before.beat, 'the page moved by itself')

  // ---- the right one: a confirmation
  const t2 = Date.now()
  await sim(page, { holding: needed })
  await eventSeen(page, 'right_hardware')
  const rightMs = Date.now() - t2
  await sleep(500)
  s = await look(page)
  await shot(page, `${view}-4-confirmation`)
  await sleep(6000)
  const calm = await look(page)
  const saidRight = calm.spoken.slice(later.spoken.length)
  out.right = { ms: rightMs, hint: s.hint, mark: s.mark, rowMarks: s.rowMarks, ring: s.ring, spoken: saidRight, hintAfter6s: calm.hint, lampAfter6s: calm.lamp }
  console.log(`  right screw: event after ${rightMs} ms; on screen "${s.hint}" with the ${s.mark} mark, row marks ${s.rowMarks.length}, ring ${s.ring}; spoken ${saidRight.map((t) => `"${t}"`).join(' ')}`)
  console.log(`    six seconds on: hint "${calm.hint}", lamp x${calm.lamp}`)
  ok(where, s.mark === 'right' && s.ring === 'right' && s.rowMarks.length === 0, 'the confirmation is not shown, or the row is still marked')
  ok(where, saidRight.length === 1, 'the confirmation was not spoken exactly once')
  ok(where, calm.hint === '' && calm.lamp < 1.05, 'the confirmation did not go away again')

  // ---- the other language, while it watches
  await page.click('#lang')
  await sim(page, { holding: null })
  await sleep(2600) // the hands leave the picture, so the server forgets what was held
  await sim(page, { holding: other })
  const again = await until(page, () => window.__watch.assist.events.filter((e) => e.event === 'wrong_hardware').length >= 2, 30000).then(() => true, () => false)
  await sleep(500)
  s = await look(page)
  out.otherLanguage = { given: again, word: s.word, hint: s.hint, label: s.label, spoken: s.spoken.at(-1) }
  console.log(`  other language: word "${s.word}", hint "${s.hint}", control "${s.label}"${again ? `, spoken "${s.spoken.at(-1)}"` : ' (the server did not repeat the warning inside 30 s)'}`)
  if (again) ok(where, s.hint.includes(other) && s.hint !== out.wrong.hint, 'the hint did not change language')
  ok(where, s.word !== out.start.word, 'the state word did not change language')
  await page.click('#lang')
  await sim(page, { reset: true })

  // ---- the step looks finished: a cue on Next, and the page stays where it is
  const lastOfStep = (() => {
    let i = screw
    while (plan[i + 1] && plan[i + 1].step === plan[screw].step) i++
    return i
  })()
  while ((await look(page)).beat < lastOfStep) await go('#next')
  const t3 = Date.now()
  await sim(page, { holding: null, step_complete: true, hands: false })
  await eventSeen(page, 'step_complete', 30000)
  const doneMs = Date.now() - t3
  await sleep(600)
  const cued = await look(page)
  await shot(page, `${view}-5-step-complete`)
  await sleep(3000)
  const stayed = await look(page)
  await page.click('#next')
  await synced(page)
  const moved = await look(page)
  out.stepComplete = { ms: doneMs, cue: cued.cue, hint: cued.hint, beatBefore: cued.beat, beatAfter3s: stayed.beat, cueAfterNext: moved.cue, spoken: cued.spoken.at(-1) }
  console.log(`  step complete: event after ${doneMs} ms; Next cued ${cued.cue}, no words under the line ("${cued.hint}"); 3 s later the page is still on action ${stayed.beat}; after Next the cue is ${moved.cue ? 'still there' : 'gone'}`)
  ok(where, cued.cue && cued.hint === '', 'Next is not cued, or words were added')
  ok(where, stayed.beat === cued.beat, 'the page advanced by itself')
  ok(where, moved.beat === cued.beat + 1 && !moved.cue, 'Next did not move on and clear the cue')

  // ---- stop
  await idle(page)
  await sim(page, { reset: true })
  await page.click('#watch')
  await sleep(500)
  s = await look(page)
  out.stop = { pressed: s.pressed, socketsOpen: s.socketsOpen, tracks: s.tracks, lastMessage: s.sent.at(-1), word: s.word, hint: s.hint }
  console.log(`  stop: control ${s.pressed === 'false' ? 'off' : 'on'}, ${s.socketsOpen} sockets open, camera track ${s.tracks.join(',')}, last message "${s.sent.at(-1)}"`)
  ok(where, s.pressed === 'false' && s.socketsOpen === 0 && s.tracks.every((t) => t === 'ended') && s.word === '' && s.hint === '' && !s.cue, 'stop left something running or showing')
  await page.click('#next')
  await idle(page)
  ok(where, (await look(page)).beat === moved.beat + 1, 'the page does not walk on after stop')
  await page.close()
}

// ------------------------------------------------------------------ 2. the states in which nothing is watched

async function states(browser, view) {
  const where = `${view} states`
  console.log(`\n${view}: nothing is watched`)
  const out = (report.views[view].states = {})

  if (NO_VISION) {
    // the dev page takes the other server as its API base; the built page is loaded from the other server itself
    const page = API ? await open(browser, view, where, { api: NO_VISION }) : await open(browser, view, where, {}, { base: NO_VISION.replace(/\/?$/, '/') })
    await opened(page)
    await page.click('#watch')
    await until(page, () => window.__watch.status?.vision === 'unavailable', 20000)
    await sleep(600)
    const s = await look(page)
    await shot(page, `${view}-6-unavailable`)
    await page.click('#next')
    await page.click('#next')
    await idle(page)
    await sleep(1200)
    const on = await look(page)
    out.unavailable = { word: s.word, ring: s.ring, state: s.state, heard: s.heard, beatAfterTwoNext: on.beat, framesSent: on.frames }
    console.log(`  server without vision: word "${s.word}", ring ${s.ring}, for a screen reader "${s.heard}"; the guide walks on (action ${on.beat}); ${on.frames} frame sent in all`)
    ok(where, s.word !== '' && s.ring === 'unavailable' && on.beat === 2, 'unavailable is not said, or the guide stopped working')
    await page.close()
  }

  if (view === 'laptop') {
    // nothing answers at the socket's address
    let page = await open(browser, view, where, {}, { dead: true, allow: [/WebSocket/, /127\.0\.0\.1:9/] })
    await opened(page)
    await page.click('#watch')
    await until(page, () => window.__watch.status?.why === 'reconnecting' && window.__watch.status.say?.from === 'notice', 30000)
    let s = await look(page)
    out.noConnection = { word: s.word, ring: s.ring, heard: s.heard }
    console.log(`  no connection: word "${s.word}", ring ${s.ring}, for a screen reader "${s.heard}"`)
    ok(where, s.word !== '' && s.ring === 'paused', 'a dead connection is not said')
    await page.click('#watch')
    await page.close()

    // the server's answers fail for a while, then come back
    page = await open(browser, view, where)
    await opened(page)
    await page.click('#watch')
    await until(page, () => window.__watch.status?.vision === 'simulated' && window.__watch.status.frames.acked > 2, 20000)
    await sim(page, { reset: true })
    await sim(page, { fail: 'timeout' })
    await until(page, () => window.__watch.status?.vision === 'paused', 40000)
    s = await look(page)
    await sim(page, { reset: true })
    await until(page, () => window.__watch.status?.vision === 'simulated', 40000)
    const back = await look(page)
    out.paused = { word: s.word, ring: s.ring, heard: s.heard, wordAfter: back.word }
    console.log(`  answers failing: word "${s.word}", ring ${s.ring}; when they return: "${back.word}"`)
    ok(where, s.word !== '' && s.ring === 'paused' && back.word !== s.word, 'paused is not said, or it does not end')

    // reduced motion: the cue on Next does not move
    await page.click('#watch')
    await page.close()
    page = await open(browser, view, where, {}, { reduced: true })
    await opened(page)
    await page.evaluate(() => document.getElementById('next').classList.add('cue'))
    s = await look(page)
    out.reducedMotion = { cueAnimation: s.cueAnimation }
    console.log(`  reduced motion: the cue's animation is "${s.cueAnimation}"`)
    ok(where, s.cueAnimation === 'none', 'the cue still moves with reduced motion')
    await page.close()

    // the control can be left out by the address
    page = await open(browser, view, where, { assist: 0 })
    await until(page, () => window.__guide.cur === 0)
    await sleep(400)
    out.assistOff = { offered: (await look(page)).offered }
    console.log(`  ?assist=0: control offered ${out.assistOff.offered}`)
    ok(where, !out.assistOff.offered, '?assist=0 did not leave the control out')
    await page.close()
  }
}

/** The browser refuses the camera: its own launch, without the flag that answers the permission question. */
async function refused(view) {
  const where = `${view} camera refused`
  const browser = await launch(false)
  try {
    await browser.defaultBrowserContext().overridePermissions(new URL(PAGE).origin, [])
    const page = await open(browser, view, where)
    await opened(page)
    await page.click('#watch')
    await until(page, () => window.__watch.status?.vision === 'unavailable', 20000)
    await sleep(500)
    const s = await look(page)
    await shot(page, `${view}-7-camera-refused`)
    await page.click('#next')
    await idle(page)
    const on = await look(page)
    report.views[view].states.cameraRefused = { why: s.status.why, word: s.word, ring: s.ring, heard: s.heard, beatAfterNext: on.beat }
    console.log(`  camera refused: word "${s.word}", ring ${s.ring}, for a screen reader "${s.heard}"; the guide walks on (action ${on.beat})`)
    ok(where, s.status.why === 'camera_denied' && s.word !== '' && on.beat === 1, 'a refused camera is not said, or the guide stopped working')
  } finally {
    await browser.close()
  }
}

// ------------------------------------------------------------------ run

const started = Date.now()
let browser = null
try {
  browser = await launch()
  for (const view of ['laptop', 'phone']) {
    await exchange(browser, view)
    if (report.views[view].start) await states(browser, view)
  }
  await browser.close()
  browser = null
  for (const view of ['laptop', 'phone']) if (report.views[view]?.states) await refused(view)
} catch (err) {
  fail('script', err?.stack ?? String(err))
}
await browser?.close()
report.problems = problems
report.seconds = Math.round((Date.now() - started) / 1000)
writeFileSync(join(OUT, 'report.json'), hide(JSON.stringify(report, null, 2)))
console.log(`\n${problems.length ? `${problems.length} PROBLEM(S):\n${problems.join('\n')}` : 'All checks passed, no console errors.'}`)
console.log(`report: ${join(OUT, 'report.json')} (${report.seconds} s)`)
process.exit(problems.length ? 1 : 0)
