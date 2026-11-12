// gekko web bridge: one Playwright process per web device, started by the gekko CLI and spoken to over loopback HTTP.
//
// It offers a fixed set of operations (below) and nothing else: no JavaScript, no file paths and no URLs of its own come
// from the wire except what an operation names (the page to open, the folder of the app to serve, where a video goes).
// Every request needs a loopback Host and no Origin header, so a web page open in any browser on this machine cannot talk
// to it, and a signature that only the CLI of this user can make (HMAC-SHA256 with GEKKO_BRIDGE_TOKEN, see Protocol in the
// shared protocol): the request carries a nonce and the MAC of its endpoint (the port), the session of this bridge, the
// nonce and the body, and every answer carries the MAC of the same nonce, the status and its body. The token never goes
// over the connection, so a program that listens on the port first learns nothing and cannot pass for the bridge. The
// session is picked at start and told in the answer to `hello`; every other operation has to name it, so a recorded
// request is worthless once the bridge restarted.
import crypto from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const options = {}
for (let i = 2; i < process.argv.length; i += 2) options[process.argv[i].replace(/^--/, '')] = process.argv[i + 1]
const PORT = Number(options.port)
const VERSION = options.version || ''
const IDLE_MS = Number(options['idle-minutes'] || 30) * 60_000
const TOKEN = process.env.GEKKO_BRIDGE_TOKEN || ''
delete process.env.GEKKO_BRIDGE_TOKEN
const KEY = Buffer.from(TOKEN)
const SESSION = crypto.randomBytes(16).toString('hex')
const MAX_NONCE = 128
const HERE = path.dirname(fileURLToPath(import.meta.url))
const PAGE_SCRIPT = fs.readFileSync(path.join(HERE, 'page.js'), 'utf8')
const MAX_BODY = 4_000_000
const CONSOLE_LINES = 500
const MOCK_HITS = 5_000
const WHEEL_STEP = 300
// How long a fill waits for the element to be editable and stable; a browser that is starting while others run is slow
const FILL_TIMEOUT_MS = 10_000

if (!PORT || TOKEN.length < 32) {
  console.error('gekko bridge: --port and GEKKO_BRIDGE_TOKEN are required')
  process.exit(2)
}

const playwrightDir = options['playwright-dir'] || HERE
const playwright = createRequire(path.join(playwrightDir, 'package.json'))('playwright')
const playwrightVersion = createRequire(path.join(playwrightDir, 'package.json'))('playwright/package.json').version

const state = {
  engine: null,
  browser: null,
  context: null,
  pages: [],
  page: null,
  dialog: null,
  waiters: new Set(),
  granted: new Set(),
  colorScheme: null,
  viewport: { width: 1280, height: 800 },
  console: [],
  frames: [],
  servers: new Map(),
  lastRequest: Date.now(),
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function note(line) {
  state.console.push(line)
  if (state.console.length > CONSOLE_LINES) state.console.shift()
}

function fail(message, kind) {
  const error = new Error(message)
  error.kind = kind
  return error
}

// ---------------------------------------------------------------------------------------------------------------- browser

async function start({ browser, headless = true, viewport, device }) {
  if (state.browser) {
    if (state.engine !== browser) throw fail(`this session runs ${state.engine}, not ${browser}`)
    return {}
  }
  if (!playwright[browser]) throw fail(`unknown browser ${browser}`)
  state.engine = browser
  state.viewport = viewport || state.viewport
  state.device = device ? playwright.devices[device] : null
  if (device && !state.device) throw fail(`Playwright does not know the device ${device}`)
  try {
    state.browser = await playwright[browser].launch({ headless })
  } catch (error) {
    state.engine = null
    throw fail(`${browser} could not be started: ${firstLine(error)}; install it with: gekko install --platform web`)
  }
  state.browser.on('disconnected', () => {
    state.browser = null
    state.context = null
    state.page = null
    state.pages = []
  })
  return {}
}

async function newContext({ locale, timezone, permissions = [], geolocation, offline = false, recordVideo = false }) {
  if (!state.browser) throw fail('the browser is not started')
  await closeContext()
  const settings = { ...(state.device || {}), acceptDownloads: true }
  if (!state.device) settings.viewport = state.viewport
  if (locale) settings.locale = locale
  if (timezone) settings.timezoneId = timezone
  if (geolocation) settings.geolocation = geolocation
  if (offline) settings.offline = true
  if (recordVideo) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'gekko-video-'))
    settings.recordVideo = { dir: directory, size: settings.viewport || state.device.viewport }
    state.videoDir = directory
  }
  state.context = await state.browser.newContext(settings)
  state.granted = new Set(permissions)
  if (permissions.length > 0) await state.context.grantPermissions(permissions)
  await state.context.addInitScript({ content: PAGE_SCRIPT })
  await routeMocks(state.context)
  state.context.on('page', (page) => adopt(page))
  adopt(await state.context.newPage())
  return {}
}

