import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'

import { repositoryRoot } from '../../dev/lib/repo-root.mjs'

const ROOT = repositoryRoot()
const SCRIPT = path.join(ROOT, 'scripts/daemon/prepare-release.sh')
const GO = process.env.GO_BIN ?? '/usr/local/opt/go/bin/go'
const run = (args, env = {}) => spawnSync('bash', [SCRIPT, ...args], {
  cwd: ROOT,
  encoding: 'utf8',
  env: { ...process.env, GO_BIN: GO, ...env },
})

test('prepare-release documents its target contract without staging artifacts', () => {
  const result = run(['--help'])
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /daemon-go\/release/)
})

test('prepare-release rejects unsupported targets before dependency resolution', () => {
  const result = run(['v1.0.0', 'windows', 'arm64'], { GO_BIN: '/missing/go' })
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /windows-arm64 has no bundled code-server runtime/)
})

test('prepare-release requires a version-matched tag before downloading runtime assets', () => {
  const result = run(['v0.0.0', 'linux', 'amd64'])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /release tag must equal Go daemon version/)
  assert.equal(existsSync(path.join(ROOT, 'daemon-go/release')), false)
})
