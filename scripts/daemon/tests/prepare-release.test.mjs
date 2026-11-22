import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { test } from 'node:test'

import { repositoryRoot } from '../../dev/lib/repo-root.mjs'
import { writeTarGz } from './support/tar.mjs'

const ROOT = repositoryRoot()
const SCRIPT = path.join(ROOT, 'scripts/daemon/prepare-release.sh')

const run = (args, env = {}) => spawnSync('bash', [SCRIPT, ...args], {
  cwd: ROOT,
  encoding: 'utf8',
  env: { ...process.env, GO_BIN: process.execPath, ...env },
})

test('prepare-release documents its target contract without staging artifacts', () => {
  const result = run(['--help'])
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /daemon\/release/)
})

test('prepare-release rejects unsupported targets before dependency resolution', () => {
  const result = run(['v1.0.0', 'windows', 'arm64'], { GO_BIN: '/missing/go' })
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /windows-arm64 has no bundled code-server runtime/)
})

test('prepare-release requires a version-matched tag before downloading runtime assets', () => {
  const release = path.join(ROOT, 'daemon/release')
  const entries = () => existsSync(release) ? readdirSync(release) : null
  const before = entries()
  const result = run(['v0.0.0', 'linux', 'amd64'])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /release tag must equal the daemon version/)
  assert.deepEqual(entries(), before)
})

const digest = (bytes) => createHash('sha256').update(bytes).digest('hex')

test('prepare-release makes identical archive digests for identical staged inputs', async (t) => {
  const tar = spawnSync('tar', ['--version'], { encoding: 'utf8' })
  if (!tar.stdout?.includes('GNU tar')) {
    t.skip('release staging requires GNU tar')
    return
  }
  const git = spawnSync('git', ['rev-parse', '--absolute-git-dir'], { cwd: ROOT, encoding: 'utf8' })
  assert.equal(git.status, 0, git.stderr)
  const fixture = mkdtempSync(path.join(tmpdir(), 'kk-prepare-release-'))
  t.after(() => rmSync(fixture, { recursive: true, force: true }))
  const write = (relative, contents, options) => {
    const file = path.join(fixture, relative)
    mkdirSync(path.dirname(file), { recursive: true })
    writeFileSync(file, contents, options)
  }
  const runtime = path.join(fixture, 'runtime.tar.gz')
  writeTarGz(runtime, [{ name: 'code-server/entry.js', content: 'fixture runtime\n' }])
  write('LICENSE', 'fixture license\n')
  write('daemon/THIRD_PARTY_NOTICES', 'fixture notices\n')
  write('daemon/ide-extension/extension.cjs', 'fixture extension\n')
  write('daemon/internal/config/version.go', 'var DaemonVersion = "1.0.0"\n')
  write('daemon/runtime-manifest.json', JSON.stringify({
    codeServerVersion: 'fixture',
    codeServer: { 'windows-amd64': {
      url: 'https://github.com/fengwk/code-server/releases/download/fixture/runtime.tar.gz',
      sha256: digest(readFileSync(runtime)),
    } },
  }))
  // Stub only the build and download; run the real extraction, staging, tar/gzip and hashing.
  write('bin/go', '#!/usr/bin/env bash\nset -eu\nwhile [ "$1" != -o ]; do shift; done\nprintf fixture > "$2"\nchmod +x "$2"\n', { mode: 0o755 })
  write('bin/curl', '#!/usr/bin/env bash\nset -eu\nwhile [ "$1" != --output ]; do shift; done\ncp "$FIXTURE_RUNTIME" "$2"\n', { mode: 0o755 })
  const stage = () => {
    const result = run(['v1.0.0', 'windows', 'amd64'], {
      KK_STUDIO_REPO_ROOT: fixture,
      GIT_DIR: git.stdout.trim(),
      GO_BIN: path.join(fixture, 'bin/go'),
      NODE_BIN: process.execPath,
      PATH: `${path.join(fixture, 'bin')}${path.delimiter}${process.env.PATH}`,
      FIXTURE_RUNTIME: runtime,
    })
    assert.equal(result.status, 0, result.stderr)
    const name = 'kk-studio-daemon-v1.0.0-windows-amd64.tar.gz'
    const output = path.join(fixture, 'daemon/release/windows-amd64', name)
    const bytes = readFileSync(output)
    assert.equal(bytes.readUInt32LE(4), 0, 'gzip must omit the timestamp')
    const sha = digest(bytes)
    assert.equal(readFileSync(`${output}.sha256`, 'utf8'), `${sha}  ${name}\n`)
    return sha
  }
  const first = stage()
  // Ensure a timestamp from the second run cannot match due to gzip's one-second resolution.
  await new Promise((resolve) => setTimeout(resolve, 1000))
  assert.equal(stage(), first)
})
