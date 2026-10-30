import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import type { DaemonConfig } from '../src/contracts.js'
import { CAPABILITY_CATALOG_VERSION, decodeEnvelope, encodeInvoke, type Envelope } from '../src/protocol.js'
import { Runtime } from '../src/runtime.js'
import { createSearchCapabilities, searchCapabilities } from '../src/coding/search.js'
import { toPosix } from '../src/coding/support.js'
import type { CapabilityExecutor } from '../src/contracts.js'
import { FakeTransport, fakeProcessSupervisor, serverMessage, testConfig, tick, waitFor } from './helpers.js'
import { installFakeRipgrep, waitForFakePid } from './coding/fake-ripgrep.js'

const ENV = '11111111-2222-4333-8444-555555555555'

const welcomePayload = {
  environmentId: ENV,
  name: 'env',
  maxResourceBytes: 1024 * 1024,
  temporaryResourceTtlSeconds: 60,
  temporaryResourceCleanupIntervalSeconds: 30,
}

const environment = {
  operatingSystem: 'linux' as const,
  architecture: 'amd64' as const,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
}

function sent(transport: FakeTransport): Envelope[] {
  return transport.sent.map((message) => decodeEnvelope(message))
}

interface Setup {
  transport: FakeTransport
  runtime: Runtime
  config: DaemonConfig
}

function buildSearchRuntime(
  capabilities: Record<string, CapabilityExecutor> = searchCapabilities,
): Setup {
  const transport = new FakeTransport()
  const config = testConfig()
  const runtime = new Runtime({
    config,
    transport,
    capabilities,
    processes: fakeProcessSupervisor,
    environment,
    heartbeatIntervalMillis: 10_000,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
  })
  return { transport, runtime, config }
}

async function handshake(setup: Setup): Promise<void> {
  setup.runtime.start()
  await tick()
  setup.transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
  await tick()
  expect(setup.runtime.getState()).toBe('READY')
}

describe('Runtime + Search 真实能力整合生命周期', () => {
  const tempDirs: string[] = []

  afterEach(async () => {
    await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
  })

  async function createTestDir(): Promise<string> {
    const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-runtime-search-')))
    tempDirs.push(dir)
    await writeFile(join(dir, 'a.ts'), 'export const needle = 42\n', 'utf8')
    return dir
  }

  it.each(['fs.find', 'fs.grep'] as const)(
    '%s 正常完成后只发送一次 COMPLETED 与 EXECUTION_EXITED',
    async (capabilityId) => {
      const dir = await createTestDir()
      const setup = buildSearchRuntime()
      const invocationId = `inv-search-${capabilityId.replace('.', '-')}`
      try {
        await handshake(setup)
        const args =
          capabilityId === 'fs.find'
            ? { pattern: '*.ts', path: dir }
            : { pattern: 'needle', path: dir }
        const payload = JSON.parse(
          encodeInvoke({
            capabilityId,
            capabilityVersion: CAPABILITY_CATALOG_VERSION,
            arguments: args,
            timeoutMillis: 10_000,
          }),
        ) as Record<string, unknown>

        setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId, payload }))
        await waitFor(() =>
          sent(setup.transport).some(
            (envelope) => envelope.invocationId === invocationId && envelope.messageType === 'COMPLETED',
          ),
        )
        await waitFor(() =>
          sent(setup.transport).some(
            (envelope) =>
              envelope.invocationId === invocationId && envelope.messageType === 'EXECUTION_EXITED',
          ),
        )

        const completed = sent(setup.transport).filter(
          (envelope) => envelope.invocationId === invocationId && envelope.messageType === 'COMPLETED',
        )
        expect(completed).toHaveLength(1)
        const body = completed[0]!.payload as {
          result: { error: boolean; contents: Array<{ type: string; text?: string }> }
        }
        expect(body.result.error).toBe(false)
        const text = body.result.contents.map((content) => content.text ?? '').join('\n')
        expect(text).toContain(
          capabilityId === 'fs.find' ? toPosix(join(dir, 'a.ts')) : 'export const needle = 42',
        )

        const types = sent(setup.transport)
          .filter((envelope) => envelope.invocationId === invocationId)
          .map((envelope) => envelope.messageType)
        expect(types.indexOf('COMPLETED')).toBeLessThan(types.indexOf('EXECUTION_EXITED'))
        expect(types.filter((messageType) => messageType === 'EXECUTION_EXITED')).toHaveLength(1)
        expect(setup.runtime.getJournal().find(invocationId)?.state).toBe('COMPLETED')
      } finally {
        setup.runtime.close()
      }
    },
  )

  it.each(['fs.find', 'fs.grep'] as const)(
    '收到 CANCEL 报文：%s 终止 rg，保留 partial 并只发送一次 COMPLETED(error=true)',
    async (capabilityId) => {
      const dir = await createTestDir()
      const file = join(dir, 'a.ts')
      const fake = await installFakeRipgrep(dir, {
        target: file,
        matches: 1,
        hang: true,
        rawStdout: '',
      })
      const setup = buildSearchRuntime(createSearchCapabilities(fake.command))
      const invocationId = `inv-search-cancel-${capabilityId.replace('.', '-')}`
      try {
        await handshake(setup)
        const args =
          capabilityId === 'fs.find'
            ? { pattern: '*.ts', path: dir }
            : { pattern: 'needle', path: dir }
        const payload = JSON.parse(
          encodeInvoke({
            capabilityId,
            capabilityVersion: CAPABILITY_CATALOG_VERSION,
            arguments: args,
            timeoutMillis: 10_000,
          }),
        ) as Record<string, unknown>

        setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId, payload }))
        const pid = await waitForFakePid(fake.pidFile)
        // fs.find 先枚举允许路径；假 rg 的 --files 会立即成功返回，随后 grep/pattern 阶段挂起。
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId }))

        await waitFor(() =>
          sent(setup.transport).some(
            (envelope) => envelope.invocationId === invocationId && envelope.messageType === 'COMPLETED',
          ),
        )

        const completed = sent(setup.transport).filter(
          (envelope) => envelope.invocationId === invocationId && envelope.messageType === 'COMPLETED',
        )
        expect(completed).toHaveLength(1)
        expect(
          sent(setup.transport).some(
            (envelope) =>
              envelope.invocationId === invocationId &&
              ['FAILED', 'CANCELLED'].includes(envelope.messageType),
          ),
        ).toBe(false)

        const body = completed[0]!.payload as {
          result: {
            error: boolean
            details: { search?: { outcome?: string; incomplete?: boolean } }
            contents: Array<{ type: string; text?: string }>
          }
        }
        expect(body.result.error).toBe(true)
        expect(body.result.details.search).toMatchObject({ outcome: 'CANCELLED', incomplete: true })
        expect(body.result.contents.map((content) => content.text ?? '').join('\n')).toContain(
          '[Search cancelled; results are incomplete.]',
        )
        expect(setup.runtime.getJournal().find(invocationId)?.state).toBe('COMPLETED')
        expect(() => process.kill(pid, 0)).toThrow()
      } finally {
        setup.runtime.close()
      }
    },
  )
})
