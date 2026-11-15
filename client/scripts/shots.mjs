// Check the guide page in a real browser and save screenshots.
//
//   node scripts/shots.mjs [outDir] [baseUrl] [job] [--quick]
//
//   outDir   where the screenshots and report.json go        (default: client/.shots, ignored by git)
//   baseUrl  the page                                         (default: http://127.0.0.1:5173/)
//   job      a job id for ?job=                               (default: none; the dev server opens local-fresh)
//   --quick  laptop size only, and skip the walk back
//
// At 1512x864 (device scale 2) and 390x844 (device scale 3) it:
//   - walks every action forward at full speed and measures that no piece leaves the screen or runs into the row
//     of parts or the buttons, then walks every action back;
//   - proves that a drag during a fall keeps producing frames and moves the camera, that Next during an animation
//     advances, that Previous moves the piece, and that reduced motion skips the fall and the camera move;
//   - checks English, the language switch, the demo key on every /files/ request, the fallback to the plain GLB,
//     the loading and the error line, and a product that is not wood;
//   - fails on any console error or warning, page error or failed request that the step did not ask for.
//
// It drives the installed Chrome through puppeteer-core. Set CHROME to another binary if needed.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2).filter((a) => !a.startsWith('--'))
const QUICK = process.argv.includes('--quick')
const OUT = resolve(args[0] ?? join(here, '..', '.shots'))
const BASE = (args[1] ?? 'http://127.0.0.1:5173/').replace(/\/?$/, '/')
const JOB = args[2] ?? null
mkdirSync(OUT, { recursive: true })

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
const report = { base: BASE, job: JOB, views: {} }
const fail = (where, text) => {
  problems.push(`[${where}] ${text}`)
  console.log(`  FAIL [${where}] ${text}`)
}

const url = (query = {}) => {
  const q = new URLSearchParams()
  if (JOB) q.set('job', JOB)
  for (const [k, v] of Object.entries(query)) q.set(k, String(v))
  const s = q.toString()
  return s ? `${BASE}?${s}` : BASE
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--use-angle=metal', '--ignore-gpu-blocklist', '--enable-webgl', '--hide-scrollbars', '--mute-audio'],
})

/**
 * Open the page. `allow` is a list of patterns for console messages and failed requests this step expects.
 * `before(page)` runs before the page loads (request interception and the like).
 */
async function open(view, where, query = {}, { reduced = false, allow = [], before = null, wait = true } = {}) {
  const page = await browser.newPage()
  await page.setViewport(VIEWS[view])
  if (reduced) await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }])
  // Other people save files under client/ and the dev server then reloads every open page. Cut this page off from
  // the hot-reload socket so a run is not reset halfway. The built page opens no socket, so nothing changes there.
  await page.evaluateOnNewDocument(() => {
    window.WebSocket = class {
      addEventListener() {}
      removeEventListener() {}
      send() {}
      close() {}
    }
  })
  const expected = (text) => allow.some((p) => p.test(text))
  page.on('console', (m) => {
    if (!['error', 'warn', 'warning'].includes(m.type())) return
    if (!expected(m.text())) fail(where, `console ${m.type()}: ${m.text()}`)
  })
  page.on('pageerror', (e) => fail(where, `page error: ${e.message}`))
  page.on('requestfailed', (r) => {
    if (!expected(r.url())) fail(where, `request failed: ${r.url()}`)
  })
  page.on('response', (r) => {
    if (r.status() >= 400 && !expected(r.url())) fail(where, `HTTP ${r.status()}: ${r.url()}`)
  })
  if (before) await before(page)
  await page.goto(url(query), { waitUntil: 'load' })
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

/** Where the guide is, where the camera is, and what the page says. */
const state = (page) =>
  page.evaluate(() => {
    const g = window.__guide
    const c = g.stage.camera.position
    const t = g.stage.controls.target
    return {
      cur: g.cur,
      phase: g.phase,
      frames: g.frames,
      azimuth: Math.atan2(c.x - t.x, c.z - t.z),
      elevation: Math.asin((c.y - t.y) / c.distanceTo(t)),
      tweening: g.tweening,
      ratio: g.stage.ratio,
      left: g.kinds.map((k) => k.left),
      seen: [...document.querySelectorAll('#now > span[aria-hidden]')].map((e) => e.textContent).join(' '),
      spoken: document.querySelector('#now .sr')?.textContent ?? '',
      prevOff: document.getElementById('prev').getAttribute('aria-disabled'),
      nextOff: document.getElementById('next').getAttribute('aria-disabled'),
      nextDone: document.getElementById('next').classList.contains('done'),
      time: performance.now(),
    }
  })
