/** Tests for four-provider E2E credential synchronization. */

import assert from 'node:assert/strict'
import test from 'node:test'

import { ValidationError } from '../../../lib/provider-credentials.mjs'
import { makeMockHttp } from '../../../tests/support/mock-http.mjs'
import {
  PROVIDER_SPECS,
  TEST_ANTHROPIC_API_KEY,
  TEST_ANTHROPIC_BASE_URL,
  TEST_GOOGLE_API_KEY,
  TEST_GOOGLE_BASE_URL,
  TEST_OPENAI_API_KEY,
  TEST_OPENAI_BASE_URL,
  TEST_OPENAI_RESPONSES_API_KEY,
  TEST_OPENAI_RESPONSES_BASE_URL,
  syncProviderCredentials,
} from '../sync-provider-credentials.mjs'

/** Four default seed rows representing seeded providers in the E2E DB; `null` removes one. */
function seedRows(overrides = {}) {
  const row = (name, description, providerType) => ({
    name,
    description,
    providerType,
    baseUrl: null,
    version: '0',
    modelCallTimeoutMillis: 1800000,
    modelCallIdleTimeoutMillis: 120000,
  })
  const defaults = {
    google: row('google', 'Google Gemini.', 'google'),
    openai: row('openai', 'OpenAI (OpenAI Responses).', 'openai_responses'),
    'minimax-anthropic': row('minimax-anthropic', 'MiniMax (Anthropic).', 'anthropic'),
    deepseek: row('deepseek', 'DeepSeek (OpenAI Chat Completions).', 'openai'),
  }
  for (const [key, value] of Object.entries(overrides)) {
    if (value === null) {
      delete defaults[key]
    } else {
      defaults[key] = { ...defaults[key], ...value }
    }
  }
  return Object.values(defaults)
}

test('test intent: no environment skips every provider without any HTTP request', async () => {
  const { request, calls } = makeMockHttp({ rows: [] })
  const lines = await syncProviderCredentials('http://backend', {}, request)

  assert.deepEqual(
    lines,
    PROVIDER_SPECS.map((spec) => `${spec.name}: skip (no credential pair)`),
  )
  assert.deepEqual(calls, [])
})

test('test intent: a half pair fails before HTTP and never leaks its value', async () => {
  for (const spec of PROVIDER_SPECS) {
    for (const env of [{ [spec.baseUrlEnv]: 'https://api.example.com' }, { [spec.apiKeyEnv]: 'test-secret' }]) {
      const { request, calls } = makeMockHttp({ rows: [] })
      await assert.rejects(syncProviderCredentials('http://backend', env, request), (error) => {
        assert.ok(error instanceof ValidationError, spec.name)
        for (const expected of [spec.name, spec.baseUrlEnv, spec.apiKeyEnv]) {
          assert.ok(error.message.includes(expected))
        }
        assert.ok(!error.message.includes('test-secret'))
        assert.ok(!error.message.includes('https://api.example.com'))
        return true
      })
      assert.deepEqual(calls, [])
    }
  }
})

test('test intent: four complete pairs issue one GET and four PUTs with normalized URLs', async () => {
  const { request, calls } = makeMockHttp({ rows: seedRows() })
  const env = {
    [TEST_GOOGLE_BASE_URL]: 'https://generativelanguage.googleapis.com///',
    [TEST_GOOGLE_API_KEY]: 'secret-gemini',
    [TEST_OPENAI_RESPONSES_BASE_URL]: 'https://api.openai.com///',
    [TEST_OPENAI_RESPONSES_API_KEY]: 'secret-openai',
    [TEST_ANTHROPIC_BASE_URL]: 'https://api.minimax.example/anthropic/v1///',
    [TEST_ANTHROPIC_API_KEY]: 'secret-minimax-anthropic',
    [TEST_OPENAI_BASE_URL]: 'https://api.deepseek.com///',
    [TEST_OPENAI_API_KEY]: 'secret-deepseek',
  }

  const lines = await syncProviderCredentials('http://backend/', env, request)

  assert.equal(calls.length, 5)
  const [getUrl, getPayload] = calls[0]
  assert.ok(getUrl.endsWith('/api/providers?pageNumber=1&pageSize=50'))
  assert.equal(getPayload, null)

  const expectedPuts = [
    ['google', 'google', 'https://generativelanguage.googleapis.com', 'secret-gemini'],
    ['openai', 'openai_responses', 'https://api.openai.com/v1', 'secret-openai'],
    ['minimax-anthropic', 'anthropic', 'https://api.minimax.example/anthropic/v1', 'secret-minimax-anthropic'],
    ['deepseek', 'openai', 'https://api.deepseek.com/v1', 'secret-deepseek'],
  ]
  expectedPuts.forEach(([name, providerType, base, secret], index) => {
    const [url, payload, headers] = calls[index + 1]
    assert.equal(url, `http://backend/api/providers/${name}`)
    assert.equal(headers['Content-Type'], 'application/json')
    assert.equal(payload.providerType, providerType)
    assert.equal(payload.baseUrl, base)
    assert.equal(payload.credential, secret)
    assert.equal(payload.expectedVersion, '0')
  })

  assert.deepEqual(lines, [
    'google: configured=True baseUrl_set=True',
    'openai: configured=True baseUrl_set=True',
    'minimax-anthropic: configured=True baseUrl_set=True',
    'deepseek: configured=True baseUrl_set=True',
  ])
})

