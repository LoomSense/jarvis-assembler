// Check the build screen (?mode=build) in a real browser against the replay stub, and save screenshots.
//
//   node scripts/build-stub.mjs &                       # PORT=8788, the stream works
//   PORT=8789 BUFFER_SSE=1 node scripts/build-stub.mjs & # the stream is buffered, as through a quick tunnel
//   node scripts/build-shots.mjs [outDir] [baseUrl]
//
// At 1512x864 and 390x844: the drop target with the recent jobs; an upload; the feed mid-run with pictures; the
// plan waiting for approval; approve; done; the ready screen it opens (?mode=ready; the guide page until that lands). Then: the long-poll fallback when the
// stream delivers nothing, ?auto=1 end to end, English, reduced motion, a finished job reopened from the list.
// Fails on any console error or warning, page error, failed request or HTTP error the step did not expect.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2).filter((a) => !a.startsWith('--'))
const OUT = resolve(args[0] ?? join(here, '..', '.shots', 'build'))
const BASE = (args[1] ?? 'http://127.0.0.1:5173/').replace(/\/?$/, '/')
const STUB = process.env.STUB ?? 'http://127.0.0.1:8788'
const BUFFERED = process.env.STUB_BUFFERED ?? 'http://127.0.0.1:8789'
const PDF = resolve(here, '../../eval/guides/ikea/lack-side-table__AA-2606170-1.pdf')
// photos of guide pages for the photo upload (JPEG); PHOTOS=<folder with page-1.jpg … page-4.jpg and page.heic>
const PHOTOS = process.env.PHOTOS ? resolve(process.env.PHOTOS) : null
mkdirSync(OUT, { recursive: true })

const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
if (!existsSync(CHROME)) {
  console.error('No Chrome found. Set CHROME to the browser binary.')
  process.exit(2)
}
const VIEWS = {
  laptop: { width: 1512, height: 864, deviceScaleFactor: 2 },
  phone: { width: 390, height: 844, deviceScaleFactor: 3, isMobile: true, hasTouch: true },
}
const problems = []
const report = {}
const fail = (where, text) => {
  problems.push(`[${where}] ${text}`)
  console.log(`  FAIL [${where}] ${text}`)
}
const ok = (where, text) => console.log(`  ok   [${where}] ${text}`)

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--use-angle=metal', '--ignore-gpu-blocklist', '--enable-webgl', '--hide-scrollbars', '--mute-audio'],
})

const url = (api, query = {}) => {
  const q = new URLSearchParams({ mode: 'build', api, key: 'stub', ...query })
  for (const [k, v] of Object.entries(query)) if (v === null) q.delete(k)
  return `${BASE}?${q}`
}

async function open(view, where, address, { reduced = false, allow = [] } = {}) {
  const page = await browser.newPage()
  await page.setViewport(VIEWS[view])
  if (reduced) await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }])
  // cut the page off from the dev server's hot-reload socket, so a save elsewhere does not reload it mid-run
  await page.evaluateOnNewDocument(() => {
    window.WebSocket = class {
      addEventListener() {}
      removeEventListener() {}
      send() {}
      close() {}
    }
  })
  const expected = (t) => allow.some((p) => p.test(t))
  page.on('console', (m) => {
    if (['error', 'warn', 'warning'].includes(m.type()) && !expected(m.text())) fail(where, `console ${m.type()}: ${m.text()}`)
  })
  page.on('pageerror', (e) => fail(where, `page error: ${e.message}`))
  page.on('requestfailed', (r) => {
    // a long poll or a stream cut short by navigation is not a failure
    if (/\/events/.test(r.url()) && /ERR_ABORTED/.test(r.failure()?.errorText ?? '')) return
    if (!expected(r.url())) fail(where, `request failed: ${r.url()} ${r.failure()?.errorText}`)
  })
  page.on('response', (r) => {
    if (r.status() >= 400 && !expected(r.url())) fail(where, `HTTP ${r.status()}: ${r.url()}`)
  })
  await page.goto(address, { waitUntil: 'load' })
  await page.waitForFunction(() => document.documentElement.dataset.ready === 'true', { timeout: 20000 })
  await page.evaluate(() => document.fonts.ready)
  return page
}
const shot = async (page, name) => {
  const file = join(OUT, `${name}.png`)
  await page.screenshot({ path: file })
  console.log(`  saved ${file}`)
}
const until = (page, fn, timeout = 90000, ...a) => page.waitForFunction(fn, { timeout, polling: 100 }, ...a)
const stateIs = (page, s, timeout) => until(page, (x) => document.documentElement.dataset.state === x, timeout, s)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function upload(page) {
  const input = await page.$('.target input')
  await input.uploadFile(PDF)
  await until(page, () => !document.querySelector('.send').hidden, 5000)
  await page.click('.send .go')
  await until(page, () => new URL(location.href).searchParams.get('job'), 15000)
}

