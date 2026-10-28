import { afterEach, describe, expect, it } from 'vitest'
import { existsSync } from 'node:fs'
import { cp, mkdtemp, mkdir, readFile, rm, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { DaemonConfig, ManagedProcess, ProcessSpec, ProcessSupervisor } from '../../src/contracts.js'
import { createProcessSupervisor } from '../../src/native/index.js'
import { createIdeService } from '../../src/ide/service.js'
import { exchangeUpstreamWebSocket, requestUpstream } from './acceptance-helpers.js'
import { awaitGone, isAlive } from '../native/process-test-util.js'

/**
 * Opt-in acceptance test that drives two frozen private views through the actual native POSIX
 * supervisor (compiled helper), not a plain process-group wrapper.
 *
 * Set KK_STUDIO_IDE_ACCEPTANCE_DIR to the extracted code-server 4.141.0 distribution. This
 * exercises the real manager/bridge stack, real HTTP and WebSocket exchanges (via
 * `resolveView`), per-view close and native scope convergence on shutdown. The mux transport
 * and browser flow are covered by their own slices.
 */

const ACCEPTANCE_DIR = process.env.KK_STUDIO_IDE_ACCEPTANCE_DIR
const VIEW_ONE = '11111111-1111-4111-8111-111111111111'
const VIEW_TWO = '22222222-2222-4222-8222-222222222222'
const DOCUMENT_ONE = '33333333-4444-4555-8666-777777777777'
const DOCUMENT_TWO = '44444444-5555-4666-8777-888888888888'

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

async function createDistribution(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'kk-ide-native-'))
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

describe.skipIf(!ACCEPTANCE_DIR)('real code-server over the native supervisor', () => {
  it(
    'runs two private views and converges the native scope on close',
    async () => {
      const distribution = await createDistribution()
      const observed = createObservedSupervisor()
      cleanups.push(() => observed.supervisor.close())
      const service = await createIdeService(makeConfig(distribution), observed.supervisor)
      cleanups.push(() => service.close())

      const dirOne = join(distribution, 'workspace-one')
      const dirTwo = join(distribution, 'workspace-two')
      await mkdir(dirOne, { recursive: true })
      await mkdir(dirTwo, { recursive: true })

      const claimOne = '55555555-6666-4777-8888-999999999999'
      const claimTwo = '66666666-7777-4888-8999-aaaaaaaaaaaa'
      const one = await service.handle(
        {
          action: 'OPEN_VIEW',
          viewId: VIEW_ONE,
          documentId: DOCUMENT_ONE,
          eventNodeId: '77777777-8888-4999-8aaa-bbbbbbbbbbbb',
          connectionId: '88888888-9999-4aaa-8bbb-cccccccccccc',
          claimId: claimOne,
          mode: 'create',
          directory: dirOne,
        },
        signal(),
      )
      expect(one.ok).toBe(true)
      const two = await service.handle(
        {
          action: 'OPEN_VIEW',
          viewId: VIEW_TWO,
          documentId: DOCUMENT_TWO,
          eventNodeId: '99999999-aaaa-4bbb-8ccc-dddddddddddd',
          connectionId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
          claimId: claimTwo,
          mode: 'create',
          directory: dirTwo,
        },
        signal(),
      )
      expect(two.ok).toBe(true)
      expect(two.workspacePath).not.toBe(one.workspacePath)

      expect(JSON.parse(await readFile(one.workspacePath as string, 'utf8'))).toEqual({
        folders: [{ path: dirOne }],
      })
      expect(JSON.parse(await readFile(two.workspacePath as string, 'utf8'))).toEqual({
        folders: [{ path: dirTwo }],
      })

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

      const health = await requestUpstream(
        await service.resolveView(VIEW_ONE, one.bindingEpoch as string, {
          mode: 'HTTP',
          method: 'GET',
          path: '/healthz',
          headers: [],
        }),
      )
      expect(health.status).toBe(200)
      expect(health.body).toContain('"status":"alive"')

      const workbench = await requestUpstream(
        await service.resolveView(VIEW_TWO, two.bindingEpoch as string, {
          mode: 'HTTP',
          method: 'GET',
          path: '/',
          headers: [],
        }),
      )
      expect(workbench.status).toBe(200)
      expect(workbench.body.toLowerCase()).toContain('<html')

      const message = await exchangeUpstreamWebSocket(
        await service.resolveView(VIEW_ONE, one.bindingEpoch as string, {
          mode: 'WS',
          method: 'GET',
          path: '/healthz',
          headers: [],
        }),
        'ping',
      )
      expect(message).toContain('"event":"health"')

      const closed = await service.handle(
        {
          action: 'CLOSE_VIEW',
          viewId: VIEW_ONE,
          documentId: DOCUMENT_ONE,
          bindingEpoch: one.bindingEpoch as string,
          discard: true,
        },
        signal(),
      )
      expect(closed.ok).toBe(true)
      await waitFor(() => !existsSync(one.workspacePath as string))
      expect(existsSync(two.workspacePath as string)).toBe(true)

      const codeServerPid = observed.pids[0] as number
      await service.close()
      await observed.supervisor.close()
      expect(await awaitGone(codeServerPid, 30_000)).toBe(true)
    },
    180_000,
  )
})
