/**
 * Permanent shell contracts of the database reset maintenance script.
 *
 * Product settings are exported and imported by the product's own settings sync. Database
 * maintenance replaces a target with an empty database or explicitly cleans up its frozen
 * snapshots. Two properties must never regress and cannot be proven by an integration test alone:
 *
 * - the reset entrypoint and its private library stay strict, non-interactive, libpq-only and free
 *   of any container or application coupling;
 * - reset keeps an explicit confirmation, a full backup and a reversible freeze.
 *
 * The real-server behaviour of the same script is covered by `reset-database-integration.test.mjs`.
 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { accessSync, constants, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import test from 'node:test'

import { LIBRARY, PRODUCTION_FILES, REPOSITORY_ROOT, RESET_SCRIPT, functionBody, readText, runLibrarySnippet } from './support/library.mjs'

const name = (file) => file.split('/').at(-1)

test('test intent: the entrypoint is executable with strict shell defaults', () => {
  accessSync(RESET_SCRIPT, constants.X_OK)
  const source = readText(RESET_SCRIPT)
  for (const expected of ['set -euo pipefail', 'umask 077', 'lib/database-maintenance.sh', 'usage()']) {
    assert.ok(source.includes(expected), expected)
  }
  const syntax = spawnSync('bash', ['-n', RESET_SCRIPT], { encoding: 'utf8' })
  assert.equal(syntax.status, 0, syntax.stderr)
})

test('test intent: the shared library is sourced, never launched', () => {
  assert.ok(statSync(LIBRARY).isFile())
  assert.throws(() => accessSync(LIBRARY, constants.X_OK))
  assert.ok(readText(LIBRARY).includes('shellcheck shell=bash'))
  assert.ok(!readText(RESET_SCRIPT).includes(`bash ${LIBRARY}`))
})

test('test intent: maintenance needs no container, application or remote-shell coupling', () => {
  for (const file of PRODUCTION_FILES) {
    const source = readText(file).toLowerCase()
    for (const forbidden of ['docker', 'kubectl', 'jdbc', 'container', 'ssh', 'psycopg']) {
      assert.ok(!source.includes(forbidden), `${name(file)} mentions ${forbidden}`)
    }
  }
})

test('test intent: passwords never travel through client argv', () => {
  for (const file of PRODUCTION_FILES) {
    const source = readText(file)
    for (const forbidden of ['--password', ' -W ', 'password=']) {
      assert.ok(!source.includes(forbidden), `${name(file)} mentions ${forbidden}`)
    }
  }
  // The library is the only place that builds a libpq client argv.
  assert.ok(readText(LIBRARY).includes('--no-password'))
  const connection = functionBody(LIBRARY, 'configure_connection')
  assert.ok(connection.includes('PGPASSWORD=$VPS_POSTGRES_PASSWORD'))
  assert.ok(connection.includes('unset VPS_POSTGRES_PASSWORD'))
  assert.ok(!functionBody(RESET_SCRIPT, 'main').includes('--password)'))

  // A direct shell client invocation must carry the flag on the spot; psql goes through the library
  // wrappers, which already apply it. Comment lines are prose, not invocations.
  const invocation = /\b(psql|pg_dump|pg_restore|createdb)\s+-/
  for (const file of [LIBRARY, RESET_SCRIPT]) {
    for (const line of readText(file).split('\n')) {
      const stripped = line.trim()
      if (stripped.startsWith('#') || !invocation.test(stripped)) {
        continue
      }
      assert.ok(stripped.includes('--no-password'), `${name(file)}: ${stripped}`)
    }
  }
})

test('test intent: reset accepts the four non-secret connection options', () => {
  const source = readText(RESET_SCRIPT)
  const main = functionBody(RESET_SCRIPT, 'main')
  for (const option of ['--host', '--port', '--username', '--database']) {
    assert.ok(source.includes(option), option)
    assert.ok(main.includes(`${option})`), option)
  }
  assert.ok(source.includes('configure_connection'))
  assert.ok(source.includes('VPS_POSTGRES_PASSWORD'))
})

test('test intent: the destructive step needs confirmation and always keeps a snapshot', () => {
  const source = readText(RESET_SCRIPT)
  assert.ok(source.includes('--yes'))
  assert.ok(source.includes('read -r -p "Type the database name to continue: "'))
  assert.ok(source.includes('allow_connections false'))
  assert.ok(!source.includes('skip-snapshot'))
  assert.ok(!source.includes('dropdb'))
  // The freeze must be reversible when the empty database cannot be created.
  assert.ok(functionBody(RESET_SCRIPT, 'on_exit').includes('unfreeze_database'))
})

test('test intent: reset documents that the caller stops writes and the dump is a point in time', () => {
  const source = readText(RESET_SCRIPT)
  assert.ok(source.includes('stop application writes for the whole reset'))
  assert.ok(source.includes('not a fence against writes'))
})

test('test intent: creation state is recorded and rollback runs in the safe order', () => {
  const create = functionBody(RESET_SCRIPT, 'create_empty_database')
  // createdb runs against the temporary name; the state is recorded on the next line.
  assert.ok(create.includes('createdb'))
  assert.ok(create.includes('createdb "${arguments[@]}" "$PARTIAL_DB"'))
  assert.ok(create.indexOf('createdb') < create.indexOf('CREATED_DB=$PARTIAL_DB'))
  assert.ok(!create.includes('createdb "${arguments[@]}" "$TARGET_DB"'))
  assert.ok(create.includes('rename to \\"$TARGET_DB\\"'))
  assert.ok(create.indexOf('connection limit') < create.indexOf('rename to'))

  const exitBody = functionBody(RESET_SCRIPT, 'on_exit')
  // The new database must be dropped before the snapshot is renamed back over its name.
  assert.ok(exitBody.indexOf('drop database if exists') < exitBody.indexOf('unfreeze'))
  assert.ok(exitBody.includes('TARGET_REPLACED'))

  const freeze = functionBody(RESET_SCRIPT, 'freeze_target_database')
  assert.ok(freeze.indexOf('RENAMED=true') < freeze.indexOf('allow_connections false'))
  assert.ok(freeze.indexOf('allow_connections false') < freeze.indexOf('pg_stat_activity'))
  assert.ok(freeze.includes('connected after preflight'))
  // ALTER DATABASE ... RENAME cannot run inside a transaction block, so the two steps are sequenced
  // statements with per-statement state instead of one atomic statement.
  assert.ok(!freeze.includes('--single-transaction'))
  assert.ok(!create.includes('--single-transaction'))
})

test('test intent: reset accepts a non-superuser database owner', () => {
  // Reset needs ownership, CREATEDB and the ability to hand the database to its owner.
  const privileges = functionBody(RESET_SCRIPT, 'require_privileges')
  assert.ok(privileges.includes('rolcreatedb'))
  assert.ok(privileges.includes('pg_has_role(current_user, $(sql_literal "$DB_OWNER"), \'SET\')'))
  assert.ok(privileges.includes('datdba = (select oid from pg_roles where rolname = current_user)'))
  // Superuser is one accepted mode, not a requirement.
  assert.ok(privileges.includes('if [ "$is_superuser" = t ]; then'))
  assert.ok(privileges.includes('PRIVILEGE_MODE="superuser"'))
  assert.ok(privileges.includes('PRIVILEGE_MODE="database owner with CREATEDB"'))
  assert.ok(!readText(RESET_SCRIPT).includes('is_superuser" = t ] \\\n'))
  // The metadata is read field by field: a `|` in an owner, locale or tablespace name must not be
  // able to shift the values.
  const metadata = functionBody(RESET_SCRIPT, 'capture_database_metadata')
  assert.ok(!metadata.includes("|| '|'"))
  assert.ok(!metadata.includes("IFS='|'"))
  for (const field of ['datcollate', 'datctype', 'datconnlimit', 'datdba']) {
    assert.ok(metadata.includes(field), field)
  }
  // A role name can contain a quote, so it is embedded through the library helper.
  assert.ok(readText(LIBRARY).includes('sql_literal'))
  const escaped = runLibrarySnippet('sql_literal "it\'s"')
  assert.equal(escaped.status, 0, escaped.stderr)
  assert.equal(escaped.stdout.trim(), "'it''s'")
})

test('test intent: unknown or empty arguments are refused before connecting and never reflected', () => {
  const secret = 'fake-cli-password-value'
  const env = { PATH: process.env.PATH, HOME: tmpdir(), KK_STUDIO_REPO_ROOT: REPOSITORY_ROOT }
  const run = (...args) => spawnSync('bash', [RESET_SCRIPT, ...args], { encoding: 'utf8', env })

  const unknown = run(`--password=${secret}`)
  assert.notEqual(unknown.status, 0)
  assert.ok(unknown.stderr.includes('unknown argument'))
  assert.ok(!(unknown.stdout + unknown.stderr).includes(secret))
  for (const args of [['--host', ''], ['--host', '--dry-run']]) {
    const invalid = run(...args)
    assert.notEqual(invalid.status, 0, args.join(' '))
    assert.ok(invalid.stderr.includes('--host requires a value'), args.join(' '))
  }
})
