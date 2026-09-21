import assert from 'node:assert/strict'
import test from 'node:test'
import { createDurationTimer } from '../lib/time.mjs'

test('measures rounded elapsed milliseconds from the supplied monotonic clock', () => {
  const readings = [100, 112.6]
  const elapsed = createDurationTimer(() => readings.shift())

  assert.equal(elapsed(), 13)
})

test('never reports a negative duration', () => {
  const readings = [100, 99]
  const elapsed = createDurationTimer(() => readings.shift())

  assert.equal(elapsed(), 0)
})
