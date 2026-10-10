import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto'
import type { AddressInfo } from 'node:net'
import { resolve as resolvePath } from 'node:path'
import { IdeError } from './errors.js'

/**
 * Loopback bridge between the daemon and the in-window VS Code bridge extension.
 *
 * The extension runs inside the code-server extension host, one instance per managed
 * workspace window. It authenticates with a random per-service secret delivered through
 * the code-server process environment (never to the browser) and reports its exact
 * assigned workspace file so the daemon can bind the window to a view id.
 */

export interface BridgeCommand {
  op: 'changeDirectory' | 'focusTerminal' | 'closeView'
  directory?: string
}

export interface BridgeResult {
  ok: boolean
  code?: string
  message?: string
}

export interface IdeBridge {
  readonly url: string
  readonly secret: string
  assign(viewId: string, workspacePath: string): void
  revoke(viewId: string): void
  isConnected(viewId: string): boolean
  onPty(listener: (viewId: string, pid: number) => void | Promise<void>): void
  request(viewId: string, command: BridgeCommand, signal: AbortSignal): Promise<BridgeResult>
  close(): Promise<void>
}

const MAX_BODY_BYTES = 64 * 1024
const COMMAND_TIMEOUT_MILLIS = 20_000

function pathKey(path: string): string {
  const resolved = resolvePath(path)
  return process.platform === 'win32' ? resolved.toLowerCase() : resolved
}

function isLoopback(address: string | undefined): boolean {
  if (address === undefined) {
    return false
  }
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1'
}

function tokenMatches(provided: string, expected: string): boolean {
  const left = Buffer.from(provided)
  const right = Buffer.from(expected)
  return left.length === right.length && timingSafeEqual(left, right)
}

async function readBody(request: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as Uint8Array)
    size += buffer.length
    if (size > MAX_BODY_BYTES) {
      throw new IdeError('bridge request body too large')
    }
    chunks.push(buffer)
  }
  return Buffer.concat(chunks)
}

interface ViewState {
  workspacePath: string
  key: string
  stream?: ServerResponse
}

interface Pending {
  viewId: string
  op: BridgeCommand['op']
  resolve: (result: BridgeResult) => void
  timer: NodeJS.Timeout
}

