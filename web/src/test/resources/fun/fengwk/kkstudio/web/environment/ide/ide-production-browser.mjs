// Production browser acceptance driver for real code-server workbench in Chromium.
//
// Real Chromium opens the Studio IDE view through the external bootstrap URL:
// no request is intercepted, mocked or fulfilled.
//
// Protocol (stdin): the first line is the JSON configuration:
//   {playwrightModule, baseUrl, environmentId, documentId, workspaceDirectory,
//    browserTempDirectory, entryUrl, viewId, bindingEpoch}
// later lines are {"continue":true} for synchronization.
//
// Protocol (stdout): newline delimited JSON only:
//   {"event":"stage","stage":"<safe fixed name>"}
//   {"event":"workbench-ready"}
//   {"event":"terminal-success"}
//   {"event":"file-saved"}
//   {"event":"done"}
//   {"event":"failure","stage":"<safe fixed name>","detail":"<error class or HTTP code>"}

import { watch } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { basename, dirname, join, resolve } from 'node:path'
import { createInterface } from 'node:readline'

const WORKBENCH_TIMEOUT = 60_000
const TERMINAL_TIMEOUT = 30_000
const EDITOR_TIMEOUT = 30_000
const FILE_TIMEOUT = 30_000

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

function namedError(name) {
  const error = new Error(name)
  error.name = name
  return error
}

function safeDetail(error) {
  if (error instanceof HttpStatusError) {
    return `HTTP ${error.status}`
  }
  const name = error instanceof Error ? error.name : 'Error'
  return typeof name === 'string' && /^[A-Za-z0-9_-]{1,60}$/.test(name) ? name : 'Error'
}

function parseConfig(raw) {
  const config = JSON.parse(raw)
  if (typeof config !== 'object' || config === null) {
    throw namedError('ConfigurationError')
  }
  const keys = [
    'playwrightModule',
    'baseUrl',
    'environmentId',
    'documentId',
    'workspaceDirectory',
    'browserTempDirectory',
    'entryUrl',
    'viewId',
    'bindingEpoch',
  ]
  for (const key of keys) {
    if (typeof config[key] !== 'string' || config[key].length === 0) {
      throw namedError('ConfigurationError')
    }
  }
  return {
    playwrightModule: resolve(config.playwrightModule),
    baseUrl: config.baseUrl,
    environmentId: config.environmentId,
    documentId: config.documentId,
    workspaceDirectory: resolve(config.workspaceDirectory),
    browserTempDirectory: resolve(config.browserTempDirectory),
    entryUrl: config.entryUrl,
    viewId: config.viewId,
    bindingEpoch: String(config.bindingEpoch),
  }
}

/**
 * Waits for a file to match an expected predicate via fs.watch.
 */
function awaitFile(filePath, predicate, timeoutMs = FILE_TIMEOUT) {
  const directory = dirname(filePath)
  const targetName = basename(filePath)
  return new Promise((accept, reject) => {
    let settled = false
    const finish = (error, result) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      clearInterval(pollTimer)
      watcher.close()
      if (error) {
        reject(error)
      } else {
        accept(result)
      }
    }

    const inspect = async () => {
      let content
      try {
        content = await readFile(filePath, 'utf8')
      } catch {
        return
      }
      if (predicate(content)) {
        finish(null, content)
      }
    }

    const watcher = watch(directory, { persistent: false }, (_event, changed) => {
      if (changed !== null && changed !== targetName) return
      void inspect()
    })

    const pollTimer = setInterval(() => void inspect(), 200)
    const timer = setTimeout(() => finish(namedError('FileTimeoutError')), timeoutMs)
    void inspect()
  })
}

/**
 * Ensures terminal is opened through workbench UI interactions.
 */
async function openTerminalThroughUi(page) {
  const xterm = page.locator('.terminal-wrapper .xterm').first()
  if (await xterm.isVisible().catch(() => false)) {
    return xterm
  }

  // Attempt 1: Ctrl+` (Control+Backquote) toggle terminal
  await page.keyboard.press('Control+Backquote')
  const appearedViaHotkey = await xterm
    .waitFor({ state: 'visible', timeout: 3000 })
    .then(() => true)
    .catch(() => false)
  if (appearedViaHotkey) {
    return xterm
  }

  // Attempt 2: F1 command palette -> "Terminal: Create New Terminal"
  await page.keyboard.press('F1')
  const quickInput = page.locator('.quick-input-widget input.input').first()
  try {
    await quickInput.waitFor({ state: 'visible', timeout: 5000 })
    await quickInput.fill('Terminal: Create New Terminal')
    await page.waitForTimeout(200)
    await page.keyboard.press('Enter')
  } catch {
    // Attempt 3: Ctrl+Shift+`
    await page.keyboard.press('Control+Shift+Backquote')
  }

  await xterm.waitFor({ state: 'visible', timeout: TERMINAL_TIMEOUT })
  return xterm
}

