// Production browser acceptance driver for real code-server workbench in Chromium.
//
// Real Chromium opens the Studio IDE view through the external bootstrap URL:
// no request is intercepted, mocked or fulfilled.
//
// Protocol (stdin): the first line is the JSON configuration:
//   {playwrightModule, baseUrl, environmentId, browserSessionId, workspaceDirectory,
//    browserTempDirectory, entryUrl, viewId, bindingEpoch, scenario?, expectedTheme?}
// later lines are {"continue":true} for synchronization; the refresh-preserves-dirty scenario
// additionally accepts {"navigate":{"entryUrl":"..."}} after the IndexedDB backup is committed.
//
// Protocol (stdout): newline delimited JSON only:
//   {"event":"stage","stage":"<safe fixed name>"}
//   {"event":"workbench-ready"}
//   {"event":"editor-ready"}
//   {"event":"dirty-backed-up","cookie":"<session-cookie>"}
//   {"event":"entry-restored"}
//   {"event":"reload-restored"}
//   {"event":"terminal-success"}
//   {"event":"websocket-duplex","sent":<n>,"received":<n>,"viewFrames":true}
//   {"event":"file-saved"}
//   {"event":"configuration-ready"}
//   {"event":"done"}
//   {"event":"failure","stage":"<safe fixed name>","detail":"<error class or HTTP code>"}

import { watch } from 'node:fs'
import { readFile, readdir } from 'node:fs/promises'
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
    'browserSessionId',
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
    browserSessionId: config.browserSessionId,
    workspaceDirectory: resolve(config.workspaceDirectory),
    browserTempDirectory: resolve(config.browserTempDirectory),
    entryUrl: config.entryUrl,
    viewId: config.viewId,
    bindingEpoch: String(config.bindingEpoch),
    scenario: config.scenario,
    expectedTheme: config.expectedTheme,
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
 * Observes the real browser WebSockets through CDP so the acceptance can prove that the
 * workbench socket of the Studio view carried frames in both directions. No traffic is mocked.
 */
function watchWorkbenchWebSockets(context, page) {
  const sockets = new Map()
  const ready = (async () => {
    const session = await context.newCDPSession(page)
    await session.send('Network.enable')
    session.on('Network.webSocketCreated', (event) => {
      if (!sockets.has(event.requestId)) {
        sockets.set(event.requestId, { url: event.url, sent: 0, received: 0 })
      }
    })
    session.on('Network.webSocketFrameSent', (event) => {
      const socket = sockets.get(event.requestId)
      if (socket) socket.sent += 1
    })
    session.on('Network.webSocketFrameReceived', (event) => {
      const socket = sockets.get(event.requestId)
      if (socket) socket.received += 1
    })
  })()
  ready.catch(() => {})
  return {
    async awaitDuplex(timeoutMs) {
      await ready
      const deadline = Date.now() + timeoutMs
      while (Date.now() < deadline) {
        for (const socket of sockets.values()) {
          if (socket.sent > 0 && socket.received > 0 && isViewSocket(socket.url)) {
            return socket
          }
        }
        await new Promise((resolve) => setTimeout(resolve, 100))
      }
      throw namedError('WebSocketDuplexTimeout')
    },
  }
}

/** The workbench socket must target this Studio view, never an external origin. */
function isViewSocket(url) {
  try {
    const parsed = new URL(url)
    return /^\/ide\/[0-9a-f-]{36}\/[0-9a-f-]{36}\//.test(parsed.pathname)
  } catch {
    return false
  }
}

/**
 * Waits until the visible Monaco model contains the expected text.
 */
async function waitForEditorText(page, text, timeoutMs = EDITOR_TIMEOUT) {
  await page.waitForFunction(
    (expected) =>
      document
        .querySelector('.monaco-editor .view-lines')
        ?.textContent?.replaceAll('\u00a0', ' ')
        .includes(expected),
    text,
    { timeout: timeoutMs },
  )
}

/**
 * Opens editable.txt through the explorer and waits until Monaco shows its original text.
 */
async function openEditableEditor(page, fileRow) {
  await fileRow.dblclick()
  const editor = page.locator('.monaco-editor .view-lines').first()
  await editor.waitFor({ state: 'visible', timeout: EDITOR_TIMEOUT })
  await waitForEditorText(page, 'initial text in workspace')
  return editor
}

