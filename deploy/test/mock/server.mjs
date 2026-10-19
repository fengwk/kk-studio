#!/usr/bin/env node
/** Small exact-route HTTP mock used by the isolated Canvas test stack. */

import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

// Deterministic OpenAI Chat Completions stub: the offline smoke agent
// (default-assistant -> stub/acceptance-stub, created through the Catalog API) resolves to this endpoint through
// the Compose network alias (http://stub.local:8080/v1).
const CHAT_COMPLETIONS_STUB_TEXT = 'This is a deterministic offline acceptance stub response.'
const CHAT_COMPLETIONS_ID = 'chatcmpl-acceptance-stub-1'
const CHAT_COMPLETIONS_CREATED = 1700000000

/** A request or route configuration is invalid; reported to the client as a 500. */
class ValueError extends Error {}

export function loadRoutes(routesPath) {
  const document = JSON.parse(readFileSync(routesPath, 'utf8'))
  const routes = document.routes
  if (!Array.isArray(routes)) {
    throw new ValueError("routes.json must contain a 'routes' array")
  }
  for (const route of routes) {
    if (route === null || typeof route !== 'object' || Array.isArray(route)) {
      throw new ValueError('each route must be an object')
    }
    if (typeof route.method !== 'string' || typeof route.path !== 'string') {
      throw new ValueError("each route requires string 'method' and 'path'")
    }
    if (!route.path.startsWith('/')) {
      throw new ValueError("route paths must start with '/'")
    }
  }
  return routes
}

/** OpenAI Chat Completions SSE `data:` event for one streamed chunk. */
function chatCompletionsChunk(model, delta, finishReason) {
  const chunk = {
    id: CHAT_COMPLETIONS_ID,
    object: 'chat.completion.chunk',
    created: CHAT_COMPLETIONS_CREATED,
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }],
  }
  return `data: ${JSON.stringify(chunk)}\n\n`
}

/**
 * Deterministic OpenAI Chat Completions SSE body: role delta, content deltas, stop chunk, then the
 * `[DONE]` sentinel. Content chunks are derived from CHAT_COMPLETIONS_STUB_TEXT (4 words per chunk,
 * space-delimited).
 */
function chatCompletionsSse(model) {
  const words = CHAT_COMPLETIONS_STUB_TEXT.split(/\s+/)
  const pieces = []
  for (let index = 0; index < words.length; index += 4) {
    pieces.push(words.slice(index, index + 4).join(' '))
  }
  const contentDeltas = [
    ...pieces.slice(0, -1).map((piece) => ({ content: `${piece} ` })),
    { content: pieces.at(-1) },
  ]
  return [
    chatCompletionsChunk(model, { role: 'assistant', content: '' }, null),
    ...contentDeltas.map((delta) => chatCompletionsChunk(model, delta, null)),
    chatCompletionsChunk(model, {}, 'stop'),
    'data: [DONE]\n\n',
  ].join('')
}

const EXECUTION_PATH = /^\/api\/executions\/([A-Za-z0-9-]+)$/
const EXECUTION_CANCEL_PATH = /^\/api\/executions\/([A-Za-z0-9-]+)\/cancel$/

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('end', () => resolve(Buffer.concat(chunks)))
    request.on('error', reject)
  })
}

class Mock {
  constructor(routesPath, opencliFakeFixtures, opencliBearerToken) {
    this.routesPath = routesPath
    this.opencliFakeFixtures = opencliFakeFixtures
    this.opencliBearerToken = opencliBearerToken
    this.executionCounter = 0
    this.uploadCounter = 0
    this.executions = new Map()
    this.resources = new Map()
  }

  sendBytes(response, request, status, headers, body) {
    response.writeHead(status, { ...headers, 'Content-Length': body.length })
    response.end(request.method === 'HEAD' ? undefined : body)
  }

  sendJson(response, request, status, document) {
    this.sendBytes(response, request, status, { 'Content-Type': 'application/json' }, Buffer.from(JSON.stringify(document)))
  }

