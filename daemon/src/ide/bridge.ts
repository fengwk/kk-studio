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

export interface BridgeRequestOptions {
  /** The directory the window must actually report for a changeDirectory to count as applied. */
  expectedDirectory?: string
}

export interface BridgeResult {
  ok: boolean
  code?: string
  message?: string
  /** For a confirmed directory change, the directory the window actually reports. */
  directory?: string
}

export interface IdeBridge {
  readonly url: string
  readonly secret: string
  assign(viewId: string, workspacePath: string): void
  revoke(viewId: string): void
  isConnected(viewId: string): boolean
  /** Resolves once a window stream for the view is registered; rejects when aborted or closed. */
  waitForConnection(viewId: string, signal: AbortSignal): Promise<void>
  onPty(listener: (viewId: string, pid: number) => void | Promise<void>): void
  request(
    viewId: string,
    command: BridgeCommand,
    signal: AbortSignal,
    options?: BridgeRequestOptions,
  ): Promise<BridgeResult>
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
  expectedDirectory?: string
  resolve: (result: BridgeResult) => void
  timer: NodeJS.Timeout
}

interface ConnectionWaiter {
  resolve: () => void
  reject: (error: unknown) => void
}

/** Aborts with the standard AbortError name so callers can distinguish cancellation. */
function abortError(): Error {
  const error = new Error('ide request was cancelled')
  error.name = 'AbortError'
  return error
}

export async function createIdeBridge(): Promise<IdeBridge> {
  const secret = randomBytes(32).toString('base64url')
  const views = new Map<string, ViewState>()
  const byKey = new Map<string, string>()
  const pending = new Map<string, Pending>()
  const ptyListeners = new Set<(viewId: string, pid: number) => void | Promise<void>>()
  const connectionWaiters = new Map<string, Set<ConnectionWaiter>>()

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

  /** Confirms a pending directory change once a (re)connected window reports the new folder. */
  const settleReconnect = (viewId: string, folders: string[]): void => {
    for (const [id, entry] of pending) {
      if (entry.viewId === viewId && entry.op === 'changeDirectory' && entry.expectedDirectory !== undefined) {
        resolvePending(
          id,
          folders.includes(entry.expectedDirectory)
            ? { ok: true, directory: entry.expectedDirectory }
            : { ok: false, message: 'directory change was not applied' },
        )
      }
    }
  }

  /** Wakes every caller waiting for the view's window stream to register. */
  const notifyConnected = (viewId: string): void => {
    const waiters = connectionWaiters.get(viewId)
    if (!waiters) {
      return
    }
    connectionWaiters.delete(viewId)
    for (const waiter of waiters) {
      waiter.resolve()
    }
  }

  const handleRequest = async (request: IncomingMessage, response: ServerResponse): Promise<void> => {
    try {
      if (!isLoopback(request.socket.remoteAddress)) {
        respond(response, 403, 'forbidden')
        return
      }
      const url = new URL(request.url ?? '/', 'http://127.0.0.1')
      // The private bridge secret travels in the Authorization header, never the URL, so it
      // cannot leak into request logs or history.
      const header = request.headers.authorization
      const token = typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : undefined
      if (token === undefined || !tokenMatches(token, secret)) {
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
    // Waking readiness waiters must happen before reconnect settlement so a caller that was
    // waiting to send a command proceeds with the freshly bound stream.
    notifyConnected(viewId)
    // The reconnecting window reports its live folders; only an actual match confirms a
    // pending directory change. A disconnect is never treated as success.
    settleReconnect(viewId, url.searchParams.getAll('folder'))
    response.on('close', () => {
      if (view.stream === response) {
        view.stream = undefined
      }
      for (const [id, entry] of pending) {
        if (entry.viewId === viewId && entry.op !== 'changeDirectory') {
          resolvePending(id, { ok: false, message: 'ide window closed' })
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
    const entry = pending.get(id)
    const actualDirectory = typeof result.directory === 'string' ? result.directory : undefined
    // A directory change only succeeds when the window reports the exact expected directory.
    if (
      entry?.op === 'changeDirectory' &&
      entry.expectedDirectory !== undefined &&
      result.ok === true &&
      actualDirectory !== entry.expectedDirectory
    ) {
      resolvePending(id, { ok: false, message: 'directory change was not applied' })
      respond(response, 204)
      return
    }
    resolvePending(id, {
      ok: result.ok === true,
      code: typeof result.code === 'string' ? result.code : undefined,
      message: typeof result.message === 'string' ? result.message : undefined,
      // Carry the confirmed directory through so callers see what the window reported.
      directory: result.ok === true ? actualDirectory : undefined,
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
    waitForConnection(viewId, signal) {
      if (views.get(viewId)?.stream) {
        return Promise.resolve()
      }
      if (signal.aborted) {
        return Promise.reject(abortError())
      }
      return new Promise<void>((resolve, reject) => {
        const waiter: ConnectionWaiter = {
          resolve: () => {
            signal.removeEventListener('abort', onAbort)
            resolve()
          },
          reject: (error) => {
            signal.removeEventListener('abort', onAbort)
            reject(error)
          },
        }
        const onAbort = (): void => {
          const waiters = connectionWaiters.get(viewId)
          waiters?.delete(waiter)
          if (waiters && waiters.size === 0) {
            connectionWaiters.delete(viewId)
          }
          reject(abortError())
        }
        const waiters = connectionWaiters.get(viewId) ?? new Set<ConnectionWaiter>()
        waiters.add(waiter)
        connectionWaiters.set(viewId, waiters)
        signal.addEventListener('abort', onAbort, { once: true })
      })
    },
    request(viewId, command, signal, options) {
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
          expectedDirectory: options?.expectedDirectory,
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
      for (const waiters of connectionWaiters.values()) {
        for (const waiter of waiters) {
          waiter.reject(new IdeError('ide bridge closed'))
        }
      }
      connectionWaiters.clear()
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
