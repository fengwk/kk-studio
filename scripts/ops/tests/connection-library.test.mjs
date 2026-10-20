/** Behaviour of the maintenance library's connection, target and directory helpers (no server needed). */

import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { shellQuote, runLibrarySnippet } from './support/library.mjs'

const output = (result) => result.stdout + result.stderr

test('test intent: CLI wins over VPS variables, which win over direct PG variables', () => {
  let result = runLibrarySnippet(
    `
configure_connection cli-host 6543 cli-user cli_db
printf '%s|%s|%s|%s\\n' "$PGHOST" "$PGPORT" "$PGUSER" "$PGDATABASE"
[ "$PGPASSWORD" = fixture-secret ]
[ -z "\${VPS_POSTGRES_PASSWORD+x}" ]
[ -z "\${PGSERVICE+x}" ]
[ -z "\${PGHOSTADDR+x}" ]
`,
    {
      PGHOST: 'pg-host',
      PGPORT: '1111',
      PGUSER: 'pg-user',
      PGDATABASE: 'pg_db',
      PGSERVICE: 'ambient-service',
      PGHOSTADDR: '192.0.2.10',
      VPS_POSTGRES_HOST: 'vps-host',
      VPS_POSTGRES_PORT: '2222',
      VPS_POSTGRES_USERNAME: 'vps-user',
      VPS_POSTGRES_PASSWORD: 'fixture-secret',
      VPS_POSTGRES_DATABASE: 'vps_db',
    },
  )
  assert.equal(result.status, 0, result.stderr)
  assert.equal(result.stdout.trim(), 'cli-host|6543|cli-user|cli_db')
  assert.ok(!output(result).includes('fixture-secret'))

  result = runLibrarySnippet(
    `
configure_connection '' '' '' ''
printf '%s|%s|%s|%s\\n' "$PGHOST" "$PGPORT" "$PGUSER" "$PGDATABASE"
`,
    {
      PGHOST: 'pg-host',
      PGPORT: '1111',
      PGUSER: 'pg-user',
      PGDATABASE: 'pg_db',
      VPS_POSTGRES_HOST: 'vps-host',
      VPS_POSTGRES_PORT: '2222',
      VPS_POSTGRES_USERNAME: 'vps-user',
      VPS_POSTGRES_DATABASE: 'vps_db',
    },
  )
  assert.equal(result.status, 0, result.stderr)
  assert.equal(result.stdout.trim(), 'vps-host|2222|vps-user|vps_db')
})

test('test intent: native libpq PGHOSTADDR stays available without a CLI or VPS host override', () => {
  const result = runLibrarySnippet(`configure_connection '' '' '' ''\n[ "$PGHOSTADDR" = 192.0.2.10 ]`, {
    PGHOSTADDR: '192.0.2.10',
    VPS_POSTGRES_HOST: null,
  })
  assert.equal(result.status, 0, result.stderr)
})

test('test intent: a VPS password alone authenticates a libpq service without disabling its endpoint', () => {
  const result = runLibrarySnippet(
    `
configure_connection '' '' '' ''
[ "$PGPASSWORD" = fixture-secret ]
[ "$PGSERVICE" = fixture-service ]
[ -z "\${VPS_POSTGRES_PASSWORD+x}" ]
`,
    {
      PGSERVICE: 'fixture-service',
      VPS_POSTGRES_PASSWORD: 'fixture-secret',
      VPS_POSTGRES_HOST: null,
      VPS_POSTGRES_PORT: null,
      VPS_POSTGRES_USERNAME: null,
      VPS_POSTGRES_DATABASE: null,
    },
  )
  assert.equal(result.status, 0, result.stderr)
  assert.ok(!output(result).includes('fixture-secret'))
})

test('test intent: malformed port or database values fail before database access without being reflected', () => {
  for (const port of ['0', '65536', 'not-a-port', '123456']) {
    const result = runLibrarySnippet("configure_connection '' '' '' ''", { VPS_POSTGRES_PORT: port })
    assert.notEqual(result.status, 0, port)
    assert.ok(result.stderr.includes('decimal port number'), port)
    assert.ok(!result.stderr.includes(port), port)
  }

  const result = runLibrarySnippet("configure_connection '' '' '' ''", {
    VPS_POSTGRES_DATABASE: 'dbname=x password=connection-secret',
  })
  assert.notEqual(result.status, 0)
  assert.ok(result.stderr.includes('plain PostgreSQL identifier'))
  assert.ok(!output(result).includes('connection-secret'))
})

test('test intent: a work directory inside the repository is rejected', () => {
  // Sensitive backups must never be materialized beneath the Git workspace.
  const inside = runLibrarySnippet('require_external_directory "$REPO_ROOT/runtime/maintenance" "the backup directory"')
  assert.notEqual(inside.status, 0)
  assert.ok(inside.stderr.includes('must be outside the repository'))

  const outside = mkdtempSync(path.join(tmpdir(), 'kk-ops-outside-'))
  try {
    const allowed = runLibrarySnippet(`require_external_directory ${shellQuote(outside)} "the backup directory"`)
    assert.equal(allowed.status, 0, allowed.stderr)
  } finally {
    rmSync(outside, { recursive: true, force: true })
  }
})

test('test intent: PGDATABASE must be a plain database name', () => {
  // A URI or conninfo can hide a password, so only a plain identifier is accepted.
  for (const value of [
    'postgres://postgres:uri-secret@127.0.0.1/kk_studio',
    'dbname=kk_studio password=conninfo-secret',
    'kk_studio;drop',
    'kk-studio',
  ]) {
    const result = runLibrarySnippet('resolve_target_database; printf \'%s\\n\' "$TARGET_DB"', { PGDATABASE: value })
    assert.notEqual(result.status, 0, value)
    assert.ok(result.stderr.includes('plain PostgreSQL identifier'), value)
    for (const secret of ['uri-secret', 'conninfo-secret']) {
      assert.ok(!output(result).includes(secret), value)
    }
  }

  const accepted = runLibrarySnippet('resolve_target_database; printf \'%s\\n\' "$TARGET_DB"', { PGDATABASE: 'kk_studio' })
  assert.equal(accepted.status, 0, accepted.stderr)
  assert.equal(accepted.stdout.trim(), 'kk_studio')
})

test('test intent: the sha256 helper streams a file and path resolution handles missing leaves', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-ops-helpers-'))
  try {
    const file = path.join(directory, 'artifact.bin')
    const digest = runLibrarySnippet(`printf 'abc' > ${shellQuote(file)}\nfile_sha256 ${shellQuote(file)}`)
    assert.equal(digest.status, 0, digest.stderr)
    assert.equal(digest.stdout.trim(), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')

    const missing = path.join(directory, 'does', 'not', 'exist')
    const resolved = runLibrarySnippet(`resolve_absolute_path ${shellQuote(missing)}`)
    assert.equal(resolved.status, 0, resolved.stderr)
    assert.ok(resolved.stdout.trim().endsWith(path.join('does', 'not', 'exist')))
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
