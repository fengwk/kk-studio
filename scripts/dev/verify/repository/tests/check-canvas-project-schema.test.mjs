/**
 * Unit tests for the repository Canvas/Project schema gate inventory.
 *
 * The gate's declared `BUSINESS_TABLES` is the accepted table set of the sole production baseline.
 * Comparing it against the DDL that the baseline actually creates keeps a new or removed table from
 * being silently ignored without spinning up the isolated PostgreSQL container.
 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

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
  assert.deepEqual([...BUSINESS_TABLES].sort(), declaredBaselineTables(BASELINE))
  assert.equal(BUSINESS_TABLES.length, 43)
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
