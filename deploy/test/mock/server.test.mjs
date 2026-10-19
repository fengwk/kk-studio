/**
 * Focused protocol tests for the deploy/test HTTP mock.
 *
 * Run from the repository root:
 *
 *     node --test deploy/test/mock/server.test.mjs
 */

import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { buildServer } from './server.mjs'

/** Start a mock server on an ephemeral loopback port and return a request helper plus a cleanup. */
async function startMock({ fixtures = false, token } = {}) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-mock-'))
  const routesPath = path.join(directory, 'routes.json')
  writeFileSync(routesPath, '{"routes": []}')
  let fixturesDir = null
  if (fixtures) {
    fixturesDir = path.join(directory, 'fixtures')
    mkdirSync(fixturesDir)
    writeFileSync(fixturesDir + '/tiny.png', Buffer.from('\x89PNG\r\n\x1a\nfake-png', 'latin1'))
    writeFileSync(fixturesDir + '/tiny.mp4', 'fake-mp4')
  }
  const server = await buildServer(routesPath, fixturesDir, token, { host: '127.0.0.1', port: 0 })
  const base = `http://127.0.0.1:${server.address().port}`
  return {
    async request(method, requestPath, body, headers = {}) {
      const response = await fetch(base + requestPath, { method, body, headers })
      return { status: response.status, headers: response.headers, payload: Buffer.from(await response.arrayBuffer()) }
    },
    async close() {
      server.closeAllConnections()
      await new Promise((resolve) => server.close(resolve))
      rmSync(directory, { recursive: true, force: true })
    },
  }
}

const postJson = (mock, requestPath, document, headers = {}) =>
  mock.request('POST', requestPath, JSON.stringify(document), { 'Content-Type': 'application/json', ...headers })

test('test intent: chat completions returns a deterministic OpenAI SSE stream', async () => {
  const mock = await startMock()
  try {
    const { status, headers, payload } = await postJson(mock, '/v1/chat/completions', {
      model: 'acceptance-stub',
      messages: [{ role: 'user', content: 'ping' }],
    })
    assert.equal(status, 200)
    assert.equal(headers.get('content-type'), 'text/event-stream')
    const body = payload.toString('utf8')
    assert.ok(body.endsWith('data: [DONE]\n\n'), body)

    const events = body.trim().split('\n\n')
    const chunks = events.slice(0, -1).map((event) => {
      assert.ok(event.startsWith('data: '), event)
      return JSON.parse(event.slice('data: '.length))
    })
    assert.ok(chunks.length >= 2)
    for (const chunk of chunks) {
      assert.equal(chunk.id, 'chatcmpl-acceptance-stub-1')
      assert.equal(chunk.object, 'chat.completion.chunk')
      assert.equal(chunk.model, 'acceptance-stub')
      assert.equal(chunk.choices.length, 1)
      assert.equal(chunk.choices[0].index, 0)
    }
    assert.equal(chunks[0].choices[0].delta.role, 'assistant')
    assert.equal(
      chunks.map((chunk) => chunk.choices[0].delta.content ?? '').join(''),
      'This is a deterministic offline acceptance stub response.',
    )
    assert.equal(chunks.at(-1).choices[0].finish_reason, 'stop')
  } finally {
    await mock.close()
  }
})

test('test intent: chat completions echoes the request model', async () => {
  const mock = await startMock()
  try {
    const { status, payload } = await postJson(mock, '/v1/chat/completions', {
      model: 'custom-model',
      messages: [{ role: 'user', content: 'ping' }],
    })
    assert.equal(status, 200)
    const first = payload.toString('utf8').split('\n\n')[0].slice('data: '.length)
    assert.equal(JSON.parse(first).model, 'custom-model')
  } finally {
    await mock.close()
  }
})

test('test intent: chat completions rejects invalid JSON with a 500', async () => {
  const mock = await startMock()
  try {
    const { status, payload } = await mock.request('POST', '/v1/chat/completions', '{not json')
    assert.equal(status, 500)
    assert.ok(payload.toString('utf8').includes('chat completions mock failed'))
  } finally {
    await mock.close()
  }
})

test('test intent: chat completions only answers POST', async () => {
  const mock = await startMock()
  try {
    const { status, payload } = await mock.request('GET', '/v1/chat/completions')
    assert.equal(status, 404)
    assert.ok(payload.toString('utf8').includes('no mock route'))
  } finally {
    await mock.close()
  }
})

