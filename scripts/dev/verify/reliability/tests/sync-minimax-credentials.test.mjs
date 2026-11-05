/** Tests for the reliability matrix's MiniMax credential synchronization. */

import assert from 'node:assert/strict'
import test from 'node:test'

import { ValidationError } from '../../../lib/provider-credentials.mjs'
import { makeMockHttp } from '../../../tests/support/mock-http.mjs'
import {
  TEST_MINIMAX_API_KEY,
  TEST_MINIMAX_BASE_URL,
  syncMinimaxCredentials,
} from '../sync-minimax-credentials.mjs'

test('test intent: unset TEST_MINIMAX_* skips without any HTTP request', async () => {
  const { request, calls } = makeMockHttp({ rows: [] })
  // An incomplete E2E credential must not be consumed by reliability.
  const lines = await syncMinimaxCredentials('http://backend', { TEST_GOOGLE_API_KEY: 'unrelated' }, request)

  assert.deepEqual(lines, ['minimax: skip (no credential pair)'])
  assert.deepEqual(calls, [])
})

test('test intent: a complete pair updates the Responses provider and appends /v1 to the base URL', async () => {
  const rows = [
    {
      name: 'minimax',
      description: 'MiniMax.',
      providerType: 'openai_responses',
      baseUrl: null,
      version: '7',
      modelCallTimeoutMillis: 1800000,
      modelCallIdleTimeoutMillis: 120000,
    },
  ]
  const { request, calls } = makeMockHttp({ rows })

  const lines = await syncMinimaxCredentials(
    'http://backend',
    { [TEST_MINIMAX_BASE_URL]: 'https://api.minimax.example///', [TEST_MINIMAX_API_KEY]: 'secret-minimax' },
    request,
  )

  assert.equal(calls.length, 2)
  assert.equal(calls[1][0], 'http://backend/api/providers/minimax')
  assert.equal(calls[1][1].baseUrl, 'https://api.minimax.example/v1')
  assert.equal(calls[1][1].providerType, 'openai_responses')
  assert.equal(calls[1][1].credential, 'secret-minimax')
  assert.deepEqual(lines, ['minimax: configured=True baseUrl_set=True'])
})

test('test intent: a half pair fails before HTTP without leaking the secret', async () => {
  const { request, calls } = makeMockHttp({ rows: [] })

  await assert.rejects(
    syncMinimaxCredentials('http://backend', { [TEST_MINIMAX_API_KEY]: 'secret-minimax' }, request),
    (error) => {
      assert.ok(error instanceof ValidationError)
      assert.ok(error.message.includes(TEST_MINIMAX_BASE_URL))
      assert.ok(error.message.includes(TEST_MINIMAX_API_KEY))
      assert.ok(!error.message.includes('secret-minimax'))
      return true
    },
  )
  assert.deepEqual(calls, [])
})
