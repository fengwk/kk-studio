/**
 * Real-PostgreSQL fixtures for the database reset maintenance script.
 *
 * `PostgresContainer` owns a throwaway container; `ResetTestContext` is the per-test workspace
 * (temporary directory, databases to drop afterwards) with helpers to run psql and the script.
 */

import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { REPOSITORY_ROOT } from './library.mjs'

export const WORKTREE_ROOT = REPOSITORY_ROOT
export const RESET_SCRIPT = path.join(WORKTREE_ROOT, 'scripts/ops/reset-database.sh')
export const V1_SCHEMA_SQL = path.join(WORKTREE_ROOT, 'schema/src/main/resources/db/migration/V1__schema.sql')
export const CURRENT_FIXTURE_SQL = path.join(WORKTREE_ROOT, 'scripts/ops/tests/resources/reset_database/current_fixture.psql')

export const FIXTURE_PASSWORD = 'probe-fixture-pg-secret-pass-789'
export const POSTGRES_MAJOR = 17
export const POSTGRES_CLIENT_TOOLS = ['psql', 'pg_dump', 'pg_restore', 'createdb', 'pg_isready']

/**
 * Fixture values that must never be surfaced by the reset script. Assertions that look at command
 * output go through `assertNoFixtureValues` first, so a leak fails without echoing it.
 */
const FORBIDDEN_VALUES = [
  FIXTURE_PASSWORD,
  'wrong-secret-password-val-987',
  'fake-uri-secret-token-123',
  'fake-conninfo-secret-token-456',
  'probe-provider-credential',
]

/** psql error lines that may quote the offending row; never surface them in a test failure. */
const SECONDARY_ERROR_PREFIXES = ['DETAIL:', 'CONTEXT:', 'HINT:', 'LINE ', 'QUERY:', 'STATEMENT:', 'LOCATION:']

/** Keep the primary psql error line only; DETAIL/CONTEXT lines can quote a row value. */
export function sanitizeError(message) {
  let fallback = ''
  for (const line of message.split('\n')) {
    const stripped = line.trim()
    if (!stripped || SECONDARY_ERROR_PREFIXES.some((prefix) => stripped.startsWith(prefix))) {
      continue
    }
    if (stripped.includes('ERROR:') || stripped.includes('FATAL:')) {
      return stripped
    }
    fallback = stripped
  }
  return fallback || 'unknown failure'
}

/** True when the docker CLI exists and the daemon answers `docker info`. */
export function dockerAvailable() {
  const result = spawnSync('docker', ['info'], { stdio: 'ignore', timeout: 10_000 })
  return result.status === 0
}

export const sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis))

/** Fail before starting a database if PATH selects an old or mixed libpq toolchain. */
export function requirePostgresClients(run = spawnSync) {
  const majors = new Set()
  for (const tool of POSTGRES_CLIENT_TOOLS) {
    const result = run(tool, ['--version'], { encoding: 'utf8', timeout: 10_000 })
    if (result.error || result.status !== 0) {
      throw new Error(`PostgreSQL client unavailable: ${tool}`)
    }
    const match = /\(PostgreSQL\)\s+(\d+)(?:[.\s]|$)/.exec(result.stdout)
    if (!match || Number(match[1]) < POSTGRES_MAJOR) {
      throw new Error(`${tool} must be PostgreSQL ${POSTGRES_MAJOR} or newer; fix PATH before testing`)
    }
    majors.add(Number(match[1]))
  }
  if (majors.size !== 1) {
    throw new Error('PostgreSQL clients must use the same major version; fix PATH before testing')
  }
}

const docker = (args, options = {}) => spawnSync('docker', args, { encoding: 'utf8', ...options })

