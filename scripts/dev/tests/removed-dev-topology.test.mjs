/** The NAS source-build node and its image must not come back. */

import assert from 'node:assert/strict'
import test from 'node:test'

import { gitGrep } from './support/shell.mjs'

// 本文件持有这些标识符本身。
const ALLOWED_SPELLING_FILES = new Set(['scripts/dev/tests/removed-dev-topology.test.mjs'])
const REMOVED_DAEMON_FLAG_ALLOWED = new Set([
  'scripts/dev/tests/removed-dev-topology.test.mjs',
  'scripts/dev/verify/e2e/tests/daemon-bootstrap-contracts.test.mjs',
  'scripts/dev/verify/reliability/tests/daemon-entrypoint-contracts.test.mjs',
])

test('test intent: deploy/dev, kk-studio-dev* and KK_STUDIO_DEV_* have no live reference', () => {
  const matches = gitGrep(['deploy/dev', 'kk-studio-dev', 'vps-kk-studio-dev', 'KK_STUDIO_DEV_'])
  const unexpected = matches.filter(({ path }) => !ALLOWED_SPELLING_FILES.has(path)).map(({ path, line }) => [path, line])
  assert.deepEqual(unexpected, [])
})

test('test intent: no startup fixture passes the removed daemon environment root flag', () => {
  // The removed daemon flag may only survive as a historical rejection test.
  const matches = gitGrep(['--environment-root'])
  const unexpected = matches
    .filter(({ path }) => !REMOVED_DAEMON_FLAG_ALLOWED.has(path))
    .map(({ path, line }) => [path, line])
  assert.deepEqual(unexpected, [])
})
