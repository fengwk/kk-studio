/**
 * Unit tests for the repository Canvas/Project schema gate inventory.
 *
 * The gate's declared `BUSINESS_TABLES` is the accepted table set of the sole production baseline.
 * Comparing it against the DDL that the baseline actually creates keeps a new or removed table from
 * being silently ignored without spinning up the isolated PostgreSQL container.
 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import { repositoryRoot } from '../../../lib/repo-root.mjs'
import {
  BASELINE_FILE,
  BUSINESS_TABLES,
  MIGRATION_DIRECTORY,
  TARGET_COLUMNS,
  TARGET_INDEXES,
  TARGET_RELATIONS,
  TARGET_TABLES,
  declaredBaselineTables,
  inventoryMismatch,
} from '../check-canvas-project-schema.mjs'

const BASELINE = readFileSync(path.join(repositoryRoot(), MIGRATION_DIRECTORY, BASELINE_FILE), 'utf8')

/** Split a CREATE TABLE body on top-level commas (parentheses and quoted literals stay intact). */
function splitTopLevel(body) {
  const parts = []
  let current = ''
  let depth = 0
  let quoted = false
  for (let index = 0; index < body.length; index += 1) {
    const character = body[index]
    if (character === "'") {
      quoted = !quoted
    } else if (!quoted && character === '(') {
      depth += 1
    } else if (!quoted && character === ')') {
      depth -= 1
    }
    if (character === ',' && !quoted && depth === 0) {
      parts.push(current.trim())
      current = ''
    } else {
      current += character
    }
  }
  if (current.trim()) parts.push(current.trim())
  return parts
}

function tableBody(sql, table) {
  const match = new RegExp(`create\\s+table\\s+${table}\\s*\\(([\\s\\S]*?)\\n\\);`, 'i').exec(sql)
  assert.ok(match, `missing create table ${table}`)
  return match[1]
}

function columnName(definition) {
  const match = /^(?:"([^"]+)"|([a-z_][a-z0-9_]*))\s+/i.exec(definition)
  return match ? (match[1] ?? match[2]) : null
}

/**
 * Derive the target columns/relations/indexes directly from the real baseline DDL.
 *
 * The production validator reads PostgreSQL catalogs; this parser is the container-free
 * counterpart for exactly the DDL constructs V1 uses, including PostgreSQL's generated
 * names for anonymous primary/unique/foreign keys.
 */
