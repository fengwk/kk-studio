import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

import type { DaemonConfig } from '../src/contracts.js'
import { acquireFileLock, type DirectoryLock } from '../src/native/index.js'
import { encodeUpdateCommand, PROTOCOL_VERSION, encodeEnvelope } from '../src/protocol.js'
import { Runtime, type UpdatePort } from '../src/runtime.js'
import {
  HANDOFF_FILE,
  MANAGED_ENTRY,
  PERMIT_FILE,
  RUNTIME_MANIFEST,
  TAKEOVER_FILE,
  UPDATE_LOCK_FILE,
  createUpdateHandler,
  type UpdateDependencies,
} from '../src/update.js'
import { FakeTransport, serverMessage, tick, waitFor } from './helpers.js'
import { artifactFixture } from './support/update-fixture.js'

/**
 * 受管更新的真实入口串联：**wire 命令 → Runtime 编排 → 生产更新处理器**（只注入网络、归档解包与子进程边界）。
 *
 * 这条路径专门覆盖「协议入口接受、更新器却必然拒绝」的错配：入口解出的 artifactUrl 必须正是更新器要下载的官方
 * 五平台归档，且许可只在 PREPARED 回执入缓存并完成一次出站尝试之后才释放。
 */

const ENVIRONMENT_ID = '11111111-2222-4333-8444-555555555555'
const OPERATION_ID = artifactFixture.operationId
const TARGET_VERSION = artifactFixture.targetVersion
const ARTIFACT_URL = artifactFixture.accepted[0]!.artifactUrl
// 占位 SHA256 是空字节串的摘要：注入的假归档同样为空，于是校验走真实摘要比对而不是被绕过。
const ARCHIVE = Buffer.alloc(0)

const environment = {
  operatingSystem: 'linux' as const,
  architecture: 'amd64' as const,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
}

const welcomePayload = {
  environmentId: ENVIRONMENT_ID,
  name: 'env',
  maxResourceBytes: 1024 * 1024,
  temporaryResourceTtlSeconds: 60,
  temporaryResourceCleanupIntervalSeconds: 30,
}

const cleans: Array<() => Promise<void>> = []
afterEach(async () => {
  for (const clean of cleans.splice(0).reverse()) {
    await clean()
  }
})

interface EntryHarness {
  runtime: Runtime
  transport: FakeTransport
  config: DaemonConfig
  downloaded: string[]
  /** 每条出站 UPDATE_RESULT 到达时许可文件是否已存在。 */
  permitAtReceipt: boolean[]
  permitPath: string
}

