/**
 * Tests for the shared provider credential primitives in scripts/dev/lib/provider-credentials.mjs:
 * safe base URL validation without secret/URL leakage, update payload construction preserving
 * versions, metadata and timeouts, and the low-level backend provider catalog HTTP operations.
 */

import assert from 'node:assert/strict'
import test from 'node:test'

import {
  DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS,
  DEFAULT_MODEL_CALL_TIMEOUT_MILLIS,
  HttpError,
  ValidationError,
  buildPayload,
  getProviders,
  normalizeBaseUrl,
  putProvider,
} from '../lib/provider-credentials.mjs'
import { makeMockHttp } from './support/mock-http.mjs'

test('test intent: trailing slashes are stripped but custom paths are kept when no /v1 suffix is required', () => {
  assert.equal(
    normalizeBaseUrl('https://generativelanguage.googleapis.com///', false, 'TEST_BASE_URL', 'custom-provider'),
    'https://generativelanguage.googleapis.com',
  )
  assert.equal(
    normalizeBaseUrl('https://api.gateway.example/custom/path///', false),
    'https://api.gateway.example/custom/path',
  )
})

test('test intent: a required /v1 suffix is normalized to exactly one regardless of input', () => {
  for (const input of ['https://api.openai.example///', 'https://api.openai.example/v1///']) {
    assert.equal(
      normalizeBaseUrl(input, true, 'TEST_OPENAI_BASE_URL', 'openai'),
      'https://api.openai.example/v1',
    )
  }
})

test('test intent: invalid URLs are rejected and the error never leaks the URL, token or password', () => {
  const invalidCases = [
    ['', 'empty'],
    ['   ', 'whitespace'],
    ['ftp://api.example.com', 'scheme'],
    ['http:///missing-host', 'host'],
    ['https://user:pass@api.example.com/v1', 'credentials'],
    ['https://api.example.com/v1?token=secret123', 'query'],
    ['https://api.example.com/v1#hash_token', 'fragment'],
    ['https://api.example.com:not-a-port/v1', 'port'],
  ]
  for (const [url, reason] of invalidCases) {
    assert.throws(
      () => normalizeBaseUrl(url, true, 'TEST_TARGET_BASE_URL', 'target-provider'),
      (error) => {
        assert.ok(error instanceof ValidationError, reason)
        assert.ok(error.message.includes('target-provider'), reason)
        assert.ok(error.message.includes('TEST_TARGET_BASE_URL'), reason)
        if (url.trim()) {
          assert.ok(!error.message.includes(url.trim()), reason)
        }
        for (const leaked of ['secret123', 'hash_token', 'pass']) {
          assert.ok(!error.message.includes(leaked), `${reason}: ${leaked}`)
        }
        return true
      },
      reason,
    )
  }
})

test('test intent: an update payload inherits the backend description, type, version and custom timeouts', () => {
  const payload = buildPayload('https://api.example.com/v1', 'test-secret', {
    description: 'Custom Description',
    providerType: 'openai_responses',
    modelCallTimeoutMillis: 600000,
    modelCallIdleTimeoutMillis: 50000,
    version: '7',
  })

  assert.equal(payload.description, 'Custom Description')
  assert.equal(payload.providerType, 'openai_responses')
  assert.equal(payload.expectedVersion, '7')
  assert.equal(payload.modelCallTimeoutMillis, 600000)
  assert.equal(payload.modelCallIdleTimeoutMillis, 50000)
  assert.equal(payload.baseUrl, 'https://api.example.com/v1')
  assert.equal(payload.credential, 'test-secret')
})

test('test intent: missing timeouts and description fall back to platform defaults and the spec metadata', () => {
  const spec = {
    name: 'mock-provider',
    providerType: 'google',
    baseUrlEnv: 'TEST_MOCK_BASE_URL',
    apiKeyEnv: 'TEST_MOCK_API_KEY',
    requiresV1Suffix: false,
    defaultDescription: 'Default Mock Description.',
  }
  const payload = buildPayload('https://api.example.com', 'test-secret', { version: 3 }, spec)

  assert.equal(payload.expectedVersion, '3')
  assert.equal(payload.providerType, 'google')
  assert.equal(payload.description, 'Default Mock Description.')
  assert.equal(payload.modelCallTimeoutMillis, DEFAULT_MODEL_CALL_TIMEOUT_MILLIS)
  assert.equal(payload.modelCallIdleTimeoutMillis, DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS)
})

test('test intent: getProviders issues a GET against the paged catalog endpoint', async () => {
  const { request, calls } = makeMockHttp({ rows: [{ name: 'test-p' }] })
  const result = await getProviders('http://backend:18080/', request)

  assert.equal(calls.length, 1)
  const [url, data] = calls[0]
  assert.ok(url.startsWith('http://backend:18080/api/providers?'))
  assert.equal(data, null)
  assert.deepEqual(result.data.results, [{ name: 'test-p' }])
})

test('test intent: getProviders wraps network failures without exposing internals', async () => {
  const { request } = makeMockHttp({ getError: new Error('raw socket failed') })
  await assert.rejects(getProviders('http://backend:18080', request), {
    message: 'Failed to fetch existing providers from backend',
  })
})

test('test intent: putProvider URL-encodes the provider name so slashes cannot break the route', async () => {
  const { request, calls } = makeMockHttp()
  await putProvider('http://backend:18080', 'custom/provider:v1', { baseUrl: 'https://api.example.com' }, request)

  assert.equal(calls.length, 1)
  const [url, payload, headers] = calls[0]
  assert.equal(url, 'http://backend:18080/api/providers/custom%2Fprovider%3Av1')
  assert.equal(headers['Content-Type'], 'application/json')
  assert.equal(payload.baseUrl, 'https://api.example.com')
})

test('test intent: putProvider exposes only the status code and provider name on an HTTP error', async () => {
  const { request } = makeMockHttp({ putError: new HttpError(409) })
  await assert.rejects(
    putProvider('http://backend:18080', 'my-prov', { credential: 'secret' }, request),
    (error) => {
      assert.match(error.message, /^Failed to update provider my-prov \(HTTP 409\)$/)
      assert.ok(!error.message.includes('secret'))
      return true
    },
  )
})