const until = (page, test, timeout = 20000) => page.waitForFunction(test, { timeout, polling: 16 })
const idle = (page) => until(page, () => window.__guide.phase === 'idle')
const jump = (page, i) =>
  page.evaluate((n) => {
    window.__guide.begin(n)
    window.__guide.finishNow()
  }, i)
const turnAngle = (a, b) => Math.abs(Math.atan2(Math.sin(b - a), Math.cos(b - a)))

// ------------------------------------------------------------------ 1. every action forward and back

async function walk(view) {
  const where = `${view} walk`
  console.log(`\n${view}: every action forward and back`)
  const page = await open(view, where)
  const out = (report.views[view] = {})

  await sleep(300)
  await shot(page, `${view}-01-opening`) // the whole row, before the first piece comes down
  const plan = await page.evaluate(() => window.__guide.beats.map((b) => (b.a ? b.a.type : 'finish')))
  const last = plan.length - 1
  const firstOf = (type, from = 0) => plan.findIndex((t, i) => i >= from && t === type)
  const fastens = plan.map((t, i) => (t === 'fasten' ? i : -1)).filter((i) => i >= 0)
  // the moments worth a picture, by beat: [name, phase to wait for, ms into that phase]
  const pictures = new Map()
  const want = (i, name, phase, ms) => i >= 0 && !pictures.has(i) && pictures.set(i, { name, phase, ms })
  want(firstOf('place', 1), '02-board-fall', 'fly', 430)
  want(firstOf('fasten'), '03-screw-driven', 'clip', 620)
  want(firstOf('turn'), '04-turn', 'clip', 560)
  want(fastens[Math.floor(fastens.length * 0.3)] ?? -1, '05-steps-in-screw', 'clip', 620)
  want(firstOf('place', Math.floor(last * 0.5)), '06-late-board-fall', 'fly', 430)
  want(last, '08-closing-turn', 'clip', 700)

  const worst = { left: Infinity, right: Infinity, top: Infinity, bottom: Infinity, at: {} } // smallest margins seen
  const texts = []
  let samples = 0
  const measure = async (i) => {
    const e = await page.evaluate(() => window.__guide.extent())
    if (!Number.isFinite(e.left)) return
    samples++
    const d = { left: e.left, right: e.width - e.right, top: e.top - e.bandTop, bottom: e.bandBottom - e.bottom }
    for (const side of ['left', 'right', 'top', 'bottom']) {
      if (d[side] < worst[side]) {
        worst[side] = d[side]
        worst.at[side] = i
      }
    }
    if (d.left < 0 || d.right < 0) fail(where, `beat ${i}: a piece is cut by the side of the screen (left ${d.left.toFixed(0)} px, right ${d.right.toFixed(0)} px)`)
    if (d.top < -4) fail(where, `beat ${i}: a piece is ${(-d.top).toFixed(0)} px into the row of parts`)
    if (d.bottom < -8) fail(where, `beat ${i}: a piece is ${(-d.bottom).toFixed(0)} px into the piece between the buttons`)
  }

  await until(page, () => window.__guide.cur === 0)
  const t0 = await state(page)
  for (let i = 0; i <= last; i++) {
    if (i > 0) {
      if (i % 2) await page.keyboard.press('ArrowRight')
      else await page.click('#next')
    }
    const picture = pictures.get(i)
    if (picture) {
      await page.waitForFunction((p) => window.__guide.phase === p, { timeout: 8000, polling: 16 }, picture.phase).catch(() => {})
      await sleep(picture.ms)
      await shot(page, `${view}-${picture.name}`)
    }
    for (;;) {
      await measure(i)
      const s = await state(page)
      if (s.cur !== i) {
        fail(where, `expected beat ${i}, the page is at ${s.cur}`)
        break
      }
      if (s.phase === 'idle' && !s.tweening) break
      await sleep(90)
    }
    const s = await state(page)
    texts.push(s.seen)
    if (!s.seen) fail(where, `beat ${i}: nothing to read`)
    if (i === Math.floor(last * 0.6)) await shot(page, `${view}-07-several-steps-in`)
  }
  const t1 = await state(page)
  out.fps = Math.round(((t1.frames - t0.frames) / (t1.time - t0.time)) * 1000)
  out.pixelRatio = t1.ratio

  // the finished piece turns by itself: it must stay whole while it does
  await sleep(1500)
  await shot(page, `${view}-09-finished`)
  for (let n = 0; n < 12; n++) {
    await measure(last)
    await sleep(400)
  }
  const end = await state(page)
  // at the end Next is not dead: it is the tick that leads out of the steps
  if (end.cur !== last || !end.nextDone || end.nextOff !== 'false') fail(where, 'the end was not reached, or Next has not become the way out there')
  if (end.left.some((n) => n !== 0)) fail(where, `pieces are left over at the end: ${end.left.join(',')}`)
  out.beats = plan.length
  out.words = [...new Set(texts)]
  out.endWord = end.seen
  out.samples = samples
  out.tightest = {
    leftPx: Math.round(worst.left),
    rightPx: Math.round(worst.right),
    underTheRowPx: Math.round(worst.top),
    overTheButtonsPx: Math.round(worst.bottom),
    atBeat: worst.at,
  }
  console.log(`  ${plan.length} actions forward, ${out.fps} frames a second, pixel ratio ${out.pixelRatio}`)
  console.log(`  words: ${out.words.join(' | ')}`)
  console.log(
    `  tightest over ${samples} samples: ${out.tightest.leftPx} px from the left edge, ${out.tightest.rightPx} px from the right, ` +
      `${out.tightest.underTheRowPx} px under the row, ${out.tightest.overTheButtonsPx} px over the buttons' band`,
  )

  if (!QUICK) {
    let rises = 0
    let rewinds = 0
    for (let i = last; i > 0; i--) {
      await page.click('#prev')
      const p = (await state(page)).phase
      if (p === 'rise') rises++
      if (p === 'rewind') rewinds++
      await idle(page)
      const s = await state(page)
      if (s.cur !== i - 1) {
        fail(where, `Previous from beat ${i} went to ${s.cur}`)
        break
      }
    }
    const start = await state(page)
    const totals = await page.evaluate(() => window.__guide.kinds.map((k) => k.total))
    if (start.cur !== 0 || start.prevOff !== 'true') fail(where, 'the start was not reached going back, or Previous is not marked as unavailable there')
    const first = await firstKind(page)
    const expectLeft = totals.map((n, k) => n - (k === first ? 1 : 0))
    if (start.left.join() !== expectLeft.join()) fail(where, `going back left ${start.left.join(',')} in the box, expected ${expectLeft.join(',')}`)
    out.back = { risesSeen: rises, rewindsSeen: rewinds }
    console.log(`  ${last} actions back: ${rises} pieces seen rising, ${rewinds} turns seen running backwards`)
  }
  await page.close()
}

