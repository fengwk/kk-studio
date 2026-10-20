/** The reliability stack shell reads Docker and API responses through stack-probe.mjs. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT } from '../../../tests/support/shell.mjs'

const PROBE = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/reliability/stack-probe.mjs')
const probe = (args, input) =>
  spawnSync(process.execPath, [PROBE, ...args], { input: typeof input === 'string' ? input : JSON.stringify(input), encoding: 'utf8' })
const workspace = { Type: 'volume', Destination: '/workspace', Name: 'vol', RW: true }

test('test intent: environment readiness needs READY and ready=true', () => {
  const rows = (row) => ({ data: [{ name: 'env', ...row }] })
  assert.equal(probe(['environment-ready', 'env'], rows({ status: 'READY', ready: true })).status, 0)
  assert.equal(probe(['environment-ready', 'env'], rows({ status: 'READY', ready: false })).status, 1)
  assert.equal(probe(['environment-ready', 'missing'], rows({ status: 'READY', ready: true })).status, 1)
})

test('test intent: environment status is printed with Python-style scalars and a missing one fails', () => {
  const ok = probe(['environment-status', 'env'], { data: [{ name: 'env', status: 'READY', ready: true }] })
  assert.equal(ok.stdout, 'environment=env status=READY ready=True\n')
  const missing = probe(['environment-status', 'env'], { data: [] })
  assert.equal(missing.status, 1)
  assert.equal(missing.stderr, 'environment not found: env\n')
})

test('test intent: the daemon workspace is exactly one named volume', () => {
  assert.equal(probe(['workspace-volume'], [{ Mounts: [workspace] }]).stdout, 'vol\n')
  for (const mounts of [[], [workspace, workspace], [{ ...workspace, Name: '' }]]) {
    assert.equal(probe(['workspace-volume'], [{ Mounts: mounts }]).status, 1)
  }
})

test('test intent: the daemon mount check rejects binds, extra mounts and read-only volumes', () => {
  assert.equal(probe(['daemon-mount'], [{ Mounts: [workspace] }]).stdout, 'daemon_mount=volume:/workspace writable=true\n')
  const failure = (mounts) => probe(['daemon-mount'], [{ Mounts: mounts }]).stderr
  assert.equal(failure([{ Type: 'bind', Destination: '/x' }]), 'daemon has a forbidden bind mount\n')
  assert.equal(failure([workspace, workspace]), 'daemon expected exactly one mount, found 2\n')
  assert.equal(failure([{ ...workspace, Destination: '/data' }]), 'unexpected daemon mount: volume:/data\n')
  assert.equal(failure([{ ...workspace, RW: false }]), 'daemon workspace volume is not writable\n')
})

test('test intent: compose status accepts an array, a single object or NDJSON, sorted by service', () => {
  const rows = [
    { Service: 'b', State: 'running', Health: 'healthy' },
    { Service: 'a', State: 'exited' },
  ]
  const expected = 'service=a state=exited health=-\nservice=b state=running health=healthy\n'
  assert.equal(probe(['compose-status'], rows).stdout, expected)
  assert.equal(probe(['compose-status'], rows.map((row) => JSON.stringify(row)).join('\n')).stdout, expected)
  assert.equal(probe(['compose-status'], '  ').stdout, 'no reliability containers\n')
})
