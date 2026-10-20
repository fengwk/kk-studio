/** History, batch-protocol and tag-object scanning tests for the full-history scanner. */

import assert from 'node:assert/strict'
import { chmodSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import * as history from '../check-sensitive-history.mjs'
import {
  auditorGit,
  git,
  gitWithInput,
  initRepository,
  render,
  sampleToken,
  withTempDir,
  writeAndCommit,
} from './support/history-fixture.mjs'

const scan = (directory, maxBlobBytes = history.DEFAULT_MAX_BLOB_BYTES) => history.run(directory, maxBlobBytes)

test('test intent: a secret removed from the tip is reported against its introducing commit without leaking the value', () => {
  withTempDir((directory) => {
    initRepository(directory)
    const introducing = writeAndCommit(directory, { 'secret.txt': 'key=' + sampleToken() + '\n' }, 'add')
    writeAndCommit(directory, { 'secret.txt': 'clean\n' }, 'remove')

    const report = scan(directory)
    const rows = report.history.findings.filter((finding) => finding.path === 'secret.txt')
    assert.ok(rows.length > 0, 'history must report the removed secret')
    assert.equal(rows[0].rule, 'aws-access-key')
    assert.equal(rows[0].commit, introducing)
    assert.deepEqual(report.worktree.findings, [])
    assert.ok(!JSON.stringify(report).includes(sampleToken()))
  })
})

test('test intent: commit and tag messages are part of the public history surface', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'chore ' + sampleToken())
    auditorGit(directory, 'tag', '-a', 'v-test', '-m', 'tag ' + sampleToken())

    const paths = new Set(scan(directory).history.findings.map((finding) => finding.path))
    assert.ok(paths.has('<commit-message>'))
    assert.ok(paths.has('<tag-message>'))
  })
})

test('test intent: an empty repository is a complete scan, not a malformed batch', () => {
  withTempDir((directory) => {
    initRepository(directory)
    const report = scan(directory)
    assert.deepEqual(report.history.findings, [])
    assert.equal(report.degraded.incomplete, false)
  })
})

test('test intent: identical content committed twice is one object attributed to both paths', () => {
  withTempDir((directory) => {
    initRepository(directory)
    const payload = 'key=' + sampleToken() + '\n'
    writeAndCommit(directory, { 'a.txt': payload }, 'a')
    writeAndCommit(directory, { 'b.txt': payload }, 'b')

    const rows = scan(directory).history.findings.filter((finding) => finding.rule === 'aws-access-key')
    assert.equal(new Set(rows.map((row) => row.object)).size, 1)
    assert.deepEqual(new Set(rows.map((row) => row.path)), new Set(['a.txt', 'b.txt']))
  })
})

test('test intent: binary blobs are candidate-scanned through strings, not skipped silently', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'bin.dat': Buffer.from('\x00\x01api_key=abcdef0123456789abcdef\x00', 'latin1') }, 'fixtures')

    const report = scan(directory)
    assert.ok(report.coverage.blobs_binary >= 1)
    assert.ok(report.coverage.binary_strings_scanned >= 1)
    assert.ok(report.binary_objects.length > 0)
    assert.ok(report.history.findings.some((finding) => finding.path === 'bin.dat'))
    assert.ok(!JSON.stringify(report).includes('abcdef0123456789abcdef'))
  })
})

test('test intent: the blob limit is explicit and counted, not silently dropped', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'big.txt': 'key=' + sampleToken() + ' ' + 'z'.repeat(256) + '\n' }, 'big')

    const report = scan(directory, 64)
    assert.ok(report.coverage.blobs_too_large >= 1)
    assert.deepEqual(report.history.findings, [])
  })
})

test('test intent: broad generic hits are listed for review, never auto-cleared', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'c.txt': 'api_key=abcdef0123456789abcdef\n' }, 'c')

    const report = scan(directory)
    const groups = report.history.value_groups.filter((group) => group.rule === 'generic-secret')
    assert.deepEqual(groups.map((group) => group.status), [history.STATUS_PENDING])
    assert.ok(!JSON.stringify(report).includes('abcdef0123456789abcdef'))
  })
})

test('test intent: a clean current tree is never presented as a clean history', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'a')
    const markdown = history.renderMarkdown(scan(directory))
    assert.ok(!markdown.includes('无需轮换'))
    assert.ok(!markdown.includes('必须轮换'))
    assert.ok(markdown.includes('待人工确认'))
  })
})

// A cat-file record is "<oid> <type> <size>\n<body>\n"; each anomaly must be recorded.
const readEntry = (bytes, issues) => history.readBatchEntry(new history.ByteReader(Buffer.from(bytes, 'latin1')), issues, history.DEFAULT_MAX_BLOB_BYTES)

test('test intent: a valid batch entry is parsed with no degraded item', () => {
  const stream = new history.ByteReader(Buffer.from('a'.repeat(40) + ' blob 5\nhello\n'))
  const issues = new history.ScanIssues()
  const entry = history.readBatchEntry(stream, issues, history.DEFAULT_MAX_BLOB_BYTES)
  assert.equal(entry.content.toString(), 'hello')
  assert.deepEqual(issues.items, [])
  assert.equal(history.readBatchEntry(stream, issues, history.DEFAULT_MAX_BLOB_BYTES), history.BATCH_EOF)
})

