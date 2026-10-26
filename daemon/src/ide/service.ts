import { rm } from 'node:fs/promises'
import { join } from 'node:path'
import type { DaemonConfig, IdeCommand, IdeReply, IdeService, ProcessSupervisor } from '../contracts.js'
import { openStreamExchange, type StreamRequest } from '../transport/stream.js'
import { ensurePrivateDirectory, writePrivateFile } from '../native/index.js'
import { validateIdePath } from './wire.js'
import { createIdeBridge } from './bridge.js'
import { createCodeServerManager, idePaths } from './codeServer.js'
import { IdeError, safeMessage } from './errors.js'
import { canonicalDirectory, hostHome, newViewId, validateViewId, workspaceFileContent } from './workspace.js'

/**
 * Managed code-server IDE service for a single environment.
 *
 * The daemon owns exactly one code-server process and one `.code-workspace` file per
 * front-end view id. Views share the same on-disk directories but keep separate window
 * identity, so restoring one window never affects another.
 */

interface ViewRecord {
  viewId: string
  directory: string
  workspacePath: string
}

const STREAM_PATH_PREFIX = '/api/harness/environment-stream/v1/'
const PROTOCOL_TOKEN = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/

export async function createIdeService(config: DaemonConfig, processes: ProcessSupervisor): Promise<IdeService> {
  const paths = idePaths(config)
  const bridge = await createIdeBridge()
  const manager = createCodeServerManager(config, processes, { url: bridge.url, token: bridge.secret })
  const views = new Map<string, ViewRecord>()

  bridge.onPty(async (_viewId, pid) => {
    const session = manager.session()
    if (!session) {
      throw new IdeError('terminal supervision is unavailable')
    }
    // The IDE PTY host intentionally creates its own session; register the shell so the
    // native supervisor can still reap it with the code-server tree. A failure here must
    // surface rather than silently leaving an unsupervised process.
    await processes.trackDescendant!(session.pid, pid)
  })

  const requireView = (viewId: string | undefined): ViewRecord => {
    if (viewId === undefined) {
      throw new IdeError('viewId is required')
    }
    const view = views.get(validateViewId(viewId))
    if (!view) {
      throw new IdeError('view is not open')
    }
    return view
  }

  // Serializes commands per view so an older write can never clobber a newer directory change.
  const viewLocks = new Map<string, Promise<unknown>>()
  const withViewLock = <T>(viewId: string, task: () => Promise<T>): Promise<T> => {
    const previous = viewLocks.get(viewId) ?? Promise.resolve()
    const next = previous.then(task, task)
    viewLocks.set(
      viewId,
      next.catch(() => undefined),
    )
    return next
  }

  const openView = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const viewId = command.viewId === undefined ? newViewId() : validateViewId(command.viewId)
    return withViewLock(viewId, async () => {
      const existing = views.get(viewId)
      if (existing) {
        // Re-opening a view is idempotent: only an explicit conflicting directory is refused,
        // and an omitted directory must never reset it to the default home.
        if (command.directory !== undefined && (await canonicalDirectory(command.directory)) !== existing.directory) {
          return {
            ok: false,
            viewId,
            directory: existing.directory,
            workspacePath: existing.workspacePath,
            message: 'view is already open with a different directory',
          }
        }
        await manager.ensureStarted(signal)
        return { ok: true, viewId, directory: existing.directory, workspacePath: existing.workspacePath }
      }
      const directory = await canonicalDirectory(command.directory ?? hostHome())
      await ensurePrivateDirectory(paths.viewsDir)
      const workspacePath = join(paths.viewsDir, `${viewId}.code-workspace`)
      await writePrivateFile(workspacePath, workspaceFileContent(directory))
      views.set(viewId, { viewId, directory, workspacePath })
      bridge.assign(viewId, workspacePath)
      await manager.ensureStarted(signal)
      return { ok: true, viewId, directory, workspacePath }
    })
  }

  const changeDirectory = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const view = requireView(command.viewId)
    if (command.directory === undefined) {
      throw new IdeError('directory is required')
    }
    return withViewLock(view.viewId, async () => {
      const directory = await canonicalDirectory(command.directory as string)
      // Success requires the window to actually report the expected directory, either in its
      // reply or on reconnect; a dropped connection is never treated as a successful change.
      const result = await bridge.request(view.viewId, { op: 'changeDirectory', directory }, signal, {
        expectedDirectory: directory,
      })
      if (!result.ok) {
        return {
          ok: false,
          viewId: view.viewId,
          directory: view.directory,
          workspacePath: view.workspacePath,
          message: result.message ?? 'directory change was rejected',
        }
      }
      // The managed workspace file is the durable record; keep it in step with the live window.
      await writePrivateFile(view.workspacePath, workspaceFileContent(directory))
      view.directory = directory
      return { ok: true, viewId: view.viewId, directory, workspacePath: view.workspacePath }
    })
  }

  const focusTerminal = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const view = requireView(command.viewId)
    if (!processes.trackDescendant) {
      throw new IdeError('terminal supervision is unavailable')
    }
    // The window is opened as an iframe and can ask for a terminal before its extension host
    // has connected the bridge. Wait for the connection under the caller's own signal rather
    // than rejecting outright or retrying blindly.
    if (!bridge.isConnected(view.viewId)) {
      await manager.ensureStarted(signal)
      await bridge.waitForConnection(view.viewId, signal)
    }
    const result = await bridge.request(view.viewId, { op: 'focusTerminal' }, signal)
    if (!result.ok) {
      return { ok: false, viewId: view.viewId, message: result.message ?? 'terminal could not be focused' }
    }
    return { ok: true, viewId: view.viewId, directory: view.directory, workspacePath: view.workspacePath }
  }

  const closeView = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    if (command.viewId === undefined) {
      throw new IdeError('viewId is required')
    }
    const viewId = validateViewId(command.viewId)
    return withViewLock(viewId, async () => {
      // A connected window must dispose its terminals and confirm no unsaved work remains
      // before the workspace is torn down; a hidden dirty window must never be closed blindly.
      if (bridge.isConnected(viewId)) {
        const result = await bridge.request(viewId, { op: 'closeView' }, signal)
        if (!result.ok) {
          return { ok: false, viewId, message: result.message ?? 'view could not be closed' }
        }
      }
      const view = views.get(viewId)
      bridge.revoke(viewId)
      views.delete(viewId)
      if (view) {
        await rm(view.workspacePath, { force: true })
      }
      return { ok: true, viewId }
    })
  }

  const openStream = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    if (command.mode !== 'HTTP' && command.mode !== 'WS') {
      throw new IdeError('mode must be HTTP or WS')
    }
    if (command.streamUrl === undefined || command.path === undefined) {
      throw new IdeError('streamUrl and path are required')
    }
    const streamUrl = resolveRelativeStream(config, command.streamUrl, command.ticket)
    let path: string
    try {
      path = validateIdePath(command.path)
    } catch {
      throw new IdeError('invalid request path')
    }
    const headers = validateHeaders(command.headers ?? [])
    const session = await manager.ensureStarted(signal)
    const request: StreamRequest = { mode: command.mode, method: command.method ?? 'GET', path, headers }
    try {
      await openStreamExchange({
        streamUrl,
        request,
        upstream: {
          host: session.host,
          port: session.port,
          cookie: session.cookie,
          protocols: extractProtocols(headers),
        },
        signal,
      })
    } catch (error) {
      // A cancelled exchange is an ordinary end; any other failure leaves one safe line on the host.
      if (!(error instanceof Error && error.name === 'AbortError')) {
        process.stderr.write(`daemon ide ${command.mode.toLowerCase()} stream failed: ${safeMessage(error)}\n`)
      }
      throw error
    }
    return { ok: true }
  }

  return {
    async handle(command, signal) {
      try {
        switch (command.action) {
          case 'OPEN_VIEW':
            return await openView(command, signal)
          case 'CHANGE_DIRECTORY':
            return await changeDirectory(command, signal)
          case 'FOCUS_TERMINAL':
            return await focusTerminal(command, signal)
          case 'CLOSE_VIEW':
            return await closeView(command, signal)
          case 'OPEN_STREAM':
            return await openStream(command, signal)
          default:
            throw new IdeError('unknown ide action')
        }
      } catch (error) {
        return { ok: false, viewId: command.viewId, message: safeMessage(error) }
      }
    },
    async close() {
      // Both resources are released even if one shutdown fails; failures are reported together.
      const errors: unknown[] = []
      try {
        await manager.close()
      } catch (error) {
        errors.push(error)
      }
      try {
        await bridge.close()
      } catch (error) {
        errors.push(error)
      }
      if (errors.length > 0) {
        throw new AggregateError(errors, 'ide service shutdown failed')
      }
    },
  }
}

