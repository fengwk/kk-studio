/** Shared helpers for the full-history sensitive-data scanner tests. */

import { execFileSync, spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { repositoryRoot } from '../../../../lib/repo-root.mjs'

export const REPOSITORY_ROOT = repositoryRoot()
export const HISTORY_SCANNER = path.join(
  REPOSITORY_ROOT,
  'scripts/dev/verify/repository/check-sensitive-history.mjs',
)

/** AWS documentation example key, assembled so no test file matches a rule. */
export function sampleToken() {
  return 'AKIA' + 'IOSFODNN7EXAMPLE'
}

/** Flatten redacted findings; the matched value is never part of a finding. */
export function render(findings) {
  return findings
    .map((finding) => `${finding.rule} ${finding.object} ${finding.commit} ${finding.path} ${finding.line}`)
    .join('\n')
}

export function git(root, ...args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
}

export function gitWithInput(root, input, ...args) {
  return execFileSync('git', args, { cwd: root, input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] })
}

export function initRepository(root) {
  execFileSync('git', ['init', '--quiet', '-b', 'main', root], { stdio: 'ignore' })
}

const AUDITOR = ['-c', 'user.email=audit@example.invalid', '-c', 'user.name=audit']

export function auditorGit(root, ...args) {
  return git(root, ...AUDITOR, ...args)
}

export function commitAll(root, message) {
  git(root, 'add', '-A')
  auditorGit(root, 'commit', '--quiet', '-m', message)
  return git(root, 'rev-parse', 'HEAD').trim()
}

export function writeAndCommit(root, files, message) {
  for (const [name, content] of Object.entries(files)) {
    const file = path.join(root, name)
    mkdirSync(path.dirname(file), { recursive: true })
    writeFileSync(file, content)
  }
  return commitAll(root, message)
}

/** Create a local bare 'origin' (optionally with a PR ref) and a consumer repo. */
export function seedOrigin(base, secret, prRef = 'refs/pull/1/head') {
  const remote = path.join(base, 'origin.git')
  execFileSync('git', ['init', '--bare', '--quiet', remote], { stdio: 'ignore' })
  const seed = path.join(base, 'seed')
  initRepository(seed)
  writeFileSync(path.join(seed, 'f.txt'), secret + '\n')
  const sha = commitAll(seed, 'seed')
  git(seed, 'remote', 'add', 'origin', remote)
  git(seed, 'push', '--quiet', 'origin', 'main')
  if (prRef) {
    execFileSync('git', ['--git-dir', remote, 'update-ref', prRef, sha], { stdio: 'ignore' })
  }
  const consumer = path.join(base, 'consumer')
  initRepository(consumer)
  git(consumer, 'remote', 'add', 'origin', remote)
  return consumer
}

/** Run `body` with a fresh temporary directory that is removed afterwards. */
export function withTempDir(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-sensitive-history-'))
  try {
    return body(directory)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

export function runCli(root, ...extra) {
  return spawnSync(process.execPath, [HISTORY_SCANNER, '--root', root, ...extra], { encoding: 'utf8' })
}