/** A throwaway PostgreSQL container with scram-enforced loopback and an isolated libpq client environment. */
export class PostgresContainer {
  async start() {
    requirePostgresClients()
    this.name = `kk-studio-integ-${randomUUID().replaceAll('-', '').slice(0, 10)}`
    const started = docker(
      [
        'run',
        '--detach',
        '--name',
        this.name,
        '--publish',
        '127.0.0.1::5432',
        '--env',
        `POSTGRES_PASSWORD=${FIXTURE_PASSWORD}`,
        '--env',
        'POSTGRES_HOST_AUTH_METHOD=scram-sha-256',
        `postgres:${POSTGRES_MAJOR}-alpine`,
      ],
      { timeout: 60_000 },
    )
    if (started.status !== 0) {
      throw new Error(`Failed to start PostgreSQL container: ${started.stderr.trim()}`)
    }

    const port = docker(['port', this.name, '5432/tcp'], { timeout: 10_000 })
    const match = /127\.0\.0\.1:(\d+)/.exec(port.stdout)
    if (port.status !== 0 || !match) {
      throw new Error(`Could not parse port from docker port output: ${port.stdout}`)
    }
    this.port = Number(match[1])

    // Mode-0600 passfile for libpq authentication.
    this.directory = mkdtempSync(path.join(tmpdir(), 'integ-pg-'))
    this.passfile = path.join(this.directory, 'pgpass')
    writeFileSync(this.passfile, `127.0.0.1:${this.port}:*:postgres:${FIXTURE_PASSWORD}\n`)
    chmodSync(this.passfile, 0o600)

    // Explicit process whitelist and an isolated HOME. Ambient PG*, VPS_POSTGRES_*, KK_STUDIO_* and
    // default pgpass/service files must never select a maintenance target.
    const home = path.join(this.directory, 'home')
    mkdirSync(home)
    const env = {}
    for (const key of ['PATH', 'TMPDIR', 'LANG', 'LC_ALL', 'SYSTEMROOT', 'WINDIR']) {
      if (key in process.env) {
        env[key] = process.env[key]
      }
    }
    Object.assign(env, {
      HOME: home,
      PGHOST: '127.0.0.1',
      PGPORT: String(this.port),
      PGUSER: 'postgres',
      PGPASSFILE: this.passfile,
      PGSSLMODE: 'disable',
      KK_STUDIO_REPO_ROOT: WORKTREE_ROOT,
    })
    this.clientEnv = env

    // The documented connection contract is PGSERVICE + PGPASSFILE: one service file for the test
    // that must prove that pair works without any PGHOST/PGPORT in the environment.
    this.serviceFile = path.join(this.directory, 'pg_service.conf')
    writeFileSync(this.serviceFile, `[kk_studio_integration]\nhost=127.0.0.1\nport=${this.port}\nuser=postgres\n`)

    let ready = false
    const deadline = Date.now() + 120_000
    while (Date.now() < deadline && !ready) {
      ready = spawnSync('pg_isready', ['-h', '127.0.0.1', '-p', String(this.port), '-U', 'postgres'], { stdio: 'ignore' }).status === 0
      if (!ready) {
        await sleep(1000)
      }
    }
    if (!ready) {
      throw new Error('PostgreSQL container did not become ready within 120 seconds')
    }

    // The image trusts the published 127.0.0.1 endpoint, which would hide a missing credential.
    // Rewrite the container-owned pg_hba.conf so host connections require scram-sha-256, then
    // reload it; the local socket stays trusted for docker exec.
    const rewrite =
      "sed -i -E 's|^(host[[:space:]]+all[[:space:]]+all[[:space:]]+127\\.0\\.0\\.1/32[[:space:]]+)trust$|\\1scram-sha-256|' /var/lib/postgresql/data/pg_hba.conf; " +
      "sed -i -E 's|^(host[[:space:]]+all[[:space:]]+all[[:space:]]+::1/128[[:space:]]+)trust$|\\1scram-sha-256|' /var/lib/postgresql/data/pg_hba.conf"
    this.exec(['sh', '-c', rewrite])
    this.exec(['psql', '-U', 'postgres', '-tAc', 'select pg_reload_conf()'])
    // Prove the loopback rules now require a password before any test connects.
    const enforced = this.exec([
      'psql',
      '-U',
      'postgres',
      '-tAc',
      "select count(*) from pg_hba_file_rules where address in ('127.0.0.1', '::1') and auth_method = 'scram-sha-256'",
    ]).stdout.trim()
    if (enforced !== '2') {
      throw new Error(`pg_hba.conf still allows passwordless loopback: ${JSON.stringify(enforced)}`)
    }
  }

  exec(args) {
    const result = docker(['exec', this.name, ...args], { timeout: 30_000 })
    if (result.status !== 0) {
      throw new Error(`docker exec failed: ${sanitizeError(result.stderr)}`)
    }
    return result
  }

  stop() {
    if (this.name) {
      docker(['rm', '--force', '--volumes', this.name], { stdio: 'ignore' })
      this.name = null
    }
    if (this.directory) {
      rmSync(this.directory, { recursive: true, force: true })
      this.directory = null
    }
  }
}

/** Per-test workspace on top of a `PostgresContainer`. */
export class ResetTestContext {
  constructor(container) {
    this.container = container
    this.testDir = mkdtempSync(path.join(tmpdir(), 'reset-test-'))
    this.createdDatabases = []
    this.cleanups = []
  }

  get clientEnv() {
    return this.container.clientEnv
  }

  /** Register a cleanup to run (in reverse order) when the test ends. */
  addCleanup(fn) {
    this.cleanups.push(fn)
  }