/** Index of the kind the very first action uses (it stays out of the box at beat 0). */
async function firstKind(page) {
  return page.evaluate(() => {
    const g = window.__guide
    const node = g.beats[0].a?.node
    return g.kinds.findIndex((k) => node && k.nodes.has(node))
  })
}

// ------------------------------------------------------------------ 2. the orbit never stalls

async function proofs(view) {
  const where = `${view} proofs`
  console.log(`\n${view}: orbit, Next, Previous, reduced motion`)
  const out = (report.views[view].proofs = {})
  const touch = !!VIEWS[view].hasTouch
  const { width, height } = VIEWS[view]
  const cx = Math.round(width / 2)
  const cy = Math.round(height * 0.45)

  // ---- a drag while a piece is on its way down
  let page = await open(view, where)
  await until(page, () => window.__guide.cur === 0 && window.__guide.phase === 'idle')
  const plan = await page.evaluate(() => window.__guide.beats.map((b) => (b.a ? b.a.type : 'finish')))
  const board = plan.findIndex((t, i) => i >= 1 && t === 'place')
  if (board > 1) await jump(page, board - 1)
  await page.click('#next')
  await sleep(60)
  const a = await state(page)
  const seen = [a.phase]
  if (touch) await page.touchscreen.touchStart(cx, cy)
  else {
    await page.mouse.move(cx, cy)
    await page.mouse.down()
  }
  for (let i = 1; i <= 22; i++) {
    if (touch) await page.touchscreen.touchMove(cx + i * 7, cy + i)
    else await page.mouse.move(cx + i * 12, cy + i * 2)
    await sleep(14)
    if (i % 5 === 0) seen.push((await state(page)).phase)
  }
  if (i8(view)) await shot(page, `${view}-10-drag-during-fall`)
  if (touch) await page.touchscreen.touchEnd()
  else await page.mouse.up()
  const b = await state(page)
  out.dragDuringFall = {
    phasesSeen: seen,
    frames: b.frames - a.frames,
    milliseconds: Math.round(b.time - a.time),
    cameraTurnedDegrees: +((turnAngle(a.azimuth, b.azimuth) * 180) / Math.PI).toFixed(1),
  }
  console.log(
    `  drag during a fall: phases ${seen.join(',')}; ${out.dragDuringFall.frames} frames in ${out.dragDuringFall.milliseconds} ms; ` +
      `camera turned ${out.dragDuringFall.cameraTurnedDegrees} degrees`,
  )
  if (a.phase !== 'fly') fail(where, `the piece was not falling when the drag began (${a.phase})`)
  if (!(out.dragDuringFall.frames >= (b.time - a.time) / 50)) fail(where, 'frames stalled during the drag')
  if (!(out.dragDuringFall.cameraTurnedDegrees > 8)) fail(where, 'the camera did not follow the drag')

  // ---- Next in the middle of an animation: the running action ends at once and the next one starts
  await idle(page)
  await page.click('#next')
  await sleep(140)
  const mid = await state(page)
  await page.click('#next')
  await sleep(40)
  const after = await state(page)
  out.nextDuringAnimation = { from: `${mid.cur} (${mid.phase})`, to: `${after.cur} (${after.phase})` }
  console.log(`  Next during an animation: beat ${mid.cur} (${mid.phase}) -> beat ${after.cur} (${after.phase})`)
  if (mid.phase === 'idle') fail(where, 'nothing was moving when Next was pressed the second time')
  if (after.cur !== mid.cur + 1 || after.phase === 'idle') fail(where, 'Next during an animation did not start the next action')

  // ---- Previous: the piece goes back up to the row, it does not vanish
  await idle(page)
  const before = await state(page)
  await page.click('#prev')
  await sleep(50)
  const rising = await state(page)
  await sleep(120)
  if (i8(view)) await shot(page, `${view}-11-previous-rising`)
  await idle(page)
  const landed = await state(page)
  out.previous = { phase: rising.phase, from: before.cur, to: landed.cur }
  console.log(`  Previous: beat ${before.cur} -> ${landed.cur}, the piece was seen in phase "${rising.phase}"`)
  if (rising.phase !== 'rise' && rising.phase !== 'rewind') fail(where, `Previous showed no motion (phase ${rising.phase})`)
  if (landed.cur !== before.cur - 1) fail(where, 'Previous did not go back one action')

  // ---- a drag in the middle of a camera move takes the camera
  const turn = plan.findIndex((t) => t === 'turn')
  if (turn > 0) {
    await jump(page, turn - 1)
    await page.click('#next')
    await sleep(120)
    const moving = (await state(page)).tweening
    if (touch) {
      await page.touchscreen.touchStart(cx, cy)
      await page.touchscreen.touchMove(cx + 6, cy)
      await page.touchscreen.touchEnd()
    } else {
      await page.mouse.move(cx, cy)
      await page.mouse.down()
      await page.mouse.move(cx + 6, cy)
      await page.mouse.up()
    }
    const taken = (await state(page)).tweening
    out.pressCancelsCameraMove = { movingBefore: moving, movingAfter: taken }
    console.log(`  press during a camera move: moving ${moving} -> ${taken}`)
    if (taken) fail(where, 'a press did not cancel the camera move')
  }
  await page.close()

  // ---- reduced motion: no fall, no camera move, no rise; the insertion still plays
  page = await open(view, where, {}, { reduced: true })
  await until(page, () => window.__guide.cur === 0)
  await idle(page)
  if (board > 1) await jump(page, board - 1)
  const r0 = await state(page)
  await page.click('#next')
  await sleep(40)
  const r1 = await state(page)
  await sleep(250)
  const r2 = await state(page)
  await shot(page, `${view}-12-reduced-motion`)
  await idle(page)
  await page.click('#prev')
  await sleep(40)
  const r3 = await state(page)
  out.reducedMotion = { afterNext: r1.phase, cameraMoving: r1.tweening, insertionFrames: r2.frames - r1.frames, afterPrevious: r3.phase }
  console.log(
    `  reduced motion: after Next the phase is "${r1.phase}" (no fall), camera moving ${r1.tweening}, ` +
      `insertion drew ${r2.frames - r1.frames} frames in 250 ms, after Previous "${r3.phase}"`,
  )
  if (r1.cur !== r0.cur + 1) fail(where, 'reduced motion: Next did not advance')
  if (r1.phase !== 'clip') fail(where, `reduced motion: expected the insertion without a fall, got ${r1.phase}`)
  if (r1.tweening) fail(where, 'reduced motion: the camera is moving by itself')
  if (r3.phase !== 'idle') fail(where, `reduced motion: Previous should be immediate, got ${r3.phase}`)
  await page.close()
}
const i8 = (view) => view === 'laptop'