function adopt(page) {
  // The first page of a context is announced by its page event and handed over by newPage: once is enough
  if (state.pages.includes(page)) return
  state.pages.push(page)
  state.page = page
  page.on('dialog', (dialog) => {
    state.dialog = dialog
    for (const waiter of [...state.waiters]) waiter()
  })
  page.on('console', (message) => note(`[${message.type()}] ${message.text()}`))
  page.on('pageerror', (error) => note(`[pageerror] ${error.stack || error.message || error}`))
  page.on('close', () => {
    state.pages = state.pages.filter((other) => other !== page)
    if (state.page === page) state.page = state.pages[state.pages.length - 1] || null
  })
  if (state.colorScheme) page.emulateMedia({ colorScheme: state.colorScheme }).catch(() => {})
}

async function closeContext() {
  const dialog = state.dialog
  state.dialog = null
  if (dialog) await dialog.dismiss().catch(() => {})
  const context = state.context
  state.context = null
  state.page = null
  state.pages = []
  state.colorScheme = null
  state.granted = new Set()
  if (context) await context.close().catch(() => {})
  // A video that was not saved (see saveVideo) is of no use once its context is gone
  if (state.videoDir) fs.rmSync(state.videoDir, { recursive: true, force: true })
  state.videoDir = null
}

function currentPage() {
  if (!state.page) throw fail('no page is open')
  return state.page
}

// A click can open an alert and then waits for it to be answered: return to the CLI at once, the CLI answers it next
function untilDialog(work) {
  return new Promise((resolve, reject) => {
    let done = false
    const waiter = () => finish(resolve, { dialog: true })
    const finish = (settle, value) => {
      if (done) return
      done = true
      state.waiters.delete(waiter)
      settle(value)
    }
    state.waiters.add(waiter)
    if (state.dialog) return waiter()
    work().then(
      (value) => finish(resolve, value || {}),
      (error) => finish(reject, error)
    )
  })
}

function noDialog() {
  if (state.dialog) throw fail(`a browser dialog is open: ${state.dialog.message()}`, 'dialog')
}

const TRANSIENT = /Execution context was destroyed|navigation|Target (page, context or browser has been )?closed|frame was detached|Frame was detached/i

async function snapshot({ idAttributes }) {
  const page = currentPage()
  if (state.dialog) return { windows: [], dialog: describeDialog(), viewport: viewportOf(page) }
  const frames = page.frames()
  state.frames = frames
  let main
  try {
    main = await frames[0].evaluate((attributes) => window.__gekko.snapshot(attributes), idAttributes)
  } catch (error) {
    if (TRANSIENT.test(String(error.message))) return { transient: true }
    if (/__gekko/.test(String(error.message))) return { windows: [], viewport: viewportOf(page), blank: true }
    throw error
  }
  prefix(main.windows[0], 0)
  if (!main.modal) {
    for (let index = 1; index < frames.length; index++) {
      const child = await frameContent(frames[index], index, idAttributes)
      if (child) main.windows[0].children.push(...child)
    }
  }
  return { ...main, dialog: null }
}

