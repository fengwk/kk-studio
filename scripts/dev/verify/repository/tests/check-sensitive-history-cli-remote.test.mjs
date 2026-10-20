/** CLI contract, remote coverage and pull-request ref tests for the full-history scanner. */

import assert from 'node:assert/strict'
import { readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'

import * as history from '../check-sensitive-history.mjs'
import {
  HISTORY_SCANNER,
  git,
  initRepository,
  runCli,
  sampleToken,
  seedOrigin,
  withTempDir,
  writeAndCommit,
} from './support/history-fixture.mjs'

test('test intent: the CLI writes redacted reports and fails on a high-confidence hit', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'secret.txt': 'key=' + sampleToken() + '\n' }, 'add')
    const jsonPath = `${directory}-scan.json`
    const markdownPath = `${directory}-scan.md`
    try {
      const completed = spawnSync(
        process.execPath,
        [HISTORY_SCANNER, '--root', directory, '--json', jsonPath, '--markdown', markdownPath, '--fail-on-high'],
        { encoding: 'utf8' },
      )
      assert.equal(completed.status, 1)
      const payload = readFileSync(jsonPath, 'utf8')
      const markdown = readFileSync(markdownPath, 'utf8')
      assert.ok(payload.includes('aws-access-key'))
      assert.ok(markdown.includes('aws-access-key'))
      for (const text of [payload, markdown, completed.stdout, completed.stderr]) {
        assert.ok(!text.includes(sampleToken()))
      }
    } finally {
      rmSync(jsonPath, { force: true })
      rmSync(markdownPath, { force: true })
    }
  })
})

test('test intent: the gate covers an untracked worktree secret, not only history', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'clean.txt': 'x\n' }, 'clean')
    writeFileSync(path.join(directory, 'untracked.txt'), 'key=' + sampleToken() + '\n')

    const completed = runCli(directory, '--fail-on-high')
    assert.equal(completed.status, 1)
    assert.ok(!(completed.stdout + completed.stderr).includes(sampleToken()))
  })
})

test('test intent: a PR-only secret fails the gate and the PR rows are the evidence', () => {
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'key=' + sampleToken())

    const completed = runCli(consumer, '--remote-pr', '--fail-on-high')
    assert.equal(completed.status, 1)
    assert.ok(completed.stdout.includes('remote_pr_refs='))
    assert.ok(!(completed.stdout + completed.stderr).includes(sampleToken()))
  })
})

test('test intent: an unverifiable remote is never reported as a clean exit', () => {
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'clean', null)
    git(consumer, 'remote', 'set-url', 'origin', path.join(directory, 'missing.git'))

    const completed = runCli(consumer)
    assert.equal(completed.status, 3)
    assert.ok(completed.stderr.includes('scan-incomplete'))
  })
})

test('test intent: a negative limit is a caller error, never a silent full scan', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'a')

    const completed = runCli(directory, '--max-blob-bytes', '-1')
    assert.equal(completed.status, 2)
    assert.ok(completed.stderr.includes('invalid-arguments'))
    assert.throws(() => history.run(directory, -1), history.ValueError)
  })
})

test('test intent: public PR refs are audited in an isolated bare repo that leaves the main refs untouched', () => {
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'key=' + sampleToken())
    const before = git(consumer, 'for-each-ref', '--format=%(refname)')

    const result = history.scanRemotePrRefs(consumer, history.DEFAULT_MAX_BLOB_BYTES)

    assert.equal(git(consumer, 'for-each-ref', '--format=%(refname)'), before, 'main repository refs must not be touched')
    assert.equal(result.status, 'scanned')
    assert.equal(result.refs, 1)
    assert.ok(result.findings >= 1)
    assert.ok(!JSON.stringify(result).includes(sampleToken()))
  })
})

test('test intent: the absence of PR refs is recorded', () => {
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'clean', null)
    const result = history.scanRemotePrRefs(consumer, history.DEFAULT_MAX_BLOB_BYTES)
    assert.equal(result.status, 'none')
    assert.equal(result.refs, 0)
  })
})

test('test intent: a report only ever labels the remote origin and never carries its URL', () => {
  // A remote URL can embed a query token, userinfo or a local filesystem path.
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'clean', null)
    const result = history.scanRemotePrRefs(consumer, history.DEFAULT_MAX_BLOB_BYTES)
    assert.equal(result.remote, 'origin')
    const payload = JSON.stringify(result)
    assert.ok(!payload.includes(directory))
    assert.ok(!payload.includes('origin.git'))

    git(consumer, 'remote', 'set-url', 'origin', 'https://user' + ':' + 'password' + '@private.invalid/owner/repo.git?token=querytokenvalue')
    const covered = history.remoteCoverage(consumer, history.listRefs(consumer))
    const coveredPayload = JSON.stringify(covered)
    for (const leaked of ['private.invalid', 'password', 'querytokenvalue', 'user']) {
      assert.ok(!coveredPayload.includes(leaked), leaked)
    }
    assert.equal(covered.status, 'ls-remote-failed')
    assert.equal(covered.label, 'origin')
  })
})

test('test intent: a tracking ref that was never fetched cannot be claimed as remote coverage', () => {
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'clean', null)
    const result = history.remoteCoverage(consumer, history.listRefs(consumer))
    assert.equal(result.status, 'mismatch')
    assert.ok(result.absent_locally >= 1)
    assert.ok(!JSON.stringify(result).includes(directory))
  })
})

test('test intent: only an OID match is reported as verified coverage', () => {
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'clean', null)
    git(consumer, 'fetch', '--quiet', 'origin')
    const result = history.remoteCoverage(consumer, history.listRefs(consumer))
    assert.equal(result.status, 'verified')
    assert.equal(result.heads, 1)
    assert.equal(result.oid_mismatch, 0)
    assert.equal(result.absent_locally, 0)
  })
})

test('test intent: "no origin" is its own state, not success', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'a')
    assert.equal(history.remoteCoverage(directory, history.listRefs(directory)).status, 'no-remote')
  })
})

test('test intent: a failed remote lookup marks the scan incomplete', () => {
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'clean', null)
    git(consumer, 'remote', 'set-url', 'origin', path.join(directory, 'missing.git'))
    const report = history.run(consumer)
    assert.equal(report.coverage.remote.status, 'ls-remote-failed')
    assert.equal(report.degraded.incomplete, true)
  })
})

test('test intent: the PR audit exposes the same finding rows as the local history scan', () => {
  withTempDir((directory) => {
    const consumer = seedOrigin(directory, 'key=' + sampleToken())
    const result = history.scanRemotePrRefs(consumer, history.DEFAULT_MAX_BLOB_BYTES)
    assert.equal(result.status, 'scanned')
    assert.ok(result.findings_detail.length > 0, 'detail rows are required for gating')
    assert.deepEqual(
      new Set(Object.keys(result.findings_detail[0])),
      new Set(['rule', 'category', 'object', 'commit', 'path', 'line']),
    )
    assert.ok(!JSON.stringify(result).includes(sampleToken()))
  })
})
