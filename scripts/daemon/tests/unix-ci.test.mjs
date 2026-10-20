/**
 * Keep the Unix/Windows daemon script suites wired into the real CI gates.
 *
 * The install/update fixtures no longer import the removed Java harness. These tests pin the
 * remaining platform-level facts: every publication path runs the Node script suite with the
 * host's system Bash, both Windows PowerShell hosts run the native installer/update suites, and a
 * failed platform job blocks publication.
 */

import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../dev/lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const TESTS_DIR = path.join(REPOSITORY_ROOT, 'scripts/daemon/tests')
const SUITE_COMMAND = 'node --test scripts/daemon/tests/*.test.mjs'

const workflow = (name) => readFileSync(path.join(REPOSITORY_ROOT, '.github/workflows', name), 'utf8')

/** Read one top-level job without adding a YAML dependency to script tests. */
function jobBlock(text, name) {
  const match = new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  [A-Za-z_][A-Za-z_0-9]*:|$(?![\\s\\S]))`, 'm').exec(text)
  assert.ok(match, `missing job: ${name}`)
  return match[1]
}

test('test intent: every publication path runs the script suite with system Bash', () => {
  // Ubuntu/macOS system Bash must run the complete install/update script suite.
  const docker = jobBlock(workflow('docker-publish.yml'), 'validate_unix_daemon')
  for (const expected of [
    'os: [ubuntu-latest, macos-latest]',
    'runs-on: ${{ matrix.os }}',
    'fail-fast: false',
    'shell: /bin/bash --noprofile --norc -eo pipefail {0}',
    '/bin/bash --version',
    SUITE_COMMAND,
  ]) {
    assert.ok(docker.includes(expected), expected)
  }

  const release = jobBlock(workflow('daemon-release.yml'), 'validate_daemon')
  assert.ok(release.includes('Test Unix installer and packaging with system Bash'))
  assert.ok(release.includes(SUITE_COMMAND))
  assert.ok(release.indexOf('npm --prefix daemon ci') < release.indexOf(SUITE_COMMAND))

  const ci = jobBlock(workflow('daemon-ci.yml'), 'node_daemon')
  for (const expected of [
    'Test the macOS installer and archive smoke with system Bash',
    'shell: /bin/bash --noprofile --norc -eo pipefail {0}',
    '/bin/bash --version',
    SUITE_COMMAND,
  ]) {
    assert.ok(ci.includes(expected), expected)
  }
})

test('test intent: the Windows gate runs both PowerShell hosts', () => {
  // PS 5.1 is the floor; the PS7 result is collected even when PS 5.1 fails.
  for (const [name, jobName] of [
    ['docker-publish.yml', 'validate_windows_daemon'],
    ['daemon-release.yml', 'validate_windows_daemon'],
    ['daemon-ci.yml', 'windows_daemon_installer'],
  ]) {
    const job = jobBlock(workflow(name), jobName)
    for (const expected of ['shell: powershell', 'shell: pwsh', 'test_daemon_install_windows.ps1', 'if: ${{ !cancelled() }}']) {
      assert.ok(job.includes(expected), `${name}: ${expected}`)
    }
  }
})

test('test intent: the Windows gate chains the managed-update native suite', () => {
  // The CI-invoked installer suite must also run the update suite on both hosts.
  const installer = readFileSync(path.join(TESTS_DIR, 'test_daemon_install_windows.ps1'), 'utf8')
  assert.ok(installer.includes('test_daemon_update_windows.ps1'))
  assert.ok(installer.includes('& $hostExe'))
  const update = readFileSync(path.join(TESTS_DIR, 'test_daemon_update_windows.ps1'), 'utf8')
  assert.ok(update.includes('daemon\\src\\update-scripts.ts'))
  assert.ok(update.includes('WINDOWS_UPDATE_SCRIPT'))
})

test('test intent: publish and release require both platform jobs', () => {
  const docker = workflow('docker-publish.yml')
  const publish = jobBlock(docker, 'publish')
  const dockerNeeds = publish.split('needs:\n')[1].split('    if:')[0]
  for (const name of ['validate', 'daemon_ci', 'validate_unix_daemon', 'validate_windows_daemon']) {
    assert.ok(dockerNeeds.includes(`      - ${name}\n`), name)
    assert.ok(publish.includes(`needs.${name}.result == 'success'`), name)
    assert.ok(publish.includes(`needs.${name}.result == 'skipped'`), name)
  }
  assert.ok(publish.includes('&& !cancelled()'))

  const stage = jobBlock(workflow('daemon-release.yml'), 'stage')
  const stageNeeds = stage.split('needs:\n')[1].split('    runs-on:')[0]
  assert.deepEqual([...stageNeeds.matchAll(/^      - (.+)$/gm)].map((match) => match[1]), [
    'validate_daemon',
    'validate_windows_daemon',
    'daemon_ci',
  ])
  assert.doesNotMatch(stage, /^ {4}if:/m)
})

test('test intent: the script suites do not reference the deleted Java update harness', () => {
  const needle = 'harness' + '/daemon'
  const visit = (directory) => {
    for (const entry of readdirSync(directory).sort()) {
      const file = path.join(directory, entry)
      if (statSync(file).isDirectory()) {
        visit(file)
      } else if (['.mjs', '.ps1', '.sh'].includes(path.extname(file))) {
        assert.ok(!readFileSync(file, 'utf8').includes(needle), file)
      }
    }
  }
  visit(TESTS_DIR)
})