function viewportOf(page) {
  const size = page.viewportSize() || state.viewport
  return { w: size.width, h: size.height }
}

function prefix(node, frameIndex) {
  node.ref = `${frameIndex}:${node.ref}`
  for (const child of node.children || []) prefix(child, frameIndex)
}

function shift(node, dx, dy, box) {
  node.bounds = { left: node.bounds.left + dx, top: node.bounds.top + dy, right: node.bounds.right + dx, bottom: node.bounds.bottom + dy }
  const inherited = node.clip
    ? { left: node.clip.left + dx, top: node.clip.top + dy, right: node.clip.right + dx, bottom: node.clip.bottom + dy }
    : box
  node.clip = {
    left: Math.max(inherited.left, box.left),
    top: Math.max(inherited.top, box.top),
    right: Math.min(inherited.right, box.right),
    bottom: Math.min(inherited.bottom, box.bottom),
  }
  for (const child of node.children || []) shift(child, dx, dy, box)
}

// The content of an iframe: read in its own frame, placed where the iframe is on the page
async function frameContent(frame, index, idAttributes) {
  try {
    const handle = await frame.frameElement()
    const area = await handle.boundingBox()
    if (!area || area.width < 1 || area.height < 1) return null
    const snap = await frame.evaluate((attributes) => window.__gekko.snapshot(attributes), idAttributes)
    const box = { left: Math.round(area.x), top: Math.round(area.y), right: Math.round(area.x + area.width), bottom: Math.round(area.y + area.height) }
    const nodes = snap.windows[0].children
    for (const node of nodes) {
      prefix(node, index)
      shift(node, box.left, box.top, box)
    }
    return nodes
  } catch (error) {
    return null
  }
}

function describeDialog() {
  const dialog = state.dialog
  return { type: dialog.type(), message: dialog.message(), defaultValue: dialog.defaultValue() }
}

async function elementHandle(ref) {
  const [frameIndex, local] = String(ref).split(':')
  const frame = state.frames[Number(frameIndex)]
  if (!frame) throw fail('the page changed', 'stale')
  const handle = await frame.evaluateHandle((id) => window.__gekko.editable(id), local)
  const target = handle.asElement()
  if (!target) throw fail('Element is not editable', 'rejected')
  return target
}

// ------------------------------------------------------------------------------------------------------------------ mocks

// Network mocks are data from the CLI (a URL regex, a method, a canned answer). They live here, not in a browser context:
// they are there when a context is made and stay when it is closed (the CLI removes them itself at the end of a test).
// A context gets its route once, when there is a rule for it; without a rule nothing is intercepted. A request that a
// service worker answers never reaches a route, so such a request cannot be mocked.
const mocks = { rules: [], hits: [], routed: new WeakSet() }

function compileMock(rule) {
  if (!rule || typeof rule !== 'object' || typeof rule.id !== 'string' || typeof rule.urlRegex !== 'string') {
    throw fail('a mock needs an id and a urlRegex')
  }
  const bad = (what) => fail(`mock ${rule.id}: ${what}`)
  const method = rule.method ?? null
  const bodyContains = rule.bodyContains ?? null
  const status = rule.status ?? 200
  const headers = rule.headers ?? {}
  const delayMs = rule.delayMs ?? 0
  const bodyBase64 = rule.bodyBase64 ?? ''
  if (method !== null && typeof method !== 'string') throw bad('method must be a string')
  if (bodyContains !== null && typeof bodyContains !== 'string') throw bad('bodyContains must be a string')
  if (!Number.isInteger(status) || status < 100 || status > 599) throw bad(`status ${status} is not an HTTP status`)
  if (typeof headers !== 'object' || Array.isArray(headers)) throw bad('headers must be an object')
  if (typeof delayMs !== 'number' || !(delayMs >= 0)) throw bad('delayMs must be a number of milliseconds')
  if (typeof bodyBase64 !== 'string') throw bad('bodyBase64 must be a string')
  let pattern
  try {
    pattern = new RegExp(rule.urlRegex)
  } catch (error) {
    throw bad(`urlRegex is not a valid regular expression: ${firstLine(error)}`)
  }
  const answer = {}
  for (const [name, value] of Object.entries(headers)) answer[name] = String(value)
  return {
    id: rule.id,
    pattern,
    method: method === null ? null : method.toUpperCase(),
    bodyContains,
    status,
    headers: answer,
    names: new Set(Object.keys(answer).map((name) => name.toLowerCase())),
    delayMs,
    body: Buffer.from(bodyBase64, 'base64'),
  }
}