test('test intent: a shared CPA gateway keeps each protocol base path without duplicate versions', async () => {
  const base = 'https://cpa.example'
  const key = 'fixture-cpa-key'
  const { request, calls } = makeMockHttp({ rows: seedRows() })
  const env = {
    [TEST_GOOGLE_BASE_URL]: `${base}/v1beta`,
    [TEST_GOOGLE_API_KEY]: key,
    [TEST_OPENAI_RESPONSES_BASE_URL]: `${base}/v1`,
    [TEST_OPENAI_RESPONSES_API_KEY]: key,
    [TEST_ANTHROPIC_BASE_URL]: base,
    [TEST_ANTHROPIC_API_KEY]: key,
    [TEST_OPENAI_BASE_URL]: `${base}/v1`,
    [TEST_OPENAI_API_KEY]: key,
  }

  await syncProviderCredentials('http://backend', env, request)

  assert.equal(calls.length, 5)
  assert.deepEqual(
    calls.slice(1).map(([url, payload]) => [url.split('/').pop(), payload.providerType, payload.baseUrl]),
    [
      ['google', 'google', `${base}/v1beta`],
      ['openai', 'openai_responses', `${base}/v1`],
      ['minimax-anthropic', 'anthropic', base],
      ['deepseek', 'openai', `${base}/v1`],
    ],
  )
  assert.ok(calls.slice(1).every(([, payload]) => payload.credential === key))
})

test('test intent: a missing seeded provider fails before any PUT', async () => {
  const { request, calls } = makeMockHttp({ rows: seedRows({ openai: null }) })
  const env = {
    [TEST_GOOGLE_BASE_URL]: 'https://generativelanguage.googleapis.com',
    [TEST_GOOGLE_API_KEY]: 'secret-gemini',
    [TEST_OPENAI_RESPONSES_BASE_URL]: 'https://api.openai.com',
    [TEST_OPENAI_RESPONSES_API_KEY]: 'secret-openai',
  }

  await assert.rejects(syncProviderCredentials('http://backend', env, request), /name=openai/)

  assert.equal(calls.length, 1)
  assert.equal(calls[0][1], null)
})

test('test intent: a providerType mismatch fails before any PUT', async () => {
  const { request, calls } = makeMockHttp({ rows: seedRows({ google: { providerType: 'wrong_type' } }) })
  const env = {
    [TEST_GOOGLE_BASE_URL]: 'https://generativelanguage.googleapis.com',
    [TEST_GOOGLE_API_KEY]: 'secret-gemini',
  }

  await assert.rejects(syncProviderCredentials('http://backend', env, request), /unexpected providerType/)

  assert.equal(calls.length, 1)
  assert.equal(calls[0][1], null)
})

test('test intent: a seeded row without a version fails before any PUT', async () => {
  const { request, calls } = makeMockHttp({ rows: seedRows({ deepseek: { version: '' } }) })
  const env = { [TEST_OPENAI_BASE_URL]: 'https://api.deepseek.com', [TEST_OPENAI_API_KEY]: 'secret-deepseek' }

  await assert.rejects(syncProviderCredentials('http://backend', env, request), /missing required version/)

  assert.equal(calls.length, 1)
  assert.equal(calls[0][1], null)
})

test('test intent: a rerun inherits the existing timeouts and version', async () => {
  const rows = seedRows({
    deepseek: { version: '42', modelCallTimeoutMillis: 999999, modelCallIdleTimeoutMillis: 88888 },
  })
  const { request, calls } = makeMockHttp({ rows })
  const env = { [TEST_OPENAI_BASE_URL]: 'https://api.deepseek.com', [TEST_OPENAI_API_KEY]: 'secret-deepseek' }

  await syncProviderCredentials('http://backend', env, request)

  assert.equal(calls.length, 2)
  const payload = calls[1][1]
  assert.equal(payload.expectedVersion, '42')
  assert.equal(payload.modelCallTimeoutMillis, 999999)
  assert.equal(payload.modelCallIdleTimeoutMillis, 88888)
})

test('test intent: status output never contains the secret or the endpoint', async () => {
  const secret = 'secret-super-sensitive-12345'
  const baseEndpoint = 'https://private.secret.endpoint.com'
  const { request } = makeMockHttp({ rows: seedRows() })
  const env = { [TEST_GOOGLE_BASE_URL]: `${baseEndpoint}/gemini`, [TEST_GOOGLE_API_KEY]: secret }

  const lines = await syncProviderCredentials('http://backend', env, request)

  const combined = lines.join('\n')
  assert.ok(!combined.includes(secret))
  assert.ok(!combined.includes(baseEndpoint))
  assert.ok(combined.includes('google: configured=True baseUrl_set=True'))
})
