/**
 * Snapshot cleanup of reset-database.sh against a real PostgreSQL container.
 *
 * Cleanup deletes only exact frozen snapshots of the named target, after confirmation, revalidating
 * identity and frozen state right before each drop. Skipped when no Docker daemon is reachable.
 */

import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { after, before, afterEach, beforeEach, describe, it } from 'node:test'

import { WORKTREE_ROOT, FIXTURE_PASSWORD, PostgresContainer, RESET_SCRIPT, ResetTestContext, dockerAvailable, sleep, uniqueSuffix } from './support/postgres-fixture.mjs'

const which = (tool) => spawnSync('which', [tool], { encoding: 'utf8' }).stdout.trim()

describe('reset-database.sh snapshot cleanup', { skip: !dockerAvailable() && 'Docker daemon is not reachable' }, () => {
  const container = new PostgresContainer()
  let ctx
  before(() => container.start(), { timeout: 240_000 })
  after(() => container.stop())
  beforeEach(() => {
    ctx = new ResetTestContext(container)
  })
  afterEach(() => ctx.cleanup())

  it('matches only exact frozen snapshots', () => {
    const target = `Probe_clean_${uniqueSuffix()}`
    ctx.createEmptyDb(target)
    ctx.psql(target, 'CREATE TABLE retained (id int); INSERT INTO retained VALUES (7)')
    const first = ctx.createSnapshot(target)
    const second = ctx.createSnapshot(target, '20260102T030405Z')
    const preserved = [
      ctx.createSnapshot(target, '20260103T000000Z', { frozen: false }),
      ctx.createSnapshot(target, '20260104T000000Z', { template: true }),
      ctx.createSnapshot(target, '20260105T000000Z_extra'),
      ctx.createSnapshot(target, '20260106T000000'),
      ctx.createSnapshot(target, '20260107T000000z'),
      ctx.createSnapshot(target.replaceAll('_', 'x')),
      ctx.createSnapshot(target.toLowerCase()),
      ctx.createSnapshot(`other_${target}`),
      ctx.createSnapshot(`${target}_pre`),
    ]
    const backup = path.join(ctx.testDir, 'retained.dump')
    writeFileSync(backup, 'retained backup fixture')
    const dry = ctx.runScript(['--database', target, '--cleanup-snapshots', '--dry-run', '--work-dir', backup])
    assert.equal(dry.status, 0, dry.stderr)
    assert.ok(dry.stdout.includes(first))
    assert.ok(dry.stdout.includes(second))
    for (const name of preserved) {
      assert.ok(!dry.stdout.includes(`${name} (`), name)
    }
    for (const name of [first, second, ...preserved]) {
      assert.ok(ctx.databaseExists(name), name)
    }
    const result = ctx.runScript(['--database', target, '--cleanup-snapshots', '--work-dir', backup], { input: `${target}\n` })
    assert.equal(result.status, 0, result.stderr)
    assert.ok(result.stdout.includes('2 snapshot(s) deleted'))
    assert.ok(!ctx.databaseExists(first))
    assert.ok(!ctx.databaseExists(second))
    for (const name of preserved) {
      assert.ok(ctx.databaseExists(name), name)
    }
    assert.equal(ctx.psql(target, 'SELECT id FROM retained'), '7')
    assert.equal(readFileSync(backup, 'utf8'), 'retained backup fixture')
  })

  it('requires confirmation and handles an empty selection', () => {
    const target = `probe_confirm_${uniqueSuffix()}`
    // Explicit target selection needs only the maintenance connection, not an existing target.
    const empty = ctx.runScript(['--cleanup-snapshots', '--database', target])
    assert.equal(empty.status, 0, empty.stderr)
    assert.ok(empty.stdout.includes('No frozen snapshots matched'))
    const snapshot = ctx.createSnapshot(target)
    for (const [answer, message] of [
      ['', 'input was not available'],
      ['wrong\n', 'did not match'],
    ]) {
      const refused = ctx.runScript(['--cleanup-snapshots', '--database', target], { input: answer })
      assert.notEqual(refused.status, 0)
      assert.ok(refused.stderr.includes(message), message)
      assert.ok(ctx.databaseExists(snapshot))
    }
    const unused = path.join(WORKTREE_ROOT, 'unused')
    const result = ctx.runScript(['--cleanup-snapshots', '--database', target, '--yes'], { env: { KK_STUDIO_RESET_DIR: unused } })
    assert.equal(result.status, 0, result.stderr)
    assert.ok(!ctx.databaseExists(snapshot))
    assert.ok(!existsSync(unused))
  })

  it('needs only psql, not the backup tools', () => {
    const target = `probe_tools_${uniqueSuffix()}`
    const snapshot = ctx.createSnapshot(target)
    const tools = path.join(ctx.testDir, 'tools')
    mkdirSync(tools)
    for (const tool of ['bash', 'date', 'dirname', 'psql']) {
      symlinkSync(which(tool), path.join(tools, tool))
    }
    const result = ctx.runScript(['--cleanup-snapshots', '--yes'], { env: { PATH: tools, VPS_POSTGRES_DATABASE: target } })
    assert.equal(result.status, 0, result.stderr)
    assert.ok(!ctx.databaseExists(snapshot))
  })

  it('preflights all permissions and needs no CREATEDB', () => {
    const target = `probe_perm_${uniqueSuffix()}`
    const first = ctx.createSnapshot(target)
    const second = ctx.createSnapshot(target, '20260102T000000Z')
    const role = `probe_clean_owner_${uniqueSuffix()}`
    ctx.psql('postgres', `CREATE ROLE ${role} LOGIN NOCREATEDB PASSWORD '${FIXTURE_PASSWORD}'`)
    ctx.addCleanup(() => ctx.psql('postgres', `DROP ROLE ${role}`))
    ctx.psql('postgres', `ALTER DATABASE "${first}" OWNER TO ${role}`)
    const env = { PGUSER: role, PGPASSWORD: FIXTURE_PASSWORD, PGPASSFILE: null }
    const refused = ctx.runScript(['--cleanup-snapshots', '--database', target, '--yes'], { env })
    assert.notEqual(refused.status, 0)
    assert.ok(refused.stderr.includes('not the database owner or a superuser'))
    assert.ok(ctx.databaseExists(first))
    assert.ok(ctx.databaseExists(second))
    ctx.psql('postgres', `ALTER DATABASE "${second}" OWNER TO ${role}`)
    const result = ctx.runScript(['--cleanup-snapshots', '--database', target, '--yes'], { env })
    assert.equal(result.status, 0, result.stderr)
    assert.ok(!ctx.databaseExists(first))
    assert.ok(!ctx.databaseExists(second))
  })

  it('preserves active sessions', async () => {
    const target = `probe_busy_${uniqueSuffix()}`
    ctx.createEmptyDb(target)
    const first = ctx.createSnapshot(target)
    const busy = ctx.createSnapshot(target, '20260102T000000Z', { frozen: false })
    const sessions = []
    try {
      for (const database of [target, busy]) {
        sessions.push(await ctx.openSession(database, 45))
      }
      ctx.psql('postgres', `ALTER DATABASE "${busy}" ALLOW_CONNECTIONS false`)
      let result = ctx.runScript(['--cleanup-snapshots', '--database', target, '--yes'])
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('active sessions exist'))
      assert.ok(ctx.databaseExists(first))
      assert.ok(ctx.databaseExists(busy))
      assert.ok(sessions.every((session) => session.exitCode === null))
      // An online target is not a cleanup blocker; only the frozen snapshot's session is.
      ctx.psql('postgres', `ALTER DATABASE "${busy}" ALLOW_CONNECTIONS true`)
      result = ctx.runScript(['--cleanup-snapshots', '--database', target, '--yes'])
      assert.equal(result.status, 0, result.stderr)
      assert.ok(!ctx.databaseExists(first))
      assert.ok(sessions.every((session) => session.exitCode === null))
    } finally {
      for (const session of sessions) {
        session.kill('SIGTERM')
      }
    }
  })

  /** Change the catalog while the operator is at the confirmation boundary. */
  async function cleanupWithMutation(target, mutate) {
    const child = spawn('bash', [RESET_SCRIPT, '--cleanup-snapshots', '--database', target], {
      env: ctx.clientEnv,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => {
      stdout += chunk
    })
    child.stderr.on('data', (chunk) => {
      stderr += chunk
    })
    const closed = new Promise((resolve) => child.once('close', resolve))
    try {
      const marker = 'The target database, backup files and object storage are not changed.'
      const deadline = Date.now() + 15_000
      while (!stdout.includes(marker)) {
        assert.ok(Date.now() < deadline, 'cleanup did not reach confirmation')
        assert.equal(child.exitCode, null, 'cleanup exited before confirmation')
        await sleep(50)
      }
      mutate()
      child.stdin.end(`${target}\n`)
      const status = await closed
      ctx.assertNoFixtureValues(stdout + stderr)
      return { status, stdout, stderr }
    } finally {
      child.kill('SIGKILL')
    }
  }

  it('revalidates identity and frozen state after confirmation', async () => {
    for (const change of ['unfreeze', 'replace', 'disappear', 'template']) {
      const target = `probe_race_${uniqueSuffix()}`
      const first = ctx.createSnapshot(target)
      const second = ctx.createSnapshot(target, '20260102T000000Z')
      const mutate = () => {
        if (change === 'unfreeze' || change === 'template') {
          const setting = change === 'unfreeze' ? 'ALLOW_CONNECTIONS true' : 'IS_TEMPLATE true'
          ctx.psql('postgres', `ALTER DATABASE "${second}" ${setting}`)
        } else {
          ctx.psql('postgres', `DROP DATABASE "${second}"`)
          if (change === 'replace') {
            ctx.createSnapshot(target, '20260102T000000Z')
          }
        }
      }
      const { status, stderr } = await cleanupWithMutation(target, mutate)
      assert.notEqual(status, 0, change)
      assert.ok(stderr.includes('stopped after 0 deletion(s)'), change)
      assert.ok(ctx.databaseExists(first), change)
      assert.equal(ctx.databaseExists(second), change !== 'disappear', change)
    }
  })

  it('does not sweep snapshots created after the plan', async () => {
    const target = `probe_arrival_${uniqueSuffix()}`
    const first = ctx.createSnapshot(target)
    const newName = `${target}_pre_20260102T000000Z`
    const { status, stdout, stderr } = await cleanupWithMutation(target, () => ctx.createSnapshot(target, '20260102T000000Z'))
    assert.equal(status, 0, stderr)
    assert.ok(stdout.includes('1 snapshot(s) deleted'))
    assert.ok(!ctx.databaseExists(first))
    assert.ok(ctx.databaseExists(newName))
  })

  it('reports partial completion on a late drop failure', () => {
    const target = `probe_partial_${uniqueSuffix()}`
    const snapshots = [1, 2, 3].map((index) => ctx.createSnapshot(target, `2026010${index}T000000Z`))
    const stubDir = ctx.writeStub(
      path.join(ctx.testDir, 'drop_failure'),
      'psql',
      '#!/bin/sh\n' +
        'for arg in "$@"; do\n' +
        `  if [ "$arg" = 'drop database "${snapshots[1]}"' ]; then\n` +
        "    echo 'simulated drop failure' >&2; exit 1\n" +
        '  fi\n' +
        'done\n' +
        `exec ${which('psql')} "$@"\n`,
    )
    const result = ctx.runScript(['--cleanup-snapshots', '--database', target, '--yes'], {
      env: { PATH: `${stubDir}:${ctx.clientEnv.PATH}` },
    })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('stopped after 1 deletion(s)'))
    assert.ok(result.stdout.includes(`Deleted snapshot: ${snapshots[0]}`))
    assert.ok(!result.stdout.includes('cleanup complete'))
    assert.ok(!ctx.databaseExists(snapshots[0]))
    assert.ok(ctx.databaseExists(snapshots[1]))
    assert.ok(ctx.databaseExists(snapshots[2]))
  })

  it('rechecks each snapshot during deletion', () => {
    const target = `probe_midway_${uniqueSuffix()}`
    const first = ctx.createSnapshot(target)
    const second = ctx.createSnapshot(target, '20260102T000000Z')
    const realPsql = which('psql')
    const stubDir = ctx.writeStub(
      path.join(ctx.testDir, 'unfreeze_during_drop'),
      'psql',
      '#!/bin/sh\n' +
        'for arg in "$@"; do\n' +
        `  if [ "$arg" = 'drop database "${first}"' ]; then\n` +
        `    ${realPsql} -X -q -w -v ON_ERROR_STOP=1 -d postgres ` +
        `-c 'ALTER DATABASE "${second}" ALLOW_CONNECTIONS true' || exit 1\n` +
        '  fi\n' +
        'done\n' +
        `exec ${realPsql} "$@"\n`,
    )
    const result = ctx.runScript(['--cleanup-snapshots', '--database', target, '--yes'], {
      env: { PATH: `${stubDir}:${ctx.clientEnv.PATH}` },
    })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('not a frozen non-template database'))
    assert.ok(result.stderr.includes('stopped after 1 deletion(s)'))
    assert.ok(!ctx.databaseExists(first))
    assert.ok(ctx.databaseExists(second))
  })

  it('excludes the maintenance database', () => {
    const target = `probe_maint_${uniqueSuffix()}`
    const maintenance = ctx.createSnapshot(target, '20260101T000000Z', { frozen: false })
    // A custom maintenance name can resemble a snapshot; it must remain available.
    const snapshot = ctx.createSnapshot(target, '20260102T000000Z')
    const result = ctx.runScript(['--cleanup-snapshots', '--database', target, '--yes'], {
      env: { KK_STUDIO_MAINTENANCE_DB: maintenance },
    })
    assert.equal(result.status, 0, result.stderr)
    assert.ok(ctx.databaseExists(maintenance))
    assert.ok(!ctx.databaseExists(snapshot))
    const refused = ctx.runScript(['--cleanup-snapshots', '--database', 'postgres', '--yes'])
    assert.notEqual(refused.status, 0)
    assert.ok(refused.stderr.includes('must differ from the maintenance database'))
  })

  it('keeps older snapshots on a default reset', () => {
    const target = `probe_retain_${uniqueSuffix()}`
    ctx.createEmptyDb(target)
    const old = ctx.createSnapshot(target)
    const result = ctx.runScript(['--database', target, '--yes', '--work-dir', path.join(ctx.testDir, 'backup')])
    assert.equal(result.status, 0, result.stderr)
    assert.ok(ctx.databaseExists(old))
    const snapshots = ctx
      .psql('postgres', `SELECT datname FROM pg_database WHERE datname ~ '^${target}_pre_[0-9]{8}T[0-9]{6}Z$'`)
      .split('\n')
    assert.equal(snapshots.length, 2)
    for (const name of snapshots) {
      assert.equal(ctx.psql('postgres', `SELECT datallowconn FROM pg_database WHERE datname = '${name}'`), 'f')
    }
  })
})
