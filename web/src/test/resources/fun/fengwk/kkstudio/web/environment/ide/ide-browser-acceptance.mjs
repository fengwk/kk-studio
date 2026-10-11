// Real-browser IDE acceptance driver (opt-in).
//
// Browser half of the Spring acceptance test: drives the real frozen code-server workbench served
// through the Java IDE Origin data plane, in Chromium launched with `--host-resolver-rules` so
// `*.browser.localhost` resolves to the loopback Spring host. Every view is opened by the Java
// `EnvironmentIdeService`; the window reaches code-server only through the real bootstrap -> cookie
// -> reverse-stream path. No daemon or data plane is faked.
//
// Required steps fail fast: the redacted report is flushed after every step so a JUnit timeout or a
// hard failure still leaves evidence. URL query values (bootstrap token, workbench connection token)
// are always redacted before they reach the report, stdout, or stderr.
//
// Usage: node ide-browser-acceptance.mjs --config <absolute-config.json>

import { createRequire } from 'node:module'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

const require = createRequire(import.meta.url)

function parseArgs(argv) {
  const index = argv.indexOf('--config')
  if (index < 0 || typeof argv[index + 1] !== 'string') {
    throw new Error('expected --config <absolute-path>')
  }
  return argv[index + 1]
}

function requireEnv(name) {
  const value = process.env[name]
  if (!value) {
    throw new Error(`${name} must be set`)
  }
  return value
}