// ------------------------------------------------------------------ 3. language, key, GLB, loading, materials

async function extras(view) {
  const where = `${view} extras`
  console.log(`\n${view}: English, the language switch, the demo key, the GLBs, materials`)
  const out = (report.views[view].extras = {})

  // ---- English, then the switch in the corner
  let page = await open(view, where, { lang: 'en' })
  await until(page, () => window.__guide.cur === 0 && window.__guide.phase === 'idle')
  const plan = await page.evaluate(() => window.__guide.beats.map((b) => (b.a ? b.a.type : 'finish')))
  const fastens = plan.map((t, i) => (t === 'fasten' ? i : -1)).filter((i) => i >= 0)
  const screw = fastens[Math.floor(fastens.length * 0.3)] ?? 1
  await jump(page, screw - 1)
  await page.click('#next')
  await until(page, () => window.__guide.phase === 'clip')
  await sleep(600)
  await shot(page, `${view}-13-english`)
  const en = await state(page)
  const enLabels = await page.evaluate(() => [document.documentElement.lang, document.getElementById('next').getAttribute('aria-label'), document.getElementById('lang').getAttribute('aria-label')])
  await page.click('#lang')
  const el = await state(page)
  const elLabels = await page.evaluate(() => [document.documentElement.lang, document.getElementById('next').getAttribute('aria-label'), new URL(location.href).searchParams.get('lang')])
  out.english = { seen: en.seen, spoken: en.spoken, next: enLabels[1] }
  out.greek = { seen: el.seen, spoken: el.spoken, next: elLabels[1] }
  console.log(`  en: "${en.seen}" / spoken "${en.spoken}" / ${enLabels[1]};  el after the switch: spoken "${el.spoken}" / ${elLabels[1]}`)
  if (enLabels[0] !== 'en' || enLabels[1] !== 'Next') fail(where, 'the page is not in English with ?lang=en')
  if (elLabels[0] !== 'el' || elLabels[2] !== 'el' || el.spoken === en.spoken) fail(where, 'the language switch did not turn the page to Greek')
  await page.close()

  if (view !== 'laptop') return

  // ---- the demo key rides on every /files/ request
  const requests = []
  page = await open(view, where, { key: 'demo-key-123' }, { before: async (p) => p.on('request', (r) => r.url().includes('/files/') && requests.push(r.url())) })
  const keyed = requests.filter((u) => new URL(u).searchParams.get('key') === 'demo-key-123').length
  out.demoKey = { requests: requests.length, withKey: keyed }
  console.log(`  demo key: ${keyed} of ${requests.length} /files/ requests carry it`)
  if (!requests.length || keyed !== requests.length) fail(where, 'a /files/ request went out without the demo key')
  await page.close()

  // ---- the model on its way: the loading line and the hairline
  page = await open(view, where, {}, {
    wait: false,
    before: async (p) => {
      await p.setRequestInterception(true)
      p.on('request', async (r) => {
        if (/\.glb(\?|$)/.test(r.url())) await sleep(1500)
        r.continue().catch(() => {})
      })
    },
  })
  await page.waitForFunction(() => document.getElementById('now').textContent.length > 0, { timeout: 15000 })
  await page.evaluate(() => document.fonts.ready)
  await sleep(300)
  out.loading = await page.evaluate(() => document.getElementById('now').innerText.replace(/\s+/g, ' ').trim())
  await shot(page, `${view}-14-loading`)
  console.log(`  loading line: "${out.loading}"`)
  await page.waitForFunction(() => document.documentElement.dataset.ready === 'true', { timeout: 30000 })
  await page.close()

  // ---- the packed GLB cannot be had: the plain one is used
  page = await open(view, where, {}, {
    allow: [/packed/, /could not use/, /Failed to load resource/],
    before: async (p) => {
      await p.setRequestInterception(true)
      p.on('request', (r) => (/packed\.glb/.test(r.url()) ? r.abort() : r.continue()).catch(() => {}))
    },
  })
  out.fallbackToPlainGlb = await page.evaluate(() => window.__guide.beats.length > 0)
  console.log(`  packed GLB blocked: the page still starts (${out.fallbackToPlainGlb})`)
  await page.close()

  // ---- no GLB at all: one line says so and offers to try again
  page = await open(view, where, {}, {
    wait: false,
    allow: [/\.glb/, /could not use/, /Failed to load resource/, /could not load/, /fetch/i],
    before: async (p) => {
      await p.setRequestInterception(true)
      p.on('request', (r) => (/\.glb/.test(r.url()) ? r.abort() : r.continue()).catch(() => {}))
    },
  })
  await page.waitForFunction(() => document.getElementById('now').getAttribute('role') === 'alert', { timeout: 15000 })
  await page.evaluate(() => document.fonts.ready)
  out.failed = await page.evaluate(() => ({ text: document.getElementById('now').innerText.replace(/\s+/g, ' ').trim(), retry: !!document.querySelector('#now button') }))
  await shot(page, `${view}-15-model-failed`)
  console.log(`  no GLB: "${out.failed.text}" (retry button: ${out.failed.retry})`)
  if (!out.failed.retry) fail(where, 'the error line has no way to try again')
  await page.close()

  // ---- a product that is not wood (debug parameter): the room must carry it
  for (const material of ['white', 'black']) {
    page = await open(view, where, { material })
    await until(page, () => window.__guide.cur === 0 && window.__guide.phase === 'idle')
    await jump(page, screw - 1)
    await page.click('#next')
    await until(page, () => window.__guide.phase === 'clip')
    await sleep(600)
    await shot(page, `${view}-16-${material}-screw`)
    await jump(page, plan.length - 1)
    await sleep(1400)
    await shot(page, `${view}-17-${material}-finished`)
    const finishes = await page.evaluate(() => window.__guide.finishes)
    out[material] = finishes
    console.log(`  ?material=${material}: finishes in use ${finishes.join(', ')}`)
    if (finishes.includes('wood')) fail(where, `?material=${material} still drew wood grain`)
    await page.close()
  }
}

// ------------------------------------------------------------------ run

const started = Date.now()
try {
  for (const view of QUICK ? ['laptop'] : ['laptop', 'phone']) {
    await walk(view)
    await proofs(view)
    await extras(view)
  }
} catch (err) {
  fail('script', err?.stack ?? String(err))
}
await browser.close()
report.problems = problems
report.seconds = Math.round((Date.now() - started) / 1000)
writeFileSync(join(OUT, 'report.json'), JSON.stringify(report, null, 2))
console.log(`\n${problems.length ? `${problems.length} PROBLEM(S):\n${problems.join('\n')}` : 'All checks passed, no console errors.'}`)
console.log(`report: ${join(OUT, 'report.json')} (${report.seconds} s)`)
process.exit(problems.length ? 1 : 0)