/** 只接受固定的根相对 stream 端点；网络权威与 TLS 只来自已有的控制连接配置。 */
export function resolveRelativeStream(config: DaemonConfig, streamUrl: string, ticket?: string): string {
  if (!streamUrl.startsWith('/') || streamUrl.startsWith('//') || /[\\\s]/.test(streamUrl)) {
    throw new IdeError('streamUrl is invalid')
  }
  const match = /^\/api\/harness\/environment-stream\/v1\/([A-Za-z0-9_-]+)(\?environment=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$/.exec(streamUrl)
  if (!match) {
    throw new IdeError('streamUrl is not a stream endpoint')
  }
  const streamTicket = match[1]
  if (ticket !== undefined && ticket !== streamTicket) {
    throw new IdeError('streamUrl ticket does not match')
  }
  const gateway = new URL(config.gatewayUri)
  const endpoint = '/api/harness/environment-daemon/v1'
  if (
    !['ws:', 'wss:'].includes(gateway.protocol) || !gateway.pathname.endsWith(endpoint)
    || gateway.username || gateway.password || gateway.search || gateway.hash
  ) {
    throw new IdeError('gatewayUri is invalid')
  }
  const contextPath = gateway.pathname.slice(0, -endpoint.length)
  gateway.pathname = `${contextPath}${STREAM_PATH_PREFIX}${streamTicket}`
  gateway.search = match[2] ?? ''
  return gateway.toString()
}

function validateHeaders(headers: [string, string][]): [string, string][] {
  if (!Array.isArray(headers) || headers.length > 128) {
    throw new IdeError('invalid request headers')
  }
  return headers.map((entry) => {
    if (!Array.isArray(entry) || entry.length !== 2) {
      throw new IdeError('invalid request headers')
    }
    const [name, value] = entry
    if (typeof name !== 'string' || typeof value !== 'string' || name.length === 0 || value.length > 8192) {
      throw new IdeError('invalid request headers')
    }
    if (/[\u0000-\u001f\u007f]/.test(name) || /[\u0000-\u001f\u007f]/.test(value)) {
      throw new IdeError('invalid request headers')
    }
    return [name, value]
  })
}

function extractProtocols(headers: readonly [string, string][]): string[] {
  const raw = headers.find(([name]) => name.toLowerCase() === 'sec-websocket-protocol')?.[1]
  if (raw === undefined) {
    return []
  }
  return raw
    .split(',')
    .map((token) => token.trim())
    .filter((token) => token.length > 0 && PROTOCOL_TOKEN.test(token))
}
