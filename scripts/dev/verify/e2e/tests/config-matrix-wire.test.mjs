import assert from 'node:assert/strict'
import test from 'node:test'

import { cases } from '../cases/config-matrix.mjs'
import { modelConfigMatrix } from '../lib/fixtures.mjs'

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

test('pricing matrix cases verify current API round-trips', async () => {
  const created = []
  const ctx = {
    vars: {},
    writeArtifact: () => {},
    async call(method, path, body) {
      if (method === 'POST' && path === '/api/platform/providers') {
        return { status: 201, json: { name: 'pricing-provider', version: '1' } }
      }
      if (method === 'POST' && path === '/api/platform/models') {
        created.push(body)
        const pricing = body.config.pricing
        return {
          status: 201,
          json: {
            name: body.name,
            version: '2',
            config: {
              ...body.config,
              pricing: pricing && {
                ...pricing,
                rules: pricing.rules.toSorted((left, right) => left.type.localeCompare(right.type)),
              },
            },
          },
        }
      }
      if (method === 'POST' && path === '/api/platform/models/pricing-preview') {
        assert.deepEqual(body.pricing.rules.map((rule) => rule.type), [
          'input_token_range', 'service_tier_multiplier', 'time_multiplier',
        ])
        assert.equal(body.serviceTier, 'batch')
        return {
          status: 200,
          json: {
            activeFrom: null,
            matchedRules: body.pricing.rules,
            rates: { input: '1.250', cacheWriteByTtl: { 3600: '0.9375' } },
          },
        }
      }
      if (method === 'DELETE') return { status: 204, json: null }
      throw new Error(`unexpected call ${method} ${path}`)
    },
  }
  await run('matrix.model.setup_provider', ctx)
  await run('config.model.valid.pricing_rules', ctx)
  await run('config.model.valid.unpriced', ctx)
  await run('matrix.model.teardown_provider', ctx)
  assert.equal(created[0].config.pricing.rules.length, 3)
  assert.equal(created[1].config.pricing, null)
})

test('Model pricing fixture uses current rules and date plans, and distinguishes unpriced from zero', () => {
  // Assert the current config contract directly so obsolete override/multiplier or request-body fields cannot return silently.
  const rows = new Map(modelConfigMatrix().map((row) => [row.id, row]))
  const pricing = rows.get('valid.pricing_rules').build().pricing
  assert.deepEqual(Object.keys(pricing).sort(), ['base', 'changes', 'rules'])
  assert.deepEqual(
    pricing.rules.map((rule) => rule.type),
    ['input_token_range', 'time_multiplier', 'service_tier_multiplier'],
  )
  assert.equal(pricing.rules[0].input, '2')
  assert.equal(pricing.rules[1].factor, '1.25')
  assert.equal(pricing.rules[2].factor, '0.5')
  assert.equal(pricing.changes[0].from, '2030-01-01')
  assert.equal(Object.hasOwn(pricing, 'overrides'), false)
  assert.equal(Object.hasOwn(pricing, 'multipliers'), false)
  assert.equal(JSON.stringify(pricing).includes('requestBody'), false)
  assert.equal(rows.get('valid.unpriced').build().pricing, null)
})

test('agent matrix consumes resource and list bodies for setup and cleanup', async () => {
  // A direct list must discover leftover agents, and each DELETE uses the returned version.
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
      if (method === 'POST' && path === '/api/platform/agents') {
        return { status: 201, json: { name: body.name, config: body.config, version: '3' } }
      }
      if (method === 'GET' && path === '/api/platform/agents') {
        return { status: 200, json: [{ name: 'ag-leftover', version: '4' }] }
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
  assert.match(deletes[0], /^\/api\/platform\/agents\/ag-valid\.empty_lists-.*\?expectedVersion=3$/)
  assert.deepEqual(deletes.slice(1), [
    '/api/platform/agents/ag-leftover?expectedVersion=4',
    '/api/platform/models/model?expectedVersion=2',
    '/api/platform/providers/provider?expectedVersion=1',
  ])
})
