import assert from 'node:assert/strict'
import test from 'node:test'

import { observeQuiescentSnapshot } from '../lib/harness.mjs'

test('quiescent observation requires the same stable cursor twice', () => {
  // Test intent: a single transient IDLE snapshot must never authorize a subsequent CAS write.
  const snapshot = quiescentSnapshot()
  const first = observeQuiescentSnapshot(null, snapshot)
  assert.equal(first.stable, false)
  assert.notEqual(first.key, null)

  const second = observeQuiescentSnapshot(first.key, snapshot)
  assert.equal(second.stable, true)
  assert.equal(second.key, first.key)
})

test('active work and cursor changes reset the quiescent candidate', () => {
  // Test intent: hidden async progress must force a fresh two-observation stability window.
  const initial = quiescentSnapshot()
  const candidate = observeQuiescentSnapshot(null, initial)

  for (const active of [
    { ...initial, thread: { ...initial.thread, status: 'MODEL_RUNNING' } },
    { ...initial, thread: { ...initial.thread, processing: true } },
    { ...initial, queuedCommands: [{ sequence: '1' }] },
    { ...initial, modelInvocation: { id: 'model-1' } },
    { ...initial, toolInvocations: [{ id: 'tool-1' }] },
  ]) {
    assert.deepEqual(observeQuiescentSnapshot(candidate.key, active), {
      key: null,
      stable: false,
    })
  }

  for (const threadChange of [
    { headEntryId: '00000000-0000-0000-0000-000000000003' },
    { nextCommandSequence: '3' },
    { version: '8' },
  ]) {
    const changed = {
      ...initial,
      thread: { ...initial.thread, ...threadChange },
    }
    const observation = observeQuiescentSnapshot(candidate.key, changed)
    assert.equal(observation.stable, false)
    assert.notEqual(observation.key, candidate.key)
  }
})

function quiescentSnapshot() {
  return {
    thread: {
      threadId: '00000000-0000-0000-0000-000000000001',
      headEntryId: '00000000-0000-0000-0000-000000000002',
      nextCommandSequence: '2',
      version: '7',
      status: 'IDLE',
      processing: false,
    },
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
  }
}
