import assert from 'node:assert/strict'
import test from 'node:test'
import { instantEpochMillis } from '../lib/http.mjs'
import { assertFreshnessAt } from '../cases/interaction.mjs'
import { assertReferencedStateCodes } from '../cases/project.mjs'

test('Instant numbers are epoch seconds and explicit ISO instants have the same value', () => {
  assert.equal(instantEpochMillis(1_700_000_000.123), 1_700_000_000_123)
  assert.equal(instantEpochMillis('2023-11-14T22:13:20.123Z'), 1_700_000_000_123)
  assert.equal(instantEpochMillis(0), 0)
  for (const invalid of [null, undefined, '', ' ', '0', '2023-11-14', 'invalid', -1, NaN, Infinity, 1e308]) {
    assert.throws(() => instantEpochMillis(invalid), /must be/)
  }
})

test('freshnessAt is explicit null or a deadline not preceding request start', () => {
  const start = 1_700_000_000_000
  assertFreshnessAt({ freshnessAt: null }, start)
  assertFreshnessAt({ freshnessAt: 1_700_000_000 }, start)
  assertFreshnessAt({ freshnessAt: 1_700_000_000.001 }, start)
  for (const invalid of [undefined, '', -1, NaN, 1_699_999_999.999]) {
    assert.throws(() => assertFreshnessAt({ freshnessAt: invalid }, start))
  }
})

test('referenced states have the exact independent set, canonical order and no duplicates', () => {
  assertReferencedStateCodes(['BLOCKED', 'INIT', 'WORK'], ['WORK', 'INIT', 'BLOCKED'])
  assertReferencedStateCodes([], [])
  for (const invalid of [
    null, ['INIT', 'INIT', 'WORK'], ['WORK', 'INIT'], ['INIT'], ['INIT', 'EXTRA', 'WORK'],
    ['INIT', 1], ['INIT', ' '],
  ]) {
    assert.throws(() => assertReferencedStateCodes(invalid, ['INIT', 'WORK']))
  }
})