async function startEntry(): Promise<EntryHarness> {
  const dataDir = await mkdtemp(path.join(tmpdir(), 'kk-update-entry-'))
  cleans.push(() => rm(dataDir, { recursive: true, force: true }))
  await mkdir(path.join(dataDir, 'lib'), { recursive: true })
  await writeFile(path.join(dataDir, 'lib', MANAGED_ENTRY), '// installed entry')
  await writeFile(path.join(dataDir, 'daemon.json'), '{}')
  // 注册凭证以属主私有文件提供：HELLO 只在真实读得到 token 时才发出。
  await writeFile(path.join(dataDir, 'daemon.token'), 'test-registration-token', { mode: 0o600 })
  const config: DaemonConfig = {
    configPath: path.join(dataDir, 'daemon.json'),
    dataDir,
    tokenPath: path.join(dataDir, 'daemon.token'),
    studioUrl: 'https://studio.example.com',
    gatewayUri: 'wss://studio.example.com/api/harness/environment-daemon/v1',
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir: path.join(dataDir, 'lib'),
  }

  const downloaded: string[] = []
  const deps: UpdateDependencies = {
    releaseOs: 'linux',
    releaseArch: 'amd64',
    id: () => OPERATION_ID,
    async fetchTo(url, destination) {
      downloaded.push(url)
      if (url.endsWith('.sha256')) {
        await writeFile(destination, `${artifactFixture.artifactSha256}  ${path.basename(ARTIFACT_URL)}\n`, { mode: 0o600 })
        return
      }
      await writeFile(destination, ARCHIVE, { mode: 0o600 })
    },
    async extractArchive(_archive, destination) {
      await mkdir(path.join(destination, 'runtime', 'code-server', 'lib'), { recursive: true })
      await mkdir(path.join(destination, 'runtime', 'code-server', 'out', 'node'), { recursive: true })
      await writeFile(path.join(destination, MANAGED_ENTRY), '// staged entry')
      await writeFile(path.join(destination, 'runtime', 'code-server', 'lib', 'node'), '')
      await writeFile(path.join(destination, 'runtime', 'code-server', 'out', 'node', 'entry.js'), '')
      await mkdir(path.join(destination, 'dist'), { recursive: true })
      await writeFile(path.join(destination, 'dist', 'update-entry.js'), '// updater entry')
      await mkdir(path.join(destination, 'node_modules', 'koffi'), { recursive: true })
      await writeFile(path.join(destination, 'node_modules', 'koffi', 'package.json'), '{}')
      const platformPackage = path.join(destination, 'node_modules', '@koromix', 'koffi-linux-x64')
      await mkdir(platformPackage, { recursive: true })
      await writeFile(path.join(platformPackage, 'package.json'), '{}')
      await writeFile(
        path.join(destination, RUNTIME_MANIFEST),
        JSON.stringify({
          schemaVersion: 1,
          daemonVersion: TARGET_VERSION,
          codeServerVersion: '4.141.0-kk.1',
          codeServer: {
            'linux-amd64': {
              url: 'https://github.com/fengwk/code-server/releases/download/v4.141.0-kk.1/code-server-4.141.0-kk.1-linux-amd64.tar.gz',
              sha256: '16d9f302fae3fe3670e6478a020da36021031ceb055d8198807ddf4b03fe0fc6',
            },
          },
        }),
      )
    },
    async run(_command, args) {
      if (args.includes('--check-config')) {
        return { code: 0, stdout: 'Daemon configuration is valid\n', stderr: '' }
      }
      return { code: 0, stdout: '', stderr: '' }
    },
  }
  const update: UpdatePort = {
    handleUpdate: (command, cfg, signal) => createUpdateHandler(deps)(command, cfg, signal),
  }

  const transport = new FakeTransport()
  const permitPath = path.join(dataDir, 'updates', OPERATION_ID, PERMIT_FILE)
  const handoffPath = path.join(dataDir, 'updates', OPERATION_ID, HANDOFF_FILE)
  const takeoverPath = path.join(dataDir, 'updates', OPERATION_ID, TAKEOVER_FILE)
  const installLockPath = path.join(dataDir, 'updates', UPDATE_LOCK_FILE)
  const permitAtReceipt: boolean[] = []
  let updaterLock: DirectoryLock | null = null
  cleans.push(async () => {
    await updaterLock?.close()
  })
  const originalConnect = transport.connect.bind(transport)
  transport.connect = async (listener) => {
    const connection = await originalConnect(listener)
    const originalSend = connection.sendText.bind(connection)
    // 同步判定：sendText 在同一同步块内先于许可释放被调用，因此这里读取的就是「回执到达时」的真实状态。
    // PREPARED 出站时模拟已接管互斥的更新器，确保真实的 releasePermit 只有在 takeover + kernel lock 后释放。
    connection.sendText = async (message: string): Promise<void> => {
      const envelope = JSON.parse(message) as { messageType?: string; payload?: { phase?: string } }
      if (envelope.messageType === 'UPDATE_RESULT') {
        permitAtReceipt.push(existsSync(permitPath))
        if (envelope.payload?.phase === 'PREPARED') {
          const handoff = JSON.parse(await readFile(handoffPath, 'utf8')) as { startupToken: string }
          await writeFile(
            takeoverPath,
            `${JSON.stringify({
              operationId: OPERATION_ID,
              targetVersion: TARGET_VERSION,
              token: handoff.startupToken,
              pid: process.pid,
            })}\n`,
            { mode: 0o600 },
          )
          updaterLock = await acquireFileLock(installLockPath)
        }
      }
      return originalSend(message)
    }
    return connection
  }

  const runtime = new Runtime({
    config,
    transport,
    capabilities: {},
    processes: {
      async start() {
        throw new Error('not implemented')
      },
      async close() {},
    },
    environment,
    update,
    heartbeatIntervalMillis: 10_000,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
  })
  runtime.start()
  await tick()
  transport.receive(serverMessage('WELCOME', { environmentId: ENVIRONMENT_ID, payload: welcomePayload }))
  await tick()
  expect(runtime.getState()).toBe('READY')
  return { runtime, transport, config, downloaded, permitAtReceipt, permitPath }
}

describe('managed update real entry', () => {
  it('downloads the official platform archive for the wire command and releases the permit after the receipt', async () => {
    const harness = await startEntry()
    const wire = encodeUpdateCommand({
      operationId: OPERATION_ID,
      targetVersion: TARGET_VERSION,
      artifactUrl: ARTIFACT_URL,
      artifactSha256: artifactFixture.artifactSha256,
    })
    // 平台真实装配：命令先在 wire 上编码，再作为 UPDATE 载荷进入 Daemon。
    harness.transport.receive(
      encodeEnvelope({
        protocolVersion: PROTOCOL_VERSION,
        messageType: 'UPDATE',
        environmentId: ENVIRONMENT_ID,
        invocationId: null,
        payload: JSON.parse(wire) as Record<string, unknown>,
      }),
    )
    await waitFor(() =>
      harness.transport.sent.some(message => {
        const payload = JSON.parse(message) as { messageType: string; payload: { phase?: string } }
        return payload.messageType === 'UPDATE_RESULT' && payload.payload.phase === 'PREPARED'
      }),
    )

    // 更新器只接受协议入口解出的同一份事实：官方 tar.gz 与它的校验文件。
    expect(harness.downloaded).toEqual([ARTIFACT_URL, `${ARTIFACT_URL}.sha256`])
    expect(createHash('sha256').update(ARCHIVE).digest('hex')).toBe(artifactFixture.artifactSha256)

    const phases = harness.transport.sent
      .map(message => JSON.parse(message) as { messageType: string; payload: { phase?: string } })
      .filter(envelope => envelope.messageType === 'UPDATE_RESULT')
      .map(envelope => envelope.payload.phase)
    expect(phases).toEqual(['ACCEPTED', 'PREPARED'])
    // 许可必须在 PREPARED 回执出站之后才释放，且此时确实已释放。
    expect(harness.permitAtReceipt).toEqual([false, false])
    await waitFor(() => existsSync(harness.permitPath))
    expect((await stat(harness.permitPath)).isFile()).toBe(true)

    harness.runtime.close()
    await harness.runtime.awaitCompletion()
    expect(harness.runtime.getShutdownFailures()).toEqual([])
  })
})