// The last rule that matches wins
function matchMock(request) {
  const method = request.method().toUpperCase()
  const url = request.url()
  for (let index = mocks.rules.length - 1; index >= 0; index--) {
    const rule = mocks.rules[index]
    if (rule.method !== null && rule.method !== method) continue
    if (!rule.pattern.test(url)) continue
    if (rule.bodyContains !== null) {
      const body = request.postData()
      if (body === null || !body.includes(rule.bodyContains)) continue
    }
    return rule
  }
  return null
}

// The browser applies CORS to what is fulfilled here, so a cross-origin answer has to allow the page that asked. Playwright
// adds such headers itself only when the answer has no access-control-allow-origin, so ours bring the credentials along
function corsHeaders(origin, taken) {
  const headers = { 'access-control-allow-origin': origin || '*' }
  if (origin && !taken.has('access-control-allow-credentials')) headers['access-control-allow-credentials'] = 'true'
  if (origin && !taken.has('vary')) headers.vary = 'Origin'
  return headers
}

async function answerMock(route) {
  const request = route.request()
  try {
    if (mocks.rules.length === 0) return await route.fallback()
    const headers = request.headers()
    const origin = headers.origin
    if (request.method() === 'OPTIONS' && headers['access-control-request-method'] !== undefined) {
      const url = request.url()
      // Only a request that a mock could answer is answered here, so the preflight of any other method (or of a URL no
      // mock is for) still reaches the real backend and its CORS check. A mock that looks at the body cannot be told
      // apart before the real request is sent; its preflight is answered.
      const asked = headers['access-control-request-method'].toUpperCase()
      const answers = (rule) => (rule.method === null || rule.method === asked) && rule.pattern.test(url)
      if (!mocks.rules.some(answers)) return await route.fallback()
      // What the page asks for is allowed as asked: a wildcard does not count when the request has credentials
      return await route.fulfill({
        status: 204,
        headers: {
          ...corsHeaders(origin, new Set()),
          'access-control-allow-methods': headers['access-control-request-method'],
          'access-control-allow-headers': headers['access-control-request-headers'] || '*',
        },
      })
    }
    const rule = matchMock(request)
    if (!rule) return await route.fallback()
    // Counted when the request is seen, so a late answer cannot show up in the log of the next test
    mocks.hits.push({ id: rule.id, method: request.method(), url: request.url() })
    if (mocks.hits.length > MOCK_HITS) mocks.hits.shift()
    if (rule.delayMs > 0) await sleep(rule.delayMs)
    const answer = rule.names.has('access-control-allow-origin') ? rule.headers : { ...rule.headers, ...corsHeaders(origin, rule.names) }
    await route.fulfill({ status: rule.status, headers: answer, body: rule.body })
  } catch (error) {
    // The context can be gone while a delayed answer waits; anything else is worth a line in the console log
    if (!TRANSIENT.test(String(error.message))) note(`[gekko] a mock could not answer ${request.url()}: ${firstLine(error)}`)
    await route.fallback().catch(() => {})
  }
}

