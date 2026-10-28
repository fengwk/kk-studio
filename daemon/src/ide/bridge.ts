import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto'
import type { AddressInfo } from 'node:net'
import { isAbsolute, resolve as resolvePath } from 'node:path'
import { IdeError } from './errors.js'

/**
 * Loopback bridge between the daemon and the in-window VS Code bridge extension.
 *
 * The extension runs inside the code-server extension host, one instance per managed
 * workspace window. It authenticates with a random per-service secret delivered through
 * the code-server process environment (never to the browser) and reports its exact
 * assigned workspace file so the daemon can bind the window to a view id.
 */

export interface BridgeDocument {
  path: string
  text: string
}

export interface BridgeCommand {
  op: 'changeDirectory' | 'focusTerminal' | 'inspect' | 'closeView' | 'snapshot' | 'restore'
  directory?: string
  discard?: boolean
  maxBytes?: number
  documents?: BridgeDocument[]
}

export interface BridgeRequestOptions {
  /** The directory the window must actually report for a changeDirectory to count as applied. */
  expectedDirectory?: string
  /** The daemon binding the command was issued for; a newer epoch discards the late result. */
  expectedEpoch?: string
}

export interface BridgeResult {
  ok: boolean
  code?: string
  message?: string
  /** For a confirmed directory change, the directory the window actually reports. */
  directory?: string
  /** Explicit clean-state observation; absent means the bridge cannot prove cleanliness. */
  clean?: boolean
  documents?: BridgeDocument[]
  bytes?: number
  omitted?: number
  complete?: boolean
  restored?: number
  skipped?: number
}

export interface IdeBridge {
  readonly url: string
  readonly secret: string
  /** The workspace file and current single canonical directory are both required for admission. */
  assign(viewId: string, workspacePath: string, bindingEpoch: string, directory: string): void
  /** Revokes only the expected epoch when supplied; an old close cannot revoke a newer binding. */
  revoke(viewId: string, expectedEpoch?: string): void
  isConnected(viewId: string, bindingEpoch?: string): boolean
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

const MAX_BODY_BYTES = 8 * 1024 * 1024
const MAX_SNAPSHOT_DOCUMENTS = 256
const MAX_SNAPSHOT_PATH_CHARS = 4096
const MAX_COMMAND_DOCUMENTS = 256
const MAX_VIEW_HOSTS = 256
const HOST_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
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
  bindingEpoch: string
  directory: string
  hosts: Set<string>
  hostId?: string
  stream?: ServerResponse
}

