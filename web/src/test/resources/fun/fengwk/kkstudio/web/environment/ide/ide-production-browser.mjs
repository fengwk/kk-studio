// Production browser acceptance driver for the managed IDE views.
//
// Real Chromium drives two Studio IDE views opened through their own external bootstrap URLs:
// no request is intercepted, mocked or fulfilled here. The workbench, the Explorer, Monaco, the
// terminal and the Studio REST API are the real ones.
//
// Protocol (stdin): the first line is the JSON configuration
//   {playwrightModule, baseUrl, environmentId, documentId, workspaceDirectory,
//    views: [{viewId, bindingEpoch, entryUrl}, ...]}
// every later line is {"continue":true} and releases the next synchronisation point.
//
// Protocol (stdout): newline delimited JSON only
//   {"event":"stage","stage":"<safe fixed name>"}
//   {"event":"shell","pid":<view A shell pid>}
//   {"event":"closed"}
//   {"event":"done"}
//   {"event":"failure","stage":"<safe fixed name>","detail":"<error class or HTTP code>"}
//
// Nothing but those events reaches stdout: no URL, token, cookie, argv, environment value or raw
// exception message is ever printed.

import { watch } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { basename, dirname, join, resolve } from 'node:path'
import { createInterface } from 'node:readline'

const WORKBENCH_TIMEOUT = 60_000
const TERMINAL_TIMEOUT = 30_000
const EDITOR_TIMEOUT = 30_000
const MARKER_TIMEOUT = 30_000
const DIRTY_DETAIL = 'save or discard unsaved changes before closing the view'

let currentStage = 'config'

function emit(event) {
  process.stdout.write(`${JSON.stringify(event)}\n`)
}

function stage(name) {
  currentStage = name
  emit({ event: 'stage', stage: name })
}

class HttpStatusError extends Error {
  constructor(status) {
    super('http status')
    this.name = 'HttpStatusError'
    this.status = status
  }
}

/** A failure whose class name is the only diagnostic that may leave this process. */
function namedError(name) {
  const error = new Error(name)
  error.name = name
  return error
}

/** Only an error class name or an HTTP status code is ever reported. */
function safeDetail(error) {
  if (error instanceof HttpStatusError) {
    return `HTTP ${error.status}`
  }
  const name = error instanceof Error ? error.name : 'Error'
  return typeof name === 'string' && /^[A-Za-z]{1,40}$/.test(name) ? name : 'Error'
}

function parseConfig(raw) {
  const config = JSON.parse(raw)
  if (typeof config !== 'object' || config === null) {
    throw namedError('ConfigurationError')
  }
  const keys = ['playwrightModule', 'baseUrl', 'environmentId', 'documentId', 'workspaceDirectory', 'browserTempDirectory']
  if (keys.some((key) => typeof config[key] !== 'string' || config[key].length === 0)) {
    throw namedError('ConfigurationError')
  }
  if (!Array.isArray(config.views) || config.views.length !== 2) {
    throw namedError('ConfigurationError')
  }
  const views = config.views.map((view) => {
    if (
      typeof view?.viewId !== 'string' ||
      view.viewId.length === 0 ||
      typeof view?.entryUrl !== 'string' ||
      view.entryUrl.length === 0 ||
      view?.bindingEpoch === undefined ||
      view?.bindingEpoch === null
    ) {
      throw namedError('ConfigurationError')
    }
    return { viewId: view.viewId, bindingEpoch: String(view.bindingEpoch), entryUrl: view.entryUrl }
  })
  return {
    playwrightModule: resolve(config.playwrightModule),
    baseUrl: config.baseUrl,
    environmentId: config.environmentId,
    documentId: config.documentId,
    workspaceDirectory: resolve(config.workspaceDirectory),
    browserTempDirectory: resolve(config.browserTempDirectory),
    views,
  }
}

function viewUrl(config, view) {
  return `${config.baseUrl}/api/environments/${config.environmentId}/ide/views/${view.viewId}`
}

/** Focuses the real managed terminal of one view through the product's own Studio API. */
async function focusTerminal(config, view) {
  const response = await fetch(`${viewUrl(config, view)}/terminal`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ documentId: config.documentId, bindingEpoch: view.bindingEpoch }),
  })
  await response.text().catch(() => '')
  if (!response.ok) {
    throw new HttpStatusError(response.status)
  }
}

