// Check the build screen (?mode=build) in a real browser against the replay stub, and save screenshots.
//
//   node scripts/build-stub.mjs &                       # PORT=8788, the stream works
//   PORT=8789 BUFFER_SSE=1 node scripts/build-stub.mjs & # the stream is buffered, as through a quick tunnel
//   node scripts/build-shots.mjs [outDir] [baseUrl]
//
// At 1512x864 and 390x844: the drop target with the recent jobs; an upload; the feed mid-run with pictures; the
// plan waiting for approval; approve; done; the guide page it opens. Then: the long-poll fallback when the
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
  await until(page, () => new URL(location.href).searchParams.get('job'), 15000)
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
  if (to.searchParams.get('mode') || !to.searchParams.get('job') || to.searchParams.get('key') !== 'stub' || to.searchParams.get('lang') !== 'el')
    fail(where, `the guide link is wrong: ${to.search.replace(/key=[^&]+/, 'key=…')}`)
  await page.waitForFunction(() => document.documentElement.dataset.ready === 'true', { timeout: 30000 })
  await sleep(1500)
  await shot(page, `${view}-5-guide`)
  ok(where, 'the guide page opened for the new job')
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
  if (!new URL(page.url()).searchParams.get('job')) fail(where, 'did not open the guide by itself')
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
  if (h.searchParams.get('mode') || !h.searchParams.get('job')) fail('recent', `a finished job should open its guide: ${h.search.replace(/key=[^&]+/, 'key=…')}`)
  else ok('recent', 'a finished job opens its guide')
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
  await until(page, () => document.querySelector('#now a'), 10000)
  await page.evaluate(() => document.fonts.ready)
  await shot(page, 'phone-0-guide-start')
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.click('#now a')])
  await stateIs(page, 'drop', 10000)
  ok(where, 'the line under the empty room opens the build screen')
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