async function run(config, lines) {
  stage('browser-launch')
  const requireModule = createRequire(config.playwrightModule)
  const { chromium } = requireModule(config.playwrightModule)

  let browser = null
  let failure = null
  let failureStage = null
  let cleanupFailure = null

  try {
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

    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
    const page = await context.newPage()

    // 1. First screen: load bootstrap URL and wait for workbench
    stage('workbench-goto')
    await page.goto(new URL(config.entryUrl, config.baseUrl).toString(), {
      waitUntil: 'domcontentloaded',
      timeout: WORKBENCH_TIMEOUT,
    })

    stage('workbench-render')
    const workbench = page.locator('.monaco-workbench').first()
    await workbench.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })

    stage('workbench-explorer')
    const fileRow = page
      .locator('.explorer-folders-view .monaco-list-row', { hasText: 'editable.txt' })
      .first()
    await fileRow.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })

    emit({ event: 'workbench-ready' })

    // 2. Terminal interaction: open terminal, type command, assert output & exit code
    stage('terminal-open')
    const xterm = await openTerminalThroughUi(page)
    await xterm.click({ position: { x: 40, y: 20 } })

    stage('terminal-execute')
    // Command writes output to test-output.txt and exit code to exit-code.txt
    const outputPath = join(config.workspaceDirectory, 'test-output.txt')
    const exitCodePath = join(config.workspaceDirectory, 'exit-code.txt')
    const command =
      'echo "hello from terminal" > test-output.txt && echo "EXIT_CODE=$?" > exit-code.txt'
    await page.keyboard.type(command, { delay: 15 })
    await page.keyboard.press('Enter')

    stage('terminal-assert')
    const outputContent = await awaitFile(
      outputPath,
      (content) => content.includes('hello from terminal'),
      TERMINAL_TIMEOUT,
    )
    if (outputContent.trim() !== 'hello from terminal') {
      throw namedError('TerminalOutputMismatch')
    }

    const exitContent = await awaitFile(
      exitCodePath,
      (content) => content.includes('EXIT_CODE=0'),
      TERMINAL_TIMEOUT,
    )
    if (!exitContent.includes('EXIT_CODE=0')) {
      throw namedError('TerminalExitCodeError')
    }

    emit({ event: 'terminal-success' })

    // 3. File editing interaction: open file, type in Monaco editor, save with Ctrl+S, assert fs
    stage('editor-open-file')
    await fileRow.dblclick()

    stage('editor-monaco-lines')
    const editor = page.locator('.monaco-editor .view-lines').first()
    await editor.waitFor({ state: 'visible', timeout: EDITOR_TIMEOUT })
    await page.waitForFunction(
      () =>
        document
          .querySelector('.monaco-editor .view-lines')
          ?.textContent?.replaceAll('\u00a0', ' ')
          .includes('initial text in workspace'),
      undefined,
      { timeout: EDITOR_TIMEOUT },
    )

    stage('editor-type')
    await editor.click()
    await page.keyboard.press('End')
    await page.keyboard.press('Enter')
    const appendText = 'appended by playwright in monaco editor'
    await page.keyboard.type(appendText, { delay: 20 })
    await page.waitForFunction(
      (text) =>
        document
          .querySelector('.monaco-editor .view-lines')
          ?.textContent?.replaceAll('\u00a0', ' ')
          .includes(text),
      appendText,
      { timeout: EDITOR_TIMEOUT },
    )

    stage('editor-save')
    await editor.click()
    await page.keyboard.press('Control+s')

    stage('editor-assert-disk')
    const editablePath = join(config.workspaceDirectory, 'editable.txt')
    const savedContent = await awaitFile(
      editablePath,
      (content) => content.includes(appendText),
      FILE_TIMEOUT,
    )
    if (!savedContent.includes(appendText) || !savedContent.includes('initial text in workspace')) {
      throw namedError('SavedContentMismatch')
    }

    emit({ event: 'file-saved' })

    // 4. Await parent acknowledgment before tearing down
    stage('await-ack')
    const next = await lines.next()
    if (next.done || next.value.trim().length === 0) {
      throw namedError('ProtocolError')
    }
    const message = JSON.parse(next.value)
    if (message.continue !== true) {
      throw namedError('ProtocolError')
    }

    stage('closing-browser')
    await browser.close()
    browser = null
    stage('done')
    emit({ event: 'done' })
  } catch (error) {
    failure = error
    failureStage = currentStage
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
    currentStage = failureStage
    if (cleanupFailure !== null) {
      throw namedError('ScenarioAndCleanupError')
    }
    throw failure
  }
  if (cleanupFailure !== null) {
    throw cleanupFailure
  }
}

async function main() {
  if (process.versions.node.split('.')[0] !== '24') {
    throw namedError('ConfigurationError')
  }
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })[
    Symbol.asyncIterator
  ]()
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
    process.stderr.write(`${error?.stack || error}\n`)
    emit({ event: 'failure', stage: currentStage, detail: safeDetail(error) })
    process.exitCode = 1
  },
)
