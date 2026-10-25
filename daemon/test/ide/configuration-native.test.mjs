/**
 * Opt-in integration: real workbench + Node extension host, no production probes.
 * Prerequisites: frontend npm ci and Playwright Chromium installation.
 * KK_STUDIO_IDE_ACCEPTANCE_DIR=<extracted code-server> node --test <this file>
 */
import assert from 'node:assert/strict'
import { once } from 'node:events'
import { test } from 'node:test'
import { createRequire } from 'node:module'
import { cp, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'

const runtime = process.env.KK_STUDIO_IDE_ACCEPTANCE_DIR
const requireFrontend = createRequire(new URL('../../../frontend/package.json', import.meta.url))
const defaults = {
  'task.autoDetect': 'off',
  'npm.autoDetect': 'off',
  'npm.fetchOnlinePackageInfo': false,
  'typescript.disableAutomaticTypeAcquisition': true,
}
const opposite = {
  'task.autoDetect': 'on',
  'npm.autoDetect': 'on',
  'npm.fetchOnlinePackageInfo': true,
  'typescript.disableAutomaticTypeAcquisition': false,
}
const stopGraceMs = 5_000
const stopForceMs = 5_000
const stopTimeoutCode = 'STOP_TIMEOUT'

// Register 'close' before the child can exit: exit precedes stdio close whenever a
// descendant inherits the pipes, and only 'close' proves those pipes are released.
function spawnTracked(file, args, options) {
  const child = spawn(file, args, options)
  const closed = new Promise((resolve, reject) => {
    child.once('close', (code, signal) => resolve({ code, signal }))
    child.once('error', reject)
  })
  // The same rejection stays observable at the await site without an interim
  // unhandled rejection if spawning fails before cleanup runs.
  closed.catch(() => {})
  return { child, closed }
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

// Terminate only this spawned child. A close timeout is a failure, never a joined child.
async function stopChild(child, closed, { graceMs = stopGraceMs, forceMs = stopForceMs } = {}) {
  if (child.exitCode === null && child.signalCode === null) {
    child.kill('SIGTERM')
  }
  try {
    return await bounded(closed, graceMs, 'child did not close after SIGTERM')
  } catch (error) {
    if (error.code !== stopTimeoutCode) throw error
  }
  child.kill('SIGKILL')
  return bounded(closed, forceMs, 'child did not close after SIGKILL')
}

test('native configuration contributions and explicit user precedence', { skip: !runtime, timeout: 180_000 }, async () => {
  const { chromium } = requireFrontend('@playwright/test')
  const root = await mkdtemp(join(tmpdir(), 'kk-native-defaults-'))
  let browser
  let rootRemovable = true
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
      const { child, closed } = spawnTracked(join(runtime, 'lib', process.platform === 'win32' ? 'node.exe' : 'node'), [
        join(runtime, 'out/node/entry.js'), '--auth', 'none', '--bind-addr', '127.0.0.1:0',
        '--user-data-dir', userData, '--extensions-dir', extensions,
        '--config', join(dir, 'config.yaml'), '--disable-telemetry', join(dir, 'workspace'),
      ], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, HOME: dir, XDG_DATA_HOME: dir, XDG_CONFIG_HOME: dir } })
      let output = ''
      child.stdout.on('data', chunk => { output += chunk })
      child.stderr.on('data', chunk => { output += chunk })
      let page
      try {
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
        snapshots.push(JSON.parse(snapshot))
      } finally {
        // Page failures must not bypass terminating and joining the real code-server child.
        const failures = []
        try {
          await page?.close()
        } catch (error) {
          failures.push(error)
        }
        try {
          await stopChild(child, closed)
        } catch (error) {
          // A still-running child may keep using the temporary profiles: never delete them.
          rootRemovable = false
          failures.push(error)
        }
        if (failures.length === 1) {
          throw failures[0]
        }
        if (failures.length > 1) {
          throw new AggregateError(failures, 'native acceptance cleanup failed')
        }
      }
    }
    const [baseline, contributed, user] = snapshots
    for (const [key, value] of Object.entries(defaults)) {
      assert.equal(contributed[key].defaultValue, value, key)
      assert.equal(contributed[key].effective, value, key)
      assert.equal(user[key].defaultValue, value, key)
      assert.equal(user[key].globalValue, opposite[key], key)
      assert.equal(user[key].effective, opposite[key], key)
    }
    assert.equal(baseline['git.autoRepositoryDetection'].defaultValue, true)
    assert.equal(baseline['git.autoRepositoryDetection'].effective, true)
    for (const snapshot of [contributed, user]) {
      assert.deepEqual(snapshot['git.autoRepositoryDetection'], baseline['git.autoRepositoryDetection'])
    }
    console.log(JSON.stringify({ baseline, contributed, user }, null, 2))
  } finally {
    try {
      await browser?.close()
    } finally {
      if (rootRemovable) {
        await rm(root, { recursive: true, force: true })
      } else {
        console.error(`preserving ${root}: a code-server child did not close`)
      }
    }
  }
})

// Public-boundary evidence: a real child exits while a real grandchild keeps stdout
// open. Only the ancestor's 'close' proves the pipe was released; stopChild must wait
// for it, and must fail explicitly instead of claiming a join while the pipe is held.
test('cleanup joins stdio close rather than child exit', { timeout: 20_000 }, async () => {
  const root = await mkdtemp(join(tmpdir(), 'kk-native-close-'))
  const gate = join(root, 'gate')
  const held = join(root, 'held')
  const holderScript = join(root, 'holder.cjs')
  const childScript = join(root, 'child.cjs')
  await writeFile(holderScript, `
const { existsSync, writeFileSync } = require('node:fs')
process.stdout.write('PIPE_HELD\\n')
writeFileSync(${JSON.stringify(held)}, '')
const timer = setInterval(() => {
  if (existsSync(${JSON.stringify(gate)})) {
    clearInterval(timer)
    process.exit(0)
  }
}, 10)
`)
  await writeFile(childScript, `
const { spawn } = require('node:child_process')
spawn(process.execPath, [${JSON.stringify(holderScript)}], { stdio: ['ignore', 'inherit', 'inherit'] })
process.exit(0)
`)
  const { child, closed } = spawnTracked(process.execPath, [childScript], { stdio: ['ignore', 'pipe', 'pipe'] })
  let output = ''
  child.stdout.on('data', chunk => { output += chunk })
  child.stderr.on('data', chunk => { output += chunk })
  try {
    await once(child, 'exit')
    const deadline = Date.now() + 10_000
    while (!output.includes('PIPE_HELD')) {
      assert.ok(Date.now() < deadline, output)
      await delay(10)
    }
    // 'exit' has fired; the gate proves the pipe-holder is still alive, so 'close' cannot fire.
    const pending = await Promise.race([
      closed.then(() => 'closed'),
      delay(150, 'pending'),
    ])
    assert.equal(pending, 'pending', output)
    await assert.rejects(
      stopChild(child, closed, { graceMs: 100, forceMs: 100 }),
      /did not close after SIGKILL/,
    )
    await writeFile(gate, '')
    const { code, signal } = await stopChild(child, closed, { graceMs: 3_000, forceMs: 3_000 })
    assert.equal(code, 0)
    assert.equal(signal, null)
  } finally {
    await writeFile(gate, '')
    try {
      await bounded(closed, 3_000, 'evidence child did not close')
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  }
})