/** Type into the search line as a person would, and wait for the catalog's answer to be drawn. */
async function typeFind(page, text) {
  await page.click('.find input', { count: 3 })
  await page.keyboard.press('Backspace')
  await page.type('.find input', text, { delay: 40 })
  await sleep(450)
}

// ------------------------------------------------------------------ the whole story, at both sizes
for (const view of Object.keys(VIEWS)) {
  console.log(`\n${view}: drop, feed, approval, done, guide`)
  const where = `${view} story`
  const page = await open(view, where, url(STUB))
  await until(page, () => document.querySelectorAll('#recent li').length > 0, 8000).catch(() => fail(where, 'no recent jobs listed'))
  await sleep(400)
  await shot(page, `${view}-1-drop`)

  await upload(page)
  ok(where, 'uploaded, the address names the job')
  // mid-run: the agent reading the guide pages, with their pictures in the feed
  await until(page, () => document.querySelectorAll('#feed .look img.in').length >= 6, 60000)
  await until(page, () => /Writing|Γράφει/.test(document.querySelector('#feed .rows')?.lastElementChild?.textContent ?? ''), 60000).catch(() => {})
  await sleep(900)
  await shot(page, `${view}-2-feed`)
  report[`${view}-transport`] = await page.evaluate(() => document.documentElement.dataset.transport)
  if (report[`${view}-transport`] !== 'sse') fail(where, `expected the stream, got ${report[`${view}-transport`]}`)

  await stateIs(page, 'approval')
  await sleep(1200)
  await shot(page, `${view}-3-approval`)
  const planText = await page.evaluate(() => document.querySelector('#feed .plan')?.textContent ?? '')
  for (const want of ['108363', '108560', '×24', '8']) if (!planText.includes(want)) fail(where, `plan lacks ${want}`)
  await page.click('#act')
  await until(page, () => /Εγκρίθηκε|Approved/.test(document.querySelector('#feed')?.textContent ?? ''), 15000)
  ok(where, 'approved')
  await stateIs(page, 'done')
  await sleep(1400)
  await shot(page, `${view}-4-done`)
  const shown = await page.evaluate(() => ({
    clock: document.querySelector('#state .clock')?.textContent,
    steps: document.querySelectorAll('#feed .steps img').length,
    word: document.querySelector('#act .word')?.textContent,
  }))
  report[`${view}-done`] = shown
  if (shown.steps !== 8) fail(where, `expected 8 step pictures, saw ${shown.steps}`)
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.click('#act')])
  const to = new URL(page.url())
  if (to.searchParams.get('mode') !== 'ready' || !to.searchParams.get('job') || to.searchParams.get('key') !== 'stub' || to.searchParams.get('lang') !== 'el')
    fail(where, `the guide link is wrong: ${to.search.replace(/key=[^&]+/, 'key=…')}`)
  // the guide page sets data-ready; the ready screen (another session's) may not, so only wait for it a while
  await page.waitForFunction(() => document.documentElement.dataset.ready === 'true', { timeout: 15000 }).catch(() => console.log('  (the opened page did not set data-ready)'))
  await sleep(1500)
  await shot(page, `${view}-5-guide`)
  ok(where, 'the ready screen (or, until it lands, the guide page) opened for the new job')
  await page.close()
}