function declaredTargetContract(sql) {
  const baseline = sql.replace(/--[^\n]*/g, '')
  const columns = {}
  const relations = {}
  const indexes = {}
  for (const table of TARGET_TABLES) {
    columns[table] = []
    relations[table] = []
    indexes[table] = []
    for (const segment of splitTopLevel(tableBody(baseline, table))) {
      const named = /^constraint\s+([a-z_][a-z0-9_]*)\s+([\s\S]+)$/i.exec(segment)
      const name = named ? named[1] : null
      const definition = (named ? named[2] : segment).trim()
      if (/^primary\s+key/i.test(definition)) {
        indexes[table].push(name ?? `${table}_pkey`)
      } else if (/^unique\s*\(/i.test(definition)) {
        const fields = definition.match(/^unique\s*\(([^)]*)\)/i)[1]
          .split(',').map((field) => field.trim())
        indexes[table].push(name ?? `${table}_${fields.join('_')}_key`)
      } else if (/^foreign\s+key/i.test(definition)) {
        assert.ok(name, `${table} has an anonymous foreign key`)
        relations[table].push(name)
      } else if (/^(?:check|exclude)\s*\(/i.test(definition)) {
        continue
      } else {
        const column = columnName(definition)
        assert.ok(column, `unrecognized ${table} definition: ${definition}`)
        columns[table].push(column)
        if (/\bprimary\s+key\b/i.test(definition)) indexes[table].push(`${table}_pkey`)
        if (/\bunique\b/i.test(definition) && !/^constraint/i.test(definition)) {
          indexes[table].push(`${table}_${column}_key`)
        }
        if (/\breferences\b/i.test(definition)) relations[table].push(`${table}_${column}_fkey`)
      }
    }
  }
  for (const match of baseline.matchAll(/^create\s+(?:unique\s+)?index\s+([a-z_][a-z0-9_]*)\s+on\s+([a-z_][a-z0-9_]*)/gim)) {
    if (TARGET_TABLES.includes(match[2])) indexes[match[2]].push(match[1])
  }
  for (const match of baseline.matchAll(/^alter\s+table\s+([a-z_][a-z0-9_]*)\s+add\s+constraint\s+([a-z_][a-z0-9_]*)\s+foreign\s+key/gim)) {
    if (TARGET_TABLES.includes(match[1])) relations[match[1]].push(match[2])
  }
  const normalize = (map) => Object.fromEntries(
    Object.entries(map).map(([table, names]) => [table, [...names].sort()]),
  )
  return { columns: normalize(columns), relations: normalize(relations), indexes: normalize(indexes) }
}

test('test intent: the inventory equals the tables the real baseline creates and targets are a subset', () => {
  // The baseline DDL is the only table-count fact source; no second hardcoded total to keep in sync.
  assert.deepEqual([...BUSINESS_TABLES].sort(), declaredBaselineTables(BASELINE))
  assert.ok(TARGET_TABLES.every((table) => BUSINESS_TABLES.includes(table)))
})

test('test intent: the declared inventory has no drift against the real baseline', () => {
  assert.deepEqual(inventoryMismatch(BASELINE), [[], []])
})

test('test intent: an added baseline table is reported as missing from the inventory', () => {
  const [missingFromInventory, notCreatedByBaseline] = inventoryMismatch(
    BASELINE + 'create table probe_added (id uuid primary key);\n',
  )
  assert.ok(missingFromInventory.includes('probe_added'))
  assert.deepEqual(notCreatedByBaseline, inventoryMismatch(BASELINE)[1])
})

test('test intent: a removed baseline table is reported as a stale inventory entry', () => {
  const [missingFromInventory, notCreatedByBaseline] = inventoryMismatch(
    'create table project (id uuid primary key);\n',
  )
  assert.deepEqual(missingFromInventory, [])
  assert.ok(notCreatedByBaseline.includes('canvas_document'))
})

test('test intent: every declared target column, relation and index matches the real DDL', () => {
  const declared = declaredTargetContract(BASELINE)
  const expected = (map) => Object.fromEntries(
    Object.entries(map).map(([table, names]) => [table, [...names].sort()]),
  )
  assert.deepEqual(declared.columns, expected(TARGET_COLUMNS))
  assert.deepEqual(declared.relations, expected(TARGET_RELATIONS))
  assert.deepEqual(declared.indexes, expected(TARGET_INDEXES))
  assert.deepEqual([...TARGET_TABLES].sort(), Object.keys(TARGET_COLUMNS).sort())
  assert.deepEqual([...TARGET_TABLES].sort(), Object.keys(TARGET_RELATIONS).sort())
  assert.deepEqual([...TARGET_TABLES].sort(), Object.keys(TARGET_INDEXES).sort())
})

test('test intent: the declaration parser handles comments, case and if-not-exists', () => {
  const sql = '-- create table commented (id uuid);\nCREATE TABLE IF NOT EXISTS real_table (id uuid);\n'
  assert.deepEqual(declaredBaselineTables(sql), ['real_table'])
})

test(
  'test intent: readiness rejects the Unix-only init server and loads DDL only after a loopback TCP SELECT 1',
  {
    skip: !existsSync('/var/run/docker.sock') &&
      'requires the local Docker socket path the checker validates',
  },
  () => {
    const root = mkdtempSync(path.join(tmpdir(), 'kk-schema-readiness-'))
    const bin = path.join(root, 'bin')
    const state = path.join(root, 'state')
    mkdirSync(bin)
    mkdirSync(state)
    const fixture = path.join(
      path.dirname(fileURLToPath(import.meta.url)),
      'fixtures',
      'schema-readiness-docker.mjs',
    )
    const quote = (value) => `'${value.replaceAll("'", "'\\''")}'`
    const docker = path.join(bin, 'docker')
    writeFileSync(
      docker,
      `#!/bin/sh\nexec ${quote(process.execPath)} ${quote(fixture)} "$@"\n`,
      { mode: 0o755 },
    )
    const environment = {
      ...process.env,
      PATH: `${bin}${path.delimiter}${process.env.PATH ?? ''}`,
      FAKE_DOCKER_STATE: state,
    }
    for (const key of ['DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']) {
      delete environment[key]
    }
    try {
      const result = spawnSync(
        process.execPath,
        [path.join(repositoryRoot(), 'scripts/dev/verify/repository/check-canvas-project-schema.mjs')],
        { cwd: repositoryRoot(), encoding: 'utf8', env: environment, timeout: 120_000 },
      )
      assert.equal(result.status, 0, result.stderr || result.stdout)
      assert.match(result.stdout, /PASS 213 positive and negative database contract assertions/)
      assert.match(result.stdout, /PASS validator detects 5 non-declared schema changes/)

      const phases = readFileSync(path.join(state, 'phases.log'), 'utf8').trim().split('\n')
      assert.deepEqual(
        phases.filter((phase) => phase.startsWith('tcp-attempt')),
        ['tcp-attempt 1', 'tcp-attempt 2', 'tcp-attempt 3'],
        'the checker must wait through the failing loopback TCP attempts',
      )
      assert.ok(!phases.includes('unix-temp-server-ready'), 'pg_isready must not be the readiness gate')
      assert.ok(!phases.includes('EARLY_DDL'), 'baseline DDL must not run before the target database is reachable')
      const readyIndex = phases.indexOf('tcp-ready')
      const baselineIndex = phases.indexOf('baseline')
      const probesIndex = phases.indexOf('probes')
      assert.ok(readyIndex >= 0, 'the loopback TCP SELECT 1 must establish readiness')
      assert.ok(baselineIndex > readyIndex, 'the baseline must load after TCP readiness')
      assert.ok(probesIndex > baselineIndex, 'the probes must run after the baseline loads')

      const calls = readFileSync(path.join(state, 'calls.log'), 'utf8')
        .trim().split('\n').map((line) => JSON.parse(line))
      const readiness = calls.filter((args) => args.includes('psql') && !args.includes('-i'))
      assert.equal(readiness.length, 3, 'readiness must be probed by non-interactive psql')
      for (const args of readiness) {
        assert.ok(args.includes('-h') && args.includes('127.0.0.1'), 'readiness must use loopback TCP')
        assert.ok(args.includes('SELECT 1'), 'readiness must select from the exact target database')
      }
      assert.ok(existsSync(path.join(state, 'removed')), 'the owned container must be cleaned up')
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  },
)
