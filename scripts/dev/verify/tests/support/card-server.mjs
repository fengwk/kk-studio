/** Shared asynchronous helpers for testing daemon bootstrap scripts against a card API. */

import { spawn } from 'node:child_process'
import { createServer } from 'node:http'

import { daemonConfigFixture } from '../../e2e/lib/fixtures.mjs'

export const CARD_ID = '11111111-1111-1111-1111-111111111111'

/**
 * Minimal Environment card API: GET list/detail and CAS PUT of note+daemonConfig.
 *
 * <p>The server runs in-process so bootstrap tests can exercise their real fetch path. Callers must
 * launch scripts asynchronously; a blocking spawn would starve this server's event loop.
 */
export async function startCardServer({
  id = CARD_ID,
  name = 'tool-e2e',
  note = null,
  version = '0',
  daemonConfig = daemonConfigFixture(),
} = {}) {
  let card = {
    id,
    name,
    note,
    daemonConfig: structuredClone(daemonConfig),
    version,
  }
  const calls = []
  const server = createServer(async (request, response) => {
    let raw = ''
    for await (const chunk of request) {
      raw += chunk
    }
    let body = null
    try {
      body = raw ? JSON.parse(raw) : null
    } catch {
      response.writeHead(400, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ type: 'about:blank', status: 400, code: 'validation' }))
      return
    }
    const url = new URL(request.url, 'http://card.test')
    calls.push({ method: request.method, path: url.pathname, body })

    const json = (status, payload) => {
      response.writeHead(status, { 'content-type': 'application/json' })
      response.end(JSON.stringify(payload))
    }

    if (request.method === 'GET' && url.pathname === '/api/platform/environments') {
      json(200, [card])
      return
    }
    if (request.method === 'GET' && url.pathname === `/api/platform/environments/${id}`) {
      json(200, card)
      return
    }
    if (request.method === 'PUT' && url.pathname === `/api/platform/environments/${id}`) {
      if (body?.expectedVersion !== card.version) {
        json(409, { type: 'about:blank', status: 409, code: 'version_conflict' })
        return
      }
      card = {
        ...card,
        note: body.note ?? null,
        daemonConfig: body.daemonConfig,
        version: String(BigInt(card.version) + 1n),
      }
      json(200, card)
      return
    }
    json(404, { type: 'about:blank', status: 404, code: 'not_found' })
  })

  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  return {
    url: `http://127.0.0.1:${address.port}`,
    calls,
    get card() {
      return card
    },
    async close() {
      await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())))
    },
  }
}

/** Run a child process asynchronously so an in-process HTTP fixture can answer it. */
export function runProcess(command, args, { cwd, env, timeoutMillis = 30_000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env })
    let stdout = ''
    let stderr = ''
    const timer = setTimeout(() => {
      child.kill('SIGKILL')
      reject(new Error(`child process timed out after ${timeoutMillis}ms`))
    }, timeoutMillis)
    child.stdout.on('data', (chunk) => {
      stdout += chunk
    })
    child.stderr.on('data', (chunk) => {
      stderr += chunk
    })
    child.once('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
    child.once('close', (status, signal) => {
      clearTimeout(timer)
      resolve({ status, signal, stdout, stderr })
    })
  })
}
