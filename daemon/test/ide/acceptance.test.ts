import { afterEach, describe, expect, it } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { cp, mkdtemp, mkdir, readFile, rm, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { DaemonConfig, ManagedProcess, ProcessExit, ProcessSpec, ProcessSupervisor } from '../../src/contracts.js'
import { createIdeService } from '../../src/ide/service.js'
import { exchangeUpstreamWebSocket, requestUpstream } from './acceptance-helpers.js'

/**
 * Opt-in acceptance test against a real, frozen code-server distribution.
 *
 * Set KK_STUDIO_IDE_ACCEPTANCE_DIR to the extracted distribution directory (the one that
 * contains `lib/node` and `out/node/entry.js`) to run it. It launches the bundled Node CLI
 * on loopback ports, registers a managed view through the fork's private management API,
 * installs the bundled bridge extension and fetches real HTTP/WS responses through
 * `resolveView`. The mux data-plane adapter and browser flow live in their own slices.
 */

const ACCEPTANCE_DIR = process.env.KK_STUDIO_IDE_ACCEPTANCE_DIR
const VIEW_ID = '11111111-1111-4111-8111-111111111111'
const DOCUMENT_ID = '22222222-2222-4222-8222-222222222222'

const cleanups: (() => Promise<void> | void)[] = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

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

async function createDistribution(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'kk-ide-acceptance-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  await mkdir(join(root, 'runtime'), { recursive: true })
  await symlink(ACCEPTANCE_DIR as string, join(root, 'runtime', 'code-server'), 'dir')
  const extensionSource = fileURLToPath(new URL('../../ide-extension', import.meta.url))
  await cp(extensionSource, join(root, 'ide-extension'), { recursive: true })
  return root
}

function makeConfig(distributionDir: string): DaemonConfig {
  return {
    configPath: join(distributionDir, 'daemon.json'),
    dataDir: join(distributionDir, 'data'),
    tokenPath: join(distributionDir, 'daemon.token'),
    studioUrl: 'http://127.0.0.1:9999',
    gatewayUri: 'ws://127.0.0.1:9999/api/harness/environment-daemon/v1',
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir,
  }
}

const signal = (): AbortSignal => new AbortController().signal

describe.skipIf(!ACCEPTANCE_DIR)('real code-server acceptance', () => {
  it(
    'starts the bundled Node CLI, installs the bridge extension and serves real HTTP/WS responses',
    async () => {
      const distribution = await createDistribution()
      const supervisor = new RealSupervisor()
      cleanups.push(() => supervisor.close())
      const service = await createIdeService(makeConfig(distribution), supervisor)
      cleanups.push(() => service.close())

      const project = join(distribution, 'project')
      await mkdir(project, { recursive: true })
      const opened = await service.handle(
        {
          action: 'OPEN_VIEW',
          viewId: VIEW_ID,
          documentId: DOCUMENT_ID,
          eventNodeId: '33333333-4444-4555-8666-777777777777',
          connectionId: '44444444-5555-4666-8777-888888888888',
          claimId: '55555555-6666-4777-8888-999999999999',
          mode: 'create',
          directory: project,
        },
        signal(),
      )
      expect(opened.ok).toBe(true)
      const epoch = opened.bindingEpoch as string

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

      // A real health probe is fetched through the private resolved upstream.
      const health = await requestUpstream(
        await service.resolveView(VIEW_ID, epoch, {
          mode: 'HTTP',
          method: 'GET',
          path: '/healthz',
          headers: [],
        }),
      )
      expect(health.status).toBe(200)
      expect(health.body).toContain('"status":"alive"')
      expect(health.body).toContain('lastHeartbeat')

      // The authenticated workbench HTML is served from the real process.
      const workbench = await requestUpstream(
        await service.resolveView(VIEW_ID, epoch, { mode: 'HTTP', method: 'GET', path: '/', headers: [] }),
      )
      expect(workbench.status).toBe(200)
      const contentType = workbench.headers['content-type'] ?? ''
      expect(Array.isArray(contentType) ? contentType.join(',') : contentType).toContain('text/html')
      expect(workbench.body.toLowerCase()).toContain('<html')

      // A real WebSocket upgrade is answered by code-server's health route.
      const message = await exchangeUpstreamWebSocket(
        await service.resolveView(VIEW_ID, epoch, {
          mode: 'WS',
          method: 'GET',
          path: '/healthz',
          headers: [],
        }),
        'ping',
      )
      expect(message).toContain('"event":"health"')

      const closed = await service.handle(
        { action: 'CLOSE_VIEW', viewId: VIEW_ID, documentId: DOCUMENT_ID, bindingEpoch: epoch, discard: true },
        signal(),
      )
      expect(closed.ok).toBe(true)
    },
    120_000,
  )
})