// ------------------------------------------------------------------ find a guide; photos of the pages
for (const view of Object.keys(VIEWS)) {
  console.log(`\n${view}: find by name, not found, IKEA down, photos with a size`)
  const where = `${view} intake`
  const page = await open(view, where, url(STUB), { allow: [/\/api\/catalog\/open/, /status of (404|502)/] })
  await typeFind(page, 'k')
  await until(page, () => document.querySelectorAll('.matches li').length >= 3, 5000).catch(() => fail(where, 'no matches for "k"'))
  const list = await page.evaluate(() => [...document.querySelectorAll('.matches li > *')].map((n) => ({ tag: n.tagName, text: n.textContent, href: n.getAttribute('href') })))
  if (list[0]?.tag !== 'A' || !/mode=ready/.test(list[0].href ?? '') || !/job=1115-0022-2d4ab6/.test(list[0].href ?? '')) fail(where, `the ready guide should come first as a link: ${JSON.stringify(list[0])}`)
  if (!list.some((i) => i.tag === 'BUTTON' && /BEKVÄM/.test(i.text))) fail(where, 'a known guide should be offered to build')
  if (!(await page.evaluate(() => getComputedStyle(document.querySelector('.put')).visibility === 'hidden'))) fail(where, 'the drop target should step back while matches show')
  await shot(page, `${view}-8-find`)
  await typeFind(page, 'bekvam')
  const folded = await page.evaluate(() => document.querySelector('.matches')?.textContent ?? '')
  if (!/BEKVÄM/.test(folded)) fail(where, 'accent-insensitive search failed')
  else ok(where, 'bekvam finds BEKVÄM')

  await typeFind(page, 'zzz')
  await page.keyboard.press('Enter')
  await until(page, () => document.querySelector('.find .said.warn')?.textContent, 5000).catch(() => fail(where, 'no line for nothing found'))
  await sleep(300)
  await shot(page, `${view}-9-not-found`)
  ok(where, `404: ${await page.evaluate(() => document.querySelector('.find .said').textContent)}`)
  await typeFind(page, '999.999.99')
  await page.keyboard.press('Enter')
  await until(page, () => /IKEA/.test(document.querySelector('.find .said.warn')?.textContent ?? ''), 8000).catch(() => fail(where, 'no line for IKEA down'))
  ok(where, `502: ${await page.evaluate(() => document.querySelector('.find .said').textContent)}`)

  // a known guide not built yet: choosing it starts its build and the feed follows it
  await typeFind(page, 'markus')
  await page.click('.matches button')
  await until(page, () => new URL(location.href).searchParams.get('job')?.startsWith('stub-'), 8000).catch(() => fail(where, 'choosing a known guide did not start its build'))
  await until(page, () => document.querySelectorAll('#feed li').length > 2, 15000).catch(() => fail(where, 'the feed did not follow the catalog build'))
  ok(where, 'a known guide starts its build and the feed follows it')
  await page.close()

  // Enter on a built guide opens it
  const ready = await open(view, `${view} ready`, url(STUB))
  await typeFind(ready, 'knagg')
  await Promise.all([ready.waitForNavigation({ waitUntil: 'load' }), ready.keyboard.press('Enter')])
  const to = new URL(ready.url())
  if (to.searchParams.get('mode') !== 'ready' || to.searchParams.get('job') !== '1115-0022-2d4ab6') fail(`${view} ready`, `Enter should open the ready screen: ${to.search.replace(/key=[^&]+/, 'key=…')}`)
  else ok(`${view} ready`, 'Enter on a built guide opens its ready screen')
  await ready.close()

  if (!PHOTOS) {
    console.log('  (no PHOTOS folder: photo upload skipped)')
    continue
  }
  const ph = await open(view, `${view} photos`, url(STUB), { allow: [/\/api\/jobs$/, /status of 415/] })
  const files = await ph.$('.target input')
  await files.uploadFile(join(PHOTOS, 'page-2.jpg'), join(PHOTOS, 'page-1.jpg'), join(PHOTOS, 'page-3.jpg'))
  await until(ph, () => !document.querySelector('.send').hidden, 5000)
  await files.uploadFile(join(PHOTOS, 'page-4.jpg')) // photos add up, as a phone takes them one by one
  await until(ph, () => document.querySelectorAll('.pages img.in').length === 4, 5000).catch(() => fail(`${view} photos`, 'expected 4 page pictures'))
  await ph.type('.send .size', '55 x 55 x 45 cm')
  await sleep(500)
  await shot(ph, `${view}-10-photos`)
  const word = await ph.evaluate(() => document.querySelector('.target .word').textContent)
  if (!/4/.test(word)) fail(`${view} photos`, `the target should count 4 pages: ${word}`)
  await ph.click('.send .go')
  await until(ph, () => new URL(location.href).searchParams.get('job'), 10000)
  const last = await (await fetch(`${STUB}/stub/last?key=stub`)).json()
  const names = last.files?.map((f) => `${f.field}:${f.filename}`).join(' ')
  if (last.source !== 'photos' || names !== 'pages:page-2.jpg pages:page-1.jpg pages:page-3.jpg pages:page-4.jpg' || last.size !== '55 x 55 x 45 cm')
    fail(`${view} photos`, `the upload carried ${JSON.stringify(last)}`)
  else ok(`${view} photos`, `sent ${names}, size ${last.size}`)
  await ph.close()

  // HEIC is refused by the server: one line says what to do; a PDF with photos is refused before sending
  const bad = await open(view, `${view} heic`, url(STUB), { allow: [/\/api\/jobs$/, /status of 415/] })
  await (await bad.$('.target input')).uploadFile(join(PHOTOS, 'page.heic'))
  await until(bad, () => !document.querySelector('.send').hidden, 5000)
  await bad.click('.send .go')
  await until(bad, () => document.querySelector('#drop .note')?.textContent, 8000).catch(() => fail(`${view} heic`, 'no line for HEIC'))
  await sleep(300)
  await shot(bad, `${view}-11-heic`)
  ok(`${view} heic`, await bad.evaluate(() => document.querySelector('#drop .note').textContent))
  await bad.click('.disc .clear')
  await (await bad.$('.target input')).uploadFile(PDF, join(PHOTOS, 'page-1.jpg'))
  const mixed = await bad.evaluate(() => document.querySelector('#drop .note').textContent)
  if (!mixed) fail(`${view} heic`, 'a PDF with photos should be refused')
  else ok(`${view} heic`, `PDF with photos: ${mixed}`)
  await bad.close()
}

