/**
 * Opt-in integration: real workbench + Node extension host, no production probes.
 * Prerequisites: frontend npm ci and Playwright Chromium installation.
 * KK_STUDIO_IDE_ACCEPTANCE_DIR=<extracted code-server> node --test <this file>
 */
import assert from 'node:assert/strict'
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

test('native configuration contributions and explicit user precedence', { skip: !runtime, timeout: 180_000 }, async () => {
  const { chromium } = requireFrontend('@playwright/test')
  const root = await mkdtemp(join(tmpdir(), 'kk-native-defaults-'))
  const browser = await chromium.launch({ headless: true })
  try {
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
      const child = spawn(join(runtime, 'lib', process.platform === 'win32' ? 'node.exe' : 'node'), [
        join(runtime, 'out/node/entry.js'), '--auth', 'none', '--bind-addr', '127.0.0.1:0',
        '--user-data-dir', userData, '--extensions-dir', extensions,
        '--config', join(dir, 'config.yaml'), '--disable-telemetry', join(dir, 'workspace'),
      ], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, HOME: dir, XDG_DATA_HOME: dir, XDG_CONFIG_HOME: dir } })
      let output = ''
      child.stdout.on('data', chunk => { output += chunk })
      child.stderr.on('data', chunk => { output += chunk })
      const page = await browser.newPage()
      page.on('console', message => { output += '\nBROWSER: ' + message.text() })
      try {
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
        await page.close()
        child.kill()
        if (child.exitCode === null) await new Promise(resolve => child.once('exit', resolve))
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
    await browser.close()
    await rm(root, { recursive: true, force: true })
  }
})
