/**
 * Keep the Unix/Windows daemon script suites wired into the real CI gates.
 *
 * The install/update fixtures no longer import the removed Java harness. These tests pin the
 * remaining platform-level facts: every publication path runs the repository script suite (including
 * Unix fixtures), the daemon matrix runs the macOS system-Bash contract, both Windows PowerShell
 * hosts run the native installer/update suites, and a failed gate blocks publication.
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

test('test intent: every publication path runs the repository script suite', () => {
  // Docker validate and release stage run the complete repository discovery, which includes the
  // daemon install/update fixtures; the daemon matrix additionally runs the macOS system Bash path.
  for (const [name, jobName] of [
    ['docker-publish.yml', 'validate'],
    ['daemon-release.yml', 'stage'],
  ]) {
    const job = jobBlock(workflow(name), jobName)
    assert.ok(job.includes('Run repository script tests'), `${name}: missing repository script tests`)
    assert.ok(job.includes('scripts/*/tests/*.test.mjs'), `${name}: missing daemon fixture discovery`)
    assert.ok(job.indexOf('npm --prefix daemon ci') < job.indexOf('Run repository script tests'))
  }

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
  // PS 5.1 is the floor; the PS7 result is collected even when PS 5.1 fails. The reusable daemon
  // matrix is the single native Windows gate for both image publishing and tag releases.
  const job = jobBlock(workflow('daemon-ci.yml'), 'windows_daemon_installer')
  for (const expected of ['shell: powershell', 'shell: pwsh', 'test_daemon_install_windows.ps1', 'if: ${{ !cancelled() }}']) {
    assert.ok(job.includes(expected), expected)
  }
  for (const [name, jobName] of [
    ['docker-publish.yml', 'validate_windows_daemon'],
    ['daemon-release.yml', 'validate_windows_daemon'],
  ]) {
    assert.throws(() => jobBlock(workflow(name), jobName), /missing job/u, `${name}: duplicate Windows job must stay removed`)
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

test('test intent: publish and release require the shared gates', () => {
  const docker = workflow('docker-publish.yml')
  const publish = jobBlock(docker, 'publish')
  const dockerNeeds = publish.split('needs:\n')[1].split('    if:')[0]
  for (const name of ['preflight', 'validate', 'validate_frontend', 'daemon_ci']) {
    assert.ok(dockerNeeds.includes(`      - ${name}\n`), name)
  }
  assert.ok(publish.includes("needs.preflight.result == 'success'"))
  for (const name of ['validate', 'validate_frontend', 'daemon_ci']) {
    assert.ok(publish.includes(`needs.${name}.result == 'success'`), name)
    assert.ok(publish.includes(`needs.${name}.result == 'skipped'`), name)
  }
  assert.ok(publish.includes('&& !cancelled()'))
  for (const name of ['validate_unix_daemon', 'validate_windows_daemon']) {
    assert.throws(() => jobBlock(docker, name), /missing job/u)
  }

  const stage = jobBlock(workflow('daemon-release.yml'), 'stage')
  const stageNeeds = stage.split('needs:\n')[1].split('    runs-on:')[0]
  assert.deepEqual([...stageNeeds.matchAll(/^      - (.+)$/gm)].map((match) => match[1]), ['daemon_ci'])
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