test('test intent: an object the refs point at but the database lacks is a coverage gap', () => {
  const issues = new history.ScanIssues()
  const entry = readEntry('b'.repeat(40) + ' missing\n', issues)
  assert.equal(entry.content, null)
  assert.deepEqual(issues.items.map((item) => item.detail), ['missing-object'])
})

test('test intent: an unparsable header is not skipped as if it were empty', () => {
  const issues = new history.ScanIssues()
  const entry = readEntry('not-a-batch-header\n', issues)
  assert.equal(entry.content, null)
  assert.deepEqual(issues.items.map((item) => item.detail), ['malformed-header'])
})

test('test intent: a truncated body yields partial content plus an explicit short-read item', () => {
  const issues = new history.ScanIssues()
  const entry = readEntry('c'.repeat(40) + ' blob 20\nshort', issues)
  assert.equal(entry.content.toString(), 'short')
  assert.deepEqual(issues.items.map((item) => item.detail), ['short-read missing=15 of=20'])
})

test('test intent: a body without the record terminator means the stream is out of sync', () => {
  const issues = new history.ScanIssues()
  const entry = readEntry('d'.repeat(40) + ' blob 3\nabcX', issues)
  assert.equal(entry.content.toString(), 'abc')
  assert.deepEqual(issues.items.map((item) => item.detail), ['missing-record-terminator'])
})

test('test intent: the streaming scan reports a missing object instead of a silently smaller result', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'a')
    const { findings, issues, stats } = history.scanBlob(
      directory,
      ['0'.repeat(39) + '1'],
      new Map(),
      history.DEFAULT_MAX_BLOB_BYTES,
      history.DEFAULT_STRING_LIMIT,
    )
    assert.deepEqual(findings, [])
    assert.deepEqual(issues.items.map((item) => item.detail), ['missing-object'])
    assert.equal(issues.asDict().incomplete, true)
    assert.equal(stats.scanned, 0)
  })
})

test('test intent: a secret on a later line of an annotated tag message is scanned', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'a')
    auditorGit(directory, 'tag', '-a', 'v-multiline', '-m', 'subject line\n\nthird line ' + sampleToken())

    const report = scan(directory)
    const rows = report.history.findings.filter((finding) => finding.path === '<tag-message>')
    assert.ok(rows.length > 0, 'the tag message must be scanned beyond its first line')
    assert.equal(rows[0].line, 3)
    assert.ok(!JSON.stringify(report).includes(sampleToken()))
  })
})

test('test intent: a lightweight tag to a blob is scanned although no commit reaches it', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'a')
    const blob = gitWithInput(directory, 'key=' + sampleToken() + '\n', 'hash-object', '-w', '--stdin').trim()
    git(directory, 'update-ref', 'refs/tags/blob-tag', blob)

    const report = scan(directory)
    const rows = report.history.findings.filter((finding) => finding.rule === 'aws-access-key')
    assert.deepEqual(new Set(rows.map((row) => row.path)), new Set(['<ref:refs/tags/blob-tag>']))
    assert.ok(report.coverage.non_commit_refs >= 1)
    assert.ok(!JSON.stringify(report).includes(sampleToken()))
  })
})

test('test intent: blobs reachable only through a tree tag are covered too', () => {
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'a')
    const blob = gitWithInput(directory, 'key=' + sampleToken() + '\n', 'hash-object', '-w', '--stdin').trim()
    const tree = gitWithInput(directory, `100644 blob ${blob}\tsecret.txt\n`, 'mktree').trim()
    git(directory, 'update-ref', 'refs/tags/tree-tag', tree)

    const rows = scan(directory).history.findings.filter((finding) => finding.rule === 'aws-access-key')
    assert.deepEqual(new Set(rows.map((row) => row.path)), new Set(['<ref:refs/tags/tree-tag>/secret.txt']))
  })
})

test('test intent: an unreadable worktree file is a recorded coverage gap, not a silent skip', (t) => {
  if (process.getuid() === 0) {
    t.skip('root ignores mode 000')
    return
  }
  withTempDir((directory) => {
    initRepository(directory)
    writeAndCommit(directory, { 'a.txt': 'x\n' }, 'a')
    const target = path.join(directory, 'a.txt')
    chmodSync(target, 0)
    let issues
    try {
      ;({ issues } = history.scanWorktree(directory))
    } finally {
      chmodSync(target, 0o644)
    }
    assert.deepEqual(issues.items.map((item) => item.detail), ['read-failed:PermissionError'])
  })
})

test('test intent: LFS pointers are counted and never scanned as content', () => {
  withTempDir((directory) => {
    initRepository(directory)
    const pointer = 'version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 5\n'
    writeAndCommit(directory, { 'lfs.dat': pointer }, 'lfs')
    writeFileSync(path.join(directory, 'untracked.txt'), 'local\n')
    assert.equal(scan(directory).coverage.blobs_lfs, 1)
  })
})