interface Pending {
  viewId: string
  bindingEpoch: string
  op: BridgeCommand['op']
  stream: ServerResponse
  hostId: string
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

/** A managed workspace has one confirmed folder, not merely a shared workspace file. */
function matchesDirectory(folders: string[], directory: string): boolean {
  return folders.length === 1 && isAbsolute(folders[0]!) && pathKey(folders[0]!) === pathKey(directory)
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

  const retireHost = (view: ViewState): void => {
    view.hostId = undefined
    const stream = view.stream
    view.stream = undefined
    if (stream !== undefined) {
      stream.write(`${JSON.stringify({ type: 'retired' })}\n`)
      stream.end()
    }
  }

  const resolvePending = (id: string, result: BridgeResult): void => {
    const entry = pending.get(id)
    if (!entry) {
      return
    }
    pending.delete(id)
    clearTimeout(entry.timer)
    const current = views.get(entry.viewId)
    if (current === undefined || current.bindingEpoch !== entry.bindingEpoch) {
      entry.resolve({ ok: false, code: 'VIEW_GONE', message: 'view binding changed' })
      return
    }
    if (result.ok && entry.op === 'changeDirectory' && entry.expectedDirectory !== undefined) {
      // Fence old-folder reconnects before the awaiting service resumes.
      current.directory = entry.expectedDirectory
    } else if (!result.ok && entry.op === 'changeDirectory' && result.code !== 'DIRTY' && current.stream === entry.stream) {
      // After an unconfirmed transition, the old registration is no longer evidence of its
      // live folders. A fresh registration must prove the still-assigned directory.
      retireHost(current)
    }
    entry.resolve(result)
  }

  const settleView = (viewId: string, result: BridgeResult): void => {
    for (const [id, entry] of pending) {
      if (entry.viewId === viewId) {
        resolvePending(id, result)
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

  /** A binding change invalidates waiters; waiting through a newer epoch would misroute commands. */
  const rejectWaiters = (viewId: string, error: unknown): void => {
    const waiters = connectionWaiters.get(viewId)
    if (!waiters) {
      return
    }
    connectionWaiters.delete(viewId)
    for (const waiter of waiters) {
      waiter.reject(error)
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
    const hostIds = url.searchParams.getAll('hostId')
    const hostId = hostIds[0]
    if (hostIds.length !== 1 || hostId === undefined || !HOST_ID_PATTERN.test(hostId)) {
      respond(response, 400, 'hostId must be a canonical lowercase UUID')
      return
    }
    if (hostId !== view.hostId && view.hosts.has(hostId)) {
      respond(response, 410, 'workspace host is retired')
      return
    }
    if (!view.hosts.has(hostId) && view.hosts.size >= MAX_VIEW_HOSTS) {
      respond(response, 503, 'workspace host limit reached')
      return
    }
    const ownTransition = [...pending.entries()].find(
      ([, entry]) => entry.viewId === viewId && entry.op === 'changeDirectory' && entry.hostId === hostId,
    )
    if (ownTransition !== undefined) {
      // Reconnecting an initiating host cannot prove whether its first-folder update applied.
      resolvePending(ownTransition[0], { ok: false, message: 'directory change was not applied' })
      respond(response, 410, 'workspace host is retired')
      return
    }
    const folders = url.searchParams.getAll('folder')
    const target = [...pending.entries()].find(
      ([, entry]) =>
        entry.viewId === viewId &&
        entry.bindingEpoch === view.bindingEpoch &&
        entry.op === 'changeDirectory' &&
        entry.hostId !== hostId &&
        entry.expectedDirectory !== undefined &&
        matchesDirectory(folders, entry.expectedDirectory),
    )
    if (!matchesDirectory(folders, view.directory) && target === undefined) {
      // This workspace file also exists in retired extension hosts. They must not reconnect.
      respond(response, 410, 'workspace folder is retired')
      return
    }
    const previous = view.stream
    const previousHost = view.hostId
    // Commit incarnation history before writing the registration acknowledgement.
    view.hosts.add(hostId)
    view.hostId = hostId
    view.stream = response
    // Fail requests sent to displaced registrations, except the change confirmed by this
    // target registration. Late results and old close callbacks cannot affect the new stream.
    for (const [id, entry] of pending) {
      if (entry.viewId === viewId && entry.stream !== response && id !== target?.[0]) {
        resolvePending(id, {
          ok: false,
          message: entry.op === 'changeDirectory' ? 'directory change was not applied' : 'ide window closed',
        })
      }
    }
    if (target !== undefined) {
      resolvePending(target[0], { ok: true, directory: target[1].expectedDirectory })
    }
    if (previous !== undefined) {
      if (previousHost !== hostId) {
        previous.write(`${JSON.stringify({ type: 'retired' })}\n`)
      }
      previous.end()
    }
    response.writeHead(200, {
      'content-type': 'application/x-ndjson',
      'cache-control': 'no-store',
      connection: 'keep-alive',
    })
    response.write(`${JSON.stringify({ type: 'registered', viewId })}\n`)
    notifyConnected(viewId)
    response.on('close', () => {
      if (view.stream === response) {
        view.stream = undefined
        if ([...pending.values()].some((entry) => entry.stream === response && entry.op === 'changeDirectory')) {
          // A disconnected initiating host cannot reclaim the view during a folder transition.
          view.hostId = undefined
        }
      }
      for (const [id, entry] of pending) {
        if (entry.viewId === viewId && entry.stream === response && entry.op !== 'changeDirectory') {
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
    if (entry !== undefined && views.get(entry.viewId)?.stream !== entry.stream) {
      // A result belongs to the registration on which its command was sent.
      respond(response, 204)
      return
    }
    const actualDirectory = typeof result.directory === 'string' ? result.directory : undefined
    // A directory change only succeeds when the window reports the exact expected directory.
    if (
      entry?.op === 'changeDirectory' &&
      entry.expectedDirectory !== undefined &&
      result.ok === true &&
      (actualDirectory === undefined ||
        !matchesDirectory([actualDirectory], entry.expectedDirectory) ||
        !matchesDirectory([actualDirectory], views.get(entry.viewId)!.directory))
    ) {
      resolvePending(id, { ok: false, message: 'directory change was not applied' })
      respond(response, 204)
      return
    }
    let documents: BridgeDocument[] | undefined
    if ('documents' in result) {
      if (!Array.isArray(result.documents) || result.documents.length > MAX_SNAPSHOT_DOCUMENTS) {
        respond(response, 400, 'invalid snapshot documents')
        return
      }
      documents = []
      let bytes = 0
      for (const document of result.documents) {
        if (
          typeof document !== 'object' ||
          document === null ||
          typeof (document as BridgeDocument).path !== 'string' ||
          typeof (document as BridgeDocument).text !== 'string'
        ) {
          respond(response, 400, 'invalid snapshot document')
          return
        }
        const snapshot = document as BridgeDocument
        if (
          !isAbsolute(snapshot.path) ||
          snapshot.path.length > MAX_SNAPSHOT_PATH_CHARS ||
          /[\u0000-\u001f\u007f]/.test(snapshot.path)
        ) {
          respond(response, 400, 'invalid snapshot path')
          return
        }
        bytes += Buffer.byteLength(snapshot.text, 'utf8')
        if (bytes > MAX_BODY_BYTES) {
          respond(response, 413, 'snapshot exceeds bridge limit')
          return
        }
        documents.push({ path: snapshot.path, text: snapshot.text })
      }
    }
    resolvePending(id, {
      ok: result.ok === true,
      code: typeof result.code === 'string' ? result.code : undefined,
      message: typeof result.message === 'string' ? result.message : undefined,
      // Carry the confirmed directory through so callers see what the window reported.
      directory: result.ok === true ? actualDirectory : undefined,
      ...(typeof result.clean === 'boolean' ? { clean: result.clean } : {}),
      ...(documents === undefined ? {} : { documents }),
      ...(typeof result.bytes === 'number' && Number.isSafeInteger(result.bytes) && result.bytes >= 0
        ? { bytes: result.bytes }
        : {}),
      ...(typeof result.omitted === 'number' && Number.isSafeInteger(result.omitted) && result.omitted >= 0
        ? { omitted: result.omitted }
        : {}),
      ...(typeof result.complete === 'boolean' ? { complete: result.complete } : {}),
      ...(typeof result.restored === 'number' && Number.isSafeInteger(result.restored) && result.restored >= 0
        ? { restored: result.restored }
        : {}),
      ...(typeof result.skipped === 'number' && Number.isSafeInteger(result.skipped) && result.skipped >= 0
        ? { skipped: result.skipped }
        : {}),
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
    assign(viewId, workspacePath, bindingEpoch, directory) {
      const key = pathKey(workspacePath)
      const existing = views.get(viewId)
      if (existing) {
        // A new assignment invalidates the previous channel and its pending commands.
        retireHost(existing)
        rejectWaiters(viewId, new IdeError('view binding changed', 'VIEW_GONE'))
      }
      if (existing && existing.key !== key) {
        byKey.delete(existing.key)
      }
      views.set(viewId, { workspacePath, key, bindingEpoch, directory, hosts: existing?.hosts ?? new Set() })
      byKey.set(key, viewId)
      if (existing !== undefined) {
        settleView(viewId, { ok: false, code: 'VIEW_GONE', message: 'view binding changed' })
      }
    },
    revoke(viewId, expectedEpoch) {
      const view = views.get(viewId)
      if (view !== undefined && expectedEpoch !== undefined && view.bindingEpoch !== expectedEpoch) {
        return
      }
      settleView(viewId, { ok: false, code: 'VIEW_GONE', message: 'ide window was closed' })
      rejectWaiters(viewId, new IdeError('ide window was closed', 'VIEW_GONE'))
      if (view) {
        view.stream?.end()
        byKey.delete(view.key)
        views.delete(viewId)
      }
    },
    onPty(listener) {
      ptyListeners.add(listener)
    },
    isConnected(viewId, bindingEpoch) {
      const view = views.get(viewId)
      return view?.stream !== undefined && (bindingEpoch === undefined || view.bindingEpoch === bindingEpoch)
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
      if (!stream || view?.hostId === undefined) {
        return Promise.reject(new IdeError('ide window is not connected'))
      }
      if (options?.expectedEpoch !== undefined && view.bindingEpoch !== options.expectedEpoch) {
        return Promise.reject(new IdeError('view binding changed', 'VIEW_GONE'))
      }
      if (signal.aborted) {
        return Promise.resolve({ ok: false, message: 'ide request was cancelled' })
      }
      if (command.documents !== undefined) {
        if (command.documents.length > MAX_COMMAND_DOCUMENTS) {
          return Promise.reject(new IdeError('too many recovery documents'))
        }
      }
      const expectedDirectory = options?.expectedDirectory
      if (
        command.op === 'changeDirectory' &&
        (command.directory === undefined ||
          expectedDirectory === undefined ||
          !isAbsolute(command.directory) ||
          !isAbsolute(expectedDirectory) ||
          pathKey(command.directory) !== pathKey(expectedDirectory))
      ) {
        return Promise.reject(new IdeError('expected directory is invalid'))
      }
      if (
        command.op === 'changeDirectory' &&
        [...pending.values()].some((entry) => entry.viewId === viewId && entry.op === 'changeDirectory')
      ) {
        return Promise.reject(new IdeError('a directory change is already pending', 'BUSY'))
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
          bindingEpoch: view.bindingEpoch,
          op: command.op,
          stream,
          hostId: view.hostId!,
          expectedDirectory,
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
