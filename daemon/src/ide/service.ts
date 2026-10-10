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

  const openView = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const viewId = command.viewId === undefined ? newViewId() : validateViewId(command.viewId)
    const directory = await canonicalDirectory(command.directory ?? hostHome())
    await ensurePrivateDirectory(paths.viewsDir)
    const workspacePath = join(paths.viewsDir, `${viewId}.code-workspace`)
    await writePrivateFile(workspacePath, workspaceFileContent(directory))
    views.set(viewId, { viewId, directory, workspacePath })
    bridge.assign(viewId, workspacePath)
    await manager.ensureStarted(signal)
    return { ok: true, viewId, directory, workspacePath }
  }

  const changeDirectory = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const view = requireView(command.viewId)
    if (command.directory === undefined) {
      throw new IdeError('directory is required')
    }
    const directory = await canonicalDirectory(command.directory)
    const result = await bridge.request(view.viewId, { op: 'changeDirectory', directory }, signal)
    if (!result.ok) {
      return {
        ok: false,
        viewId: view.viewId,
        directory: view.directory,
        workspacePath: view.workspacePath,
        message: result.message ?? 'directory change was rejected',
      }
    }
    // The managed workspace file is the durable source of truth. The extension switches
    // the live window through the public API; a folder change may restart its host, which
    // the bridge reports as a delivered command rather than a failure.
    await writePrivateFile(view.workspacePath, workspaceFileContent(directory))
    view.directory = directory
    return { ok: true, viewId: view.viewId, directory, workspacePath: view.workspacePath }
  }

  const focusTerminal = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const view = requireView(command.viewId)
    if (!processes.trackDescendant) {
      throw new IdeError('terminal supervision is unavailable')
    }
    const result = await bridge.request(view.viewId, { op: 'focusTerminal' }, signal)
    if (!result.ok) {
      return { ok: false, viewId: view.viewId, message: result.message ?? 'terminal could not be focused' }
    }
    return { ok: true, viewId: view.viewId, directory: view.directory, workspacePath: view.workspacePath }
  }

  const closeView = async (command: IdeCommand): Promise<IdeReply> => {
    if (command.viewId === undefined) {
      throw new IdeError('viewId is required')
    }
    const viewId = validateViewId(command.viewId)
    const view = views.get(viewId)
    bridge.revoke(viewId)
    views.delete(viewId)
    if (view) {
      await rm(view.workspacePath, { force: true })
    }
    return { ok: true, viewId }
  }

  const openStream = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    if (command.mode !== 'HTTP' && command.mode !== 'WS') {
      throw new IdeError('mode must be HTTP or WS')
    }
    if (command.streamUrl === undefined || command.path === undefined) {
      throw new IdeError('streamUrl and path are required')
    }
    const streamUrl = resolveStreamUrl(config, command.streamUrl, command.ticket)
    let path: string
    try {
      path = validateIdePath(command.path)
    } catch {
      throw new IdeError('invalid request path')
    }
    const headers = validateHeaders(command.headers ?? [])
    const session = await manager.ensureStarted(signal)
    const request: StreamRequest = { mode: command.mode, method: command.method ?? 'GET', path, headers }
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
            return await closeView(command)
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
      await manager.close()
      await bridge.close()
    },
  }
}

function resolveStreamUrl(config: DaemonConfig, streamUrl: string, ticket: string | undefined): string {
  let url: URL
  try {
    url = new URL(streamUrl)
  } catch {
    throw new IdeError('streamUrl is invalid')
  }
  // The endpoint is issued by the authenticated control server and may point at a load
  // balancer or the current owner node, so its host need not match the control gateway.
  // The transport security must still match the gateway so the URL can never downgrade it.
  const gatewayProtocol = new URL(config.gatewayUri).protocol
  const secureGateway = gatewayProtocol === 'https:' || gatewayProtocol === 'wss:'
  if (secureGateway ? url.protocol !== 'wss:' : url.protocol !== 'ws:' && url.protocol !== 'wss:') {
    throw new IdeError('streamUrl must use a secure websocket endpoint')
  }
  // It must be a clean, credential-free stream path and never carry a registration token.
  if (url.username.length > 0 || url.password.length > 0) {
    throw new IdeError('streamUrl must not contain credentials')
  }
  if (!url.pathname.startsWith(STREAM_PATH_PREFIX) || url.pathname.includes('..') || url.hash.length > 0) {
    throw new IdeError('streamUrl is not a stream endpoint')
  }
  if (ticket !== undefined && ticket.length > 0 && !url.pathname.endsWith(`/${ticket}`)) {
    url.pathname = `${url.pathname.replace(/\/$/, '')}/${encodeURIComponent(ticket)}`
  }
  return url.toString()
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