/** Clicks the real xterm surface of a view so keystrokes reach the managed shell. */
async function focusTerminalSurface(page) {
  const xterm = page.locator('.terminal-wrapper .xterm').first()
  await xterm.waitFor({ state: 'visible', timeout: TERMINAL_TIMEOUT })
  await xterm.click({ position: { x: 40, y: 20 } })
}

async function typeCommand(page, command) {
  await page.keyboard.type(command, { delay: 15 })
  await page.keyboard.press('Enter')
}

/**
 * Waits for a marker file to carry its exact content, driven by real filesystem events.
 * The watch is bounded by a deadline; there is no polling loop and no fixed sleep.
 */
function awaitMarker(path, label) {
  const pattern = new RegExp(`^${label} ([0-9]+)\\n$`)
  const directory = dirname(path)
  const name = basename(path)
  return new Promise((accept, reject) => {
    let settled = false
    const finish = (error, pid) => {
      if (settled) {
        return
      }
      settled = true
      clearTimeout(timer)
      watcher.close()
      if (error) {
        reject(error)
      } else {
        accept(pid)
      }
    }
    const inspect = async () => {
      let content
      try {
        content = await readFile(path, 'utf8')
      } catch {
        return
      }
      const match = pattern.exec(content)
      if (match !== null && Number(match[1]) > 1) {
        finish(null, Number.parseInt(match[1], 10))
      }
    }
    const watcher = watch(directory, { persistent: false }, (_event, changed) => {
      if (changed !== null && changed !== name) {
        return
      }
      void inspect()
    })
    const timer = setTimeout(() => finish(namedError('MarkerTimeoutError')), MARKER_TIMEOUT)
    void inspect()
  })
}

/** Writes one per-view marker file with the real shell pid and proves its content. */
async function writeMarker(page, path, label) {
  const quotedPath = `'${path.replaceAll("'", "'\\''")}'`
  await typeCommand(page, `printf '${label} %s\\n' "$$" > ${quotedPath}`)
  return await awaitMarker(path, label)
}

/** Opens the workspace file through the real Explorer and leaves the tab dirty. */
async function makeDirty(page) {
  stage('dirty-open-file')
  const row = page.locator('.explorer-folders-view .monaco-list-row', { hasText: 'draft.txt' }).first()
  await row.waitFor({ state: 'visible', timeout: EDITOR_TIMEOUT })
  await row.click()
  stage('dirty-focus-editor')
  const editor = page.locator('.monaco-editor .view-lines').first()
  await editor.waitFor({ state: 'visible', timeout: EDITOR_TIMEOUT })
  await editor.click()
  stage('dirty-type')
  await page.keyboard.type('kk-studio-unsaved-edit', { delay: 20 })
  stage('dirty-buffer')
  await page.waitForFunction(
    () => {
      const lines = document.querySelector('.monaco-editor .view-lines')
      return lines !== null && (lines.textContent || '').includes('kk-studio-unsaved-edit')
    },
    undefined,
    { timeout: EDITOR_TIMEOUT },
  )
}

/** Closes one view through the product's own REST API and returns the raw outcome. */
async function closeView(config, view, discard) {
  const response = await fetch(viewUrl(config, view), {
    method: 'DELETE',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ documentId: config.documentId, bindingEpoch: view.bindingEpoch, discard }),
  })
  const body = await response.text().catch(() => '')
  return { status: response.status, ok: response.ok, body }
}

/** Opens one view in its own tab through the direct Studio bootstrap URL. */
async function openWorkbench(browser, config, view) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  const page = await context.newPage()
  await page.goto(new URL(view.entryUrl, config.baseUrl).toString(), {
    waitUntil: 'domcontentloaded',
    timeout: WORKBENCH_TIMEOUT,
  })
  const workbench = page.locator('.monaco-workbench').first()
  await workbench.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })
  const row = page.locator('.explorer-folders-view .monaco-list-row', { hasText: 'draft.txt' }).first()
  await row.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })
  return { page, context }
}

/** Releases the next synchronisation point; the parent acknowledges with {"continue":true}. */
async function awaitAck(lines) {
  const next = await lines.next()
  if (next.done || next.value.trim().length === 0) {
    throw namedError('ConfigurationError')
  }
  const message = JSON.parse(next.value)
  if (message.continue !== true) {
    throw namedError('ConfigurationError')
  }
}