async function routeMocks(context) {
  if (!context || mocks.rules.length === 0 || mocks.routed.has(context)) return
  mocks.routed.add(context)
  try {
    await context.route('**/*', answerMock)
  } catch (error) {
    mocks.routed.delete(context)
    throw error
  }
}

// ---------------------------------------------------------------------------------------------------------- operations

const operations = {
  hello() {
    return { version: VERSION, engine: state.engine, started: !!state.browser, playwright: playwrightVersion, context: !!state.context }
  },

  start,
  newContext,

  async closeContext() {
    await closeContext()
    return {}
  },

  // The end of a test in a session that several tests share: what the test left open is cleaned up, the browser
  // context (cookies, storage, a login) stays
  async endTest() {
    const dialog = state.dialog
    state.dialog = null
    if (dialog) await dialog.dismiss().catch(() => {})
    for (const page of state.pages.slice(1)) await page.close().catch(() => {})
    state.colorScheme = null
    for (const page of state.pages) await page.emulateMedia({ colorScheme: null }).catch(() => {})
    if (state.context) {
      await state.context.setOffline(false).catch(() => {})
      await state.context.setGeolocation(null).catch(() => {})
    }
    return {}
  },

  // An alert while the page loads (or a beforeunload of the page that is left) waits to be answered: return to the CLI
  // at once instead of after the 30s of the load
  async goto({ url, relative = false }) {
    const page = currentPage()
    noDialog()
    return untilDialog(async () => {
      try {
        // A path or a query is meant for the page that is open
        const target = relative ? new URL(url, page.url()).href : url
        await page.goto(target, { waitUntil: 'load', timeout: 30_000 })
      } catch (error) {
        // A page that is slow to load is used as far as it got; a page that cannot be reached is an error
        if (!/Timeout/i.test(String(error.message))) throw fail(`Unable to open ${url}: ${firstLine(error)}`)
        note(`[gekko] ${url} did not finish loading within 30s`)
      }
      return { url: page.url() }
    })
  },

  async back() {
    const page = currentPage()
    noDialog()
    return untilDialog(async () => {
      const response = await page.goBack({ waitUntil: 'load', timeout: 30_000 }).catch(() => null)
      return { moved: response !== null }
    })
  },

  snapshot,

  async click({ x, y, count = 1, holdMs = 0 }) {
    const page = currentPage()
    noDialog()
    return untilDialog(async () => {
      await page.mouse.move(x, y)
      if (holdMs > 0) {
        await page.mouse.down()
        await sleep(holdMs)
        await page.mouse.up()
      } else {
        await page.mouse.click(x, y, { clickCount: count })
      }
    })
  },

  async drag({ fromX, fromY, toX, toY, durationMs = 200, holdMs = 0 }) {
    const page = currentPage()
    noDialog()
    return untilDialog(async () => {
      const steps = Math.max(2, Math.min(20, Math.round(durationMs / 16)))
      await page.mouse.move(fromX, fromY)
      await page.mouse.down()
      for (let step = 1; step <= steps; step++) {
        await page.mouse.move(fromX + ((toX - fromX) * step) / steps, fromY + ((toY - fromY) * step) / steps)
        await sleep(durationMs / steps)
      }
      // At rest at the end before the mouse goes up: nothing is left of the speed
      if (holdMs > 0) {
        await sleep(holdMs)
      }
      await page.mouse.up()
    })
  },

  async wheel({ x, y, dx, dy }) {
    const page = currentPage()
    noDialog()
    return untilDialog(async () => {
      await page.mouse.move(x, y)
      // A browser may cap what one wheel event scrolls (Firefox: about a page), so a long way is a few turns
      let right = dx
      let down = dy
      while (right !== 0 || down !== 0) {
        const stepX = Math.max(-WHEEL_STEP, Math.min(WHEEL_STEP, right))
        const stepY = Math.max(-WHEEL_STEP, Math.min(WHEEL_STEP, down))
        await page.mouse.wheel(stepX, stepY)
        right -= stepX
        down -= stepY
      }
    })
  },

  async fill({ ref, text }) {
    noDialog()
    const target = await elementHandle(ref)
    try {
      const tag = await target.evaluate((node) => node.tagName)
      if (tag === 'SELECT') {
        await target.selectOption({ label: text }, { timeout: 2000 }).catch(() => target.selectOption({ value: text }, { timeout: 2000 }))
      } else {
        await target.fill(text, { timeout: FILL_TIMEOUT_MS })
      }
    } catch (error) {
      throw fail(firstLine(error), 'rejected')
    }
    return {}
  },

  async scrollIntoView({ ref }) {
    noDialog()
    const [frameIndex, local] = String(ref).split(':')
    const frame = state.frames[Number(frameIndex)]
    if (!frame) throw fail('the page changed', 'stale')
    const found = await frame.evaluate((id) => window.__gekko.reveal(id), local)
    if (!found) throw fail('the page changed', 'stale')
    return {}
  },

  async key({ key }) {
    const page = currentPage()
    noDialog()
    return untilDialog(() => page.keyboard.press(key))
  },

  // The element that has the input focus, in the page or in the first frame that has one
  async focus() {
    noDialog()
    for (const frame of state.frames) {
      const focus = await frame.evaluate(() => window.__gekko && window.__gekko.focus()).catch(() => null)
      if (focus) return { focused: true, editable: focus.editable, text: focus.text }
    }
    return { focused: false }
  },

  // Types at the cursor of whatever has the input focus, without selecting or replacing anything first
  async insertText({ text }) {
    const page = currentPage()
    noDialog()
    await page.keyboard.insertText(text)
    return {}
  },

  async blur() {
    noDialog()
    for (const frame of state.frames) await frame.evaluate(() => window.__gekko && window.__gekko.blur()).catch(() => {})
    return {}
  },

  async screenshot() {
    const page = currentPage()
    if (state.dialog) throw fail(`a browser dialog is open: ${state.dialog.message()}`, 'dialog')
    const bytes = await page.screenshot({ type: 'png' })
    return { png: bytes.toString('base64') }
  },

  async emulate({ colorScheme, offline, geolocation, viewport }) {
    const page = currentPage()
    if (colorScheme !== undefined) {
      state.colorScheme = colorScheme
      for (const open of state.pages) await open.emulateMedia({ colorScheme }).catch(() => {})
    }
    if (offline !== undefined) await state.context.setOffline(offline)
    if (geolocation !== undefined) await state.context.setGeolocation(geolocation)
    if (viewport) {
      state.viewport = { width: viewport.w, height: viewport.h }
      await page.setViewportSize(state.viewport)
    }
    return {}
  },

  // Replaces all network mocks (an empty list switches mocking off and empties the hit log); the context that is open
  // starts to use them now, one made later does too
  async setMocks({ rules }) {
    if (!Array.isArray(rules)) throw fail('rules must be a list')
    const compiled = rules.map(compileMock)
    mocks.rules = compiled
    if (compiled.length === 0) mocks.hits = []
    await routeMocks(state.context)
    return {}
  },

  // The requests the mocks answered since the last call, oldest first
  mockHits() {
    const hits = mocks.hits
    mocks.hits = []
    return { hits }
  },

  async permissions({ grant = [], revoke = [] }) {
    if (!state.context) throw fail('no browser context')
    grant.forEach((name) => state.granted.add(name))
    revoke.forEach((name) => state.granted.delete(name))
    await state.context.clearPermissions()
    if (state.granted.size > 0) await state.context.grantPermissions([...state.granted])
    return {}
  },

  async dialog({ accept, text }) {
    const dialog = state.dialog
    if (!dialog) throw fail('no browser dialog is open')
    state.dialog = null
    if (accept) await dialog.accept(text)
    else await dialog.dismiss()
    return {}
  },

  console({ clear = false }) {
    const text = state.console.join('\n')
    if (clear) state.console = []
    return { text }
  },

  // The page has to close for the video to be complete; the folder of the CLI is the only place it is written to
  async saveVideo({ path: target }) {
    const videos = state.pages.map((page) => page.video()).filter(Boolean)
    for (const page of [...state.pages]) await page.close().catch(() => {})
    let saved = false
    for (const [index, video] of videos.entries()) {
      if (index === 0 && target) {
        await video.saveAs(target)
        saved = true
      }
      await video.delete().catch(() => {})
    }
    if (state.videoDir) fs.rmSync(state.videoDir, { recursive: true, force: true })
    state.videoDir = null
    return { saved }
  },

  // The web app of a project can be a folder or a file: it is served on loopback so that the page is a secure context.
  // The file system tells which it is (a folder may have a dot in its name); a file is served from its folder
  async serve({ path: target }) {
    const resolved = path.resolve(String(target || ''))
    let stat
    try {
      stat = fs.statSync(resolved)
    } catch (error) {
      throw fail(`app.web ${target} does not exist`)
    }
    const root = stat.isDirectory() ? resolved : path.dirname(resolved)
    const entry = stat.isDirectory() ? '' : encodeURIComponent(path.basename(resolved))
    let server = state.servers.get(root)
    if (!server) {
      server = await serveFolder(root)
      state.servers.set(root, server)
    }
    return { url: `http://127.0.0.1:${server.port}/${entry}` }
  },

  async shutdown() {
    setTimeout(() => exit(), 50)
    return {}
  },
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.htm': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.ico': 'image/x-icon', '.webp': 'image/webp', '.txt': 'text/plain; charset=utf-8',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.wasm': 'application/wasm', '.map': 'application/json',
}

