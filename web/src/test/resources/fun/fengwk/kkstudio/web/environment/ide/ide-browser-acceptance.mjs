// Real-browser IDE acceptance driver (opt-in).
//
// Browser half of the Spring acceptance test: drives the real frozen code-server workbench served
// through the Java Studio same-origin data plane. Every view is opened by the Java
// `EnvironmentIdeService`; the window reaches code-server only through the real bootstrap -> cookie
// -> reverse-stream path. No daemon or data plane is faked.
//
// Each view is driven the way the product embeds it: a same-origin Studio parent fixture embeds the real
// one-time entry URL in an `<iframe>` that reproduces the production `IdeOverlayPanel` sandbox/allow
// contract. Playwright fulfills only that parent document; it fakes no Gateway, Daemon, code-server,
// API or file result. The workbench is then driven through the real child `Frame`
// (locators/evaluate) with keystrokes sent to the owning `Page`.
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

/** Screenshots the owning Studio parent page so the evidence shows the actual embedded iframe. */
async function screenshot(ownerPage, dir, name) {
  await mkdir(dir, { recursive: true })
  const path = join(dir, `${name}.png`)
  await ownerPage.screenshot({ path, fullPage: false })
  return path
}

/** Opens a workspace file through the Explorer tree (deterministic focus, unlike Quick Open). */
async function openFile(ide, name) {
  const row = ide.frame.locator('.explorer-folders-view .monaco-list-row', { hasText: name }).first()
  await row.waitFor({ state: 'visible', timeout: 30_000 })
  await row.click()
  await ide.frame.waitForSelector('.monaco-editor .view-lines', { timeout: 30_000 })
  await ide.frame.waitForTimeout(500)
}

async function focusEditor(ide) {
  const view = ide.frame.locator('.monaco-editor .view-lines').first()
  await view.waitFor({ state: 'visible', timeout: 15_000 })
  await view.click({ position: { x: 60, y: 30 } })
  const probe = await ide.frame.evaluate(() => ({
    active: document.activeElement ? document.activeElement.tagName : 'none',
    inEditor: !!(document.activeElement && document.activeElement.closest('.monaco-editor')),
    editors: document.querySelectorAll('.monaco-editor').length,
  }))
  if (!probe.inEditor) {
    // Second attempt: click the editor container itself, then re-probe.
    await ide.frame.locator('.monaco-editor').first().click({ position: { x: 80, y: 40 } })
  }
  return probe
}

/** Rendered editor text without touching focus; used to prove an edit landed in the buffer. */
async function editorText(ide) {
  return ide.frame.evaluate(() => {
    const lines = document.querySelector('.monaco-editor .view-lines')
    return lines ? lines.innerText : null
  })
}

/** Explicitly clicks a real workspace-trust dialog if code-server still shows one (bounded, not a fake pass). */
async function dismissTrustDialogIfPresent(ide, report) {
  const trustButton = ide.frame
    .locator('.monaco-dialog-box .monaco-button', { hasText: /trust|Trust/ })
    .first()
  try {
    await trustButton.waitFor({ state: 'visible', timeout: 5_000 })
  } catch {
    return false
  }
  await trustButton.click()
  if (report) {
    report.notes.push('[trust] dismissed a real workspace-trust dialog')
  }
  await ide.frame
    .waitForSelector('.monaco-dialog-box', { state: 'detached', timeout: 15_000 })
    .catch(() => {})
  return true
}

/** Reads the rendered editor text (no private globals) after scrolling to the end of the document. */
async function readActiveEditorText(ide) {
  await focusEditor(ide)
  await ide.ownerPage.keyboard.press('Control+End')
  return editorText(ide)
}

/** A visible workbench dialog or notification whose text matches the pattern. */
async function workbenchMessageMatches(ide, pattern) {
  return ide.frame.evaluate((source) => {
    const matcher = new RegExp(source, 'i')
    const nodes = document.querySelectorAll(
      '.monaco-dialog-box, .notifications-toasts .notification-list-item, .monaco-workbench .dialog-shadow',
    )
    return Array.from(nodes).some((node) => matcher.test(node.textContent || ''))
  }, pattern)
}