const TOKEN = 'synthetic-opencli-key'

test('test intent: the health check requires no auth', async () => {
  const mock = await startMock({ fixtures: true, token: TOKEN })
  try {
    const { status, payload } = await mock.request('GET', '/health')
    assert.equal(status, 200)
    assert.deepEqual(JSON.parse(payload), { status: 'ok' })
  } finally {
    await mock.close()
  }
})

test('test intent: protected endpoints reject a missing or invalid bearer token', async () => {
  const mock = await startMock({ fixtures: true, token: TOKEN })
  try {
    const endpoints = [
      ['POST', '/api/resources/uploads', 'fake-body'],
      ['POST', '/api/opencli/execute', JSON.stringify({ argv: ['chatgpt-agent', 'ask'] })],
      ['GET', '/api/executions/fake-1', undefined],
      ['POST', '/api/executions/fake-1/cancel', undefined],
      ['GET', '/api/resources/fake/fake-1/output.png', undefined],
    ]
    const invalidHeaders = [
      {},
      { Authorization: 'Bearer wrong-token' },
      { Authorization: `Basic ${TOKEN}` },
      { Authorization: TOKEN },
    ]
    for (const [method, requestPath, body] of endpoints) {
      for (const headers of invalidHeaders) {
        const { status, payload } = await mock.request(method, requestPath, body, headers)
        assert.equal(status, 401, `${method} ${requestPath} must reject ${JSON.stringify(headers)}`)
        assert.equal(JSON.parse(payload.toString('utf8')).error, 'unauthorized')
      }
    }
  } finally {
    await mock.close()
  }
})

test('test intent: protected endpoints serve the fake Hub flow with a valid bearer token', async () => {
  const mock = await startMock({ fixtures: true, token: TOKEN })
  try {
    const auth = { Authorization: `Bearer ${TOKEN}` }
    const execute = (argv) => postJson(mock, '/api/opencli/execute', { argv }, auth)

    // 1. Upload resource
    const multipart =
      '--boundary\r\n' +
      'Content-Disposition: form-data; name="file"; filename="sample.png"\r\n\r\n' +
      'sample-data\r\n--boundary--'
    let response = await mock.request('POST', '/api/resources/uploads', multipart, {
      ...auth,
      'Content-Type': 'multipart/form-data; boundary=boundary',
    })
    assert.equal(response.status, 201)
    const upload = JSON.parse(response.payload.toString('utf8')).data
    assert.ok(upload.items[0].resourcePath.startsWith('/resources/fake/upload-'))

    // 2. Execute chatgpt-agent ask
    response = await execute(['chatgpt-agent', 'ask'])
    assert.equal(response.status, 200)
    const execution = JSON.parse(response.payload.toString('utf8')).data
    assert.equal(execution.status, 'SUCCEEDED')
    assert.equal(execution.resources.length, 1)
    const resourcePath = execution.resources[0].downloadUrl

    // 3. Query execution
    response = await mock.request('GET', `/api/executions/${execution.id}`, undefined, auth)
    assert.equal(response.status, 200)
    assert.equal(JSON.parse(response.payload.toString('utf8')).data.id, execution.id)

    // 4. Download resource
    response = await mock.request('GET', resourcePath, undefined, auth)
    assert.equal(response.status, 200)
    assert.equal(response.headers.get('content-type'), 'image/png')
    assert.ok(response.payload.equals(Buffer.from('\x89PNG\r\n\x1a\nfake-png', 'latin1')))

    // 5. Cancel execution
    response = await mock.request('POST', `/api/executions/${execution.id}/cancel`, undefined, auth)
    assert.equal(response.status, 200)
    assert.equal(JSON.parse(response.payload.toString('utf8')).data.id, execution.id)

    // 6. Execute jimeng-agent video (submit=0)
    response = await execute(['jimeng-agent', 'video', '--submit', '0'])
    assert.equal(response.status, 200)
    assert.ok(JSON.parse(response.payload.toString('utf8')).data.stdout.includes('"status":"prepared"'))

    // 7. Execute jimeng-agent status
    response = await execute(['jimeng-agent', 'status'])
    assert.equal(response.status, 200)
    assert.ok(JSON.parse(response.payload.toString('utf8')).data.stdout.includes('"status":"ready"'))
  } finally {
    await mock.close()
  }
})
