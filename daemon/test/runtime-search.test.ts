import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { create } from '@bufbuild/protobuf'

import type { DaemonConfig } from '../src/contracts.js'
import {
  Architecture,
  CancelSchema,
  EnvelopeSchema,
  EnvironmentConfigSchema,
  DaemonConfigurationSchema,
  InvokeSchema,
  MessageType,
  OperatingSystem,
  ResourceBudgetsSchema,
  SettingsSchema,
  type Completed,
  type Envelope,
} from '../src/generated/control_pb.js'
import { Runtime } from '../src/runtime.js'
import { createSearchCapabilities, searchCapabilities } from '../src/coding/search.js'
import { toPosix } from '../src/coding/support.js'
import type { CapabilityExecutor } from '../src/contracts.js'
import { FakeTransport, fakeProcessSupervisor, testConfig, tick, waitFor } from './helpers.js'
import { installFakeRipgrep, waitForFakePid } from './coding/fake-ripgrep.js'

const ENV = '11111111-2222-4333-8444-555555555555'

const environment = {
  operatingSystem: OperatingSystem.LINUX,
  architecture: Architecture.AMD64,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  tempDirectory: '/tmp',
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
  await waitFor(() => setup.transport.connection !== null)
  await tick()
  setup.transport.receive(
    create(EnvelopeSchema, {
      messageType: MessageType.SETTINGS,
      environmentId: ENV,
      payload: {
        case: 'settings',
        value: create(SettingsSchema, {
          resourceBudgets: create(ResourceBudgetsSchema, {
            maxResourceBytes: 1048576n,
            temporaryResourceTtlSeconds: 60n,
            temporaryResourceCleanupIntervalSeconds: 30n,
          }),
        }),
      },
    }),
  )
  setup.transport.receive(
    create(EnvelopeSchema, {
      messageType: MessageType.ENVIRONMENT_CONFIG,
      environmentId: ENV,
      payload: {
        case: 'environmentConfig',
        value: create(EnvironmentConfigSchema, {
          version: 0n,
          config: create(DaemonConfigurationSchema, { studioUrl: 'https://studio.example.com' }),
        }),
      },
    }),
  )
  await waitFor(() => setup.runtime.getState() === 'READY')
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

        setup.transport.receive(
          create(EnvelopeSchema, {
            messageType: MessageType.INVOKE,
            environmentId: ENV,
            invocationId,
            payload: {
              case: 'invoke',
              value: create(InvokeSchema, {
                capabilityId,
                argumentsJson: JSON.stringify(args),
                timeoutMillis: 10_000n,
              }),
            },
          }),
        )

        await waitFor(() =>
          setup.transport.sent.some(
            (envelope) => envelope.invocationId === invocationId && envelope.messageType === MessageType.COMPLETED,
          ),
        )
        await waitFor(() =>
          setup.transport.sent.some(
            (envelope) =>
              envelope.invocationId === invocationId && envelope.messageType === MessageType.EXECUTION_EXITED,
          ),
        )

        const completed = setup.transport.sent.filter(
          (envelope) => envelope.invocationId === invocationId && envelope.messageType === MessageType.COMPLETED,
        )
        expect(completed).toHaveLength(1)
        const payload = completed[0]!.payload.value as Completed
        expect(payload.result?.error).toBe(false)
        const contents = payload.result?.contents ?? []
        const text = contents.map((content) => content.body.value as { text?: string }).map((t) => t.text ?? '').join('\n')
        expect(text).toContain(
          capabilityId === 'fs.find' ? toPosix(join(dir, 'a.ts')) : 'export const needle = 42',
        )

        const types = setup.transport.sent
          .filter((envelope) => envelope.invocationId === invocationId)
          .map((envelope) => envelope.messageType)
        expect(types.indexOf(MessageType.COMPLETED)).toBeLessThan(types.indexOf(MessageType.EXECUTION_EXITED))
        expect(types.filter((messageType) => messageType === MessageType.EXECUTION_EXITED)).toHaveLength(1)
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

        setup.transport.receive(
          create(EnvelopeSchema, {
            messageType: MessageType.INVOKE,
            environmentId: ENV,
            invocationId,
            payload: {
              case: 'invoke',
              value: create(InvokeSchema, {
                capabilityId,
                argumentsJson: JSON.stringify(args),
                timeoutMillis: 10_000n,
              }),
            },
          }),
        )
        const pid = await waitForFakePid(fake.pidFile)
        // fs.find 先枚举允许路径；假 rg 的 --files 会立即成功返回，随后 grep/pattern 阶段挂起。
        setup.transport.receive(
          create(EnvelopeSchema, {
            messageType: MessageType.CANCEL,
            environmentId: ENV,
            invocationId,
            payload: { case: 'cancel', value: create(CancelSchema, {}) },
          }),
        )

        await waitFor(() =>
          setup.transport.sent.some(
            (envelope) => envelope.invocationId === invocationId && envelope.messageType === MessageType.COMPLETED,
          ),
        )

        const completed = setup.transport.sent.filter(
          (envelope) => envelope.invocationId === invocationId && envelope.messageType === MessageType.COMPLETED,
        )
        expect(completed).toHaveLength(1)
        expect(
          setup.transport.sent.some(
            (envelope) =>
              envelope.invocationId === invocationId &&
              [MessageType.FAILED, MessageType.CANCELLED].includes(envelope.messageType),
          ),
        ).toBe(false)

        const payload = completed[0]!.payload.value as Completed
        expect(payload.result?.error).toBe(true)
        const details = JSON.parse(payload.result?.detailsJson ?? '{}') as { search?: { outcome?: string; incomplete?: boolean } }
        expect(details.search).toMatchObject({ outcome: 'CANCELLED', incomplete: true })
        const contents = payload.result?.contents ?? []
        const text = contents.map((content) => content.body.value as { text?: string }).map((t) => t.text ?? '').join('\n')
        expect(text).toContain('[Search cancelled; results are incomplete.]')
        expect(setup.runtime.getJournal().find(invocationId)?.state).toBe('COMPLETED')
        expect(() => process.kill(pid, 0)).toThrow()
      } finally {
        setup.runtime.close()
      }
    },
  )
})