  /** Drop every database the test created, including snapshots made by reset. */
  cleanup() {
    for (const fn of this.cleanups.reverse()) {
      try {
        fn()
      } catch {
        // Cleanup must not mask the test result.
      }
    }
    for (const name of new Set(this.createdDatabases)) {
      try {
        if (this.psql('postgres', `SELECT datistemplate FROM pg_database WHERE datname = '${name}'`) === 't') {
          this.psql('postgres', `ALTER DATABASE "${name}" IS_TEMPLATE false`)
        }
        this.psql('postgres', `DROP DATABASE IF EXISTS "${name}" WITH (FORCE)`)
      } catch {
        // Cleanup must not mask the test result.
      }
      try {
        const snapshots = this.psql('postgres', `SELECT datname FROM pg_database WHERE datname LIKE '${name}_pre_%'`).split('\n')
        for (const snapshot of snapshots.map((line) => line.trim()).filter(Boolean)) {
          this.psql('postgres', `DROP DATABASE IF EXISTS "${snapshot}" WITH (FORCE)`)
        }
      } catch {
        // Cleanup must not mask the test result.
      }
    }
    rmSync(this.testDir, { recursive: true, force: true })
  }

  registerDb(name) {
    this.createdDatabases.push(name)
    return name
  }

  /** Assert that no forbidden fixture value or secret substring leaked in text. */
  assertNoFixtureValues(text, message = 'Credential disclosure check') {
    for (const forbidden of FORBIDDEN_VALUES) {
      assert.ok(!(text ?? '').includes(forbidden), `${message}: output contained forbidden fixture/credential token`)
    }
  }

  /** Execute a SQL statement and return its trimmed standard output. */
  psql(database, sql, env = this.clientEnv) {
    const result = spawnSync(
      'psql',
      ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '--no-password', '-d', database, '-c', sql],
      { env, encoding: 'utf8', timeout: 30_000 },
    )
    if (result.status !== 0) {
      throw new Error(`psql command failed on ${database}: ${sanitizeError(result.stderr)}`)
    }
    return result.stdout.trim()
  }

  /** Apply a SQL fixture file with ON_ERROR_STOP=1. */
  psqlFile(database, file, env = this.clientEnv) {
    const result = spawnSync('psql', ['-X', '-q', '-v', 'ON_ERROR_STOP=1', '--no-password', '-d', database, '-f', file], {
      env,
      encoding: 'utf8',
      timeout: 60_000,
    })
    if (result.status !== 0) {
      throw new Error(`psql file failed on ${database} (${path.basename(file)}): ${sanitizeError(result.stderr)}`)
    }
  }

  createEmptyDb(name) {
    this.registerDb(name)
    this.psql('postgres', `CREATE DATABASE "${name}"`)
  }

  /** A target database initialized with the current V1 schema, as external init does. */
  createV1Database(name) {
    this.createEmptyDb(name)
    this.psqlFile(name, V1_SCHEMA_SQL)
  }

  /** Environment for the script: the whitelisted client environment plus overrides (`null` removes). */
  scriptEnv(override = {}) {
    const env = { ...this.clientEnv }
    for (const [key, value] of Object.entries(override)) {
      if (value === null) {
        delete env[key]
      } else {
        env[key] = value
      }
    }
    return env
  }

  /** Run reset-database.sh and assert that no fixture value leaked into its output. */
  runScript(args, { env = {}, timeout = 60_000, input = '' } = {}) {
    const result = spawnSync('bash', [RESET_SCRIPT, ...args], {
      env: this.scriptEnv(env),
      input,
      encoding: 'utf8',
      timeout,
    })
    this.assertNoFixtureValues(result.stdout, 'reset-database.sh stdout')
    this.assertNoFixtureValues(result.stderr, 'reset-database.sh stderr')
    return result
  }

  createSnapshot(target, stamp = '20260101T000000Z', { template = false, frozen = true } = {}) {
    const name = `${target}_pre_${stamp}`
    this.createEmptyDb(name)
    if (template) {
      this.psql('postgres', `ALTER DATABASE "${name}" IS_TEMPLATE true`)
    }
    if (frozen) {
      this.psql('postgres', `ALTER DATABASE "${name}" ALLOW_CONNECTIONS false`)
    }
    return name
  }

  databaseExists(name) {
    return this.psql('postgres', `SELECT count(*) FROM pg_database WHERE datname = '${name}'`) === '1'
  }

  /** Write an executable stub into `directory` and return the directory. */
  writeStub(directory, name, content) {
    mkdirSync(directory, { recursive: true })
    const file = path.join(directory, name)
    writeFileSync(file, content)
    chmodSync(file, 0o755)
    return directory
  }

  /** A background `psql` session holding a connection open; resolves once it is visible in pg_stat_activity. */
  async openSession(database, seconds, extraArgs = []) {
    const child = spawn('psql', ['-X', '-q', '-w', ...extraArgs, '-d', database, '-c', `SELECT pg_sleep(${seconds})`], {
      env: this.clientEnv,
      stdio: 'ignore',
    })
    const deadline = Date.now() + 10_000
    while (this.psql('postgres', `SELECT count(*) FROM pg_stat_activity WHERE datname = '${database}' AND pid <> pg_backend_pid()`) === '0') {
      assert.ok(Date.now() < deadline, 'fixture connection did not appear')
      await sleep(50)
    }
    return child
  }
}

export const uniqueSuffix = () => randomUUID().replaceAll('-', '').slice(0, 8)
