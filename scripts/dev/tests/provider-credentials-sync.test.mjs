/**
 * Tests for the provider-spec synchronization engine: pre-HTTP validation, pre-PUT validation,
 * deterministic execution order and secret sanitization.
 */

import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import test from 'node:test'

import { ValidationError, syncProviderSpecs } from '../lib/provider-credentials.mjs'
import { makeMockHttp } from './support/mock-http.mjs'

const SPEC_A = {
  name: 'prov-a',
  providerType: 'openai_responses',
  baseUrlEnv: 'TEST_A_BASE_URL',
  apiKeyEnv: 'TEST_A_API_KEY',
  requiresV1Suffix: true,
  defaultDescription: 'Provider A',
}
const SPEC_B = {
  name: 'prov-b',
  providerType: 'google',
  baseUrlEnv: 'TEST_B_BASE_URL',
  apiKeyEnv: 'TEST_B_API_KEY',
  requiresV1Suffix: false,
  defaultDescription: 'Provider B',
}
const SPECS = [SPEC_A, SPEC_B]
const BACKEND = 'http://backend:18080'

test('test intent: all-empty environments skip without any HTTP request', async () => {
  const { request, calls } = makeMockHttp()
  const lines = await syncProviderSpecs(BACKEND, SPECS, {}, request)

  assert.equal(calls.length, 0)
  assert.deepEqual(lines, ['prov-a: skip (no credential pair)', 'prov-b: skip (no credential pair)'])
})

test('test intent: a half pair throws before any HTTP request and never leaks its value', async () => {
  for (const env of [{ TEST_A_BASE_URL: 'https://api.example.com' }, { TEST_A_API_KEY: 'test-key' }]) {
    const { request, calls } = makeMockHttp()
    await assert.rejects(syncProviderSpecs(BACKEND, SPECS, env, request), (error) => {
      assert.ok(error instanceof ValidationError)
      for (const expected of ['prov-a', 'TEST_A_BASE_URL', 'TEST_A_API_KEY']) {
        assert.ok(error.message.includes(expected))
      }
      assert.ok(!error.message.includes('test-key'))
      assert.ok(!error.message.includes('https://api.example.com'))
      return true
    })
    assert.equal(calls.length, 0)
  }
})

test('test intent: a missing seeded provider row blocks every PUT', async () => {
  const { request, calls } = makeMockHttp({
    rows: [{ name: 'prov-a', providerType: 'openai_responses', version: '1' }],
  })
  const env = {
    TEST_A_BASE_URL: 'https://api.a.example',
    TEST_A_API_KEY: 'key-a',
    TEST_B_BASE_URL: 'https://api.b.example',
    TEST_B_API_KEY: 'key-b',
  }
  await assert.rejects(syncProviderSpecs(BACKEND, SPECS, env, request), /missing seeded provider name=prov-b/)

  // Only the GET happened.
  assert.equal(calls.length, 1)
  assert.equal(calls[0][1], null)
})

test('test intent: a seeded row without an optimistic-lock version blocks every PUT', async () => {
  const { request, calls } = makeMockHttp({
    rows: [{ name: 'prov-a', providerType: 'openai_responses', version: '' }],
  })
  const env = { TEST_A_BASE_URL: 'https://api.a.example', TEST_A_API_KEY: 'key-a' }
  await assert.rejects(syncProviderSpecs(BACKEND, [SPEC_A], env, request), /missing required version/)

  assert.equal(calls.length, 1)
  assert.equal(calls[0][1], null)
})

test('test intent: a providerType that differs from the spec blocks every PUT', async () => {
  const { request, calls } = makeMockHttp({
    rows: [{ name: 'prov-a', providerType: 'unexpected_type', version: '1' }],
  })
  const env = { TEST_A_BASE_URL: 'https://api.a.example', TEST_A_API_KEY: 'key-a' }
  await assert.rejects(syncProviderSpecs(BACKEND, [SPEC_A], env, request), /unexpected providerType/)

  assert.equal(calls.length, 1)
  assert.equal(calls[0][1], null)
})

test('test intent: partially configured specs skip the rest and report in deterministic spec order', async () => {
  const { request, calls } = makeMockHttp({
    rows: [{ name: 'prov-b', providerType: 'google', version: '5', description: 'Seeded B' }],
  })
  const env = { TEST_B_BASE_URL: 'https://api.b.example///', TEST_B_API_KEY: 'key-b' }
  const lines = await syncProviderSpecs(BACKEND, SPECS, env, request)

  assert.equal(calls.length, 2)
  assert.equal(calls[0][1], null) // GET
  assert.equal(calls[1][0], 'http://backend:18080/api/platform/providers/prov-b')
  const putPayload = calls[1][1]
  assert.equal(putPayload.baseUrl, 'https://api.b.example')
  assert.deepEqual(putPayload.credential, { operation: 'SET', value: 'key-b' })
  assert.equal(putPayload.expectedVersion, '5')
  assert.equal(putPayload.description, 'Seeded B')

  assert.deepEqual(lines, ['prov-a: skip (no credential pair)', 'prov-b: configured=True baseUrl_set=True'])
})

test('the real synchronizer uses the Studio session and does not resend credentials on redirect', async t => {
  const calls = []
  let redirect = false
  const server = createServer(async (request, response) => {
    let content = ''
    for await (const chunk of request) content += chunk
    calls.push({ method: request.method, path: request.url, headers: request.headers, content })
    response.setHeader('Content-Type', 'application/json')
    if (request.url === '/api/auth/login') {
      response.setHeader('Set-Cookie', 'studio_session=fixture; Path=/; HttpOnly')
      return response.end('{}')
    }
    if (request.url === '/api/platform/providers') {
      return response.end(JSON.stringify([{ name: SPEC_A.name, providerType: SPEC_A.providerType, version: '7' }]))
    }
    if (request.url === `/api/platform/providers/${SPEC_A.name}`) {
      if (redirect) {
        response.writeHead(307, { Location: '/unexpected' })
        return response.end('opaqueFixture')
      }
      return response.end(JSON.stringify({ baseUrl: 'https://api.a.example/v1', credential: { configured: true } }))
    }
    response.writeHead(404).end()
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => server.close(resolve)))
  const origin = `http://127.0.0.1:${server.address().port}`
  const inputs = { TEST_A_BASE_URL: 'https://api.a.example', TEST_A_API_KEY: 'opaqueFixture' }
  assert.deepEqual(await syncProviderSpecs(origin, [SPEC_A], inputs), ['prov-a: configured=True baseUrl_set=True'])
  assert.deepEqual(calls.map(call => `${call.method} ${call.path}`), [
    'POST /api/auth/login', 'GET /api/platform/providers', 'PUT /api/platform/providers/prov-a',
  ])
  assert.ok(calls.every(call => call.headers.origin === origin))
  assert.ok(calls.slice(1).every(call => call.headers.cookie === 'studio_session=fixture'))
  assert.equal(JSON.parse(calls[2].content).expectedVersion, '7')
  assert.deepEqual(JSON.parse(calls[2].content).credential, { operation: 'SET', value: 'opaqueFixture' })

  calls.length = 0
  redirect = true
  await assert.rejects(syncProviderSpecs(origin, [SPEC_A], inputs), { message: 'Failed to update provider prov-a (HTTP 307)' })
  assert.deepEqual(calls.map(call => call.path), ['/api/auth/login', '/api/platform/providers', '/api/platform/providers/prov-a'])
})
