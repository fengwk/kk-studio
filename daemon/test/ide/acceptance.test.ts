import { afterEach, describe, expect, it } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { cp, mkdtemp, mkdir, readFile, rm, symlink } from 'node:fs/promises'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { WebSocketServer, type WebSocket } from 'ws'
import type { DaemonConfig, ManagedProcess, ProcessExit, ProcessSpec, ProcessSupervisor } from '../../src/contracts.js'
import { createIdeService } from '../../src/ide/service.js'
import { decodeBinaryFrame, decodeTextFrame, encodeWsFrame } from '../../src/ide/wire.js'

/**
 * Opt-in acceptance test against a real, frozen code-server distribution.
 *
 * Set KK_STUDIO_IDE_ACCEPTANCE_DIR to the extracted distribution directory (the one that
 * contains `lib/node` and `out/node/entry.js`) to run it. It launches the bundled Node CLI
 * on loopback ports, proxies real HTTP responses through a fake control-server stream and
 * verifies the managed bridge extension is installed. The browser-driven window flow is
 * covered separately.
 */

const ACCEPTANCE_DIR = process.env.KK_STUDIO_IDE_ACCEPTANCE_DIR

const cleanups: (() => Promise<void> | void)[] = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
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

/** Runs the real bundled Node CLI in its own process group so the whole tree can be reaped. */
class RealSupervisor implements ProcessSupervisor {
  readonly children = new Set<ChildProcess>()

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    const child = spawn(spec.executable, spec.args, {
      cwd: spec.cwd,
      env: spec.env,
      stdio: ['pipe', 'pipe', 'pipe'],
      detached: true,
    })
    this.children.add(child)
    const exit = new Promise<ProcessExit>((resolve) => {
      child.on('exit', (code, signal) => resolve({ exitCode: code, signal }))
    })
    return {
      pid: child.pid as number,
      stdin: child.stdin as ManagedProcess['stdin'],
      stdout: child.stdout as ManagedProcess['stdout'],
      stderr: child.stderr as ManagedProcess['stderr'],
      exit,
      terminate: async () => {
        killGroup(child)
        await exit
      },
    }
  }

  async trackDescendant(): Promise<void> {}

  async close(): Promise<void> {
    for (const child of this.children) {
      killGroup(child)
    }
  }
}

function killGroup(child: ChildProcess): void {
  if (child.pid === undefined) {
    return
  }
  try {
    process.kill(-child.pid, 'SIGKILL')
  } catch {
    try {
      child.kill('SIGKILL')
    } catch {
      // already gone
    }
  }
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
  const root = await mkdtemp(join(tmpdir(), 'kk-ide-acceptance-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
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
    studioUrl: 'http://127.0.0.1:9999',
    gatewayUri: `ws://${gatewayHost}/api/harness/environment-daemon/v1`,
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir,
  }
}

const signal = (): AbortSignal => new AbortController().signal

describe.skipIf(!ACCEPTANCE_DIR)('real code-server acceptance', () => {
  it(
    'starts the bundled Node CLI, installs the bridge extension and proxies real HTTP responses',
    async () => {
      const distribution = await createDistribution()
      const supervisor = new RealSupervisor()
      cleanups.push(() => supervisor.close())

      const gatewayServer = createServer()
      await new Promise<void>((resolve) => gatewayServer.listen(0, '127.0.0.1', resolve))
      const gatewayPort = (gatewayServer.address() as AddressInfo).port
      const wss = new WebSocketServer({ server: gatewayServer })
      const captured: Captured = { headers: [], body: '', ended: false, errors: [], wsMessages: [] }
      const gatewaySockets: WebSocket[] = []
      wss.on('connection', (socket) => {
        gatewaySockets.push(socket)
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
      cleanups.push(
        () =>
          new Promise<void>((resolve) => {
            for (const client of wss.clients) {
              client.terminate()
            }
            wss.close(() => gatewayServer.close(() => resolve()))
          }),
      )

      const service = await createIdeService(makeConfig(distribution, `127.0.0.1:${gatewayPort}`), supervisor)
      cleanups.push(() => service.close())

      const project = join(distribution, 'project')
      await mkdir(project, { recursive: true })
      const opened = await service.handle({ action: 'OPEN_VIEW', directory: project }, signal())
      expect(opened.ok).toBe(true)

      // The managed bridge extension is installed into the private extensions directory.
      const extensionManifest = join(
        distribution,
        'data',
        'ide',
        'code-server',
        'extensions',
        'kk-studio-ide-bridge',
        'package.json',
      )
      expect(JSON.parse(await readFile(extensionManifest, 'utf8')).name).toBe('kk-studio-ide-bridge')

      const endpoint = `ws://127.0.0.1:${gatewayPort}/api/harness/environment-stream/v1/ticket-real`

      // A real health probe is proxied back through the stream.
      const healthy = await service.handle(
        { action: 'OPEN_STREAM', streamUrl: endpoint, ticket: 'ticket-real', mode: 'HTTP', method: 'GET', path: '/healthz', headers: [] },
        signal(),
      )
      expect(healthy.ok).toBe(true)
      await waitFor(() => captured.ended)
      expect(captured.status).toBe(200)
      // code-server exposes a heartbeat health payload.
      expect(captured.body).toContain('"status":"alive"')
      expect(captured.body).toContain('lastHeartbeat')

      // The authenticated workbench HTML is served from the real process.
      captured.status = undefined
      captured.headers = []
      captured.body = ''
      captured.ended = false
      const workbench = await service.handle(
        { action: 'OPEN_STREAM', streamUrl: endpoint, ticket: 'ticket-real', mode: 'HTTP', method: 'GET', path: '/', headers: [] },
        signal(),
      )
      expect(workbench.ok).toBe(true)
      await waitFor(() => captured.ended)
      expect(captured.status).toBe(200)
      const contentType = captured.headers.find(([name]) => name.toLowerCase() === 'content-type')?.[1] ?? ''
      expect(contentType).toContain('text/html')
      expect(captured.body.toLowerCase()).toContain('<html')

      // A real WebSocket upgrade is proxied through the stream: code-server's health route answers
      // every message with a JSON heartbeat, proving the reverse data plane end to end.
      const before = gatewaySockets.length
      const wsStream = service.handle(
        {
          action: 'OPEN_STREAM',
          streamUrl: endpoint,
          ticket: 'ticket-real',
          mode: 'WS',
          method: 'GET',
          path: '/healthz',
          headers: [],
        },
        signal(),
      )
      await waitFor(() => gatewaySockets.length > before)
      const wsGateway = gatewaySockets[gatewaySockets.length - 1] as WebSocket
      wsGateway.send(encodeWsFrame('text', Buffer.from('ping'), true))
      await waitFor(() => captured.wsMessages.length > 0)
      expect(captured.wsMessages.join('')).toContain('"event":"health"')
      wsGateway.close()
      expect((await wsStream).ok).toBe(true)
    },
    120_000,
  )
})
