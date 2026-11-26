import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readdirSync, readFileSync, readlinkSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { RESET_SCRIPT, readText, runLibrarySnippet } from './support/library.mjs'

const dumpName = 'fixture_20250102T030405Z.dump'
const checksumName = `${dumpName}.sha256`
const source = readText(RESET_SCRIPT)
// Install the production functions and trap without calling the database entrypoint.
const setup = source.match(/\n(WORK_DIR=[\s\S]*\ntrap on_exit EXIT)\nmain "\$@"\s*$/)?.[1]
assert.ok(setup, 'could not isolate the reset script setup from its main call')

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'reset backup '))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const sentinel = `${dumpName}.partial`
  writeFileSync(join(dir, sentinel), 'not owned by this invocation')
  return {
    dir,
    run: (scenario, afterBackup = '') => runLibrarySnippet(`
set -euo pipefail
umask 077
${setup}
TARGET_DB=fixture
STAMP=20250102T030405Z
DB_SIZE_BYTES=0
pg_dump() {
  local output=$5
  [[ "$output" == "--file=$BACKUP_STAGE_DIR/"* ]] || return 90
  printf '%s' "\${PAYLOAD:-loser}" > "\${output#--file=}" || return
  return "\${DUMP_STATUS:-0}"
}
pg_restore() { test -s "$3"; }
configure_paths
prepare_work_directory
${scenario}
write_backup
${afterBackup}
`, { KK_STUDIO_RESET_DIR: dir }),
    assertEntries: (...names) => {
      assert.deepEqual(readdirSync(dir).sort(), [sentinel, ...names].sort())
      assert.equal(readFileSync(join(dir, sentinel), 'utf8'), 'not owned by this invocation')
    },
  }
}

const failStageCleanup = `
rm() {
  if [ "$#" -eq 3 ] && [ "$1" = -rf ] && [ "$2" = -- ] && [ "$3" = "$BACKUP_STAGE_DIR" ]; then
    return 23
  fi
  command rm "$@"
}`

const stubReplacement = `
DB_LOCALE_PROVIDER=libc
DB_TABLESPACE=pg_default
DB_CONNECTION_LIMIT=-1
psql_database() { printf 'SQL: %s\\n' "$3" >&2; }
database_scalar() { echo 0; }
createdb() { return "\${CREATE_STATUS:-0}"; }
`

function assertCleanupFailure(result, dir, assertEntries) {
  const stages = readdirSync(dir).filter((name) => name.startsWith('.reset-backup.'))
  assert.equal(stages.length, 1)
  const [stage] = stages
  // The path contains spaces: the diagnostic quotes it so it can be identified unambiguously.
  const quotedPath = join(dir, stage).replaceAll(' ', '\\ ')
  assert.ok(result.stderr.includes(`cannot clean backup staging directory: ${quotedPath}\n`), result.stderr)
  assertEntries(dumpName, checksumName, stage)
  const dump = readFileSync(join(dir, dumpName))
  assert.equal(dump.toString(), 'fixture data')
  assert.equal(readFileSync(join(dir, checksumName), 'utf8'),
    `${createHash('sha256').update(dump).digest('hex')}  ${dumpName}\n`)
}

test('staging cleanup failure after replacement reports the residual path without undoing the switch', (t) => {
  const { dir, run, assertEntries } = fixture(t)
  const result = run(`${failStageCleanup}\n${stubReplacement}\nPAYLOAD='fixture data'`, 'replace_database')
  assert.equal(result.status, 1, result.stderr)
  assertCleanupFailure(result, dir, assertEntries)
  assert.match(result.stderr, /Reset failed after the database was replaced; fixture is empty/)
  assert.match(result.stderr, /pre-reset database is frozen as fixture_pre_20250102T030405Z/)
  assert.deepEqual(result.stderr.match(/^SQL: .+$/gm), [
    'SQL: alter database "fixture" rename to "fixture_pre_20250102T030405Z"',
    'SQL: alter database "fixture_pre_20250102T030405Z" allow_connections false',
    'SQL: alter database "fixture__kk_partial_20250102T030405Z" rename to "fixture"',
  ])
})

test('staging cleanup failure preserves the original status and rollback before replacement', (t) => {
  const { dir, run, assertEntries } = fixture(t)
  const result = run(`${failStageCleanup}\n${stubReplacement}\nPAYLOAD='fixture data' CREATE_STATUS=37`, 'replace_database')
  assert.equal(result.status, 37, result.stderr)
  assertCleanupFailure(result, dir, assertEntries)
  assert.match(result.stderr, /The original database is back in place: fixture/)
  assert.deepEqual(result.stderr.match(/^SQL: .+$/gm)?.slice(-2), [
    'SQL: alter database "fixture_pre_20250102T030405Z" allow_connections true',
    'SQL: alter database "fixture_pre_20250102T030405Z" rename to "fixture"',
  ])
})

test('a failed dump removes only the invocation staging directory', (t) => {
  const { run, assertEntries } = fixture(t)
  const result = run('PAYLOAD=partial DUMP_STATUS=1')
  assert.equal(result.status, 1, result.stderr)
  assert.match(result.stderr, /the full backup failed;/)
  assertEntries()
})

test('a checksum mismatch stops before either artifact is published', (t) => {
  const { run, assertEntries } = fixture(t)
  const result = run(`
file_sha256() {
  if [ -f "$1.sha256" ]; then printf '%064d\\n' 1; else printf '%064d\\n' 0; fi
}`)
  assert.equal(result.status, 1, result.stderr)
  assert.match(result.stderr, /checksum failed validation/)
  assertEntries()
})

test('a same-stamp loser cannot alter the winner pair or its own staging during the winner exit', (t) => {
  const { dir, run, assertEntries } = fixture(t)
  // Pause the first writer in validation and complete the second before resuming publication.
  const result = run(`
pg_restore() {
  local outer_dump=$3
  (
    BACKUP_STAGE_DIR=
    trap on_exit EXIT
    PAYLOAD=winner
    pg_restore() { test -s "$3"; }
    prepare_work_directory
    write_backup
    test "$BACKUP_STAGE_DIR" != "\${outer_dump%/*}"
  ) || { echo 'winner did not finish' >&2; return 1; }
  test "$(< "$outer_dump")" = loser
}`)
  assert.equal(result.status, 1, result.stderr)
  assert.match(result.stderr, /EEXIST/)
  assert.match(result.stderr, /cannot publish the full backup/)
  assertEntries(dumpName, checksumName)
  const dump = readFileSync(join(dir, dumpName))
  assert.equal(dump.toString(), 'winner')
  assert.equal(readFileSync(join(dir, checksumName), 'utf8'),
    `${createHash('sha256').update(dump).digest('hex')}  ${dumpName}\n`)
  for (const name of [dumpName, checksumName]) {
    assert.equal(statSync(join(dir, name)).mode & 0o777, 0o600)
  }
})

test('a late checksum destination collision retracts only the newly linked dump', (t) => {
  const { dir, run, assertEntries } = fixture(t)
  const result = run(`pg_restore() { ln -s missing "$CHECKSUM_FILE"; }`)
  assert.equal(result.status, 1, result.stderr)
  assert.match(result.stderr, /EEXIST/)
  assert.match(result.stderr, /cannot publish the full backup/)
  assertEntries(checksumName)
  assert.equal(readlinkSync(join(dir, checksumName)), 'missing')
})