/** Removes every query value so tokens never leak into reports, stdout or stderr. */
function redactUrl(value) {
  if (typeof value !== 'string') {
    return value
  }
  return value.replace(/([?&][^=\s&#]*=)[^&\s#]*/g, '$1<redacted>')
}

function redact(value) {
  return redactUrl(value instanceof Error ? value.message : String(value))
}

function delay(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

async function flush(report, reportPath) {
  await writeFile(reportPath, JSON.stringify(report, null, 2))
}

async function waitFor(predicate, timeoutMillis, label) {
  const deadline = Date.now() + timeoutMillis
  while (Date.now() < deadline) {
    if (await predicate()) {
      return true
    }
    await delay(200)
  }
  throw new Error(`timed out waiting for ${label}`)
}

/**
 * Runs one step and always records its honest outcome. Required failures abort the run after
 * flushing the report; optional failures are recorded as failed and never masked as success.
 */
async function runStep(report, reportPath, name, required, action) {
  const started = Date.now()
  let entry
  try {
    const value = await action()
    entry = { ok: true, value: typeof value === 'string' ? redactUrl(value) : value === undefined ? true : value }
  } catch (error) {
    entry = { ok: false, error: redact(error) }
  }
  entry.required = required
  entry.millis = Date.now() - started
  report.steps[name] = entry
  await flush(report, reportPath)
  if (!entry.ok && required) {
    report.ok = false
    report.abortedAt = name
    await flush(report, reportPath)
    throw new Error(`required browser step '${name}' failed: ${entry.error}`)
  }
  return entry.ok
}

async function screenshot(page, dir, name) {
  await mkdir(dir, { recursive: true })
  const path = join(dir, `${name}.png`)
  await page.screenshot({ path, fullPage: false })
  return path
}

/** Opens a workspace file through the Explorer tree (deterministic focus, unlike Quick Open). */
async function openFile(page, name) {
  const row = page.locator('.explorer-folders-view .monaco-list-row', { hasText: name }).first()
  await row.waitFor({ state: 'visible', timeout: 30_000 })
  await row.click()
  await page.waitForSelector('.monaco-editor .view-lines', { timeout: 30_000 })
  await page.waitForTimeout(500)
}

async function focusEditor(page) {
  const view = page.locator('.monaco-editor .view-lines').first()
  await view.waitFor({ state: 'visible', timeout: 15_000 })
  await view.click({ position: { x: 60, y: 30 } })
  const probe = await page.evaluate(() => ({
    active: document.activeElement ? document.activeElement.tagName : 'none',
    inEditor: !!(document.activeElement && document.activeElement.closest('.monaco-editor')),
    editors: document.querySelectorAll('.monaco-editor').length,
  }))
  if (!probe.inEditor) {
    // Second attempt: click the editor container itself, then re-probe.
    await page.locator('.monaco-editor').first().click({ position: { x: 80, y: 40 } })
  }
  return probe
}

/** Rendered editor text without touching focus; used to prove an edit landed in the buffer. */
async function editorText(page) {
  return page.evaluate(() => {
    const lines = document.querySelector('.monaco-editor .view-lines')
    return lines ? lines.innerText : null
  })
}

/** Explicitly clicks a real workspace-trust dialog if code-server still shows one (bounded, not a fake pass). */
async function dismissTrustDialogIfPresent(page, report) {
  const trustButton =
    page.locator('.monaco-dialog-box .monaco-button', { hasText: /trust|Trust/ }).first()
  try {
    await trustButton.waitFor({ state: 'visible', timeout: 5_000 })
  } catch {
    return false
  }
  await trustButton.click()
  if (report) {
    report.notes.push('[trust] dismissed a real workspace-trust dialog')
  }
  await page.waitForSelector('.monaco-dialog-box', { state: 'detached', timeout: 15_000 }).catch(() => {})
  return true
}

/** Reads the rendered editor text (no private globals) after scrolling to the end of the document. */
async function readActiveEditorText(page) {
  await focusEditor(page)
  await page.keyboard.press('Control+End')
  return editorText(page)
}

/** A visible workbench dialog or notification whose text matches the pattern. */
async function workbenchMessageMatches(page, pattern) {
  return page.evaluate((source) => {
    const matcher = new RegExp(source, 'i')
    const nodes = document.querySelectorAll(
      '.monaco-dialog-box, .notifications-toasts .notification-list-item, .monaco-workbench .dialog-shadow',
    )
    return Array.from(nodes).some((node) => matcher.test(node.textContent || ''))
  }, pattern)
}

/** Dismisses a stray modal (e.g. a leftover save-conflict dialog) through real UI so later clicks land. */
async function dismissDialogsIfAny(page) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const dialog = page.locator('.monaco-dialog-box').first()
    if (!(await dialog.isVisible().catch(() => false))) {
      return
    }
    const cancel = page.locator('.monaco-dialog-box .monaco-button', { hasText: /^Cancel$/i }).first()
    if (await cancel.isVisible().catch(() => false)) {
      await cancel.click()
    } else {
      await page.keyboard.press('Escape')
    }
    await page.waitForTimeout(300)
  }
}

/** Runs a Command Palette command by its human-readable title. */
async function runCommandPalette(page, title) {
  await dismissDialogsIfAny(page)
  await page.keyboard.press('Control+Shift+P')
  await page.waitForSelector('.quick-input-widget', { timeout: 15_000 })
  await page.keyboard.type(title, { delay: 20 })
  const row = page.locator('.quick-input-list .monaco-list-row', { hasText: title }).first()
  await row.waitFor({ state: 'visible', timeout: 15_000 })
  await row.click()
  await page.waitForTimeout(400)
}

/**
 * Clears every unsaved buffer through real UI. `page.reload` is NOT a discard: VS Code Hot Exit
 * restores dirty buffers, so the daemon keeps rejecting close/directory-change. Closing all editors
 * and choosing "Don't Save" is the documented user action that actually drops the dirty state.
 */
async function clearAllDirtyBuffers(page, report, label) {
  if ((await page.locator('.tabs-container .tab.dirty').count()) === 0) {
    return false
  }
  await runCommandPalette(page, 'Close All Editors')
  const dontSave = page
    .locator('.monaco-dialog-box .monaco-button', { hasText: /Don't Save|Do not save/i })
    .first()
  const hadDialog = await dontSave
    .waitFor({ state: 'visible', timeout: 10_000 })
    .then(() => true)
    .catch(() => false)
  if (hadDialog) {
    await dontSave.click()
    report.notes.push(`[${label}] discarded unsaved editors via Don't Save`)
  }
  await page.waitForFunction(
    () => document.querySelectorAll('.tabs-container .tab.dirty').length === 0,
    { timeout: 15_000 },
  )
  return hadDialog
}

async function main() {
  const configPath = parseArgs(process.argv)
  const config = JSON.parse(await readFile(configPath, 'utf8'))
  const playwrightModule = requireEnv('KK_STUDIO_PLAYWRIGHT_MODULE_DIR')
  const { chromium } = require(playwrightModule)
  const report = { ok: false, steps: {}, notes: [], screenshots: [] }
  await mkdir(dirname(config.reportPath), { recursive: true })
  await flush(report, config.reportPath)

  const browser = await chromium.launch({
    args: [
      '--host-resolver-rules=MAP *.browser.localhost 127.0.0.1',
      '--no-sandbox',
      '--disable-dev-shm-usage',
    ],
  })
  const pages = {}
  /** Redacted network trace: path (query removed) plus status/content-encoding or failure reason. */
  const network = []
  const recordPath = (raw) => {
    const redacted = redactUrl(raw)
    const match = redacted.match(/^[a-z]+:\/\/[^/]+(\/[^\s]*)?$/i)
    return match ? match[1] || '/' : redacted
  }
  const attachNetworkTrace = (page) => {
    page.on('response', (response) => {
      if (network.length < 60) {
        network.push({
          path: recordPath(response.url()),
          status: response.status(),
          contentEncoding: response.headers()['content-encoding'] || null,
        })
      }
    })
    page.on('requestfailed', (request) => {
      if (network.length < 60) {
        network.push({
          path: recordPath(request.url()),
          failed: redact(request.failure() ? request.failure().errorText : 'unknown'),
        })
      }
    })
  }
  try {
    for (const view of config.views) {
      const context = await browser.newContext({ ignoreHTTPSErrors: true })
      const page = await context.newPage()
      attachNetworkTrace(page)
      page.on('console', (message) => {
        if (message.type() === 'error') {
          report.notes.push(`[${view.name}] console: ${redact(message.text())}`)
        }
      })
      await runStep(report, config.reportPath, `workbench${view.name}`, true, async () => {
        const response = await page.goto(view.entryUrl, { waitUntil: 'domcontentloaded', timeout: 60_000 })
        report.notes.push(`[${view.name}] bootstrap status ${response ? response.status() : 'none'}`)
        await page.waitForSelector('.monaco-workbench', { timeout: 60_000 })
        await dismissTrustDialogIfPresent(page, report)
        return 'loaded'
      })
      pages[view.name] = page
      report.screenshots.push(await screenshot(page, config.screenshotDir, `workbench-${view.name}`))
    }

    const first = config.views[0]
    const second = config.views[1]
    const pageA = pages[first.name]
    const pageB = pages[second.name]

    const alive = (pid) => {
      try {
        process.kill(pid, 0)
        return true
      } catch {
        return false
      }
    }
    const readMarkerPid = async (name) => {
      const raw = await readFile(join(config.workspaceDirectory, `terminal-${name}.pid`), 'utf8')
      const pid = Number.parseInt(raw.trim().split(/\s+/)[1], 10)
      if (!Number.isInteger(pid) || pid <= 2) {
        throw new Error(`terminal ${name} wrote an invalid pid marker`)
      }
      return pid
    }
    const closeView = async (view) => {
      const response = await fetch(
        `${config.baseUrl}/api/harness/environments/${config.environmentId}/ide/views/${view.viewId}`,
        { method: 'DELETE' },
      )
      const body = redact(await response.text().catch(() => ''))
      report.notes.push(
        `close view ${view.name} HTTP ${response.status}${body ? ' body=' + body.slice(0, 200) : ''}`,
      )
      if (!response.ok) {
        throw new Error(`close view ${view.name} HTTP ${response.status}: ${body.slice(0, 200)}`)
      }
      return response.status
    }

    // Required: edit + save in view A, proven by the editor buffer and by the on-disk file.
    await runStep(report, config.reportPath, 'editSaveA', true, async () => {
      await openFile(pageA, 'alpha.txt')
      const probe = await focusEditor(pageA)
      report.notes.push(`[A] editor focus ${JSON.stringify(probe)}`)
      await pageA.keyboard.press('Control+End')
      await pageA.keyboard.type('\nline-from-viewA', { delay: 20 })
      await waitFor(
        async () => ((await editorText(pageA)) || '').includes('line-from-viewA'),
        20_000,
        'edit reflected in the editor buffer',
      )
      await pageA.keyboard.press('Control+S')
      await waitFor(
        async () =>
          (await readFile(join(config.workspaceDirectory, 'alpha.txt'), 'utf8').catch(() => '')).includes(
            'line-from-viewA',
          ),
        20_000,
        'save reaching the on-disk file',
      )
      return 'edited+saved'
    })
    report.screenshots.push(await screenshot(pageA, config.screenshotDir, 'after-save-A'))

    // Required: view B (same disk) observes the externally changed file content.
    await runStep(report, config.reportPath, 'externalChangeB', true, async () => {
      await openFile(pageB, 'alpha.txt')
      const text = await readActiveEditorText(pageB)
      if (text === null || !text.includes('line-from-viewA')) {
        throw new Error('view B editor did not observe the on-disk change made in view A')
      }
      return true
    })
    report.screenshots.push(await screenshot(pageB, config.screenshotDir, 'external-change-B'))

    // Required: one real terminal per view; the browser shells write their real OS pid.
    for (const view of config.views) {
      const viewUrl = `${config.baseUrl}/api/harness/environments/${config.environmentId}/ide/views/${view.viewId}/terminal`
      const response = await fetch(viewUrl, { method: 'POST' })
      if (!response.ok) {
        throw new Error(`terminal ${view.name} HTTP ${response.status}`)
      }
    }
    for (const view of config.views) {
      const page = pages[view.name]
      const marker = `terminal-${view.name}.pid`
      await runStep(report, config.reportPath, `terminal${view.name}`, true, async () => {
        await page.waitForSelector('.terminal-wrapper .xterm', { timeout: 45_000 })
        await page.click('.terminal-wrapper .xterm', { position: { x: 40, y: 20 } })
        await page.keyboard.type(`echo "${view.viewId} $$" > ${marker}`, { delay: 15 })
        await page.keyboard.press('Enter')
        await waitFor(
          async () => {
            const content = await readFile(join(config.workspaceDirectory, marker), 'utf8').catch(() => '')
            return content.trim().startsWith(view.viewId)
          },
          30_000,
          marker,
        )
        return true
      })
      report.screenshots.push(await screenshot(page, config.screenshotDir, `terminal-${view.name}`))
    }

    // Required: an unsaved editor blocks a directory change with the daemon's own DIRTY refusal, and the
    // rejection reason must be the unsaved-changes refusal, not an unrelated 4xx.
    await runStep(report, config.reportPath, 'dirtyChangeDirectoryRejected', true, async () => {
      await openFile(pageA, 'alpha.txt')
      await focusEditor(pageA)
      await pageA.keyboard.type('\nunsaved-edit', { delay: 20 })
      await waitFor(
        async () => ((await editorText(pageA)) || '').includes('unsaved-edit'),
        20_000,
        'unsaved edit reflected in the editor buffer',
      )
      const response = await fetch(
        `${config.baseUrl}/api/harness/environments/${config.environmentId}/ide/views/${first.viewId}/directory`,
        {
          method: 'PUT',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ directory: join(config.workspaceDirectory, 'subdir') }),
        },
      )
      const body = redact(await response.text().catch(() => ''))
      report.notes.push(
        `dirty change-directory HTTP ${response.status} body=${body.slice(0, 200)}`,
      )
      if (response.ok) {
        throw new Error('directory change was accepted while an editor was dirty')
      }
      if (!/unsaved/i.test(body)) {
        throw new Error(
          `directory change was rejected for an unrelated reason, not unsaved changes: ${body.slice(0, 200)}`,
        )
      }
      return response.status
    })

    // Required: a real save conflict. Both windows dirty the same file; A saves to disk, then B must
    // not silently overwrite the newer on-disk content but surface a conflict prompt.
    await runStep(report, config.reportPath, 'conflictSave', true, async () => {
      await openFile(pageA, 'beta.txt')
      await focusEditor(pageA)
      await pageA.keyboard.press('Control+End')
      await pageA.keyboard.type('\nA-side', { delay: 20 })
      await waitFor(async () => ((await editorText(pageA)) || '').includes('A-side'), 20_000, 'A edit in buffer')
      await openFile(pageB, 'beta.txt')
      await focusEditor(pageB)
      await pageB.keyboard.press('Control+End')
      await pageB.keyboard.type('\nB-side', { delay: 20 })
      await waitFor(async () => ((await editorText(pageB)) || '').includes('B-side'), 20_000, 'B edit in buffer')
      await pageA.keyboard.press('Control+S')
      await waitFor(
        async () =>
          (await readFile(join(config.workspaceDirectory, 'beta.txt'), 'utf8').catch(() => '')).includes(
            'A-side',
          ),
        20_000,
        'view A version on disk',
      )
      await pageB.keyboard.press('Control+S')
      await waitFor(
        () => workbenchMessageMatches(pageB, 'conflict|newer version|overwrite|modified on disk'),
        20_000,
        'conflict prompt',
      )
      const onDisk = await readFile(join(config.workspaceDirectory, 'beta.txt'), 'utf8').catch(() => '')
      if (onDisk.includes('B-side')) {
        throw new Error('view B silently overwrote the newer on-disk content instead of reporting a conflict')
      }
      return 'conflict prompt shown; disk kept A-side'
    })
    report.screenshots.push(await screenshot(pageB, config.screenshotDir, 'conflict-B'))

    // Required: external delete of the open, dirty file, then save. This code-server/VS Code build does
    // not show a prompt for a deleted file (unlike a modified file): it recreates it on disk. Assert that
    // real outcome, which still exercises the full write path through the reverse data plane.
    await runStep(report, config.reportPath, 'externalDelete', true, async () => {
      await openFile(pageA, 'gamma.txt')
      await focusEditor(pageA)
      await pageA.keyboard.type('\ngamma-edit', { delay: 20 })
      await waitFor(
        async () => ((await editorText(pageA)) || '').includes('gamma-edit'),
        20_000,
        'gamma edit in buffer',
      )
      const { rm } = await import('node:fs/promises')
      await rm(join(config.workspaceDirectory, 'gamma.txt'), { force: true })
      await pageA.keyboard.press('Control+S')
      try {
        await waitFor(
          async () =>
            (
              await readFile(join(config.workspaceDirectory, 'gamma.txt'), 'utf8').catch(() => '')
            ).includes('gamma-edit'),
          20_000,
          'save recreating the externally deleted file on disk',
        )
        return 'recreated on disk after external delete'
      } catch (error) {
        const evidence = await pageA.evaluate(() => ({
          dialogs: Array.from(document.querySelectorAll('.monaco-dialog-box, .dialog-shadow')).map(
            (node) => (node.textContent || '').slice(0, 160),
          ),
          toasts: Array.from(
            document.querySelectorAll('.notifications-toasts .notification-list-item'),
          ).map((node) => (node.textContent || '').slice(0, 160)),
          activeTab: document.querySelector('.tabs-container .tab.active')?.textContent || null,
        }))
        let onDisk = 'absent'
        try {
          onDisk = (await readFile(join(config.workspaceDirectory, 'gamma.txt'), 'utf8')).slice(0, 80)
        } catch {
          onDisk = 'absent'
        }
        report.notes.push(
          `[A] delete-evidence ${JSON.stringify(evidence)} onDisk=${JSON.stringify(onDisk)}`,
        )
        throw error
      }
    })
    report.screenshots.push(await screenshot(pageA, config.screenshotDir, 'external-delete-A'))

    // Required: a connected view with unsaved work must refuse to close with the daemon's own DIRTY
    // reason (not an unrelated 4xx), and the refusal must keep the view alive for a later retry.
    await runStep(report, config.reportPath, 'dirtyCloseRejected', true, async () => {
      await dismissDialogsIfAny(pageB)
      const response = await fetch(
        `${config.baseUrl}/api/harness/environments/${config.environmentId}/ide/views/${second.viewId}`,
        { method: 'DELETE' },
      )
      const body = redact(await response.text().catch(() => ''))
      report.notes.push(`dirty close view B HTTP ${response.status} body=${body.slice(0, 200)}`)
      if (response.ok) {
        throw new Error('view B closed while its editor still had unsaved changes')
      }
      if (!/unsaved/i.test(body)) {
        throw new Error(
          `view B close was rejected for an unrelated reason, not unsaved changes: ${body.slice(0, 200)}`,
        )
      }
      return response.status
    })

    // Required: clear every unsaved buffer through real UI so the daemon will accept close. VS Code Hot
    // Exit restores dirty buffers across reload, so a page reload must never be used as a discard.
    await runStep(report, config.reportPath, 'clearDirtyBuffersNormalUI', true, async () => {
      const clearedA = await clearAllDirtyBuffers(pageA, report, 'A')
      const clearedB = await clearAllDirtyBuffers(pageB, report, 'B')
      return `A=${clearedA},B=${clearedB}`
    })

    // Required: close view B first and prove its terminal converged while view A stayed alive.
    await runStep(report, config.reportPath, 'closeViewB', true, async () => {
      const pidB = await readMarkerPid('B')
      const pidA = await readMarkerPid('A')
      if (!alive(pidB) || !alive(pidA)) {
        throw new Error('both view terminals must be alive before closing view B')
      }
      await closeView(second)
      await waitFor(() => !alive(pidB), 30_000, 'terminal B convergence')
      if (!alive(pidA)) {
        throw new Error('terminal A must stay alive while only view B is closed')
      }
      return 'bGone=true,aAlive=true'
    })

    // Required: close view A; its terminal must converge too.
    await runStep(report, config.reportPath, 'closeViewA', true, async () => {
      const pidA = await readMarkerPid('A')
      await closeView(first)
      await waitFor(() => !alive(pidA), 30_000, 'terminal A convergence')
      return 'aGone=true'
    })

    report.ok = true
  } finally {
    report.network = network
    await browser.close()
    await flush(report, config.reportPath)
  }
  return report
}

main()
  .then(() => {
    process.exitCode = 0
  })
  .catch((error) => {
    process.stderr.write(`ide-browser-acceptance aborted: ${redact(error)}\n`)
    process.exitCode = 1
  })
