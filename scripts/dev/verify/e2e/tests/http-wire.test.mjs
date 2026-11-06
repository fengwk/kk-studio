import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import test from 'node:test'

import { HttpError, httpJson, listResults } from '../lib/http.mjs'

test('HTTP helper reads resources, empty 204 and problem details without a response wrapper', async () => {
  // Exercise the actual fetch path including the no-content and structured error cases.
  const problem = {
    type: 'about:blank',
    title: 'The command conflicts.',
    status: 409,
    code: 'conflict',
    reason: 'STALE_COMMAND_CURSOR',
    detail: 'command cursor is stale',
    errors: { conflicts: [{ type: 'STALE_NODE' }] },
  }
  const server = createServer((request, response) => {
    if (request.url === '/empty') {
      response.writeHead(204).end()
    } else if (request.url === '/conflict') {
      response.writeHead(409, { 'Content-Type': 'application/problem+json' }).end(JSON.stringify(problem))
    } else {
      response.writeHead(201, { 'Content-Type': 'application/json' }).end('{"id":"canvas"}')
    }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    const base = `http://127.0.0.1:${server.address().port}`
    const created = await httpJson(base, 'POST', '/resource')
    assert.equal(created.status, 201)
    assert.deepEqual(created.json, { id: 'canvas' })
    assert.deepEqual((await httpJson(base, 'DELETE', '/empty')).json, null)
    await assert.rejects(() => httpJson(base, 'GET', '/conflict'), (error) => {
      assert.ok(error instanceof HttpError)
      assert.equal(error.status, 409)
      assert.deepEqual(JSON.parse(error.body), problem)
      return true
    })
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
  }
})

test('listResults reads the direct list and rejects retired envelopes', () => {
  // 所有集合端点直接返回数组；页码分页与响应信封都已删除，对象形状一律视为契约漂移。
  const items = [{ id: 'first' }]
  assert.equal(listResults(items), items)
  assert.throws(() => listResults({ results: items }), /expected list data/)
  assert.throws(() => listResults({ data: { results: items } }), /expected list data/)
  assert.throws(() => listResults({ items, nextCursor: null }), /expected list data/)
  assert.throws(() => listResults(null), /expected list data/)
})