/** Dismisses a stray modal (e.g. a leftover save-conflict dialog) through real UI so later clicks land. */
async function dismissDialogsIfAny(ide) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const dialog = ide.frame.locator('.monaco-dialog-box').first()
    if (!(await dialog.isVisible().catch(() => false))) {
      return
    }
    const cancel = ide.frame.locator('.monaco-dialog-box .monaco-button', { hasText: /^Cancel$/i }).first()
    if (await cancel.isVisible().catch(() => false)) {
      await cancel.click()
    } else {
      await ide.ownerPage.keyboard.press('Escape')
    }
    await ide.frame.waitForTimeout(300)
  }
}

/** Runs a Command Palette command by its human-readable title. */
async function runCommandPalette(ide, title) {
  await dismissDialogsIfAny(ide)
  await ide.ownerPage.keyboard.press('Control+Shift+P')
  await ide.frame.waitForSelector('.quick-input-widget', { timeout: 15_000 })
  await ide.ownerPage.keyboard.type(title, { delay: 20 })
  const row = ide.frame.locator('.quick-input-list .monaco-list-row', { hasText: title }).first()
  await row.waitFor({ state: 'visible', timeout: 15_000 })
  await row.click()
  await ide.frame.waitForTimeout(400)
}

/**
 * Clears every unsaved buffer through real UI. `page.reload` is NOT a discard: VS Code Hot Exit
 * restores dirty buffers, so the daemon keeps rejecting close/directory-change. Closing all editors
 * and choosing "Don't Save" is the documented user action that actually drops the dirty state.
 */
async function clearAllDirtyBuffers(ide, report, label) {
  await runCommandPalette(ide, 'Close All Editors')
  const dontSave = ide.frame
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
  await ide.frame.waitForFunction(
    () => document.querySelectorAll('.tabs-container .tab').length === 0,
    undefined,
    { timeout: 15_000 },
  )
  return hadDialog
}

/** Escapes a value for a double-quoted HTML attribute of the parent harness. */
function escapeAttribute(value) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * Minimal Studio parent page that only embeds the real one-time entry URL. It reproduces the
 * production `IdeOverlayPanel` iframe contract verbatim (same `sandbox` and `allow`), is pinned to a
 * 1280x720 viewport, and fakes no data plane: the child frame still navigates the real bootstrap ->
 * cookie -> reverse-stream path. Only this parent HTML is fulfilled by the browser fixture;
 * all IDE requests, including bootstrap, assets and WebSockets, use the real Studio gateway.
 */
function harnessHtml(entryUrl) {
  return `<!doctype html>
<html lang="en">
<head><meta charset="utf-8" /><title>kk-studio IDE acceptance parent</title>
<style>
html, body { margin: 0; padding: 0; width: 1280px; height: 720px; overflow: hidden; }
iframe.ide-panel-iframe { display: block; width: 1280px; height: 720px; border: 0; }
</style></head>
<body>
<iframe class="ide-panel-iframe" data-testid="ide-iframe" title="IDE"
 src="${escapeAttribute(entryUrl)}"
 sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads allow-modals"
 allow="clipboard-read; clipboard-write"></iframe>
</body></html>`
}

/** Waits for the real IDE child frame to become the frame whose URL contains the environment IDE route. */
async function waitForIdeFrame(ownerPage, expectedPathPrefix) {
  await waitFor(
    () =>
      ownerPage
        .frames()
        .some(
          (frame) =>
            frame !== ownerPage.mainFrame() && frame.url().includes(expectedPathPrefix),
        ),
    60_000,
    `IDE iframe with path ${expectedPathPrefix}`,
  )
  return ownerPage
    .frames()
    .find(
      (frame) =>
        frame !== ownerPage.mainFrame() && frame.url().includes(expectedPathPrefix),
    )
}

/** Best-effort failure evidence for an embedded workbench: parent screenshot plus explorer DOM state. */
async function captureExplorerDiagnostics(report, ide, name, config) {
  try {
    report.screenshots.push(await screenshot(ide.ownerPage, config.screenshotDir, `failure-${name}`))
  } catch (error) {
    report.notes.push(`[${name}] failure screenshot failed: ${redact(error)}`)
  }
  try {
    const state = await ide.frame.evaluate(() => ({
      visibility: document.visibilityState,
      workbenches: document.querySelectorAll('.monaco-workbench').length,
      explorerFoldersViews: document.querySelectorAll('.explorer-folders-view').length,
      rows: Array.from(document.querySelectorAll('.explorer-folders-view .monaco-list-row'))
        .slice(0, 12)
        .map((node) => (node.textContent || '').trim()),
      body: document.body ? document.body.innerText.slice(0, 1200) : '',
    }))
    report.notes.push(`[${name}] explorer-diagnostics ${JSON.stringify(state)}`)
  } catch (error) {
    report.notes.push(`[${name}] explorer-diagnostics failed: ${redact(error)}`)
  }
}

