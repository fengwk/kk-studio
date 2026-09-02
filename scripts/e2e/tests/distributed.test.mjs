import assert from 'node:assert/strict'
import test from 'node:test'

import { createBaseUrls, createNodeCall } from '../lib/distributed.mjs'
import { redactSecrets } from '../lib/redact.mjs'

test('createBaseUrls requires both node URLs and normalizes trailing slashes', () => {
  // Test intent: distributed capability is only active when both node URLs exist.
  assert.equal(createBaseUrls('', ''), null)
  assert.equal(createBaseUrls('http://a', ''), null)
  assert.equal(createBaseUrls('', 'http://b'), null)
  assert.deepEqual(createBaseUrls('http://a/', 'http://b/'), {
    a: 'http://a',
    b: 'http://b',
  })
})

test('createNodeCall routes by node id and rejects unknown nodes', async () => {
  // Test intent: ctx.callNode('a'|'b') must hit exactly the selected node URL.
  const seen = []
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url, options) => {
    seen.push({ url, method: options?.method })
    return new Response(JSON.stringify({ data: { ok: true } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const call = createNodeCall(createBaseUrls('http://node-a.test', 'http://node-b.test'))
    const a = await call('a', 'GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=1')
    const b = await call('b', 'GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=1')
    assert.equal(seen[0].url, 'http://node-a.test/api/ai/catalog/agents?pageNumber=1&pageSize=1')
    assert.equal(seen[1].url, 'http://node-b.test/api/ai/catalog/agents?pageNumber=1&pageSize=1')
    assert.equal(a.json.data.ok, true)
    assert.equal(b.json.data.ok, true)

    await assert.rejects(() => call('c', 'GET', '/api/ai/catalog/agents'), /unknown node 'c'/)
    assert.equal(seen.length, 2, 'unknown node must not trigger a request')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('createNodeCall forwards the body as JSON like the single-node path', async () => {
  // Test intent: dual-node routing must not change the httpJson wire contract.
  const seen = []
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url, options) => {
    seen.push({ url, body: options?.body, headers: options?.headers })
    return new Response('{"data":1}', { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const call = createNodeCall(createBaseUrls('http://a.test', 'http://b.test'))
    await call('b', 'POST', '/api/canvases', { title: 't' }, 5000)
    assert.deepEqual(JSON.parse(seen[0].body), { title: 't' })
    assert.equal(seen[0].headers['Content-Type'], 'application/json')
    assert.equal(seen[0].url, 'http://b.test/api/canvases')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('host-mock cases stay in the single-instance matrix and leave distributed', async () => {
  // Test intent: cases with host-loopback mocks cannot run against containerized
  // distributed apps; the capability must be default-on and disabled by --distributed.
  const source = await import('node:fs').then((fs) =>
    fs.readFileSync('scripts/e2e/run-matrix.mjs', 'utf8'),
  )
  assert.match(source, /c\.requires\.has\('host-mock'\) && args\.distributed\) return false/)
  // 单实例默认路径不受影响：host-mock 只在 distributed 下被排除。
  assert.match(source, /if \(c\.requires\.has\('host-mock'\) && args\.distributed\) return false/)
})

test('redactSecrets masks credential values and keeps ordinary text', () => {
  // Test intent: distributed reports must never carry credentials into reports/e2e.
  assert.match(redactSecrets('TEST_MINIMAX_API_KEY=sk-value-123'), /^TEST_MINIMAX_API_KEY: \[REDACTED\]$/)
  assert.match(
    redactSecrets('{"TEST_MINIMAX_API_KEY": "sk-value-123"}'),
    /"TEST_MINIMAX_API_KEY": "\[REDACTED\]"/,
  )
  assert.equal(redactSecrets('Authorization: Bearer jwt.value'), 'Authorization: [REDACTED]')
  assert.match(redactSecrets('gatewayToken=tok-1'), /^gatewayToken: \[REDACTED\]$/)
  // 非 credential 键（URL、普通文本）不脱敏。
  assert.equal(
    redactSecrets('{"TEST_MINIMAX_BASE_URL": "https://host.example/v1"}'),
    '{"TEST_MINIMAX_BASE_URL": "https://host.example/v1"}',
  )
  assert.equal(
    redactSecrets('plain text and baseUrl=https://host.example/v1 stay'),
    'plain text and baseUrl=https://host.example/v1 stay',
  )
  assert.equal(redactSecrets(null), null)
})
