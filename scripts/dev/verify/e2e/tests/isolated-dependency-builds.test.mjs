/** Execute isolated lifecycle scripts with fake tools, never a real Docker daemon. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT } from '../../../tests/support/shell.mjs'

const CASES = [
  ['smoke/offline-chat.sh', [], 1],
  ['smoke/offline-chat.sh', ['--with-app'], 1],
  ['performance/run.sh', [], 1],
  ['performance/run.sh', ['--skip-build'], 0],
  ['e2e/distributed.sh', ['up'], 2],
  ['e2e/distributed.sh', ['up', '--skip-build'], 0],
]

// Log argv as JSON, not shell text: option boundaries and service selection matter. The shebang
// names the real interpreter because the fake `node` below shadows it on PATH.
const DOCKER = `#!${process.execPath}
const { appendFileSync } = require('node:fs')
const args = process.argv.slice(2)
appendFileSync(process.env.FAKE_DOCKER_LOG, JSON.stringify(args) + '\\n')
if (args[0] === 'compose' && args.includes('build')) {
  const rest = args.slice(args.indexOf('build') + 1)
  if (rest.length !== 1 || rest[0] !== 'minio-init') {
    process.exit(91)
  }
  if (process.env.FAIL_MC_BUILD === 'true') {
    process.exit(42)
  }
}
if (args[0] === 'compose' && args.includes('exec') && args.includes('postgres')) {
  console.log('1')
}
`

function runEntrypoint(script, args, fail = false) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-isolated-build-contract-'))
  try {
    const binDir = path.join(directory, 'bin')
    mkdirSync(binDir)
    // `node` is stubbed so no embedded smoke program executes; the environment-status probe
    // (`json-probe.mjs environment-status`) answers READY.
    const tools = {
      docker: DOCKER,
      node: '#!/usr/bin/env bash\nif [[ "${1:-}" == *json-probe.mjs && "${2:-}" == "environment-status" ]]; then\n  printf "READY\\n"\nfi\n',
      curl: '#!/usr/bin/env bash\nexit 0\n',
    }
    for (const [name, content] of Object.entries(tools)) {
      writeFileSync(path.join(binDir, name), content)
      chmodSync(path.join(binDir, name), 0o755)
    }
    const log = path.join(directory, 'docker.jsonl')
    // Keep proxy credentials out of mocked argv/logs and make tests host-independent.
    const env = Object.fromEntries(
      Object.entries(process.env).filter(
        ([key]) =>
          !key.toUpperCase().includes('PROXY') &&
          !['CANVAS_TEST_BUILD_', 'KK_STUDIO_BUILD_', 'KK_STUDIO_MAVEN_BUILD_'].some((prefix) => key.startsWith(prefix)),
      ),
    )
    Object.assign(env, {
      PATH: `${binDir}${path.delimiter}${process.env.PATH ?? ''}`,
      KK_STUDIO_REPO_ROOT: REPOSITORY_ROOT,
      FAKE_DOCKER_LOG: log,
      FAIL_MC_BUILD: String(fail),
    })
    const argv = [path.join(REPOSITORY_ROOT, 'scripts/dev/verify', script), ...args]
    if (script === 'performance/run.sh') {
      argv.push('--duration-seconds', '1', '--report-root', path.join(directory, 'report'))
    }
    const result = spawnSync('bash', argv, { cwd: directory, env, encoding: 'utf8', timeout: 15_000 })
    const calls = readFileSync(log, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line))
    return { result, calls }
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

test('test intent: mc is built before start even when source builds are skipped', () => {
  // A clean host always builds mc, without rebuilding app/daemon via Compose.
  for (const [script, args, sourceBuilds] of CASES) {
    const context = `${script} ${args.join(' ')}`
    const { result, calls } = runEntrypoint(script, args)
    assert.equal(result.status, 0, context + result.stdout + result.stderr)
    const builds = calls.flatMap((call, index) => (call[0] === 'compose' && call.includes('build') ? [index] : []))
    const starts = calls.flatMap((call, index) => (call.includes('up') ? [index] : []))
    assert.equal(builds.length, 1, context)
    assert.equal(starts.length, 1, context)
    assert.deepEqual(calls[builds[0]].slice(-2), ['build', 'minio-init'], context)
    assert.ok(builds[0] < starts[0], context)
    assert.ok(!calls[starts[0]].includes('--build'), context)
    if (script !== 'smoke/offline-chat.sh' || args.includes('--with-app')) {
      assert.ok(calls[starts[0]].includes('--no-build'), context)
    }
    assert.equal(calls.filter((call) => call[0] === 'build').length, sourceBuilds, context)
    if (script === 'smoke/offline-chat.sh') {
      const init = calls.findIndex((call) => call.includes('run') && call.at(-1) === 'minio-init')
      assert.ok(builds[0] < init, context)
    }
  }
})

test('test intent: a failed mc build blocks every startup path and cleanup stays intact', () => {
  // A dependency build failure propagates, with smoke/performance cleanup intact.
  for (const [script, args] of CASES) {
    const context = `${script} ${args.join(' ')}`
    const { result, calls } = runEntrypoint(script, args, true)
    assert.equal(result.status, 42, context + result.stdout + result.stderr)
    assert.ok(!calls.some((call) => call.includes('up') || call.includes('run')), context)
    if (script !== 'e2e/distributed.sh') {
      assert.equal(calls.at(-1)[0], 'compose', context)
      assert.ok(calls.at(-1).includes('down'), context)
      assert.ok(calls.at(-1).includes('--volumes'), context)
    }
  }
})

test('test intent: skip-build help names source images only', () => {
  // Public shell help must not promise to skip the dependency build.
  for (const script of ['performance/run.sh', 'e2e/distributed.sh']) {
    const result = spawnSync('bash', [path.join(REPOSITORY_ROOT, 'scripts/dev/verify', script), '--help'], {
      encoding: 'utf8',
    })
    assert.equal(result.status, 0, script)
    assert.ok(result.stdout.includes('shared MinIO client is still built'), script)
  }
})