/** Only this parent document is a fixture; no IDE/data-plane request matches it. */
const HARNESS_PATH = '/ide-acceptance-harness'

async function main() {
  const configPath = parseArgs(process.argv)
  const config = JSON.parse(await readFile(configPath, 'utf8'))
  const playwrightModule = requireEnv('KK_STUDIO_PLAYWRIGHT_MODULE_DIR')
  const { chromium } = require(playwrightModule)
  const report = { ok: false, steps: {}, notes: [], screenshots: [] }
  await mkdir(dirname(config.reportPath), { recursive: true })
  await flush(report, config.reportPath)

  let browser = null
  let primaryError = null
  /** Redacted network trace: path (query removed) plus status/content-encoding or failure reason. */
  const network = []
  const recordPath = (raw) => {
    const redacted = redactUrl(raw)
    const match = redacted.match(/^[a-z]+:\/\/[^/]+(\/[^\s]*)?$/i)
    return match ? match[1] || '/' : redacted
  }
  const attachNetworkTrace = (page, viewName) => {
    page.on('response', (response) => {
      if (network.filter((entry) => entry.view === viewName).length < 60) {
        network.push({
          view: viewName,
          path: recordPath(response.url()),
          status: response.status(),
          contentEncoding: response.headers()['content-encoding'] || null,
        })
      }
    })
    page.on('requestfailed', (request) => {
      if (network.filter((entry) => entry.view === viewName).length < 60) {
        network.push({
          view: viewName,
          path: recordPath(request.url()),
          failed: redact(request.failure() ? request.failure().errorText : 'unknown'),
        })
      }
    })
  }
  try {
    const parentOrigin = new URL(config.baseUrl).origin
    browser = await chromium.launch({
      args: [
        '--no-sandbox',
        '--disable-dev-shm-usage',
      ],
    })
    const ideViews = {}
    for (const view of config.views) {
      const context = await browser.newContext({
        ignoreHTTPSErrors: true,
        viewport: { width: 1280, height: 720 },
      })
      await context.route(`${parentOrigin}${HARNESS_PATH}/${view.name}`, (route) =>
        route.fulfill({ contentType: 'text/html; charset=utf-8', body: harnessHtml(view.entryUrl) }),
      )
      const ownerPage = await context.newPage()
      attachNetworkTrace(ownerPage, view.name)
      ownerPage.on('console', (message) => {
        if (message.type() === 'error') {
          report.notes.push(`[${view.name}] console: ${redact(message.text())}`)
        }
      })
      const ide = { name: view.name, ownerPage, frame: null }
      const idePath = `/environment-ide/${config.environmentId}/`
      await runStep(report, config.reportPath, `workbench${view.name}`, true, async () => {
        const response = await ownerPage.goto(`${parentOrigin}${HARNESS_PATH}/${view.name}`, {
          waitUntil: 'domcontentloaded',
          timeout: 60_000,
        })
        report.notes.push(`[${view.name}] parent harness status ${response ? response.status() : 'none'}`)
        ide.frame = await waitForIdeFrame(ownerPage, idePath)
        await ide.frame.waitForSelector('.monaco-workbench', { timeout: 60_000 })
        report.notes.push(`[${view.name}] embedded ${redactUrl(ide.frame.url())}`)
        await dismissTrustDialogIfPresent(ide, report)
        try {
          await ide.frame
            .locator('.explorer-folders-view .monaco-list-row', { hasText: 'alpha.txt' })
            .first()
            .waitFor({ state: 'visible', timeout: 60_000 })
        } catch (error) {
          await captureExplorerDiagnostics(report, ide, view.name, config)
          throw error
        }
        return {
          embedded: true,
          parentTransport: 'browser-fixture',
          parentOrigin,
          ideOrigin: new URL(ide.frame.url()).origin,
          frameUrl: redactUrl(ide.frame.url()),
        }
      })
      ideViews[view.name] = ide
      report.screenshots.push(await screenshot(ownerPage, config.screenshotDir, `workbench-${view.name}`))
    }

    const first = config.views[0]
    const second = config.views[1]
    const ideA = ideViews[first.name]
    const ideB = ideViews[second.name]

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
      // Editor UI and the remote extension host propagate discard asynchronously.
      // Only the exact DIRTY refusal is pending; all other errors fail immediately.
      await waitFor(async () => {
        const response = await fetch(
          `${config.baseUrl}/api/harness/environments/${config.environmentId}/ide/views/${view.viewId}`,
          { method: 'DELETE' },
        )
        const body = redact(await response.text())
        report.notes.push(
          `close view ${view.name} HTTP ${response.status}${body ? ' body=' + body.slice(0, 200) : ''}`,
        )
        if (response.ok) {
          return true
        }
        if (response.status === 400 && /save or discard unsaved changes before closing the view/i.test(body)) {
          return false
        }
        throw new Error(`close view ${view.name} HTTP ${response.status}: ${body.slice(0, 200)}`)
      }, 5_000, `view ${view.name} close after UI discard`)
    }

    // Required: edit + save in view A, proven by the editor buffer and by the on-disk file.
    await runStep(report, config.reportPath, 'editSaveA', true, async () => {
      await openFile(ideA, 'alpha.txt')
      const probe = await focusEditor(ideA)
      report.notes.push(`[A] editor focus ${JSON.stringify(probe)}`)
      await ideA.ownerPage.keyboard.press('Control+End')
      await ideA.ownerPage.keyboard.type('\nline-from-viewA', { delay: 20 })
      await waitFor(
        async () => ((await editorText(ideA)) || '').includes('line-from-viewA'),
        20_000,
        'edit reflected in the editor buffer',
      )
      await ideA.ownerPage.keyboard.press('Control+S')
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
    report.screenshots.push(await screenshot(ideA.ownerPage, config.screenshotDir, 'after-save-A'))

    // Required: view B (same disk) observes the externally changed file content.
    await runStep(report, config.reportPath, 'externalChangeB', true, async () => {
      await openFile(ideB, 'alpha.txt')
      const text = await readActiveEditorText(ideB)
      if (text === null || !text.includes('line-from-viewA')) {
        throw new Error('view B editor did not observe the on-disk change made in view A')
      }
      return true
    })
    report.screenshots.push(await screenshot(ideB.ownerPage, config.screenshotDir, 'external-change-B'))

    // Required: one real terminal per view; the browser shells write their real OS pid.
    for (const view of config.views) {
      const viewUrl = `${config.baseUrl}/api/harness/environments/${config.environmentId}/ide/views/${view.viewId}/terminal`
      const response = await fetch(viewUrl, { method: 'POST' })
      if (!response.ok) {
        throw new Error(`terminal ${view.name} HTTP ${response.status}`)
      }
    }
    for (const view of config.views) {
      const ide = ideViews[view.name]
      const marker = `terminal-${view.name}.pid`
      await runStep(report, config.reportPath, `terminal${view.name}`, true, async () => {
        await ide.frame.waitForSelector('.terminal-wrapper .xterm', { timeout: 45_000 })
        await ide.frame.locator('.terminal-wrapper .xterm').first().click({ position: { x: 40, y: 20 } })
        await ide.ownerPage.keyboard.type(`echo "${view.viewId} $$" > ${marker}`, { delay: 15 })
        await ide.ownerPage.keyboard.press('Enter')
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
      report.screenshots.push(
        await screenshot(ide.ownerPage, config.screenshotDir, `terminal-${view.name}`),
      )
    }

    // Required: an unsaved editor blocks a directory change with the daemon's own DIRTY refusal, and the
    // rejection reason must be the unsaved-changes refusal, not an unrelated 4xx.
    await runStep(report, config.reportPath, 'dirtyChangeDirectoryRejected', true, async () => {
      await openFile(ideA, 'alpha.txt')
      await focusEditor(ideA)
      await ideA.ownerPage.keyboard.type('\nunsaved-edit', { delay: 20 })
      await waitFor(
        async () => ((await editorText(ideA)) || '').includes('unsaved-edit'),
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
      if (response.status !== 400) {
        throw new Error(`dirty directory change must return HTTP 400, got ${response.status}`)
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
      await openFile(ideA, 'beta.txt')
      await focusEditor(ideA)
      await ideA.ownerPage.keyboard.press('Control+End')
      await ideA.ownerPage.keyboard.type('\nA-side', { delay: 20 })
      await waitFor(async () => ((await editorText(ideA)) || '').includes('A-side'), 20_000, 'A edit in buffer')
      await openFile(ideB, 'beta.txt')
      await focusEditor(ideB)
      await ideB.ownerPage.keyboard.press('Control+End')
      await ideB.ownerPage.keyboard.type('\nB-side', { delay: 20 })
      await waitFor(async () => ((await editorText(ideB)) || '').includes('B-side'), 20_000, 'B edit in buffer')
      await ideA.ownerPage.keyboard.press('Control+S')
      await waitFor(
        async () =>
          (await readFile(join(config.workspaceDirectory, 'beta.txt'), 'utf8').catch(() => '')).includes(
            'A-side',
          ),
        20_000,
        'view A version on disk',
      )
      await ideB.ownerPage.keyboard.press('Control+S')
      await waitFor(
        () => workbenchMessageMatches(ideB, 'conflict|newer version|overwrite|modified on disk'),
        20_000,
        'conflict prompt',
      )
      const onDisk = await readFile(join(config.workspaceDirectory, 'beta.txt'), 'utf8').catch(() => '')
      if (onDisk.includes('B-side')) {
        throw new Error('view B silently overwrote the newer on-disk content instead of reporting a conflict')
      }
      return 'conflict prompt shown; disk kept A-side'
    })
    report.screenshots.push(await screenshot(ideB.ownerPage, config.screenshotDir, 'conflict-B'))

    // Required: external delete of the open, dirty file, then save. This code-server/VS Code build does
    // not show a prompt for a deleted file (unlike a modified file): it recreates it on disk. Assert that
    // real outcome, which still exercises the full write path through the reverse data plane.
    await runStep(report, config.reportPath, 'externalDelete', true, async () => {
      await openFile(ideA, 'gamma.txt')
      await focusEditor(ideA)
      await ideA.ownerPage.keyboard.type('\ngamma-edit', { delay: 20 })
      await waitFor(
        async () => ((await editorText(ideA)) || '').includes('gamma-edit'),
        20_000,
        'gamma edit in buffer',
      )
      const { rm } = await import('node:fs/promises')
      await rm(join(config.workspaceDirectory, 'gamma.txt'), { force: true })
      await ideA.ownerPage.keyboard.press('Control+S')
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
        const evidence = await ideA.frame.evaluate(() => ({
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
    report.screenshots.push(await screenshot(ideA.ownerPage, config.screenshotDir, 'external-delete-A'))

    // Required: a connected view with unsaved work must refuse to close with the daemon's own DIRTY
    // reason (not an unrelated 4xx), and the refusal must keep the view alive for a later retry.
    await runStep(report, config.reportPath, 'dirtyCloseRejected', true, async () => {
      await dismissDialogsIfAny(ideB)
      const response = await fetch(
        `${config.baseUrl}/api/harness/environments/${config.environmentId}/ide/views/${second.viewId}`,
        { method: 'DELETE' },
      )
      const body = redact(await response.text().catch(() => ''))
      report.notes.push(`dirty close view B HTTP ${response.status} body=${body.slice(0, 200)}`)
      if (response.status !== 400) {
        throw new Error(`dirty view close must return HTTP 400, got ${response.status}`)
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
      const clearedA = await clearAllDirtyBuffers(ideA, report, 'A')
      const clearedB = await clearAllDirtyBuffers(ideB, report, 'B')
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
  } catch (error) {
    primaryError = error
  } finally {
    report.network = network
    const cleanupErrors = []
    // Browser shutdown also releases its real HTTP and WebSocket connections.
    if (browser) {
      try {
        await browser.close()
      } catch (error) {
        cleanupErrors.push(error)
      }
    }
    await flush(report, config.reportPath)
    // Never swallow cleanup failures: surface them (with the primary cause when both happened).
    if (primaryError && cleanupErrors.length > 0) {
      throw new Error(
        `${redact(primaryError)}\n[cleanup] ${cleanupErrors.map((error) => redact(error)).join('; ')}`,
      )
    }
    if (primaryError) {
      throw primaryError
    }
    if (cleanupErrors.length > 0) {
      throw cleanupErrors[0]
    }
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
