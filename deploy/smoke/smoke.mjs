/**
 * Browser smoke test — the gate that would have caught the blank-page regression.
 *
 * Every other check we had asked "did the server respond?" and the server responded
 * perfectly: 200 on every path, nothing 404'd, /actuator/health green, CI passing. The
 * bundle was being served as text/html, the browser refused it under strict MIME
 * checking, and the entire UI rendered blank. Status codes are not evidence the app works.
 *
 * So this asserts what a browser actually experiences:
 *   - no request fails
 *   - no console error and no uncaught page error
 *   - the page renders visible text
 *   - the JavaScript bundle is served as JavaScript, not as HTML
 *
 * Usage: node smoke.mjs http://localhost:8081
 */
import { chromium } from 'playwright'

const BASE = process.argv[2]
if (!BASE) {
  console.error('usage: node smoke.mjs <base-url>')
  process.exit(2)
}

// A client-side route is included deliberately: it is loaded directly, not clicked
// through, which is the case that breaks when the SPA fallback is misconfigured.
const ROUTES = ['/', '/capabilities', '/history']

const failures = []

const browser = await chromium.launch({ args: ['--no-sandbox'] })

for (const route of ROUTES) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const badResponses = []
  const consoleErrors = []
  const pageErrors = []

  page.on('response', r => {
    if (r.status() >= 400) badResponses.push(`${r.status()} ${new URL(r.url()).pathname}`)
  })
  page.on('console', m => {
    if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200))
  })
  page.on('pageerror', e => pageErrors.push(e.message.slice(0, 200)))

  await page.goto(BASE + route, { waitUntil: 'networkidle', timeout: 60000 })
  await page.waitForTimeout(2000)

  const text = (await page.locator('body').innerText()).trim()

  if (badResponses.length) failures.push(`${route}: failed requests -> ${badResponses.join(', ')}`)
  if (consoleErrors.length) failures.push(`${route}: console errors -> ${consoleErrors.join(' | ')}`)
  if (pageErrors.length) failures.push(`${route}: page errors -> ${pageErrors.join(' | ')}`)
  if (!text) failures.push(`${route}: rendered no visible text (blank page)`)

  console.log(`  ${failures.length ? '?' : 'ok'}  ${route}  (${text.length} chars rendered)`)
  await page.close()
}

// The specific regression, asserted directly: whatever <script src> the shell points at
// must come back as JavaScript. Serving HTML here returns 200 and blanks the page.
const page = await browser.newPage()
await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 60000 })
const bundle = await page.getAttribute('script[type="module"]', 'src')
if (!bundle) {
  failures.push('no module script found in the SPA shell')
} else {
  const res = await page.request.get(new URL(bundle, BASE).toString())
  const ctype = res.headers()['content-type'] || ''
  if (!res.ok()) failures.push(`bundle ${bundle}: status ${res.status()}`)
  else if (!/javascript|ecmascript/i.test(ctype)) {
    failures.push(`bundle ${bundle}: served as "${ctype}", expected JavaScript`)
  } else {
    console.log(`  ok  ${bundle} -> ${ctype}`)
  }
}
await browser.close()

if (failures.length) {
  console.error('\nSMOKE FAILED:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log('\nsmoke passed')
