/**
 * Docker CLI stand-in for the schema-checker readiness regression.
 *
 * It models the official postgres entrypoint: a Unix-only temporary server answers
 * `pg_isready` while the target database has not been created and no TCP listener is
 * open yet. Only a loopback TCP `psql ... SELECT 1` that returns exactly `1` is ready.
 *
 * The fixture keeps its state under `FAKE_DOCKER_STATE`; the caller owns and removes it.
 */

import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const state = process.env.FAKE_DOCKER_STATE
if (!state) {
  throw new Error('FAKE_DOCKER_STATE is required')
}

const log = path.join(state, 'calls.log')
const phases = path.join(state, 'phases.log')
const attemptsFile = path.join(state, 'tcp-attempts')
const readyFile = path.join(state, 'tcp-ready')
const appendPhase = (value) => appendFileSync(phases, `${value}\n`)
const fail = (message) => {
  process.stderr.write(`${message}\n`)
  process.exit(1)
}

const args = process.argv.slice(2)
appendFileSync(log, `${JSON.stringify(args)}\n`)
if (args[0] === '--host') {
  args.splice(0, 2)
}

/** Emit the exact violation row shape the checker parses (`problem<US>object`). */
const violation = (problem, object) => process.stdout.write(`${problem}\x1f${object}\n`)

const group = args.shift()
if (group === 'image' && args[0] === 'inspect') {
  process.exit(0)
}

if (group === 'run') {
  process.stdout.write('fake-container\n')
  process.exit(0)
}

if (group === 'inspect') {
  const formatIndex = args.indexOf('--format')
  const format = formatIndex >= 0 ? args[formatIndex + 1] : ''
  if (format === '{{.HostConfig.NetworkMode}}') {
    process.stdout.write('none\n')
  } else if (format === '{{len .NetworkSettings.Ports}}') {
    process.stdout.write('0\n')
  } else {
    fail(`unexpected inspect format: ${format}`)
  }
  process.exit(0)
}

if (group === 'rm') {
  writeFileSync(path.join(state, 'removed'), '')
  process.exit(0)
}

if (group === 'exec') {
  const stdin = args[0] === '-i'
  if (stdin) {
    args.shift()
  }
  args.shift() // container id
  const command = args.shift()
  if (command === 'pg_isready') {
    // The temporary init server listens on Unix sockets only and does not own POSTGRES_DB.
    appendPhase('unix-temp-server-ready')
    process.stdout.write('/var/run/postgresql:5432 - accepting connections\n')
    process.exit(0)
  }
  if (command !== 'psql') {
    fail(`unexpected container command: ${command}`)
  }
  if (!stdin) {
    const hostIndex = args.indexOf('-h')
    const sqlIndex = args.indexOf('SELECT 1')
    if (hostIndex < 0 || args[hostIndex + 1] !== '127.0.0.1' || sqlIndex < 0) {
      fail(`readiness must use loopback TCP SELECT 1: ${args.join(' ')}`)
    }
    const attempt = Number(
      (existsSync(attemptsFile) ? readFileSync(attemptsFile, 'utf8') : '') || '0',
    ) + 1
    writeFileSync(attemptsFile, String(attempt))
    appendPhase(`tcp-attempt ${attempt}`)
    if (attempt < 3) {
      process.stderr.write(
        'psql: error: connection to server at "127.0.0.1", port 5432 failed: Connection refused\n',
      )
      process.exit(2)
    }
    writeFileSync(readyFile, '')
    appendPhase('tcp-ready')
    process.stdout.write('1\n')
    process.exit(0)
  }

  const sql = readFileSync(0, 'utf8')
  if (!existsSync(readyFile)) {
    appendPhase('EARLY_DDL')
    fail('refusing SQL before the target database is reachable over loopback TCP')
  }
  if (sql.includes('Flyway PostgreSQL baseline')) {
    appendPhase('baseline')
    process.exit(0)
  }
  if (sql.includes('Baseline contract probes')) {
    appendPhase('probes')
    process.stdout.write('PASS 213 database contract assertions\n')
    process.exit(0)
  }
  if (sql.includes('drop constraint fk_project_issue_run_stage_budget')) {
    violation('missing relation', 'project_issue_run.fk_project_issue_run_stage_budget')
  } else if (sql.includes('drop index idx_project_issue_run_budget')) {
    violation('missing index', 'project_issue_run.idx_project_issue_run_budget')
  } else if (sql.includes('drop column remaining_execution_ms')) {
    violation('missing column', 'project_issue_run.remaining_execution_ms')
  } else if (sql.includes('add column probe_field')) {
    violation('unexpected column', 'durable_work.probe_field')
  } else if (sql.includes('create table probe_table')) {
    violation('unexpected table', 'probe_table')
  }
  process.exit(0)
}

fail(`unexpected docker invocation: ${[group, ...args].join(' ')}`)
