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
  TARGET_TABLES,
  declaredBaselineTables,
  inventoryMismatch,
} from '../check-canvas-project-schema.mjs'

const BASELINE = readFileSync(path.join(repositoryRoot(), MIGRATION_DIRECTORY, BASELINE_FILE), 'utf8')

test('test intent: the inventory equals the tables the real baseline creates and targets are a subset', () => {
  assert.deepEqual([...BUSINESS_TABLES].sort(), declaredBaselineTables(BASELINE))
  assert.equal(BUSINESS_TABLES.length, 46)
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

test('test intent: the declaration parser handles comments, case and if-not-exists', () => {
  const sql = '-- create table commented (id uuid);\nCREATE TABLE IF NOT EXISTS real_table (id uuid);\n'
  assert.deepEqual(declaredBaselineTables(sql), ['real_table'])
})
