import { afterEach, describe, expect, it } from 'vitest'
import { existsSync } from 'node:fs'
import { cp, mkdir, mkdtemp, readFile, rm, symlink } from 'node:fs/promises'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { WebSocketServer, type WebSocket } from 'ws'
import type { DaemonConfig, ManagedProcess, ProcessSpec, ProcessSupervisor } from '../../src/contracts.js'
import { createProcessSupervisor } from '../../src/native/index.js'
import { createIdeService } from '../../src/ide/service.js'
import { decodeBinaryFrame, decodeTextFrame, encodeWsFrame } from '../../src/ide/wire.js'
import { awaitGone, isAlive } from '../native/process-test-util.js'

/**
 * Opt-in acceptance test that drives the real, frozen code-server through the actual native
 * POSIX process supervisor (compiled helper), not a plain process-group wrapper.
 *
 * Set KK_STUDIO_IDE_ACCEPTANCE_DIR to the extracted code-server 4.141.0 distribution (the
 * directory that contains `lib/node` and `out/node/entry.js`). This exercises the real
 * manager/bridge/wire stack, two UUID views with private `.code-workspace` files, real HTTP
 * and WebSocket data-plane exchanges against the bundled Node CLI, and proves the supervisor
 * converges the whole code-server scope on shutdown (no orphans). The model/tool plane is
 * never touched; the browser-driven two-window flow lives in the IDE acceptance suite.
 */

const ACCEPTANCE_DIR = process.env.KK_STUDIO_IDE_ACCEPTANCE_DIR
const VIEW_ONE = '11111111-1111-4111-8111-111111111111'
const VIEW_TWO = '22222222-2222-4222-8222-222222222222'

const cleans: (() => Promise<void> | void)[] = []
afterEach(async () => {
  for (const clean of cleans.splice(0).reverse()) {
    await clean()
  }
})

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

async function waitFor(predicate: () => boolean, timeoutMillis = 60_000): Promise<void> {
  const deadline = Date.now() + timeoutMillis
  while (!predicate()) {
    if (Date.now() > deadline) {
      throw new Error('condition not met before timeout')
    }
    await delay(25)
  }
}

interface ObservedSupervisor {
  supervisor: ProcessSupervisor
  /** Every real supervisor start pid, so shutdown convergence can be asserted on real processes. */
  pids: number[]
}

/** Wraps the real native supervisor only to observe pids; every call is delegated unchanged. */
function createObservedSupervisor(): ObservedSupervisor {
  const real = createProcessSupervisor()
  const pids: number[] = []
  const supervisor: ProcessSupervisor = {
    async start(spec: ProcessSpec): Promise<ManagedProcess> {
      const managed = await real.start(spec)
      pids.push(managed.pid)
      return managed
    },
    async trackDescendant(rootPid: number, childPid: number): Promise<void> {
      if (!real.trackDescendant) {
        throw new Error('the native supervisor does not support descendant tracking')
      }
      await real.trackDescendant(rootPid, childPid)
    },
    close: () => real.close(),
  }
  return { supervisor, pids }
}

interface Captured {
  status?: number
  headers: [string, string][]
  body: string
  ended: boolean
  errors: string[]
  wsMessages: string[]
}

async function createDistribution(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'kk-ide-native-'))
  cleans.push(() => rm(root, { recursive: true, force: true }))
  await mkdir(join(root, 'runtime'), { recursive: true })
  await symlink(ACCEPTANCE_DIR as string, join(root, 'runtime', 'code-server'), 'dir')
  const extensionSource = fileURLToPath(new URL('../../ide-extension', import.meta.url))
  await cp(extensionSource, join(root, 'ide-extension'), { recursive: true })
  return root
}

function makeConfig(distributionDir: string, gatewayHost: string): DaemonConfig {
  return {
    configPath: join(distributionDir, 'daemon.json'),
    dataDir: join(distributionDir, 'data'),
    tokenPath: join(distributionDir, 'daemon.token'),
    studioUrl: `http://${gatewayHost}`,
    gatewayUri: `ws://${gatewayHost}/api/harness/environment-daemon/v1`,
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir,
  }
}

const signal = (): AbortSignal => new AbortController().signal

interface Gateway {
  host: string
  endpoint: string
  captured: Captured
  sockets: WebSocket[]
  close(): Promise<void>
}

async function createGateway(): Promise<Gateway> {
  const server = createServer()
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const port = (server.address() as AddressInfo).port
  const wss = new WebSocketServer({ server })
  const captured: Captured = { headers: [], body: '', ended: false, errors: [], wsMessages: [] }
  const sockets: WebSocket[] = []
  wss.on('connection', (socket) => {
    sockets.push(socket)
    socket.on('error', (error) => captured.errors.push(error.message))
    socket.on('message', (data, isBinary) => {
      if (!isBinary) {
        const frame = decodeTextFrame(data.toString('utf8'))
        if (frame.type === 'HTTP_RESPONSE') {
          captured.status = frame.status
          captured.headers = frame.headers
        } else if (frame.type === 'ERROR') {
          captured.errors.push(frame.message)
        }
        return
      }
      const frame = decodeBinaryFrame(data as Buffer)
      if (frame.kind === 'http-body') {
        captured.body += frame.payload.toString('utf8')
      } else if (frame.kind === 'http-end') {
        captured.ended = true
      } else if (frame.kind === 'ws-text' || frame.kind === 'ws-binary') {
        captured.wsMessages.push(frame.payload.toString('utf8'))
      }
    })
  })
  const host = `127.0.0.1:${port}`
  const gateway: Gateway = {
    host,
    endpoint: `ws://${host}/api/harness/environment-stream/v1/ticket-native`,
    captured,
    sockets,
    async close() {
      for (const client of wss.clients) {
        client.terminate()
      }
      await new Promise<void>((resolve) => wss.close(() => server.close(() => resolve())))
    },
  }
  cleans.push(() => gateway.close())
  return gateway
}

