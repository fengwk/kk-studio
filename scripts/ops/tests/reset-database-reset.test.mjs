/**
 * Reset behaviour of reset-database.sh against a real PostgreSQL container.
 *
 * These are behaviours that shell contracts cannot show: exact libpq connection and authentication
 * mechanics without command-line passwords, real pg_dump/pg_restore archives, session detection and
 * database freezing, a reversible freeze that rolls back when a later step fails, encoding / owner /
 * connection-limit preservation, and the documented VPS_POSTGRES_* / PGSERVICE / CLI connection
 * sources with credential non-disclosure. Skipped when no Docker daemon is reachable.
 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { after, afterEach, before, beforeEach, describe, it } from 'node:test'

import {
  CURRENT_FIXTURE_SQL,
  FIXTURE_PASSWORD,
  PostgresContainer,
  ResetTestContext,
  V1_SCHEMA_SQL,
  WORKTREE_ROOT,
  dockerAvailable,
  sanitizeError,
  uniqueSuffix,
} from './support/postgres-fixture.mjs'

const which = (tool) => spawnSync('which', [tool], { encoding: 'utf8' }).stdout.trim()
const dumpsIn = (directory) => readdirSync(directory).filter((name) => name.endsWith('.dump')).map((name) => path.join(directory, name))

describe('reset-database.sh reset', { skip: !dockerAvailable() && 'Docker daemon is not reachable' }, () => {
  const container = new PostgresContainer()
  let ctx
  before(() => container.start(), { timeout: 240_000 })
  after(() => container.stop())
  beforeEach(() => {
    ctx = new ResetTestContext(container)
  })
  afterEach(() => ctx.cleanup())

  const agentCount = (database) => Number(ctx.psql(database, 'SELECT count(*) FROM agent_definition'))
  const allowConn = (database) => ctx.psql('postgres', `SELECT datallowconn FROM pg_database WHERE datname = '${database}'`)
  const snapshotCount = (database) => Number(ctx.psql('postgres', `SELECT count(*) FROM pg_database WHERE datname LIKE '${database}_pre_%'`))

  /** A seeded V1 database with the current fixture rows (two agent definitions). */
  function seededDatabase(prefix) {
    const name = ctx.registerDb(`${prefix}_${uniqueSuffix()}`)
    ctx.createV1Database(name)
    ctx.psqlFile(name, CURRENT_FIXTURE_SQL)
    return name
  }

  // Guards: protect data integrity across critical failure modes.

  it('--dry-run creates no directories and changes nothing', () => {
    const database = seededDatabase('probe_reset_dry')
    const workDir = path.join(ctx.testDir, 'dry_run_dir')
    const result = ctx.runScript(['--dry-run', '--work-dir', workDir], { env: { PGDATABASE: database } })
    assert.equal(result.status, 0, `Dry-run failed: ${sanitizeError(result.stderr)}`)
    assert.ok(!existsSync(workDir), 'Dry-run must not create any work directory')
    assert.equal(agentCount(database), 2)
  })

  it('refuses a target with another connected session and leaves it untouched', async () => {
    const database = seededDatabase('probe_reset_busy')
    const session = await ctx.openSession(database, 30, ['--no-password'])
    try {
      const result = ctx.runScript(['--yes', '--work-dir', path.join(ctx.testDir, 'busy_reset_dir')], { env: { PGDATABASE: database } })
      assert.notEqual(result.status, 0, 'Reset should refuse target with active sessions')
      assert.ok(result.stderr.includes('other session'))
      assert.equal(agentCount(database), 2)
    } finally {
      session.kill('SIGTERM')
    }
  })

  it('a failing createdb unfreezes the original and keeps the backup', () => {
    const database = seededDatabase('probe_reset_fail_cdb')
    const stubDir = ctx.writeStub(path.join(ctx.testDir, 'stub_bin'), 'createdb', "#!/bin/sh\necho 'simulated createdb failure' >&2\nexit 1\n")
    const workDir = path.join(ctx.testDir, 'fail_reset_dir')
    const result = ctx.runScript(['--yes', '--work-dir', workDir], {
      env: { PATH: `${stubDir}:${process.env.PATH}`, PGDATABASE: database },
    })
    assert.notEqual(result.status, 0, 'Reset should fail when createdb fails')

    assert.equal(allowConn(database), 't', 'Original database was not unfrozen')
    assert.equal(agentCount(database), 2)
    assert.equal(snapshotCount(database), 0, 'Snapshot database was not cleaned up after rollback')

    const dumps = dumpsIn(workDir)
    assert.equal(dumps.length, 1, 'Backup dump was not retained')
    assert.equal(statSync(dumps[0]).mode & 0o777, 0o600)
    const restore = spawnSync('pg_restore', ['--no-password', '--list', dumps[0]], { env: ctx.clientEnv })
    assert.equal(restore.status, 0)
  })

  it('a failure after createdb succeeded restores the original over the target name', () => {
    // The new database is dropped and the snapshot is renamed back; it must not be misread as "never created".
    const database = ctx.registerDb(`probe_reset_late_${uniqueSuffix()}`)
    ctx.psql('postgres', `CREATE DATABASE "${database}" CONNECTION LIMIT 5`)
    ctx.psqlFile(database, V1_SCHEMA_SQL)
    ctx.psqlFile(database, CURRENT_FIXTURE_SQL)

    // Only the connection-limit step fails: createdb and the rename into place succeed.
    const stubDir = ctx.writeStub(
      path.join(ctx.testDir, 'late_stub_bin'),
      'psql',
      '#!/bin/sh\n' +
        'for arg in "$@"; do\n' +
        '  case "$arg" in\n' +
        "    *'connection limit'*) echo 'simulated late failure' >&2; exit 1 ;;\n" +
        '  esac\n' +
        'done\n' +
        `exec ${which('psql')} "$@"\n`,
    )
    const result = ctx.runScript(['--yes', '--work-dir', path.join(ctx.testDir, 'late_reset_dir')], {
      env: { PATH: `${stubDir}:${process.env.PATH}`, PGDATABASE: database },
    })
    assert.notEqual(result.status, 0, 'Reset should fail on the late failure')
    assert.ok(result.stderr.includes('Dropped the incomplete database'))
    assert.ok(result.stderr.includes('back in place'))

    // The target name again points at the original data, unfrozen and complete.
    assert.equal(allowConn(database), 't')
    assert.equal(agentCount(database), 2)
    assert.equal(Number(ctx.psql('postgres', "SELECT count(*) FROM pg_database WHERE datname LIKE '%kk_partial%'")), 0, 'A partial database was left behind')
    assert.equal(snapshotCount(database), 0, 'The snapshot name was left behind after a successful rollback')
  })

  it('a session racing in after preflight is detected after the freeze and the original is restored', () => {
    // A connection that appears after the old database was renamed and made non-connectable must
    // restore the original name instead of creating an empty replacement.
    const database = seededDatabase('probe_reset_race')
    const stubDir = ctx.writeStub(
      path.join(ctx.testDir, 'race_stub_bin'),
      'psql',
      '#!/bin/sh\n' +
        'previous=\n' +
        'for arg in "$@"; do\n' +
        '  if [ "$previous" = -c ]; then\n' +
        `    case "$arg" in *pg_stat_activity*"datname = '${database}_pre_"*) printf '1\\n'; exit 0 ;; esac\n` +
        '  fi\n' +
        '  previous=$arg\n' +
        'done\n' +
        `exec ${which('psql')} "$@"\n`,
    )
    const result = ctx.runScript(['--yes', '--work-dir', path.join(ctx.testDir, 'race_reset_dir')], {
      env: { PATH: `${stubDir}:${process.env.PATH}`, PGDATABASE: database },
    })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('connected after preflight'))
    assert.ok(result.stderr.includes('back in place'))
    assert.equal(agentCount(database), 2)
    assert.equal(snapshotCount(database), 0)
  })

  it('a successful reset keeps the metadata, freezes the snapshot and leaves no temporary name', () => {
    const database = ctx.registerDb(`probe_reset_ok_${uniqueSuffix()}`)
    ctx.psql('postgres', `CREATE DATABASE "${database}" CONNECTION LIMIT 3`)
    ctx.psqlFile(database, V1_SCHEMA_SQL)
    ctx.psqlFile(database, CURRENT_FIXTURE_SQL)

    const workDir = path.join(ctx.testDir, 'ok_reset_dir')
    const result = ctx.runScript(['--yes', '--work-dir', workDir], { env: { PGDATABASE: database } })
    assert.equal(result.status, 0, `Reset failed: ${sanitizeError(result.stderr)}`)
    assert.ok(result.stdout.includes('role:           superuser'))

    const metadata = ctx
      .psql(
        'postgres',
        "SELECT datallowconn || ' ' || datconnlimit || ' ' || pg_get_userbyid(datdba) || ' ' || pg_encoding_to_char(encoding)" +
          ` FROM pg_database WHERE datname = '${database}'`,
      )
      .split(/\s+/)
    assert.deepEqual(metadata, ['true', '3', 'postgres', 'UTF8'])

    // A reset leaves a completely empty database for the external schema initialization.
    assert.equal(Number(ctx.psql(database, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")), 0)

    const snapshot = ctx.psql('postgres', `SELECT datname FROM pg_database WHERE datname LIKE '${database}_pre_%'`)
    // A frozen snapshot cannot be read without unfreezing it, which is the point: the rows are
    // proven to be there by the rollback guards above, and by the backup below.
    assert.equal(allowConn(snapshot), 'f', 'The snapshot must stay frozen')
    ctx.registerDb(snapshot)
    const dumps = dumpsIn(workDir)
    assert.equal(dumps.length, 1, 'The full backup was not retained')
    assert.equal(spawnSync('pg_restore', ['--no-password', '--list', dumps[0]], { env: ctx.clientEnv }).status, 0)
    assert.equal(Number(ctx.psql('postgres', "SELECT count(*) FROM pg_database WHERE datname LIKE '%kk_partial%'")), 0, 'A partial database name was left behind')
  })

  // Portable privileges and the PGSERVICE connection contract.

  describe('portable privileges and service connection', () => {
    /** Environment for a specific role: the fixture passfile only knows postgres. */
    const roleEnv = (role, password) => ({ PGPASSFILE: null, PGUSER: role, PGPASSWORD: password })

    function setup() {
      const suffix = uniqueSuffix()
      const ownerRole = `probe_owner_${suffix}`
      const plainRole = `probe_plain_${suffix}`
      ctx.addCleanup(() => {
        for (const role of [ownerRole, plainRole]) {
          ctx.psql('postgres', `DROP ROLE IF EXISTS "${role}"`)
        }
      })
      ctx.psql(
        'postgres',
        `CREATE ROLE "${ownerRole}" LOGIN CREATEDB PASSWORD 'probe-owner-pw'; CREATE ROLE "${plainRole}" LOGIN PASSWORD 'probe-plain-pw'`,
      )
      return { suffix, ownerRole, plainRole }
    }

    it('an owner role with CREATEDB resets without being a superuser', () => {
      const { suffix, ownerRole } = setup()
      const owned = ctx.registerDb(`probe_owned_${suffix}`)
      ctx.psql('postgres', `CREATE DATABASE "${owned}" OWNER "${ownerRole}" CONNECTION LIMIT 4`)
      const env = ctx.scriptEnv(roleEnv(ownerRole, 'probe-owner-pw'))
      // The target role owns its tables, which is what pg_dump needs to read the whole database.
      ctx.psqlFile(owned, V1_SCHEMA_SQL, env)
      ctx.psqlFile(owned, CURRENT_FIXTURE_SQL, env)

      const result = ctx.runScript(['--yes', '--work-dir', path.join(ctx.testDir, 'owner_dir')], {
        env: { PGDATABASE: owned, ...roleEnv(ownerRole, 'probe-owner-pw') },
      })
      assert.equal(result.status, 0, `Owner reset failed: ${sanitizeError(result.stderr)}`)
      assert.ok(result.stdout.includes('database owner with CREATEDB'))
      const metadata = ctx.psql(
        'postgres',
        `SELECT datconnlimit || ' ' || pg_get_userbyid(datdba) || ' ' || datallowconn FROM pg_database WHERE datname = '${owned}'`,
      )
      assert.equal(metadata, `4 ${ownerRole} true`)
      ctx.registerDb(ctx.psql('postgres', `SELECT datname FROM pg_database WHERE datname LIKE '${owned}_pre_%'`))
    })

    it('a role that does not own the target is refused read-only', () => {
      const { suffix, ownerRole } = setup()
      const foreign = ctx.registerDb(`probe_foreign_${suffix}`)
      ctx.psql('postgres', `CREATE DATABASE "${foreign}" OWNER "postgres" CONNECTION LIMIT 4`)
      const result = ctx.runScript(['--dry-run', '--work-dir', path.join(ctx.testDir, 'foreign_dir')], {
        env: { PGDATABASE: foreign, ...roleEnv(ownerRole, 'probe-owner-pw') },
      })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('cannot rename or freeze'))
      assert.equal(allowConn(foreign), 't')
    })

    it('a role without CREATEDB is refused read-only', () => {
      const { suffix, plainRole } = setup()
      const plain = ctx.registerDb(`probe_plain_owner_${suffix}`)
      ctx.psql('postgres', `CREATE DATABASE "${plain}" OWNER "${plainRole}" CONNECTION LIMIT 4`)
      const env = ctx.scriptEnv(roleEnv(plainRole, 'probe-plain-pw'))
      ctx.psqlFile(plain, V1_SCHEMA_SQL, env)
      ctx.psqlFile(plain, CURRENT_FIXTURE_SQL, env)
      const result = ctx.runScript(['--dry-run', '--work-dir', path.join(ctx.testDir, 'plain_dir')], {
        env: { PGDATABASE: plain, ...roleEnv(plainRole, 'probe-plain-pw') },
      })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('cannot create databases'))
      assert.ok(result.stderr.includes('CREATEDB'))
    })

    it('PGSERVICE plus PGPASSFILE works without host or port', () => {
      const service = seededDatabase('probe_service')
      // Only PGSERVICE + PGPASSFILE: no PGHOST, PGPORT, PGUSER or passfile path in PG*.
      const result = ctx.runScript(['--dry-run', '--work-dir', path.join(ctx.testDir, 'service_backup_dir')], {
        env: {
          PGHOST: null,
          PGPORT: null,
          PGUSER: null,
          PGSERVICE: 'kk_studio_integration',
          PGSERVICEFILE: container.serviceFile,
          PGPASSFILE: container.passfile,
          PGDATABASE: service,
        },
      })
      assert.equal(result.status, 0, `Service reset preflight failed: ${sanitizeError(result.stderr)}`)
      assert.ok(result.stdout.includes('maintenance db: postgres'))
    })
  })

  // VPS_POSTGRES_* and CLI connection sources.

  it('VPS_POSTGRES_* and the CLI options select the connection with real password authentication', () => {
    // The documented VPS_POSTGRES_* contract performs real password authentication, overrides
    // conflicting PG* defaults and never reflects the password; the four non-secret CLI options
    // override conflicting VPS endpoint values.
    const target = seededDatabase('probe_conn_target')
    const conflictingDefaults = {
      PGHOST: '127.0.0.2',
      PGHOSTADDR: '127.0.0.2',
      PGPORT: '1',
      PGUSER: 'wrong-role',
      PGDATABASE: 'wrong_database',
      PGPASSWORD: null,
      PGPASSFILE: null,
      PGSERVICE: 'wrong-service',
    }
    const vps = ctx.runScript(['--dry-run', '--work-dir', path.join(ctx.testDir, 'vps_env_backup')], {
      env: {
        ...conflictingDefaults,
        VPS_POSTGRES_HOST: '127.0.0.1',
        VPS_POSTGRES_PORT: String(container.port),
        VPS_POSTGRES_USERNAME: 'postgres',
        VPS_POSTGRES_PASSWORD: FIXTURE_PASSWORD,
        VPS_POSTGRES_DATABASE: target,
      },
    })
    assert.equal(vps.status, 0, `VPS environment reset failed: ${sanitizeError(vps.stderr)}`)
    assert.ok(vps.stdout.includes(`database:       ${target}`))

    const cli = ctx.runScript(
      [
        '--dry-run',
        '--host',
        '127.0.0.1',
        '--port',
        String(container.port),
        '--username',
        'postgres',
        '--database',
        target,
        '--work-dir',
        path.join(ctx.testDir, 'cli_env_backup'),
      ],
      {
        env: {
          ...conflictingDefaults,
          VPS_POSTGRES_HOST: '127.0.0.2',
          VPS_POSTGRES_PORT: '1',
          VPS_POSTGRES_USERNAME: 'wrong-role',
          VPS_POSTGRES_PASSWORD: FIXTURE_PASSWORD,
          VPS_POSTGRES_DATABASE: 'wrong_database',
        },
      },
    )
    assert.equal(cli.status, 0, `CLI reset failed: ${sanitizeError(cli.stderr)}`)
    assert.ok(cli.stdout.includes(`database:       ${target}`))
    assert.ok(cli.stdout.includes('Dry-run complete'))
  })

  // Ambient pollution cannot redirect reset.

  it('hostile ambient PG/VPS/KK_STUDIO settings cannot redirect a real reset', () => {
    // The harness ignores ambient settings and `reset --yes` can only replace the explicitly
    // injected throwaway fixture database.
    const target = seededDatabase('probe_isolated_reset')
    const untouched = seededDatabase('probe_isolated_control')
    const pollution = {
      PGHOSTADDR: '192.0.2.10',
      PGSERVICE: 'hostile-service',
      VPS_POSTGRES_HOST: '192.0.2.11',
      VPS_POSTGRES_DATABASE: 'hostile_database',
      KK_STUDIO_MAINTENANCE_DB: 'hostile_maintenance',
    }
    const saved = Object.fromEntries(Object.keys(pollution).map((key) => [key, process.env[key]]))
    Object.assign(process.env, pollution)
    let result
    try {
      for (const key of Object.keys(pollution)) {
        assert.ok(!(key in ctx.clientEnv), key)
      }
      result = ctx.runScript(['--yes', '--database', target, '--work-dir', path.join(ctx.testDir, 'isolated_reset')])
    } finally {
      for (const [key, value] of Object.entries(saved)) {
        if (value === undefined) {
          delete process.env[key]
        } else {
          process.env[key] = value
        }
      }
    }
    assert.equal(result.status, 0, sanitizeError(result.stderr))
    assert.equal(ctx.psql(target, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"), '0')
    assert.equal(ctx.psql(untouched, 'SELECT count(*) FROM agent_definition'), '2')
  })

  // Credential non-disclosure.

  describe('credential non-disclosure', () => {
    const wrongPassword = 'wrong-secret-password-val-987'

    it('a wrong PGPASSWORD fails without leaking the password', () => {
      // The container pg_hba.conf enforces scram-sha-256 on the published loopback endpoint.
      const workDir = path.join(ctx.testDir, 'disclosure_work')
      const result = ctx.runScript(['--dry-run', '--work-dir', workDir], {
        env: { PGPASSWORD: wrongPassword, PGPASSFILE: '/dev/null', PGDATABASE: 'probe_auth_target' },
      })
      assert.notEqual(result.status, 0, 'Wrong password must fail')
      assert.ok(result.stderr.includes('authentication failed'))
      assert.ok(!result.stdout.includes(wrongPassword), 'Password leaked into stdout')
      assert.ok(!result.stderr.includes(wrongPassword), 'Password leaked into stderr')
      ctx.assertNoFixtureValues(result.stdout + result.stderr)
    })

    it('a missing password fails immediately instead of prompting', () => {
      const emptyPassfile = path.join(ctx.testDir, 'empty_passfile')
      writeFileSync(emptyPassfile, '', { mode: 0o600 })
      const result = ctx.runScript(['--dry-run', '--work-dir', path.join(ctx.testDir, 'disclosure_work')], {
        env: { PGPASSWORD: '', PGPASSFILE: emptyPassfile, PGDATABASE: 'probe_auth_target' },
        timeout: 10_000,
      })
      assert.notEqual(result.status, 0, 'Missing password must fail')
      const stderr = result.stderr.toLowerCase()
      assert.ok(stderr.includes('no password supplied') || stderr.includes('fe_sendauth'), `Expected fe_sendauth failure message, got: ${result.stderr}`)
      ctx.assertNoFixtureValues(result.stdout + result.stderr)
    })

    it('a URI or conninfo in PGDATABASE is refused without echoing secrets', () => {
      const workDir = path.join(ctx.testDir, 'disclosure_work')
      for (const [value, secret] of [
        ['postgres://user:fake-uri-secret-token-123@127.0.0.1/db', 'fake-uri-secret-token-123'],
        ['dbname=x password=fake-conninfo-secret-token-456', 'fake-conninfo-secret-token-456'],
      ]) {
        const result = ctx.runScript(['--dry-run', '--work-dir', workDir], { env: { PGDATABASE: value } })
        assert.notEqual(result.status, 0, 'URI/conninfo in PGDATABASE must be rejected')
        assert.ok(!result.stdout.includes(secret), 'secret leaked to stdout')
        assert.ok(!result.stderr.includes(secret), 'secret leaked to stderr')
        assert.ok(result.stderr.includes('plain PostgreSQL identifier'))
        ctx.assertNoFixtureValues(result.stdout + result.stderr)
      }
    })

    it('a work directory inside the repository is refused before any artifact is created', () => {
      const target = ctx.registerDb(`probe_repo_workdir_${uniqueSuffix()}`)
      ctx.createV1Database(target)
      const inRepoDir = path.join(WORKTREE_ROOT, 'scripts/ops/tests/inside_repo_output_dir')
      const result = ctx.runScript(['--dry-run', '--work-dir', inRepoDir], { env: { PGDATABASE: target } })
      assert.notEqual(result.status, 0, 'In-repository work-dir must be rejected')
      assert.ok(result.stderr.includes('must be outside the repository'))
      assert.ok(!existsSync(inRepoDir), 'In-repo directory must not be created')
      ctx.assertNoFixtureValues(result.stdout + result.stderr)
    })
  })
})
