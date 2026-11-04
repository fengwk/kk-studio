/**
 * Opt-in: real daemon service, bridge, extension host and workbench; only the
 * code-server supervisor is faked. Build daemon first and set KK_STUDIO_IDE_ACCEPTANCE_DIR.
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createRequire } from 'node:module'
import { randomUUID } from 'node:crypto'
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { createIdeBridge } from '../../dist/ide/bridge.js'
import { createIdeService } from '../../dist/ide/service.js'

const runtime = process.env.KK_STUDIO_IDE_ACCEPTANCE_DIR
const requireFrontend = createRequire(new URL('../../../frontend/package.json', import.meta.url))

async function waitUntil(predicate, message, timeout = 30_000) {
  const deadline = Date.now() + timeout
  while (!await predicate()) {
    if (Date.now() >= deadline) throw new Error(message)
    await delay(50)
  }
}

async function bounded(promise, ms, message) {
  const controller = new AbortController()
  const timer = delay(ms, null, { signal: controller.signal }).then(() => { throw new Error(message) })
  try { return await Promise.race([promise, timer]) } finally { controller.abort() }
}

test('service native close honors Cancel, Save and Don’t Save through real bridge', {
  skip: !runtime, timeout: 180_000,
}, async () => {
  const { chromium } = requireFrontend('@playwright/test')
  const root = await mkdtemp(join(tmpdir(), 'kk-service-native-'))
  const workspace = join(root, 'workspace')
  const extensions = join(root, 'extensions')
  const userData = join(root, 'user-data')
  const bridge = await createIdeBridge()
  const calls = { registers: [], closes: [], stops: 0 }
  const session = { host: '127.0.0.1', port: 4100, cookie: 'test', pid: 1, generation: 1, managementToken: 'test' }
  const manager = {
    async ensureStarted() { return session },
    session() { return session },
    diagnostics() { return '' },
    onUnexpectedExit() {},
    async registerView(viewId, bindingEpoch, workspacePath) {
      calls.registers.push({ viewId, bindingEpoch, workspacePath })
      return { viewId, bindingEpoch, workspacePath, state: 'OPEN' }
    },
    async closeView(viewId, bindingEpoch, mode) {
      calls.closes.push({ viewId, bindingEpoch, mode })
      return { viewId, bindingEpoch, mode, state: 'CLOSED', cleanup: { complete: true } }
    },
    async stop() { calls.stops++ },
    async close() { calls.stops++ },
  }
  const config = {
    configPath: join(root, 'config.json'), dataDir: root, tokenPath: join(root, 'token'),
    studioUrl: 'https://studio.example', gatewayUri: 'wss://studio.example/api/harness/environment-daemon',
    bashExecutable: '/bin/bash', note: null, lsp: {}, distributionDir: join(root, 'distribution'),
  }
  const processes = { async start() { throw new Error('unexpected supervisor start') }, async close() {}, async trackDescendant() {} }
  let service, browser, page, child, closed
  let joined = true
  let output = ''
  const failures = []
  try {
    await mkdir(join(extensions, 'probe'), { recursive: true })
    await mkdir(join(userData, 'User'), { recursive: true })
    await mkdir(workspace, { recursive: true })
    await cp(new URL('../../ide-extension/', import.meta.url), join(extensions, 'bridge'), { recursive: true })
    await writeFile(join(userData, 'User/settings.json'), JSON.stringify({
      'security.workspace.trust.enabled': false, 'files.autoSave': 'off',
    }))
    await writeFile(join(extensions, 'probe/package.json'), JSON.stringify({
      name: 'service-native-probe', publisher: 'test', version: '1.0.0',
      engines: { vscode: '^1.80.0' }, main: './extension.cjs', activationEvents: ['*'],
      extensionKind: ['workspace'], capabilities: { untrustedWorkspaces: { supported: true } },
    }))
    // The probe only prepares an actual dirty editor and signals readiness; it never closes it.
    await writeFile(join(extensions, 'probe/extension.cjs'), `
const vscode = require('vscode');
const fs = require('node:fs/promises');
exports.activate = async () => {
  try {
    const name = await fs.readFile(${JSON.stringify(join(root, 'case'))}, 'utf8');
    const uri = vscode.Uri.file(${JSON.stringify(workspace)} + '/' + name + '.txt');
    const doc = await vscode.workspace.openTextDocument(uri);
    await vscode.window.showTextDocument(doc, { preview: false });
    const edit = new vscode.WorkspaceEdit();
    edit.replace(uri, new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length)), 'changed ' + name);
    if (!await vscode.workspace.applyEdit(edit) || !doc.isDirty) throw new Error('document not dirty');
    await fs.writeFile(${JSON.stringify(join(root, 'ready'))}, name);
  } catch (error) {
    await fs.writeFile(${JSON.stringify(join(root, 'error'))}, String(error.stack || error));
  }
};
`)
    service = await createIdeService(config, processes, { manager, bridge })
    child = spawn(join(runtime, 'lib', process.platform === 'win32' ? 'node.exe' : 'node'), [
      join(runtime, 'out/node/entry.js'), '--auth', 'none', '--bind-addr', '127.0.0.1:0',
      '--user-data-dir', userData, '--extensions-dir', extensions,
      '--config', join(root, 'code-server.yaml'), '--disable-telemetry',
    ], { detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'], env: {
      ...process.env, HOME: root, XDG_DATA_HOME: root, XDG_CONFIG_HOME: root,
      KK_STUDIO_IDE_BRIDGE_URL: bridge.url, KK_STUDIO_IDE_BRIDGE_TOKEN: bridge.secret,
    } })
    joined = false
    let spawnError
    child.once('error', error => { spawnError = error })
    closed = new Promise((resolve, reject) => child.once('close', (code, signal) =>
      spawnError ? reject(spawnError) : resolve({ code, signal })))
    closed.catch(() => {})
    child.stdout.on('data', chunk => { output += chunk })
    child.stderr.on('data', chunk => { output += chunk })
    browser = await chromium.launch({ headless: true })
    await waitUntil(() => /http:\/\/127\.0\.0\.1:\d+/.test(output), 'code-server did not listen', 45_000)
    const url = output.match(/http:\/\/127\.0\.0\.1:\d+/)[0]
    const signal = () => new AbortController().signal
    for (const [index, choice] of ['save', 'discard'].entries()) {
      await writeFile(join(root, 'case'), choice)
      await writeFile(join(workspace, `${choice}.txt`), 'before')
      const viewId = randomUUID()
      const documentId = randomUUID()
      const opened = await service.handle({
        action: 'OPEN_VIEW', viewId, documentId, claimId: randomUUID(), mode: 'create', directory: workspace,
        eventNodeId: randomUUID(), connectionId: randomUUID(),
      }, signal())
      assert.equal(opened.ok, true, JSON.stringify(opened))
      const binding = { viewId, documentId, bindingEpoch: opened.bindingEpoch }
      page = await browser.newPage()
      page.on('console', message => { output += '\nBROWSER: ' + message.text() })
      await page.goto(`${url}/?workspace=${encodeURIComponent(calls.registers.at(-1).workspacePath)}`)
      await waitUntil(async () => {
        const error = await readFile(join(root, 'error'), 'utf8').catch(e => e.code === 'ENOENT' ? '' : Promise.reject(e))
        if (error) throw new Error(error)
        return (await readFile(join(root, 'ready'), 'utf8').catch(e => e.code === 'ENOENT' ? '' : Promise.reject(e))) === choice
      }, 'dirty editor not ready')
      await bounded(bridge.waitForConnection(viewId, signal()), 15_000, 'bridge did not connect')
      if (index === 0) {
        // Repeating the real command should leave the native sidebar and panel closed.
        for (let i = 0; i < 2; i++) {
          assert.deepEqual(await service.handle({ action: 'COLLAPSE_MOBILE', ...binding }, signal()), { ok: true, viewId })
          await page.waitForFunction(() => ['.part.sidebar', '.part.panel'].every(selector => {
            const element = document.querySelector(selector)
            return !element || element.getBoundingClientRect().width === 0 || element.getBoundingClientRect().height === 0
          }))
        }
      }
      const close = () => service.handle({ action: 'CLOSE_VIEW', ...binding, discard: false }, signal())
      const cancelled = close()
      await page.getByRole('button', { name: 'Cancel', exact: true }).click({ timeout: 10_000 })
      assert.equal((await cancelled).code, 'CANCELLED')
      const listed = await service.handle({ action: 'LIST_VIEWS' }, signal())
      assert.equal(listed.views.find(view => view.viewId === viewId)?.state, 'OPEN')
      assert.equal(calls.closes.length, index)
      assert.equal(calls.stops, 0)
      assert.equal(await readFile(join(workspace, `${choice}.txt`), 'utf8'), 'before')
      const accepted = close()
      await page.getByRole('button', { name: choice === 'save' ? /^Save$/ : /don.t save/i }).click({ timeout: 10_000 })
      assert.deepEqual(await accepted, { ok: true, viewId })
      assert.equal((await service.handle({ action: 'LIST_VIEWS' }, signal())).views.length, 0)
      assert.deepEqual(calls.closes.at(-1), { viewId, bindingEpoch: opened.bindingEpoch, mode: 'discard' })
      assert.equal(await readFile(join(workspace, `${choice}.txt`), 'utf8'), choice === 'save' ? 'changed save' : 'before')
      await page.close()
      page = undefined
    }
  } catch (error) {
    failures.push(new Error(`native service test failed; code-server output:\n${output}`, { cause: error }))
  } finally {
    try { if (page) await bounded(page.close(), 5000, 'page close timed out') } catch (error) { failures.push(error) }
    try { if (browser) await bounded(browser.close(), 5000, 'browser close timed out') } catch (error) { failures.push(error) }
    try { if (service) await bounded(service.close(), 15000, 'service close timed out'); else await bridge.close() } catch (error) { failures.push(error) }
    if (child) {
      const kill = sig => {
        try { process.platform === 'win32' ? child.kill(sig) : process.kill(-child.pid, sig) }
        catch (error) { if (error.code !== 'ESRCH') throw error }
      }
      try {
        kill('SIGTERM')
        try { await bounded(closed, 5000, 'child close timed out') }
        catch { kill('SIGKILL'); await bounded(closed, 5000, 'child did not close after SIGKILL') }
        joined = true
      } catch (error) { failures.push(error) }
    }
    if (joined) {
      try { await rm(root, { recursive: true, force: true }) } catch (error) { failures.push(error) }
    } else {
      console.error(`preserving ${root}: code-server did not close`)
    }
  }
  if (failures.length) throw new AggregateError(failures, 'service native integration failed')
})