function serveFolder(root) {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      let requested
      try {
        requested = decodeURIComponent((req.url || '/').split('?')[0])
      } catch (error) {
        res.writeHead(400).end()
        return
      }
      let file = path.join(root, path.normalize(`/${requested}`))
      if (file !== root && !file.startsWith(root + path.sep)) {
        res.writeHead(403).end()
        return
      }
      try {
        if (fs.statSync(file).isDirectory()) file = path.join(file, 'index.html')
        if (!fs.statSync(file).isFile()) throw new Error('not a file')
      } catch (error) {
        res.writeHead(404).end()
        return
      }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-store' })
      fs.createReadStream(file)
        .on('error', () => res.destroy())
        .pipe(res)
    })
    server.on('error', reject)
    server.listen(0, '127.0.0.1', () => resolve({ port: server.address().port, server }))
  })
}

// ---------------------------------------------------------------------------------------------------------------- server

function firstLine(error) {
  return String((error && error.message) || error).split('\n')[0]
}

function allowed(req) {
  if (req.headers.origin !== undefined) return false
  const host = String(req.headers.host || '')
  return host === `127.0.0.1:${PORT}` || host === `localhost:${PORT}`
}

// Keep in sync with Protocol.requestMessage / responseMessage and the servers of the other platforms. Known answer: key
// "0123456789abcdef" x 4 and the request message for endpoint "20412", no session, nonce
// "00112233445566778899aabbccddeeff" and body {"type":"hello"} give
// 4ee5a8b1b169ea84d0a350b84270a987f8ff285a9087ce3bf17d75a0943631a0.
function mac(message) {
  return crypto.createHmac('sha256', KEY).update(message).digest('hex')
}