// ------------------------------------------------------------------ the address of a job still running opens its live feed
{
  // what the workbench does with a catalog guide that is not built yet: /?mode=build&job=<id>
  console.log('\nan unfinished job opened from its address: feed, approval, done, ready screen')
  const where = 'job address'
  const started = await (await fetch(`${STUB}/api/catalog/open`, { method: 'POST', headers: { 'X-Demo-Key': 'stub', 'Content-Type': 'application/json' }, body: JSON.stringify({ q: 'AA-251870-22' }) })).json()
  if (started.ready || !started.job_id) fail(where, `expected a build to start: ${JSON.stringify(started)}`)
  const page = await open('phone', where, url(STUB, { job: started.job_id }))
  await until(page, () => document.querySelectorAll('#feed li').length > 2, 15000).catch(() => fail(where, 'no live feed'))
  if (!(await page.evaluate(() => document.querySelector('#drop').hidden))) fail(where, 'the drop screen showed instead of the feed')
  await stateIs(page, 'approval')
  await page.click('#act')
  await stateIs(page, 'done')
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.click('#act')])
  const to = new URL(page.url())
  if (to.searchParams.get('mode') !== 'ready' || to.searchParams.get('job') !== started.job_id) fail(where, `done should open the ready screen: ${to.search.replace(/key=[^&]+/, 'key=…')}`)
  else ok(where, 'live feed, approval, done, then the ready screen of the same job')
  await page.close()
}

// ------------------------------------------------------------------ the stream says nothing: long poll
{
  console.log('\nbuffered stream: the long poll takes over, ?auto=1 end to end')
  const where = 'buffered'
  const page = await open('laptop', where, url(BUFFERED, { auto: '1' }))
  await upload(page)
  const t0 = Date.now()
  await until(page, () => document.documentElement.dataset.transport === 'poll', 20000)
  report.fallbackAfterMs = Date.now() - t0
  ok(where, `switched to the long poll after ${report.fallbackAfterMs} ms`)
  await until(page, () => document.querySelectorAll('#feed li').length > 5, 20000)
  await stateIs(page, 'approval')
  ok(where, 'approval reached through the long poll')
  await stateIs(page, 'running', 10000) // approved by itself
  ok(where, 'approved by itself')
  await stateIs(page, 'done')
  await page.waitForNavigation({ waitUntil: 'load', timeout: 15000 })
  if (new URL(page.url()).searchParams.get('mode') !== 'ready' || !new URL(page.url()).searchParams.get('job')) fail(where, 'did not open the ready screen by itself')
  else ok(where, 'opened the guide by itself')
  await page.close()
}