  /** Stream a body without Content-Length: the connection closes afterwards, so clients read until EOF. */
  sendSse(response, request, status, body) {
    response.writeHead(status, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'close',
    })
    response.end(request.method === 'HEAD' ? undefined : body)
  }

  result(response, request, data, status = 200) {
    this.sendJson(response, request, status, {
      status,
      code: status === 201 ? 'CREATED' : 'OK',
      success: true,
      data,
    })
  }

  execution(id, stdout, resources) {
    return {
      id,
      status: 'SUCCEEDED',
      stdout,
      stdoutTruncated: false,
      stderr: '',
      stderrTruncated: false,
      resources,
    }
  }

  /** Built-in OpenAI Chat Completions stub: deterministic SSE stream for langchain4j's OpenAI streaming client. */
  handleChatCompletions(request, response, requestPath, body) {
    if (request.method !== 'POST' || requestPath !== '/v1/chat/completions') {
      return false
    }
    const document = body.length > 0 ? JSON.parse(body.toString('utf8')) : {}
    if (document === null || typeof document !== 'object' || Array.isArray(document)) {
      throw new ValueError('chat completions request must be a JSON object')
    }
    const model = typeof document.model === 'string' && document.model ? document.model : 'acceptance-stub'
    this.sendSse(response, request, 200, chatCompletionsSse(model))
    return true
  }

  isOpencliFakeRequest(request, requestPath) {
    const { method } = request
    if (method === 'POST' && (requestPath === '/api/resources/uploads' || requestPath === '/api/opencli/execute')) {
      return true
    }
    if (method === 'GET' && EXECUTION_PATH.test(requestPath)) {
      return true
    }
    if (method === 'POST' && EXECUTION_CANCEL_PATH.test(requestPath)) {
      return true
    }
    return method === 'GET' && (this.resources.has(requestPath) || requestPath.startsWith('/api/resources/fake/'))
  }

  mediaResource(executionId, fileName, mimeType, fixture) {
    const resourcePath = `/api/resources/fake/${executionId}/${fileName}`
    const media = readFileSync(path.join(this.opencliFakeFixtures, fixture))
    this.resources.set(resourcePath, [mimeType, media])
    return [
      {
        fileName,
        mimeType,
        size: media.length,
        contentUrl: `${resourcePath}?inline=true`,
        downloadUrl: resourcePath,
      },
    ]
  }

  handleOpencliFake(request, response, requestPath, body) {
    if (this.opencliFakeFixtures === null || !this.isOpencliFakeRequest(request, requestPath)) {
      return false
    }
    if ((request.headers.authorization ?? '') !== `Bearer ${this.opencliBearerToken}`) {
      this.sendJson(response, request, 401, { error: 'unauthorized' })
      return true
    }
    const { method } = request

    if (method === 'POST' && requestPath === '/api/resources/uploads') {
      const disposition = /Content-Disposition:[^\r\n]*filename="([^"]+)"/.exec(body.toString('latin1'))
      const filename = disposition ? Buffer.from(disposition[1], 'latin1').toString('utf8') : 'resource.bin'
      this.uploadCounter += 1
      this.result(
        response,
        request,
        { items: [{ resourcePath: `/resources/fake/upload-${this.uploadCounter}/${filename}` }] },
        201,
      )
      return true
    }

    if (method === 'POST' && requestPath === '/api/opencli/execute') {
      const { argv } = JSON.parse(body.toString('utf8'))
      if (!Array.isArray(argv)) {
        this.sendJson(response, request, 400, { error: 'argv required' })
        return true
      }
      this.executionCounter += 1
      const executionId = `fake-${this.executionCounter}`
      let resources = []
      let stdout
      const command = argv.slice(0, 2).join(' ')
      if (command === 'chatgpt-agent ask') {
        resources = this.mediaResource(executionId, 'output.png', 'image/png', 'tiny.png')
        stdout = '[]'
      } else if (command === 'jimeng-agent video') {
        const submitIndex = argv.indexOf('--submit')
        if (submitIndex === -1) {
          throw new ValueError('--submit is not in argv')
        }
        const submitted = argv[submitIndex + 1] === '1'
        stdout = JSON.stringify([
          { status: submitted ? 'submitted' : 'prepared', submitted, assetId: '0123456789abcdef' },
        ])
      } else if (command === 'jimeng-agent status') {
        resources = this.mediaResource(executionId, 'output.mp4', 'video/mp4', 'tiny.mp4')
        stdout = '[{"status":"ready","downloaded":true}]'
      } else {
        this.sendJson(response, request, 400, { error: 'unsupported fake argv', argv })
        return true
      }
      const execution = this.execution(executionId, stdout, resources)
      this.executions.set(executionId, execution)
      this.result(response, request, execution)
      return true
    }

    const executionMatch = EXECUTION_PATH.exec(requestPath)
    if (method === 'GET' && executionMatch) {
      this.respondExecution(response, request, executionMatch[1])
      return true
    }

    const cancelMatch = EXECUTION_CANCEL_PATH.exec(requestPath)
    if (method === 'POST' && cancelMatch) {
      this.respondExecution(response, request, cancelMatch[1])
      return true
    }

    if (method === 'GET' && this.resources.has(requestPath)) {
      const [mediaType, media] = this.resources.get(requestPath)
      this.sendBytes(response, request, 200, { 'Content-Type': mediaType }, media)
      return true
    }
    return false
  }

  respondExecution(response, request, executionId) {
    const execution = this.executions.get(executionId)
    if (execution === undefined) {
      this.sendJson(response, request, 404, { error: 'execution not found' })
    } else {
      this.result(response, request, execution)
    }
  }

  async handle(request, response) {
    const requestPath = request.url.split(/[?#]/)[0]
    const body = await readBody(request)

    if (requestPath === '/health') {
      this.sendJson(response, request, 200, { status: 'ok' })
      return
    }

    try {
      if (this.handleChatCompletions(request, response, requestPath, body)) {
        return
      }
    } catch (error) {
      this.sendJson(response, request, 500, { error: `chat completions mock failed: ${error.message}` })
      return
    }

    try {
      if (this.handleOpencliFake(request, response, requestPath, body)) {
        return
      }
    } catch (error) {
      this.sendJson(response, request, 500, { error: `opencli fake failed: ${error.message}` })
      return
    }

    let routes
    try {
      routes = loadRoutes(this.routesPath)
    } catch (error) {
      this.sendJson(response, request, 500, { error: `invalid route configuration: ${error.message}` })
      return
    }

    const route = routes.find(
      (candidate) => candidate.method.toUpperCase() === request.method && candidate.path === requestPath,
    )
    if (route === undefined) {
      this.sendJson(response, request, 404, { error: 'no mock route', method: request.method, path: requestPath })
      return
    }

    const status = Number.parseInt(route.status ?? 200, 10)
    let headers = route.headers ?? {}
    if (headers === null || typeof headers !== 'object' || Array.isArray(headers)) {
      this.sendJson(response, request, 500, { error: 'route headers must be an object' })
      return
    }

    let routeBody
    if ('json' in route) {
      routeBody = Buffer.from(JSON.stringify(route.json))
      headers = { 'Content-Type': 'application/json', ...headers }
    } else {
      const bodyValue = route.body ?? ''
      if (typeof bodyValue !== 'string') {
        this.sendJson(response, request, 500, { error: 'route body must be a string' })
        return
      }
      routeBody = Buffer.from(bodyValue)
    }
    this.sendBytes(response, request, status, headers, routeBody)
  }
}

/**
 * Create and start a configured mock server; tests pass port 0 for an ephemeral port.
 * Resolves once the server is listening.
 */
export async function buildServer(
  routesPath,
  opencliFakeFixtures = null,
  opencliBearerToken = 'synthetic-opencli-key',
  { host = '0.0.0.0', port = 8080 } = {},
) {
  loadRoutes(routesPath)
  const mock = new Mock(routesPath, opencliFakeFixtures, opencliBearerToken)
  const server = createServer((request, response) => {
    mock.handle(request, response).catch((error) => {
      response.destroy(error)
    })
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, host, resolve)
  })
  return server
}

async function main() {
  const { values } = parseArgs({
    options: {
      routes: { type: 'string' },
      'opencli-fake-fixtures': { type: 'string' },
      'opencli-bearer-token': { type: 'string', default: 'synthetic-opencli-key' },
    },
  })
  if (!values.routes) {
    process.stderr.write('error: the following arguments are required: --routes\n')
    process.exit(2)
  }
  await buildServer(values.routes, values['opencli-fake-fixtures'] ?? null, values['opencli-bearer-token'])
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await main()
}
