/**
 * Opt-in integration: real workbench + Node extension host, no production probes.
 * Prerequisites: frontend npm ci and Playwright Chromium installation.
 * KK_STUDIO_IDE_ACCEPTANCE_DIR=<extracted code-server> node --test <this file>
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createRequire } from 'node:module'
import { cp, mkdir, mkdtemp, readFile, readdir, rm, writeFile, access } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { setTimeout as delay } from 'node:timers/promises'

const runtime = process.env.KK_STUDIO_IDE_ACCEPTANCE_DIR
const requireFrontend = createRequire(new URL('../../../frontend/package.json', import.meta.url))
const defaults = {
  'workbench.colorTheme': 'Dark Modern',
  'window.density.editorTabHeight': 'compact',
  'chat.disableAIFeatures': true,
  'extensions.ignoreRecommendations': true,
  'files.watcherExclude': { '**': true },
  'task.autoDetect': 'off',
  'npm.autoDetect': 'off',
  'npm.fetchOnlinePackageInfo': false,
  'typescript.disableAutomaticTypeAcquisition': true,
}
const opposite = {
  'workbench.colorTheme': 'Light Modern',
  'window.density.editorTabHeight': 'default',
  'chat.disableAIFeatures': false,
  'extensions.ignoreRecommendations': false,
  'files.watcherExclude': { '**': false },
  'task.autoDetect': 'on',
  'npm.autoDetect': 'on',
  'npm.fetchOnlinePackageInfo': true,
  'typescript.disableAutomaticTypeAcquisition': false,
}
const stopGraceMs = 5_000
const stopForceMs = 5_000
const stopTimeoutCode = 'STOP_TIMEOUT'

// Register 'exit' and 'close' before any await: exit can precede stdio close when a
// descendant inherits the pipes. `closed` settles only on the real 'close' event; a
// spawn error is recorded and surfaced after that close, never as a fake join.
function spawnTracked(file, args, options) {
  const child = spawn(file, args, options)
  let spawnError
  const exited = new Promise(resolve => {
    child.once('exit', (code, signal) => resolve({ code, signal }))
  })
  const closed = new Promise((resolve, reject) => {
    child.once('error', error => { spawnError = error })
    child.once('close', (code, signal) => {
      if (spawnError) {
        reject(spawnError)
      } else {
        resolve({ code, signal })
      }
    })
  })
  // Keep a later spawn failure observable without an interim unhandled rejection.
  closed.catch(() => {})
  return { child, closed, exited }
}

// Bound a promise without leaving its losing timer behind.
async function bounded(promise, ms, message) {
  const controller = new AbortController()
  const timeout = delay(ms, null, { signal: controller.signal }).then(() => {
    const error = new Error(message)
    error.code = stopTimeoutCode
    throw error
  })
  timeout.catch(() => {})
  try {
    return await Promise.race([promise, timeout])
  } finally {
    controller.abort()
  }
}

async function waitUntil(predicate, ms, message) {
  const deadline = Date.now() + ms
  while (!(await predicate())) {
    if (Date.now() >= deadline) throw new Error(message)
    await delay(10)
  }
}

async function pathExists(path) {
  try {
    await access(path)
    return true
  } catch (error) {
    if (error.code === 'ENOENT') return false
    throw error
  }
}

// Terminate only this spawned child. A close timeout is a failure, never a joined child.
// Non-timeout errors surface only after 'close'; `childClosed` records that fact so the
// caller can clean the profile while still reporting the original failure.
async function stopChild(child, closed, { graceMs = stopGraceMs, forceMs = stopForceMs } = {}) {
  if (child.exitCode === null && child.signalCode === null) {
    child.kill('SIGTERM')
  }
  try {
    return await bounded(closed, graceMs, 'child did not close after SIGTERM')
  } catch (error) {
    if (error.code !== stopTimeoutCode) {
      throw Object.assign(error, { childClosed: true })
    }
  }
  child.kill('SIGKILL')
  try {
    return await bounded(closed, forceMs, 'child did not close after SIGKILL')
  } catch (error) {
    if (error.code !== stopTimeoutCode) {
      throw Object.assign(error, { childClosed: true })
    }
    throw Object.assign(error, { childClosed: false })
  }
}

test('native configuration contributions and explicit user precedence', { skip: !runtime, timeout: 180_000 }, async () => {
  const { chromium } = requireFrontend('@playwright/test')
  const root = await mkdtemp(join(tmpdir(), 'kk-native-defaults-'))
  let browser
  let rootRemovable = true
  const failures = []
  try {
    browser = await chromium.launch({ headless: true })
    const snapshots = []
    for (const mode of ['baseline', 'defaults', 'user']) {
      const dir = join(root, mode)
      const extensions = join(dir, 'extensions')
      const userData = join(dir, 'user-data')
      const result = join(dir, 'snapshot.json')
      await mkdir(join(extensions, 'probe'), { recursive: true })
      await mkdir(join(userData, 'User'), { recursive: true })
      await mkdir(join(dir, 'workspace'), { recursive: true })
      if (mode !== 'baseline') {
        await cp(new URL('../../ide-extension/', import.meta.url), join(extensions, 'bridge'), { recursive: true })
      }
      // Only this disposable profile is written; test both globalValue and effective value.
      // Trust is not under test: restricted workspaces disable Git/TS and the bridge itself.
      await writeFile(join(userData, 'User/settings.json'), JSON.stringify({
        'security.workspace.trust.enabled': false,
        ...(mode === 'user' ? opposite : {}),
      }))
      await writeFile(join(extensions, 'probe/package.json'), JSON.stringify({
        name: 'configuration-probe', publisher: 'test', version: '1.0.0',
        engines: { vscode: '^1.80.0' }, main: './extension.cjs', activationEvents: ['*'],
        extensionKind: ['workspace'], capabilities: { untrustedWorkspaces: { supported: true } },
      }))
      await writeFile(join(extensions, 'probe/extension.cjs'), `
const vscode = require('vscode');
exports.activate = async () => {
  const deadline = Date.now() + 20000;
  while (vscode.workspace.getConfiguration().inspect('git.autoRepositoryDetection')?.defaultValue === undefined) {
    if (Date.now() > deadline) throw new Error('Git configuration never registered');
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  const config = vscode.workspace.getConfiguration();
  const values = Object.fromEntries(${JSON.stringify([...Object.keys(defaults), 'git.autoRepositoryDetection'])}
    .map(key => [key, { ...config.inspect(key), effective: config.get(key) }]));
  require('node:fs').writeFileSync(${JSON.stringify(result)}, JSON.stringify(values));
};
`)
      let child
      let closed
      let childClosed = true
      let page
      try {
        ;({ child, closed } = spawnTracked(join(runtime, 'lib', process.platform === 'win32' ? 'node.exe' : 'node'), [
          join(runtime, 'out/node/entry.js'), '--auth', 'none', '--bind-addr', '127.0.0.1:0',
          '--user-data-dir', userData, '--extensions-dir', extensions,
          '--config', join(dir, 'config.yaml'), '--disable-telemetry', join(dir, 'workspace'),
        ], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, HOME: dir, XDG_DATA_HOME: dir, XDG_CONFIG_HOME: dir } }))
        let output = ''
        child.stdout.on('data', chunk => { output += chunk })
        child.stderr.on('data', chunk => { output += chunk })
        page = await browser.newPage()
        page.on('console', message => { output += '\nBROWSER: ' + message.text() })
        const deadline = Date.now() + 45_000
        while (!/http:\/\/127\.0\.0\.1:\d+/.test(output)) {
          assert.equal(child.exitCode, null, output)
          assert.ok(Date.now() < deadline, output)
          await delay(50)
        }
        await page.goto(`${output.match(/http:\/\/127\.0\.0\.1:\d+/)[0]}/?folder=${encodeURIComponent(join(dir, 'workspace'))}`)
        let snapshot
        while (!snapshot) {
          if (Date.now() >= deadline) {
            const files = await readdir(userData, { recursive: true })
            for (const file of files.filter(file => file.endsWith('.log'))) {
              output += await readFile(join(userData, file), 'utf8')
            }
            assert.fail(`extension host did not report: ${output}`)
          }
          snapshot = await readFile(result, 'utf8').catch(error => {
            if (error.code !== 'ENOENT') throw error
          })
          if (!snapshot) await delay(100)
        }
        if (mode === 'defaults') {
          // A registered value alone is insufficient: assert the actual Dark Modern theme is applied.
          await page.waitForFunction(() =>
            getComputedStyle(document.querySelector('.monaco-workbench')).getPropertyValue('--vscode-editor-background').trim().toLowerCase() === '#1f1f1f',
          null, { timeout: 10_000 }).catch(async error => {
            const theme = await page.locator('.monaco-workbench').evaluate(element => ({
              classes: element.className,
              background: getComputedStyle(element).getPropertyValue('--vscode-editor-background'),
            }))
            throw new Error(`theme not applied: ${JSON.stringify(theme)}`, { cause: error })
          })
        }
        snapshots.push(JSON.parse(snapshot))
      } catch (error) {
        failures.push(error)
      } finally {
        // A failed or stalled page close must not bypass joining the real code-server child.
        try {
          if (page) {
            await bounded(page.close(), stopGraceMs, 'page did not close')
          }
        } catch (error) {
          failures.push(error)
        }
        if (child) {
          try {
            await stopChild(child, closed)
            childClosed = true
          } catch (error) {
            // Only the real 'close' event may make the profiles removable.
            childClosed = error.childClosed === true
            failures.push(error)
          }
        }
        if (!childClosed) {
          rootRemovable = false
        }
      }
      if (failures.length > 0) {
        break
      }
    }
    if (failures.length === 0) {
      const [baseline, contributed, user] = snapshots
      for (const [key, value] of Object.entries(defaults)) {
        if (key === 'files.watcherExclude') {
          // Object-valued settings merge with VS Code's other default exclusions.
          assert.equal(contributed[key].defaultValue['**'], true, key)
          assert.equal(contributed[key].effective['**'], true, key)
          assert.equal(user[key].defaultValue['**'], true, key)
          assert.deepEqual(user[key].globalValue, opposite[key], key)
          assert.equal(user[key].effective['**'], false, key)
        } else {
          assert.equal(contributed[key].defaultValue, value, key)
          assert.equal(contributed[key].effective, value, key)
          assert.equal(user[key].defaultValue, value, key)
          assert.equal(user[key].globalValue, opposite[key], key)
          assert.equal(user[key].effective, opposite[key], key)
        }
      }
      assert.equal(baseline['git.autoRepositoryDetection'].defaultValue, true)
      assert.equal(baseline['git.autoRepositoryDetection'].effective, true)
      for (const snapshot of [contributed, user]) {
        assert.deepEqual(snapshot['git.autoRepositoryDetection'], baseline['git.autoRepositoryDetection'])
      }
      console.log(JSON.stringify({ baseline, contributed, user }, null, 2))
    }
  } catch (error) {
    failures.push(error)
  } finally {
    try {
      if (browser) {
        await bounded(browser.close(), stopGraceMs, 'browser did not close')
      }
    } catch (error) {
      failures.push(error)
    }
    if (rootRemovable) {
      try {
        await rm(root, { recursive: true, force: true })
      } catch (error) {
        failures.push(error)
      }
    } else {
      console.error(`preserving ${root}: a code-server child did not close`)
    }
  }
  if (failures.length === 1) {
    throw failures[0]
  }
  if (failures.length > 1) {
    throw new AggregateError(failures, 'native acceptance failed')
  }
})

// Exercise the actual bridge against Code OSS: its non-recursive file watches must feed the
// workbench file service, and native close must await the user's choice instead of prechecking.
test('opened files reload, dirty saves conflict, and native close can be cancelled or accepted',
  { skip: !runtime, timeout: 120_000 }, async () => {
    const { chromium } = requireFrontend('@playwright/test')
    const root = await mkdtemp(join(tmpdir(), 'kk-native-bridge-'))
    const workspace = join(root, 'workspace')
    const extensions = join(root, 'extensions')
    const userData = join(root, 'user-data')
    const result = join(root, 'result.json')
    const file = join(workspace, 'open[1].txt')
    let browser
    let page
    let child
    let closed
    let childClosed = true
    let stream
    const replies = new Map()
    const server = createServer((request, response) => {
      if (request.url.startsWith('/bridge/stream?')) {
        stream = response
        response.writeHead(200, { 'content-type': 'application/x-ndjson' })
        response.flushHeaders()
      } else if (request.url === '/bridge/result') {
        let body = ''
        request.setEncoding('utf8')
        request.on('data', chunk => { body += chunk })
        request.on('end', () => {
          const reply = JSON.parse(body)
          replies.set(reply.id, reply)
          response.writeHead(200).end()
        })
      } else {
        response.writeHead(404).end()
      }
    })
    const failures = []
    try {
      await mkdir(join(extensions, 'probe'), { recursive: true })
      await mkdir(join(userData, 'User'), { recursive: true })
      await mkdir(workspace, { recursive: true })
      await writeFile(file, 'initial')
      await cp(new URL('../../ide-extension/', import.meta.url), join(extensions, 'bridge'), { recursive: true })
      await writeFile(join(userData, 'User/settings.json'), JSON.stringify({
        'security.workspace.trust.enabled': false,
        'files.autoSave': 'off',
      }))
      await writeFile(join(extensions, 'probe/package.json'), JSON.stringify({
        name: 'bridge-probe', publisher: 'test', version: '1.0.0',
        engines: { vscode: '^1.80.0' }, main: './extension.cjs', activationEvents: ['*'],
        extensionKind: ['workspace'], capabilities: { untrustedWorkspaces: { supported: true } },
      }))
      await writeFile(join(extensions, 'probe/extension.cjs'), `
const vscode = require('vscode');
const fs = require('node:fs/promises');
const path = require('node:path');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
exports.activate = async () => {
  try {
    await vscode.extensions.getExtension('kk-studio.kk-studio-ide-bridge').activate();
    const uri = vscode.Uri.file(${JSON.stringify(file)});
    const doc = await vscode.workspace.openTextDocument(uri);
    await vscode.window.showTextDocument(doc, { preview: false });
    // Retry until the real watcher delivers both an atomic replacement and a later write.
    const waitForReload = async (text, write) => {
      const deadline = Date.now() + 15000;
      while (doc.getText() !== text) {
        if (Date.now() > deadline) throw new Error('clean document did not reload: ' + text);
        await write();
        const nextWrite = Date.now() + 1000;
        while (doc.getText() !== text && Date.now() < nextWrite) await delay(25);
      }
    };
    await waitForReload('atomic replacement', async () => {
      const replacement = uri.fsPath + '.tmp';
      await fs.writeFile(replacement, 'atomic replacement');
      await fs.rename(replacement, uri.fsPath);
    });
    await waitForReload('external clean', () => fs.writeFile(uri.fsPath, 'external clean'));
    const cleanStat = await fs.stat(uri.fsPath);
    const edit = new vscode.WorkspaceEdit();
    edit.replace(uri, new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length)), 'unsaved');
    if (!await vscode.workspace.applyEdit(edit)) throw new Error('edit failed');
    // A test-only observer acknowledges the dirty-file event rather than relying on a sleep.
    const observer = vscode.workspace.createFileSystemWatcher(
      new vscode.RelativePattern(vscode.Uri.file(path.dirname(uri.fsPath)), '*'));
    let changed = false;
    observer.onDidChange(event => { if (event.fsPath === uri.fsPath) changed = true; });
    const dirtyDeadline = Date.now() + 10000;
    let dirtyStat;
    do {
      if (Date.now() > dirtyDeadline) throw new Error('dirty-file event was not observed');
      await fs.writeFile(uri.fsPath, 'external newer content');
      dirtyStat = await fs.stat(uri.fsPath);
      const nextWrite = Date.now() + 1000;
      while (!changed && Date.now() < nextWrite) await delay(25);
    } while (!changed || dirtyStat.mtimeMs <= cleanStat.mtimeMs);
    observer.dispose();
    const retained = doc.isDirty && doc.getText() === 'unsaved';
    const saved = await doc.save();
    await fs.writeFile(${JSON.stringify(result)}, JSON.stringify({
      retained, saved, dirty: doc.isDirty, text: doc.getText(), disk: await fs.readFile(uri.fsPath, 'utf8')
    }));
    // A second dirty file checks the native Save choice independently of the conflict.
    const gate = ${JSON.stringify(join(root, 'next'))};
    const nextDeadline = Date.now() + 30000;
    while (!await fs.access(gate).then(() => true, () => false)) {
      if (Date.now() > nextDeadline) throw new Error('next step was not requested');
      await delay(50);
    }
    const nextUri = vscode.Uri.file(${JSON.stringify(join(workspace, 'save.txt'))});
    await fs.writeFile(nextUri.fsPath, 'before');
    const nextDoc = await vscode.workspace.openTextDocument(nextUri);
    await vscode.window.showTextDocument(nextDoc, { preview: false });
    const nextEdit = new vscode.WorkspaceEdit();
    nextEdit.replace(nextUri, new vscode.Range(nextDoc.positionAt(0), nextDoc.positionAt(nextDoc.getText().length)), 'saved by user');
    if (!await vscode.workspace.applyEdit(nextEdit)) throw new Error('second edit failed');
    await fs.writeFile(${JSON.stringify(join(root, 'ready'))}, '');
  } catch (error) {
    await fs.writeFile(${JSON.stringify(result)}, JSON.stringify({ error: String(error.stack || error) }));
  }
};
`)
      await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
      const bridgeUrl = `http://127.0.0.1:${server.address().port}`
      ;({ child, closed } = spawnTracked(join(runtime, 'lib', process.platform === 'win32' ? 'node.exe' : 'node'), [
        join(runtime, 'out/node/entry.js'), '--auth', 'none', '--bind-addr', '127.0.0.1:0',
        '--user-data-dir', userData, '--extensions-dir', extensions,
        '--config', join(root, 'config.yaml'), '--disable-telemetry', workspace,
      ], { stdio: ['ignore', 'pipe', 'pipe'], env: {
        ...process.env, HOME: root, XDG_DATA_HOME: root, XDG_CONFIG_HOME: root,
        KK_STUDIO_IDE_BRIDGE_URL: bridgeUrl, KK_STUDIO_IDE_BRIDGE_TOKEN: 'test-bridge-token',
      } }))
      childClosed = false
      let output = ''
      child.stdout.on('data', chunk => { output += chunk })
      child.stderr.on('data', chunk => { output += chunk })
      browser = await chromium.launch({ headless: true })
      page = await browser.newPage()
      page.on('console', message => { output += '\nBROWSER: ' + message.text() })
      await waitUntil(() => /http:\/\/127\.0\.0\.1:\d+/.test(output), 45_000, 'code-server did not listen')
      await page.goto(`${output.match(/http:\/\/127\.0\.0\.1:\d+/)[0]}/?folder=${encodeURIComponent(workspace)}`)
      await waitUntil(() => pathExists(result), 35_000, 'probe did not report: ' + output)
      const observation = JSON.parse(await readFile(result, 'utf8'))
      assert.equal(observation.error, undefined, observation.error)
      assert.deepEqual(observation, {
        retained: true, saved: false, dirty: true, text: 'unsaved', disk: 'external newer content',
      })
      await waitUntil(() => Boolean(stream), 10_000, 'bridge did not connect')
      stream.write(`${JSON.stringify({ type: 'command', id: 'cancel', op: 'closeView' })}\n`)
      await page.getByRole('button', { name: 'Cancel', exact: true }).click({ timeout: 10_000 })
      await waitUntil(() => replies.has('cancel'), 10_000, 'bridge did not report cancellation')
      assert.equal(replies.get('cancel').ok, false)
      assert.equal(replies.get('cancel').code, 'DIRTY')
      assert.equal(await readFile(file, 'utf8'), 'external newer content')
      stream.write(`${JSON.stringify({ type: 'command', id: 'close', op: 'closeView' })}\n`)
      await page.getByRole('button', { name: /don.t save/i }).click({ timeout: 10_000 })
      await waitUntil(() => replies.has('close'), 10_000, 'bridge did not report close')
      assert.equal(replies.get('close').ok, true)
      assert.equal(await readFile(file, 'utf8'), 'external newer content')
      await writeFile(join(root, 'next'), '')
      await waitUntil(() => pathExists(join(root, 'ready')), 10_000, 'second dirty document was not ready')
      stream.write(`${JSON.stringify({ type: 'command', id: 'save', op: 'closeView' })}\n`)
      await page.getByRole('button', { name: 'Save', exact: true }).click({ timeout: 10_000 })
      await waitUntil(() => replies.has('save'), 10_000, 'bridge did not report save and close')
      assert.equal(replies.get('save').ok, true)
      assert.equal(await readFile(join(workspace, 'save.txt'), 'utf8'), 'saved by user')
    } catch (error) {
      failures.push(error)
    } finally {
      try { if (page) await bounded(page.close(), stopGraceMs, 'page did not close') } catch (error) { failures.push(error) }
      try { if (browser) await bounded(browser.close(), stopGraceMs, 'browser did not close') } catch (error) { failures.push(error) }
      if (child) {
        try {
          await stopChild(child, closed)
          childClosed = true
        } catch (error) {
          childClosed = error.childClosed === true
          failures.push(error)
        }
      }
      if (stream) stream.destroy()
      await new Promise(resolve => server.close(resolve))
      if (childClosed) await rm(root, { recursive: true, force: true })
      else console.error(`preserving ${root}: a code-server child did not close`)
    }
    if (failures.length === 1) throw failures[0]
    if (failures.length > 1) throw new AggregateError(failures, 'native bridge acceptance failed')
  })

// Public-boundary evidence: a real child exits while a tracked grandchild keeps stdout
// open. The ACK instrument proves the holder is alive, so 'close' cannot have fired only
// because time passed. The old policy joined at 'exit'; the assertion below is what turns
// red under that policy, while the new path must wait for the actual 'close' event.
test('cleanup joins stdio close rather than child exit', { timeout: 30_000 }, async () => {
  let root
  let gate
  let probe
  let ack
  let pidFile
  let child
  let closed
  let exited
  let holderPid
  let rootRemovable = true
  let failure
  try {
    root = await mkdtemp(join(tmpdir(), 'kk-native-close-'))
    gate = join(root, 'gate')
    probe = join(root, 'probe')
    ack = join(root, 'ack')
    pidFile = join(root, 'holder-pid')
    const holderScript = join(root, 'holder.cjs')
    const childScript = join(root, 'child.cjs')
    await writeFile(holderScript, `
const { existsSync, writeFileSync } = require('node:fs')
process.stdout.write('PIPE_HELD ' + process.pid + '\\n')
let acknowledged = false
const timer = setInterval(() => {
  if (!acknowledged && existsSync(${JSON.stringify(probe)})) {
    writeFileSync(${JSON.stringify(ack)}, '')
    acknowledged = true
  }
  if (existsSync(${JSON.stringify(gate)})) {
    clearInterval(timer)
    process.exit(0)
  }
}, 10)
// Bounded self-cleanup if the parent never opens the gate.
setTimeout(() => process.exit(2), 15000).unref()
`)
    await writeFile(childScript, `
const { spawn } = require('node:child_process')
const { writeFileSync } = require('node:fs')
const holder = spawn(process.execPath, [${JSON.stringify(holderScript)}], { stdio: ['ignore', 'inherit', 'inherit'] })
writeFileSync(${JSON.stringify(pidFile)}, String(holder.pid))
process.exit(0)
`)
    ;({ child, closed, exited } = spawnTracked(process.execPath, [childScript], { stdio: ['ignore', 'pipe', 'pipe'] }))
    let output = ''
    child.stdout.on('data', chunk => { output += chunk })
    child.stderr.on('data', chunk => { output += chunk })
    let closeSettled = false
    closed.then(() => { closeSettled = true }, () => { closeSettled = true })
    // Bound the exit observation so a broken child still reaches cleanup.
    await bounded(exited, 10_000, 'direct child did not exit')
    await waitUntil(() => /PIPE_HELD \d+/.test(output), 10_000, output)
    holderPid = Number(output.match(/PIPE_HELD (\d+)/)[1])
    // The direct child has exited; the pipe holder is still alive.
    const directJoin = () => exited
    await directJoin()
    await writeFile(probe, '')
    await waitUntil(() => pathExists(ack), 10_000, 'pipe holder did not acknowledge')
    assert.equal(closeSettled, false, 'close must remain pending while the pipe is held')
    // The bounded timeout is an expected negative while the gate stays closed; it is not
    // the exit evidence itself.
    await assert.rejects(
      stopChild(child, closed, { graceMs: 100, forceMs: 100 }),
      /did not close after SIGKILL/,
    )
    assert.equal(closeSettled, false)
    await writeFile(gate, '')
    const { code, signal } = await stopChild(child, closed, { graceMs: 3_000, forceMs: 3_000 })
    assert.equal(code, 0)
    assert.equal(signal, null)
    assert.equal(closeSettled, true)
  } catch (error) {
    failure = error
  } finally {
    const cleanupFailures = []
    let childClosed = !child
    if (gate) {
      try {
        await writeFile(gate, '')
      } catch (error) {
        cleanupFailures.push(error)
      }
    }
    if (child) {
      try {
        await stopChild(child, closed, { graceMs: 3_000, forceMs: 3_000 })
        childClosed = true
      } catch (error) {
        childClosed = error.childClosed === true
        cleanupFailures.push(error)
      }
    }
    if (!childClosed) {
      if (holderPid === undefined && pidFile) {
        try {
          const value = Number(await readFile(pidFile, 'utf8'))
          if (Number.isInteger(value) && value > 0) {
            holderPid = value
          }
        } catch (error) {
          if (error.code !== 'ENOENT') cleanupFailures.push(error)
        }
      }
      if (holderPid === undefined) {
        cleanupFailures.push(new Error('pipe holder PID unavailable'))
      } else {
        try {
          process.kill(holderPid, 'SIGKILL')
        } catch (error) {
          if (error.code !== 'ESRCH') cleanupFailures.push(error)
        }
      }
      try {
        await bounded(closed, 3_000, 'child did not close after killing the pipe holder')
        childClosed = true
      } catch (error) {
        if (error.code !== stopTimeoutCode) childClosed = true
        cleanupFailures.push(error)
      }
    }
    if (!childClosed) {
      rootRemovable = false
    }
    if (root && rootRemovable) {
      try {
        await rm(root, { recursive: true, force: true })
      } catch (error) {
        cleanupFailures.push(error)
      }
    } else if (root) {
      console.error(`preserving ${root}: evidence child did not close`)
    }
    if (cleanupFailures.length > 0) {
      const cleanupError = cleanupFailures.length === 1
        ? cleanupFailures[0]
        : new AggregateError(cleanupFailures, 'evidence cleanup failed')
      failure = failure
        ? new AggregateError([failure, cleanupError], 'evidence failed')
        : cleanupError
    }
  }
  if (failure) throw failure
})
