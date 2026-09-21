import assert from 'node:assert/strict'
import test from 'node:test'

import { assertProviderExecutionBoundary } from '../lib/provider-boundary.mjs'

test('free mode accepts only an entirely unconfigured provider catalog', () => {
  // Test intent: free runners may start only when no catalog row can route a paid call.
  assert.doesNotThrow(() =>
    assertProviderExecutionBoundary({
      real: false,
      providers: [
        { name: 'google', configured: false, baseUrl: null },
        { name: 'openai', configured: false },
        { name: 'minimax-anthropic', configured: false, baseUrl: null },
        { name: 'deepseek', configured: false, baseUrl: null },
      ],
    }),
  )
})

test('free mode fails closed without exposing provider details', () => {
  // Test intent: missing/malformed/configured state must stop before cases without leaking endpoints.
  const endpoint = 'https://provider-secret.example/v1'
  for (const providers of [
    [],
    null,
    [null],
    [{ name: 'minimax', configured: true, baseUrl: null }],
    [{ name: 'minimax', configured: false, baseUrl: endpoint }],
    [{ name: 'minimax', baseUrl: null }],
    [
      { name: 'minimax', configured: false, baseUrl: null },
      { name: 'openai', configured: true, baseUrl: null },
    ],
  ]) {
    let error = null
    try {
      assertProviderExecutionBoundary({ real: false, providers })
    } catch (caught) {
      error = caught
    }
    assert(error instanceof Error)
    assert.match(error.message, /free E2E requires every catalog provider/)
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
    /free E2E requires every catalog provider/,
  )
})