function reset(captured: Captured): void {
  captured.status = undefined
  captured.headers = []
  captured.body = ''
  captured.ended = false
  captured.errors = []
  captured.wsMessages = []
}

describe.skipIf(!ACCEPTANCE_DIR)('real code-server over the native supervisor', () => {
  it(
    'runs two private views through the real data plane and converges the native scope on close',
    async () => {
      const distribution = await createDistribution()
      const observed = createObservedSupervisor()
      cleans.push(() => observed.supervisor.close())
      const gateway = await createGateway()

      const service = await createIdeService(makeConfig(distribution, gateway.host), observed.supervisor)
      cleans.push(() => service.close())

      // Two UUID views with separate managed workspace identities over the same, real on-disk dirs.
      const dirOne = join(distribution, 'workspace-one')
      const dirTwo = join(distribution, 'workspace-two')
      await mkdir(dirOne, { recursive: true })
      await mkdir(dirTwo, { recursive: true })

      const one = await service.handle({ action: 'OPEN_VIEW', viewId: VIEW_ONE, directory: dirOne }, signal())
      expect(one.ok).toBe(true)
      expect(one.workspacePath).toBe(join(distribution, 'data', 'ide', 'views', `${VIEW_ONE}.code-workspace`))
      const two = await service.handle({ action: 'OPEN_VIEW', viewId: VIEW_TWO, directory: dirTwo }, signal())
      expect(two.ok).toBe(true)
      expect(two.workspacePath).not.toBe(one.workspacePath)

      // Managed workspace files point at the real, canonical folders and never share identity.
      expect(JSON.parse(await readFile(one.workspacePath as string, 'utf8'))).toEqual({ folders: [{ path: dirOne }] })
      expect(JSON.parse(await readFile(two.workspacePath as string, 'utf8'))).toEqual({ folders: [{ path: dirTwo }] })

      // Exactly one code-server process backs both views, launched through the native scope.
      const extensionManifest = join(
        distribution,
        'data',
        'ide',
        'code-server',
        'extensions',
        'kk-studio-ide-bridge',
        'package.json',
      )
      await waitFor(() => existsSync(extensionManifest))
      expect(JSON.parse(await readFile(extensionManifest, 'utf8')).name).toBe('kk-studio-ide-bridge')
      expect(observed.pids.length).toBe(1)
      expect(isAlive(observed.pids[0] as number)).toBe(true)

      // Real health probe proxied through the reverse data plane.
      const healthy = await service.handle(
        { action: 'OPEN_STREAM', viewId: VIEW_ONE, streamUrl: gateway.endpoint, ticket: 'ticket-native', mode: 'HTTP', method: 'GET', path: '/healthz', headers: [] },
        signal(),
      )
      expect(healthy.ok).toBe(true)
      await waitFor(() => gateway.captured.ended)
      expect(gateway.captured.status).toBe(200)
      expect(gateway.captured.body).toContain('"status":"alive"')

      // The authenticated workbench HTML is served from the real process through the same plane.
      reset(gateway.captured)
      const workbench = await service.handle(
        { action: 'OPEN_STREAM', viewId: VIEW_TWO, streamUrl: gateway.endpoint, ticket: 'ticket-native', mode: 'HTTP', method: 'GET', path: '/', headers: [] },
        signal(),
      )
      expect(workbench.ok).toBe(true)
      await waitFor(() => gateway.captured.ended)
      expect(gateway.captured.status).toBe(200)
      expect(gateway.captured.body.toLowerCase()).toContain('<html')

      // A real WebSocket upgrade is proxied and answered by code-server's health route.
      reset(gateway.captured)
      const beforeSockets = gateway.sockets.length
      const wsStream = service.handle(
        { action: 'OPEN_STREAM', viewId: VIEW_ONE, streamUrl: gateway.endpoint, ticket: 'ticket-native', mode: 'WS', method: 'GET', path: '/healthz', headers: [] },
        signal(),
      )
      await waitFor(() => gateway.sockets.length > beforeSockets)
      const wsSocket = gateway.sockets[gateway.sockets.length - 1] as WebSocket
      wsSocket.send(encodeWsFrame('text', Buffer.from('ping'), true))
      await waitFor(() => gateway.captured.wsMessages.length > 0)
      expect(gateway.captured.wsMessages.join('')).toContain('"event":"health"')
      wsSocket.close()
      expect((await wsStream).ok).toBe(true)

      // Closing one view removes only its managed workspace; the other view remains open.
      const closed = await service.handle({ action: 'CLOSE_VIEW', viewId: VIEW_ONE }, signal())
      expect(closed.ok).toBe(true)
      await waitFor(() => !existsSync(one.workspacePath as string))
      expect(existsSync(two.workspacePath as string)).toBe(true)

      // Shutdown converges the entire native scope: no code-server helper survives.
      const codeServerPid = observed.pids[0] as number
      await service.close()
      await observed.supervisor.close()
      expect(await awaitGone(codeServerPid, 30_000)).toBe(true)
    },
    180_000,
  )
})
