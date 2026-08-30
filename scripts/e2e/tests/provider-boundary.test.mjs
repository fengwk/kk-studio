import assert from 'node:assert/strict'
import test from 'node:test'

import { assertProviderExecutionBoundary } from '../lib/provider-boundary.mjs'

test('free mode accepts only the explicitly unconfigured seed provider', () => {
  // Test intent: free runners may start only when no credential or endpoint can route a paid call.
  assert.doesNotThrow(() =>
    assertProviderExecutionBoundary({
      real: false,
      providers: [{ name: 'minimax', configured: false, baseUrl: null }],
    }),
  )
  assert.doesNotThrow(() =>
    assertProviderExecutionBoundary({
      real: false,
      providers: [{ name: 'minimax', configured: false }],
    }),
  )
})

test('free mode fails closed without exposing provider details', () => {
  // Test intent: missing/malformed/configured state must stop before cases without leaking endpoints.
  const endpoint = 'https://provider-secret.example/v1'
  for (const providers of [
    [],
    null,
    [{ name: 'minimax', configured: true, baseUrl: null }],
    [{ name: 'minimax', configured: false, baseUrl: endpoint }],
    [{ name: 'minimax', baseUrl: null }],
  ]) {
    let error = null
    try {
      assertProviderExecutionBoundary({ real: false, providers })
    } catch (caught) {
      error = caught
    }
    assert(error instanceof Error)
    assert.match(error.message, /free E2E requires seed provider minimax/)
    assert.equal(error.message.includes(endpoint), false)
  }
})

test('real mode bypasses the free-provider state guard', () => {
  // Test intent: explicit --real authorization leaves readiness checks to the real seed contract.
  assert.doesNotThrow(() =>
    assertProviderExecutionBoundary({
      real: true,
      providers: [
        {
          name: 'minimax',
          configured: true,
          baseUrl: 'https://example.invalid/v1',
        },
      ],
    }),
  )
  assert.doesNotThrow(() =>
    assertProviderExecutionBoundary({ real: true, providers: null }),
  )
  assert.throws(
    () =>
      assertProviderExecutionBoundary({
        real: 'true',
        providers: [{ name: 'minimax', configured: true }],
      }),
    /free E2E requires seed provider minimax/,
  )
})