export async function createIdeBridge(): Promise<IdeBridge> {
  const secret = randomBytes(32).toString('base64url')
  const views = new Map<string, ViewState>()
  const byKey = new Map<string, string>()
  const pending = new Map<string, Pending>()
  const ptyListeners = new Set<(viewId: string, pid: number) => void | Promise<void>>()

  const server = createServer((request, response) => {
    void handleRequest(request, response)
  })

  const respond = (response: ServerResponse, status: number, body = ''): void => {
    response.writeHead(status, { 'content-type': 'text/plain', 'cache-control': 'no-store' })
    response.end(body)
  }

  const resolvePending = (id: string, result: BridgeResult): void => {
    const entry = pending.get(id)
    if (entry) {
      pending.delete(id)
      clearTimeout(entry.timer)
      entry.resolve(result)
    }
  }

  const settleView = (viewId: string, result: BridgeResult): void => {
    for (const [id, entry] of pending) {
      if (entry.viewId === viewId) {
        resolvePending(id, result)
      }
    }
  }

  const handleRequest = async (request: IncomingMessage, response: ServerResponse): Promise<void> => {
    try {
      if (!isLoopback(request.socket.remoteAddress)) {
        respond(response, 403, 'forbidden')
        return
      }
      const url = new URL(request.url ?? '/', 'http://127.0.0.1')
      const token = url.searchParams.get('token')
      if (token === null || !tokenMatches(token, secret)) {
        respond(response, 401, 'unauthorized')
        return
      }
      switch (`${request.method ?? 'GET'} ${url.pathname}`) {
        case 'GET /bridge/stream':
          handleStream(url, response)
          return
        case 'POST /bridge/result':
          await handleResult(request, response)
          return
        case 'POST /bridge/pty':
          await handlePty(request, response)
          return
        default:
          respond(response, 404, 'not found')
      }
    } catch (error) {
      if (!response.headersSent) {
        respond(response, 400, error instanceof IdeError ? error.message : 'bad request')
      } else {
        response.destroy()
      }
    }
  }

  const handleStream = (url: URL, response: ServerResponse): void => {
    const workspacePath = url.searchParams.get('workspacePath')
    if (workspacePath === null) {
      respond(response, 400, 'workspacePath is required')
      return
    }
    const viewId = byKey.get(pathKey(workspacePath))
    if (viewId === undefined) {
      // Unknown or stale workspace: refuse rather than trusting the caller.
      respond(response, 403, 'workspace is not assigned')
      return
    }
    const view = views.get(viewId)
    if (!view) {
      respond(response, 403, 'workspace is not assigned')
      return
    }
    // A newer window replaces a stale stream for the same view.
    view.stream?.end()
    response.writeHead(200, {
      'content-type': 'application/x-ndjson',
      'cache-control': 'no-store',
      connection: 'keep-alive',
    })
    view.stream = response
    response.write(`${JSON.stringify({ type: 'registered', viewId })}\n`)
    response.on('close', () => {
      if (view.stream === response) {
        view.stream = undefined
      }
      // A delivered folder change may restart the extension host, which drops this stream.
      // Treat that as success instead of a timeout or a false rollback; other pending
      // commands can no longer be answered and must fail.
      for (const [id, entry] of pending) {
        if (entry.viewId === viewId) {
          resolvePending(
            id,
            entry.op === 'changeDirectory' ? { ok: true } : { ok: false, message: 'ide window closed' },
          )
        }
      }
    })
  }

  const handleResult = async (request: IncomingMessage, response: ServerResponse): Promise<void> => {
    const body = await readBody(request)
    let parsed: unknown
    try {
      parsed = JSON.parse(body.toString('utf8'))
    } catch {
      respond(response, 400, 'invalid json')
      return
    }
    if (typeof parsed !== 'object' || parsed === null) {
      respond(response, 400, 'invalid result')
      return
    }
    const result = parsed as Record<string, unknown>
    const id = result.id
    if (typeof id !== 'string') {
      respond(response, 400, 'invalid result')
      return
    }
    resolvePending(id, {
      ok: result.ok === true,
      code: typeof result.code === 'string' ? result.code : undefined,
      message: typeof result.message === 'string' ? result.message : undefined,
    })
    respond(response, 204)
  }

  const handlePty = async (request: IncomingMessage, response: ServerResponse): Promise<void> => {
    const body = await readBody(request)
    let parsed: unknown
    try {
      parsed = JSON.parse(body.toString('utf8'))
    } catch {
      respond(response, 400, 'invalid json')
      return
    }
    if (typeof parsed !== 'object' || parsed === null) {
      respond(response, 400, 'invalid pty event')
      return
    }
    const event = parsed as Record<string, unknown>
    const workspacePath = event.workspacePath
    const pid = event.pid
    if (typeof workspacePath !== 'string' || typeof pid !== 'number' || !Number.isInteger(pid) || pid <= 0) {
      respond(response, 400, 'invalid pty event')
      return
    }
    const viewId = byKey.get(pathKey(workspacePath))
    if (viewId === undefined) {
      respond(response, 403, 'workspace is not assigned')
      return
    }
    for (const listener of ptyListeners) {
      try {
        await listener(viewId, pid)
      } catch (error) {
        respond(response, 500, error instanceof IdeError ? error.message : 'pty registration failed')
        return
      }
    }
    respond(response, 204)
  }

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => resolve())
  })
  const port = (server.address() as AddressInfo).port

  return {
    url: `http://127.0.0.1:${port}`,
    secret,
    assign(viewId, workspacePath) {
      const key = pathKey(workspacePath)
      const existing = views.get(viewId)
      if (existing && existing.key !== key) {
        byKey.delete(existing.key)
      }
      views.set(viewId, { workspacePath, key })
      byKey.set(key, viewId)
    },
    revoke(viewId) {
      settleView(viewId, { ok: false, message: 'ide window was closed' })
      const view = views.get(viewId)
      if (view) {
        view.stream?.end()
        byKey.delete(view.key)
        views.delete(viewId)
      }
    },
    onPty(listener) {
      ptyListeners.add(listener)
    },
    isConnected(viewId) {
      return views.get(viewId)?.stream !== undefined
    },
    request(viewId, command, signal) {
      const view = views.get(viewId)
      const stream = view?.stream
      if (!stream) {
        return Promise.reject(new IdeError('ide window is not connected'))
      }
      if (signal.aborted) {
        return Promise.resolve({ ok: false, message: 'ide request was cancelled' })
      }
      const id = randomUUID()
      return new Promise<BridgeResult>((resolve) => {
        const timer = setTimeout(() => {
          resolvePending(id, { ok: false, message: 'ide window did not respond' })
        }, COMMAND_TIMEOUT_MILLIS)
        timer.unref()
        const onAbort = (): void => {
          resolvePending(id, { ok: false, message: 'ide request was cancelled' })
        }
        signal.addEventListener('abort', onAbort, { once: true })
        pending.set(id, {
          viewId,
          op: command.op,
          timer,
          resolve: (result) => {
            signal.removeEventListener('abort', onAbort)
            resolve(result)
          },
        })
        stream.write(`${JSON.stringify({ type: 'command', id, ...command })}\n`)
      })
    },
    close() {
      for (const view of views.values()) {
        view.stream?.end()
      }
      for (const entry of pending.values()) {
        clearTimeout(entry.timer)
        entry.resolve({ ok: false, message: 'ide bridge closed' })
      }
      pending.clear()
      views.clear()
      byKey.clear()
      ptyListeners.clear()
      return closeServer(server)
    },
  }
}

function closeServer(server: Server): Promise<void> {
  return new Promise<void>((resolve) => {
    server.closeAllConnections?.()
    server.close(() => resolve())
  })
}
