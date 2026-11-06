import assert from 'node:assert/strict'
import test from 'node:test'

import { cases } from '../cases/config-matrix.mjs'

const run = (id, ctx) => {
  const caseDef = cases.find((item) => item.id === id)
  assert.ok(caseDef, `missing case ${id}`)
  return caseDef.run(ctx)
}

test('model matrix consumes resource bodies and cleans up with their versions', async () => {
  // Execute the registered setup, valid model and cleanup against unwrapped responses.
  const calls = []
  const artifacts = []
  const ctx = {
    vars: {},
    writeArtifact: (name, contents) => artifacts.push([name, JSON.parse(contents)]),
    async call(method, path, body) {
      calls.push([method, path, body])
      if (method === 'POST' && path === '/api/platform/providers') {
        return { status: 201, json: { name: 'matrix-provider', version: '3' } }
      }
      if (method === 'POST' && path === '/api/platform/models') {
        assert.equal(body.providerName, 'matrix-provider')
        return { status: 201, json: { name: 'matrix-model', version: '4' } }
      }
      if (method === 'DELETE') return { status: 204, json: null }
      throw new Error(`unexpected call ${method} ${path}`)
    },
  }
  await run('matrix.model.setup_provider', ctx)
  await run('config.model.valid.minimal', ctx)
  await run('matrix.model.teardown_provider', ctx)
  assert.equal(ctx.vars.matrixProviderName, 'matrix-provider')
  assert.equal(artifacts[0][1].name, 'matrix-model')
  assert.deepEqual(calls.filter(([method]) => method === 'DELETE').map(([, path]) => path), [
    '/api/platform/models/matrix-model?expectedVersion=4',
    '/api/platform/providers/matrix-provider?expectedVersion=3',
  ])
})

test('agent matrix consumes resource and page bodies for setup and cleanup', async () => {
  // A direct page must discover leftover agents, and each DELETE uses the returned version.
  const deletes = []
  const ctx = {
    vars: {},
    writeArtifact: () => {},
    async call(method, path, body) {
      if (method === 'POST' && path === '/api/platform/providers') {
        return { status: 201, json: { name: 'provider', version: '1' } }
      }
      if (method === 'POST' && path === '/api/platform/models') {
        assert.equal(body.providerName, 'provider')
        return { status: 201, json: { name: 'model', version: '2' } }
      }
      if (method === 'POST' && path === '/api/agents') {
        return { status: 201, json: { name: body.name, config: body.config, version: '3' } }
      }
      if (method === 'GET' && path.startsWith('/api/agents?')) {
        return { status: 200, json: { results: [{ name: 'ag-leftover', version: '4' }] } }
      }
      if (method === 'DELETE') {
        deletes.push(path)
        return { status: 204, json: null }
      }
      throw new Error(`unexpected call ${method} ${path}`)
    },
  }
  await run('matrix.agent.setup_model', ctx)
  await run('config.agent.valid.empty_lists', ctx)
  await run('matrix.agent.teardown_model', ctx)
  assert.match(deletes[0], /^\/api\/agents\/ag-valid\.empty_lists-.*\?expectedVersion=3$/)
  assert.deepEqual(deletes.slice(1), [
    '/api/agents/ag-leftover?expectedVersion=4',
    '/api/platform/models/model?expectedVersion=2',
    '/api/platform/providers/provider?expectedVersion=1',
  ])
})