async function run(config, lines) {
  stage('browser-launch')
  const requireModule = createRequire(config.playwrightModule)
  const { chromium } = requireModule(config.playwrightModule)

  let browser = null
  let failure = null
  let cleanupFailure = null
  try {
    // The Java fixture owns this directory and deletes it only after child processes have exited.
    const browserTemp = config.browserTempDirectory
    browser = await chromium.launch({
      channel: 'chromium',
      headless: true,
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
      env: {
        ...process.env,
        HOME: browserTemp,
        XDG_CONFIG_HOME: browserTemp,
        XDG_CACHE_HOME: browserTemp,
        XDG_DATA_HOME: browserTemp,
        TMPDIR: browserTemp,
        TMP: browserTemp,
        TEMP: browserTemp,
      },
    })

    const opened = []
    for (const [index, view] of config.views.entries()) {
      stage(index === 0 ? 'workbench-a' : 'workbench-b')
      opened.push(await openWorkbench(browser, config, view))
    }
    const [a, b] = opened
    const [viewA, viewB] = config.views

    stage('terminal-a')
    await focusTerminal(config, viewA)
    stage('terminal-b')
    await focusTerminal(config, viewB)

    stage('markers')
    await focusTerminalSurface(a.page)
    const pidA = await writeMarker(a.page, join(config.workspaceDirectory, 'terminal-a.pid'), 'a')
    await focusTerminalSurface(b.page)
    const pidB = await writeMarker(b.page, join(config.workspaceDirectory, 'terminal-b.pid'), 'b')

    emit({ event: 'shell', pid: pidA })
    await awaitAck(lines)

    stage('dirty-edit')
    await makeDirty(a.page)
    if (await readFile(join(config.workspaceDirectory, 'draft.txt'), 'utf8') !== 'fixture draft\n') {
      throw namedError('DraftWasSavedError')
    }

    stage('close-rejected')
    const rejected = await closeView(config, viewA, false)
    if (rejected.status !== 400) {
      throw new HttpStatusError(rejected.status)
    }
    let detail = null
    try {
      detail = JSON.parse(rejected.body).errors?.detail
    } catch {
      detail = null
    }
    if (detail !== DIRTY_DETAIL) {
      throw namedError('DirtyRejectionError')
    }

    stage('markers-after-reject')
    await focusTerminalSurface(a.page)
    if ((await writeMarker(a.page, join(config.workspaceDirectory, 'terminal-a-2.pid'), 'a')) !== pidA) {
      throw namedError('MarkerMismatchError')
    }
    await focusTerminalSurface(b.page)
    if ((await writeMarker(b.page, join(config.workspaceDirectory, 'terminal-b-2.pid'), 'b')) !== pidB) {
      throw namedError('MarkerMismatchError')
    }

    stage('close-a')
    const closedA = await closeView(config, viewA, true)
    if (!closedA.ok) {
      throw new HttpStatusError(closedA.status)
    }
    emit({ event: 'closed' })
    await awaitAck(lines)

    stage('prove-b')
    await focusTerminalSurface(b.page)
    if ((await writeMarker(b.page, join(config.workspaceDirectory, 'terminal-b-3.pid'), 'b')) !== pidB) {
      throw namedError('MarkerMismatchError')
    }

    stage('close-b')
    const closedB = await closeView(config, viewB, true)
    if (!closedB.ok) {
      throw new HttpStatusError(closedB.status)
    }
  } catch (error) {
    failure = error
  } finally {
    if (browser !== null) {
      try {
        await browser.close()
      } catch (error) {
        cleanupFailure = error
      }
    }
  }
  if (failure !== null) {
    throw failure
  }
  if (cleanupFailure !== null) {
    throw cleanupFailure
  }
  emit({ event: 'done' })
}

async function main() {
  if (process.versions.node.split('.')[0] !== '24') {
    throw namedError('ConfigurationError')
  }
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })[Symbol.asyncIterator]()
  const first = await lines.next()
  if (first.done || first.value.trim().length === 0) {
    throw namedError('ConfigurationError')
  }
  await run(parseConfig(first.value), lines)
}

main().then(
  () => {
    process.exitCode = 0
  },
  (error) => {
    emit({ event: 'failure', stage: currentStage, detail: safeDetail(error) })
    process.exitCode = 1
  },
)