// ------------------------------------------------------------------ English, reduced motion, a finished job
{
  console.log('\nEnglish, reduced motion, a finished job reopened')
  const where = 'english'
  const page = await open('phone', where, url(STUB, { lang: 'en', job: '1115-0022-2d4ab6' }), { reduced: true })
  await stateIs(page, 'done', 15000)
  await sleep(500)
  await shot(page, 'phone-6-english-done-reduced')
  const t = await page.evaluate(() => document.querySelector('#feed').textContent)
  if (!/Rejected|Writing|Looking at/.test(t)) fail(where, 'no English tool lines')
  if (!(await page.evaluate(() => getComputedStyle(document.querySelector('#state .dot')).animationName === 'none'))) fail(where, 'the dot still moves with reduced motion')
  await page.click('#lang')
  await sleep(300)
  const el = await page.evaluate(() => ({ lang: document.documentElement.lang, text: document.querySelector('#feed').textContent }))
  if (el.lang !== 'el' || !/Κοιτάζει/.test(el.text)) fail(where, 'the switch did not turn the feed Greek')
  else ok(where, 'the language switch rewrites the feed')
  // a finished job opened from its address does not leave by itself, even with auto
  await sleep(500)
  if (!new URL(page.url()).searchParams.get('mode')) fail(where, 'a finished job left the feed by itself')
  await page.close()

  const list = await open('laptop', 'recent', url(STUB, { lang: 'en' }))
  await until(list, () => document.querySelectorAll('#recent a').length > 0, 8000)
  const href = await list.evaluate(() => [...document.querySelectorAll('#recent a')].find((a) => /KNAGGLIG/.test(a.textContent))?.href ?? '')
  const h = new URL(href)
  if (h.searchParams.get('mode') !== 'ready' || !h.searchParams.get('job')) fail('recent', `a finished job should open its ready screen: ${h.search.replace(/key=[^&]+/, 'key=…')}`)
  else ok('recent', 'a finished job opens its ready screen')
  await list.close()
}

// ------------------------------------------------------------------ the guide page without a guide offers the build screen
{
  console.log('\nthe guide page without a guide')
  const where = 'guide start'
  const page = await browser.newPage()
  await page.setViewport(VIEWS.phone)
  page.on('pageerror', (e) => fail(where, `page error: ${e.message}`))
  await page.goto(`${BASE}?job=&key=stub&api=${encodeURIComponent(STUB)}`, { waitUntil: 'load' })
  // once the workbench (src/home/) is routed, an address without a job opens it instead of the guide page
  const empty = await until(page, () => document.querySelector('#now a'), 10000).then(() => true, () => false)
  if (!empty) console.log('  (no guide page without a job: the workbench has that address now; skipped)')
  else {
    await page.evaluate(() => document.fonts.ready)
    await shot(page, 'phone-0-guide-start')
    await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.click('#now a')])
    await stateIs(page, 'drop', 10000)
    ok(where, 'the line under the empty room opens the build screen')
  }
  await page.close()
}

// ------------------------------------------------------------------ no key, no server
{
  console.log('\nno key, no server')
  const page = await open('phone', 'nokey', url(STUB, { key: null }), { allow: [/\/api\/jobs/, /401/] })
  await until(page, () => document.querySelector('#drop .note')?.textContent, 8000)
  await shot(page, 'phone-7-no-key')
  await page.close()
  const off = await open('phone', 'offline', url('http://127.0.0.1:9'), { allow: [/127\.0\.0\.1:9/, /Failed to load/] })
  await until(off, () => document.querySelector('#drop .note')?.textContent, 8000)
  ok('offline', await off.evaluate(() => document.querySelector('#drop .note').textContent))
  await off.close()
}

await browser.close()
writeFileSync(join(OUT, 'report.json'), JSON.stringify({ problems, report }, null, 1))
console.log(`\n${problems.length ? `${problems.length} problems` : 'all good'}; report in ${join(OUT, 'report.json')}`)
process.exit(problems.length ? 1 : 0)