/** Observe the remote workbench's working-copy backup, never save the edited file. */
async function waitForWorkingCopyBackup(workspaceDirectory, marker) {
  const userData = join(dirname(workspaceDirectory), 'distribution', 'data', 'ide', 'code-server', 'user-data')
  const inspect = async () => {
    const entries = await readdir(userData, { recursive: true, withFileTypes: true })
    for (const entry of entries) {
      const path = join(entry.parentPath, entry.name)
      if (!entry.isFile() || !/[\\/]backups[\\/]/i.test(path)) continue
      const content = await readFile(path, 'utf8')
      if (content.includes('editable.txt') && content.includes(marker)) return true
    }
    return false
  }
  await new Promise((resolve, reject) => {
    let settled = false
    const finish = (error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      watcher.close()
      if (error) reject(error)
      else resolve()
    }
    const scan = () => {
      void inspect().then((found) => { if (found) finish() }, finish)
    }
    const watcher = watch(userData, { recursive: true }, scan)
    watcher.on('error', finish)
    const timer = setTimeout(() => finish(namedError('WorkingCopyBackupTimeout')), EDITOR_TIMEOUT)
    scan()
  })
}

/**
 * Waits for a reloaded workbench to restore the dirty model and keep its dirty tab.
 */
async function waitForRestoredEditor(page, marker) {
  // Workbench editor-layout persistence is separate from working-copy backup. Reopen the
  // same file through the UI so restoration does not depend on an unload-time layout write.
  const fileRow = page
    .locator('.explorer-folders-view .monaco-list-row', { hasText: 'editable.txt' }).first()
  await fileRow.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })
  await openEditableEditor(page, fileRow)
  await waitForEditorText(page, marker)
  await page.locator('.tab.dirty').first().waitFor({ state: 'visible', timeout: EDITOR_TIMEOUT })
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
    const workbenchSockets = watchWorkbenchWebSockets(context, page)

    // 1. First screen: load bootstrap URL and wait for workbench
    stage('workbench-goto')
    await page.goto(new URL(config.entryUrl, config.baseUrl).toString(), {
      waitUntil: 'domcontentloaded',
      timeout: WORKBENCH_TIMEOUT,
    })

    stage('workbench-render')
    const workbench = page.locator('.monaco-workbench').first()
    await workbench.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })

    // The configuration scenario only inspects the workbench-wide state; all other scenarios
    // drive an editor and therefore need the workspace file in the explorer.
    let fileRow = null
    if (config.scenario !== 'configuration') {
      stage('workbench-explorer')
      fileRow = page
        .locator('.explorer-folders-view .monaco-list-row', { hasText: 'editable.txt' })
        .first()
      await fileRow.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })
    }

    emit({ event: 'workbench-ready' })

    if (config.scenario === 'configuration') {
      // The applied workbench theme is observed from the real workbench class, not from a probe.
      const awaitTheme = async (theme) => {
        stage('configuration-theme')
        if (typeof theme !== 'string' || !/^[a-z0-9_-]{1,60}$/.test(theme)) {
          throw namedError('ConfigurationError')
        }
        await page.waitForFunction(
          (expected) =>
            document.querySelector('.monaco-workbench')?.className.includes(
              `vscode-theme-defaults-themes-${expected}-json`,
            ),
          theme,
          { timeout: WORKBENCH_TIMEOUT },
        )
      }
      // The probe runs inside the managed extension host and reports the registered settings.
      const awaitProbe = async () => {
        stage('configuration-probe')
        await awaitFile(
          join(config.workspaceDirectory, 'config-probe.json'),
          (content) => {
            try {
              return typeof JSON.parse(content) === 'object'
            } catch {
              return false
            }
          },
          WORKBENCH_TIMEOUT,
        )
        emit({ event: 'configuration-ready' })
      }

      await awaitTheme(config.expectedTheme)
      await awaitProbe()
      const acknowledge = await lines.next()
      if (acknowledge.done || JSON.parse(acknowledge.value).continue !== true) {
        throw namedError('ProtocolError')
      }
      await browser.close()
      browser = null
      emit({ event: 'done' })
      return
    }

    if (config.scenario === 'dirty-close') {
      stage('dirty-editor')
      const editor = await openEditableEditor(page, fileRow)
      emit({ event: 'editor-ready' })
      const ready = await lines.next()
      if (ready.done || JSON.parse(ready.value).continue !== true) throw namedError('ProtocolError')
      await editor.click()
      await page.keyboard.press('End')
      await page.keyboard.type(' dirty acceptance')
      await waitForEditorText(page, 'dirty acceptance')
      try {
        await page.locator('.tab.dirty').first().waitFor({ state: 'visible', timeout: EDITOR_TIMEOUT })
      } catch (error) {
        process.stderr.write(`tab classes: ${JSON.stringify(await page.locator('.tab').evaluateAll((tabs) => tabs.map((tab) => ({ class: tab.className, label: tab.getAttribute('aria-label') }))))}\n`)
        throw error
      }
      const cookies = await context.cookies()
      const cookie = cookies.find((item) => item.name === 'kk_ide_session')
      if (!cookie) throw namedError('MissingSessionCookie')
      emit({ event: 'dirty-ready', cookie: cookie.value })
      for (const choice of ['Cancel', "Don't Save"]) {
        stage('native-close-dialog')
        const dialog = page.locator('.monaco-dialog-box').filter({ hasText: 'editable.txt' }).first()
        await dialog.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })
        emit({ event: 'close-prompt' })
        const next = await lines.next()
        if (next.done || JSON.parse(next.value).continue !== true) throw namedError('ProtocolError')
        await dialog.getByRole('button', { name: choice, exact: true }).click()
        emit({ event: 'dialog-answered' })
      }
      const next = await lines.next()
      if (next.done || JSON.parse(next.value).continue !== true) throw namedError('ProtocolError')
      await browser.close()
      browser = null
      emit({ event: 'done' })
      return
    }

    if (config.scenario === 'refresh-preserves-dirty') {
      const editablePath = join(config.workspaceDirectory, 'editable.txt')
      const initialText = 'initial text in workspace\n'
      const marker = 'refresh-preserves-dirty'

      stage('editor-open-file')
      const editor = await openEditableEditor(page, fileRow)
      emit({ event: 'editor-ready' })
      const ready = await lines.next()
      if (ready.done || JSON.parse(ready.value).continue !== true) throw namedError('ProtocolError')

      stage('editor-type')
      await editor.click()
      await page.keyboard.press('End')
      await page.keyboard.type(` ${marker}`, { delay: 20 })
      await waitForEditorText(page, marker)
      await page.locator('.tab.dirty').first().waitFor({ state: 'visible', timeout: EDITOR_TIMEOUT })

      // The remote workbench stores working-copy backups on its managed host.
      stage('editor-backup')
      await waitForWorkingCopyBackup(config.workspaceDirectory, marker)
      if ((await readFile(editablePath, 'utf8')) !== initialText) {
        throw namedError('DirtyFileWasSaved')
      }
      const cookies = await context.cookies()
      const cookie = cookies.find((item) => item.name === 'kk_ide_session')
      if (!cookie) throw namedError('MissingSessionCookie')
      emit({ event: 'dirty-backed-up', cookie: cookie.value })

      stage('await-refresh')
      const refresh = await lines.next()
      if (refresh.done || refresh.value.trim().length === 0) throw namedError('ProtocolError')
      const refreshMessage = JSON.parse(refresh.value)
      const refreshEntryUrl = refreshMessage?.navigate?.entryUrl
      if (typeof refreshEntryUrl !== 'string' || refreshEntryUrl.length === 0) {
        throw namedError('ProtocolError')
      }

      // Programmatic navigation can be guarded by the browser's beforeunload confirmation; the
      // workbench's in-page dirty dialog is handled by the daemon path and is not involved here.
      page.on('dialog', (dialog) => {
        void dialog.accept()
      })

      stage('workbench-navigate')
      await page.goto(new URL(refreshEntryUrl, config.baseUrl).toString(), {
        waitUntil: 'domcontentloaded',
        timeout: WORKBENCH_TIMEOUT,
      })
      await workbench.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })
      await waitForRestoredEditor(page, marker)
      if ((await readFile(editablePath, 'utf8')) !== initialText) {
        throw namedError('DirtyFileWasSaved')
      }
      emit({ event: 'entry-restored' })

      const afterEntry = await lines.next()
      if (afterEntry.done || JSON.parse(afterEntry.value).continue !== true) {
        throw namedError('ProtocolError')
      }

      stage('workbench-reload')
      await page.reload({ waitUntil: 'domcontentloaded', timeout: WORKBENCH_TIMEOUT })
      await workbench.waitFor({ state: 'visible', timeout: WORKBENCH_TIMEOUT })
      await waitForRestoredEditor(page, marker)
      if ((await readFile(editablePath, 'utf8')) !== initialText) {
        throw namedError('DirtyFileWasSaved')
      }
      emit({ event: 'reload-restored' })

      const afterReload = await lines.next()
      if (afterReload.done || JSON.parse(afterReload.value).continue !== true) {
        throw namedError('ProtocolError')
      }
      stage('closing-browser')
      await browser.close()
      browser = null
      emit({ event: 'done' })
      return
    }

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

    // 双向 payload：真实工作台 WebSocket 必须在本视图路径上既发送又接收过帧
    stage('terminal-duplex')
    const duplex = await workbenchSockets.awaitDuplex(TERMINAL_TIMEOUT)
    emit({ event: 'websocket-duplex', sent: duplex.sent, received: duplex.received, viewFrames: true })
    emit({ event: 'terminal-success' })

    // 3. File editing interaction: open file, type in Monaco editor, save with Ctrl+S, assert fs
    stage('editor-open-file')
    const editor = await openEditableEditor(page, fileRow)

    stage('editor-type')
    await editor.click()
    await page.keyboard.press('End')
    await page.keyboard.press('Enter')
    const appendText = 'appended by playwright in monaco editor'
    await page.keyboard.type(appendText, { delay: 20 })
    await waitForEditorText(page, appendText)

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