function requestMessage(session, nonce, body) {
  return Buffer.concat([Buffer.from(`gekko-req\n${PORT}\n${session}\n${nonce}\n`), body])
}

function responseMessage(nonce, status, body) {
  return Buffer.concat([Buffer.from(`gekko-res\n${PORT}\n${SESSION}\n${nonce}\n${status}\n`), body])
}

// True when the request is signed with the key of this user; every header has to be there exactly once.
function isSigned(req, nonce, body) {
  const given = req.headers['x-gekko-auth']
  if (typeof given !== 'string') return false
  const session = typeof req.headers['x-gekko-session'] === 'string' ? req.headers['x-gekko-session'] : ''
  const wanted = Buffer.from(mac(requestMessage(session, nonce, body)))
  const offered = Buffer.from(given)
  return offered.length === wanted.length && crypto.timingSafeEqual(offered, wanted)
}

// Requests that touch the page one after the other; a dialog or a hello must get through while one of them waits
let chain = Promise.resolve()
const UNQUEUED = new Set(['hello', 'dialog', 'console', 'shutdown'])

function run(operation, body) {
  if (UNQUEUED.has(operation)) return Promise.resolve().then(() => operations[operation](body))
  const result = chain.then(() => operations[operation](body))
  chain = result.catch(() => {})
  return result
}

