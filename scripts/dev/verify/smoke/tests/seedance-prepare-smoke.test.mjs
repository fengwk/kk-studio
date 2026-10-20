/** Regression guards for the real Seedance prepare-only smoke boundary. */

import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { createServer } from 'node:http'
import path from 'node:path'
import test from 'node:test'
import { promisify } from 'node:util'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const SMOKE_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/smoke/seedance-prepare.mjs')
const run = promisify(execFile)

/**
 * Run the smoke against a recording local server. The server answers 500 so a request that does
 * go out ends the run quickly; the recorded requests are the evidence of what reached the network.
 */
async function runSmoke({ origin, apiKey = 'test-api-key' } = {}) {
  const requests = []
  const server = createServer((request, response) => {
    requests.push({ url: request.url, authorization: request.headers.authorization })
    response.writeHead(500).end()
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const local = `http://127.0.0.1:${server.address().port}`
  try {
    const env = {
      ...process.env,
      RUN_REAL_SEEDANCE_PREPARE_SMOKE: '1',
      SEEDANCE_WORKSPACE_ID: 'test-workspace',
    }
    delete env.OPENCLI_HUB_BASE_URL
    delete env.OPENCLI_API_KEY
    const hub = typeof origin === 'function' ? origin(local) : origin
    if (hub !== undefined) {
      env.OPENCLI_HUB_BASE_URL = hub
    }
    if (apiKey !== null) {
      env.OPENCLI_API_KEY = apiKey
    }
    const result = await run(process.execPath, [SMOKE_SCRIPT, '--confirm-prepare-only'], {
      cwd: REPOSITORY_ROOT,
      env,
    }).catch((error) => error)
    return { code: result.code ?? 0, stdout: result.stdout, stderr: result.stderr, requests }
  } finally {
    server.close()
  }
}

test('test intent: a missing Hub origin exits before any network request', async () => {
  const result = await runSmoke()
  assert.equal(result.code, 2, result.stdout + result.stderr)
  assert.ok(result.stderr.includes('OPENCLI_HUB_BASE_URL is required'))
  assert.deepEqual(result.requests, [])
})

test('test intent: an invalid Hub origin fails closed without echoing the URL or touching the network', async () => {
  for (const invalidOrigin of [() => 'ftp://secret.example', (local) => `${local}/private-token`]) {
    const result = await runSmoke({ origin: invalidOrigin })
    assert.equal(result.code, 2, result.stdout + result.stderr)
    assert.ok(result.stderr.includes('OPENCLI_HUB_BASE_URL must be an HTTP(S) origin'))
    assert.ok(!result.stderr.includes('secret.example') && !result.stderr.includes('private-token'))
    assert.deepEqual(result.requests, [])
  }
})

test('test intent: a missing explicit API key exits before any network request', async () => {
  const result = await runSmoke({ origin: (local) => `${local}/`, apiKey: null })
  assert.equal(result.code, 2, result.stdout + result.stderr)
  assert.ok(result.stderr.includes('OPENCLI_API_KEY is required'))
  assert.deepEqual(result.requests, [])
})

test('test intent: a valid origin is normalized before the first request and carries the bearer header', async () => {
  const result = await runSmoke({ origin: (local) => `${local}/`, apiKey: 'explicit-caller-token' })

  assert.equal(result.requests.length, 1, result.stdout + result.stderr)
  assert.equal(result.requests[0].url, '/api/opencli/execute')
  assert.equal(result.requests[0].authorization, 'Bearer explicit-caller-token')
})
