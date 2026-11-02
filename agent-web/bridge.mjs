// gekko web bridge: one Playwright process per web device, started by the gekko CLI and spoken to over loopback HTTP.
//
// It offers a fixed set of operations (below) and nothing else: no JavaScript, no file paths and no URLs of its own come
// from the wire except what an operation names (the page to open, the folder of the app to serve, where a video goes).
// Every request needs the token of the user (GEKKO_BRIDGE_TOKEN), a loopback Host and no Origin header, so a web page
// open in any browser on this machine cannot talk to it.
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
const HERE = path.dirname(fileURLToPath(import.meta.url))
const PAGE_SCRIPT = fs.readFileSync(path.join(HERE, 'page.js'), 'utf8')
const MAX_BODY = 1_000_000
const CONSOLE_LINES = 500
const WHEEL_STEP = 300

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

  async drag({ fromX, fromY, toX, toY, durationMs = 200 }) {
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
        await target.fill(text, { timeout: 2000 })
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
  if (host !== `127.0.0.1:${PORT}` && host !== `localhost:${PORT}`) return false
  const given = Buffer.from(String(req.headers['x-gekko-token'] || ''))
  const wanted = Buffer.from(TOKEN)
  return given.length === wanted.length && crypto.timingSafeEqual(given, wanted)
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
  state.lastRequest = Date.now()
  const reply = (status, payload) => {
    const text = JSON.stringify(payload)
    res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) })
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
    let body
    try {
      body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
    } catch (error) {
      return reply(400, { error: 'invalid JSON' })
    }
    const operation = body.op
    if (typeof operation !== 'string' || !Object.hasOwn(operations, operation)) return reply(400, { error: `unknown operation ${operation}` })
    try {
      reply(200, { ok: true, ...(await run(operation, body)) })
    } catch (error) {
      reply(200, { ok: false, error: firstLine(error), kind: error.kind || null, transient: TRANSIENT.test(String(error.message)) })
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