const server = http.createServer((req, res) => {
  // [nonce] is the one of the request that is answered; without one the answer is not signed (a refusal of a stranger)
  const reply = (status, payload, nonce) => {
    const text = JSON.stringify(payload)
    const headers = { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) }
    if (nonce) {
      headers['X-Gekko-Session'] = SESSION
      headers['X-Gekko-Auth'] = mac(responseMessage(nonce, status, Buffer.from(text)))
    }
    res.writeHead(status, headers)
    res.end(text)
  }
  if (req.method !== 'POST' || req.url !== '/') return reply(404, { error: 'not found' })
  if (!allowed(req)) return reply(403, { error: 'Browser and foreign requests are not served' })
  let size = 0
  const chunks = []
  req.on('data', (chunk) => {
    size += chunk.length
    if (size > MAX_BODY) {
      reply(413, { error: 'request too large' })
      req.destroy()
    } else {
      chunks.push(chunk)
    }
  })
  req.on('end', async () => {
    if (size > MAX_BODY) return
    const raw = Buffer.concat(chunks)
    const nonce = req.headers['x-gekko-nonce']
    if (typeof nonce !== 'string' || nonce.length === 0 || nonce.length > MAX_NONCE || !isSigned(req, nonce, raw)) {
      return reply(401, { ok: false, error: 'The request is not signed with the key of this bridge', kind: 'unauthorized' })
    }
    // Only a request that is signed counts as use: a stranger must not keep the bridge alive
    state.lastRequest = Date.now()
    let body
    try {
      body = JSON.parse(raw.toString('utf8') || '{}')
    } catch (error) {
      return reply(400, { error: 'invalid JSON' }, nonce)
    }
    const operation = body.op
    if (typeof operation !== 'string' || !Object.hasOwn(operations, operation)) return reply(400, { error: `unknown operation ${operation}` }, nonce)
    // Only `hello` may come without a session: it is how the client learns the one of this bridge
    if (operation !== 'hello' && req.headers['x-gekko-session'] !== SESSION) {
      return reply(401, { ok: false, error: 'The bridge was restarted since the last hello', kind: 'session' }, nonce)
    }
    try {
      reply(200, { ok: true, ...(await run(operation, body)) }, nonce)
    } catch (error) {
      reply(200, { ok: false, error: firstLine(error), kind: error.kind || null, transient: TRANSIENT.test(String(error.message)) }, nonce)
    }
  })
})

server.on('error', (error) => {
  console.error(`gekko bridge: ${error.message}`)
  process.exit(3)
})

async function exit() {
  await closeContext().catch(() => {})
  if (state.browser) await state.browser.close().catch(() => {})
  for (const { server: folder } of state.servers.values()) folder.close()
  if (options.session) fs.rmSync(options.session, { force: true })
  process.exit(0)
}

server.listen(PORT, '127.0.0.1', () => {
  if (options.session) fs.writeFileSync(options.session, JSON.stringify({ port: PORT, pid: process.pid, version: VERSION }), { mode: 0o600 })
})

setInterval(() => {
  if (Date.now() - state.lastRequest > IDLE_MS) exit()
}, 60_000).unref()
process.on('SIGTERM', exit)
process.on('SIGINT', exit)
