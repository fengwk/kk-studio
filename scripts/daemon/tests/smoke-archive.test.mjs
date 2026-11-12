import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'

import { repositoryRoot } from '../../dev/lib/repo-root.mjs'
import { writeTarGz } from './support/tar.mjs'

const ROOT = repositoryRoot()
const SCRIPT = path.join(ROOT, 'scripts/daemon/smoke-archive.sh')
const HOST = os.platform() === 'darwin' ? 'macos' : 'linux'
const ARCH = ['arm64', 'aarch64'].includes(os.arch()) ? 'arm64' : 'amd64'
const ARCHIVE_NAME = `kk-studio-daemon-v1.0.0-${HOST}-${ARCH}.tar.gz`
const UNIX_HOST = ['linux', 'darwin'].includes(os.platform())

function withArchive(body, { forbidden = false, ripgrep = false, ripgrepPackage = false, binary = true } = {}) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'kk-daemon-smoke-test-'))
  try {
    const members = [
      { name: 'runtime/code-server/out/node/entry.js', content: '// bundled runtime\n' },
      { name: 'ide-extension/extension.cjs', content: '// extension\n' },
      { name: 'runtime-manifest.json', content: '{"daemonVersion":"1.0.0"}\n' },
      { name: 'LICENSE', content: 'license\n' },
      { name: 'THIRD_PARTY_NOTICES', content: 'notices\n' },
    ]
    if (binary) {
      const executable = path.join(root, 'kk-studio-daemon')
      writeFileSync(executable, '#!/bin/sh\ncase "$1" in --version) echo "kk-studio-daemon 1.0.0";; --check-config) echo "ok";; *) exit 2;; esac\n')
      chmodSync(executable, 0o755)
      members.push({ name: 'kk-studio-daemon', content: readFileSync(executable), mode: 0o755 })
    }
    if (forbidden) {
      members.push({ name: 'main.js', content: 'legacy node daemon\n' })
    }
    if (ripgrep) {
      members.push({ name: 'runtime/code-server/lib/vscode/node_modules/@vscode/ripgrep-universal/bin/linux-x64/rg', content: 'binary' })
    }
    if (ripgrepPackage) {
      members.push({ name: 'runtime/code-server/lib/vscode/node_modules/@vscode/ripgrep-universal/package.json', content: '{}\n' })
    }
    const archive = path.join(root, ARCHIVE_NAME)
    writeTarGz(archive, members.map((entry) => ({ type: 'file', mode: 0o644, ...entry })))
    return body({ archive, root })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

const run = (archive) => spawnSync('bash', [SCRIPT, archive], { cwd: ROOT, encoding: 'utf8' })

test('smoke archive rejects invalid arguments', () => {
  assert.equal(spawnSync('bash', [SCRIPT, '--help'], { encoding: 'utf8' }).status, 1)
  assert.equal(spawnSync('bash', [SCRIPT], { encoding: 'utf8' }).status, 1)
})

test('smoke archive checks platform and refuses a non-Go payload', { skip: !UNIX_HOST }, () => {
  withArchive(({ archive }) => {
    const result = run(archive)
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
    assert.match(result.stdout, /archive smoke ok/)
  })
  withArchive(({ archive }) => {
    const result = run(archive)
    assert.match(result.stderr, /archive is missing executable kk-studio-daemon/)
  }, { binary: false })
  withArchive(({ archive }) => {
    const result = run(archive)
    assert.match(result.stderr, /unexpectedly contains main\.js/)
  }, { forbidden: true })
  withArchive(({ archive }) => {
    const result = run(archive)
    assert.match(result.stderr, /unexpectedly contains ripgrep binary/)
  }, { ripgrep: true })
  withArchive(({ archive }) => {
    const result = run(archive)
    assert.match(result.stderr, /unexpectedly contains ripgrep package/)
  }, { ripgrepPackage: true })
})
