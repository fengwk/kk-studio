import { describe, expect, it, vi } from 'vitest'
import { mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PassThrough } from 'node:stream'

import type {
  CapabilityExecutor,
  CapabilityResult,
  DaemonConfig,
  DataBindHandler,
  IdeLeaseHandler,
  IdeService,
  ManagedProcess,
  ProcessExit,
  ProcessSupervisor,
  ResourceUploader,
} from '../src/contracts.js'
import { DAEMON_VERSION } from '../src/config.js'
import { PROTOCOL_VERSION } from '../src/generated/protocol-contract.js'
import {
  CAPABILITY_CATALOG_VERSION,
  READY_CAPABILITIES_VERSION,
  decodeCapabilities,
  decodeEnvelope,
  decodeHeartbeat,
  decodeRuntimeStatus,
  encodeInvoke,
  type Envelope,
} from '../src/protocol.js'
import { Runtime, type TemporaryResourceSweeper, type UpdatePort, type UpdateProgressReporter } from '../src/runtime.js'
import { fileCapabilities } from '../src/coding/files.js'
import { processCapabilities } from '../src/coding/process.js'
import { artifactFixture } from './support/update-fixture.js'
import {
  FakeTransport,
  fakeProcessSupervisor,
  serverMessage,
  testConfig,
  tick,
  waitFor,
} from './helpers.js'

const ENV = '11111111-2222-4333-8444-555555555555'
const OTHER_ENV = '99999999-2222-4333-8444-555555555555'

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
  environmentId: ENV,
  name: 'env',
  maxResourceBytes: 1024 * 1024,
  temporaryResourceTtlSeconds: 60,
  temporaryResourceCleanupIntervalSeconds: 30,
}

function invokePayload(capabilityId = 'fs.read', timeoutMillis = 0, args: Record<string, unknown> = {}): Record<string, unknown> {
  return JSON.parse(encodeInvoke({ capabilityId, capabilityVersion: '3', arguments: args, timeoutMillis })) as Record<string, unknown>
}

function sent(transport: FakeTransport): Envelope[] {
  return transport.sent.map((message) => decodeEnvelope(message))
}

function textResult(callId: string, text = 'ok'): CapabilityResult {
  return { callId, error: false, details: {}, contents: [{ type: 'text', text }] }
}

const openViewPayload = {
  action: 'OPEN_VIEW',
  viewId: ENV,
  documentId: '22222222-3333-4444-8555-666666666666',
  eventNodeId: '33333333-4444-4555-8666-777777777777',
  connectionId: '44444444-5555-4666-8777-888888888888',
  claimId: '55555555-6666-4777-8888-999999999999',
  mode: 'create',
}

/** Complete IDE test double; lifecycle-focused tests override only the port they exercise. */
function ideService(overrides: Partial<IdeService> = {}): IdeService {
  const base: IdeService = {
    async handle() {
      return { ok: true }
    },
    canStartUpdate: () => true,
    async stopForUpdate() {},
    getViewState: () => null,
    activeStreamCount: () => 0,
    async resolveView() {
      throw new Error('view resolution is not used by this test')
    },
    async restoreBackup() {
      return { restored: 0, skipped: 0 }
    },
    hasActiveViews: () => false,
    apply() {},
    async close() {},
  }
  return { ...base, ...overrides }
}

interface Setup {
  transport: FakeTransport
  runtime: Runtime
  config: DaemonConfig
}

function buildRuntime(options: {
  capabilities?: Record<string, CapabilityExecutor>
  processes?: ProcessSupervisor
  resources?: ResourceUploader
  update?: UpdatePort
  ide?: IdeService
  dataBind?: DataBindHandler
  ideLease?: IdeLeaseHandler
  temporaryResources?: TemporaryResourceSweeper
  heartbeatIntervalMillis?: number
  maxConcurrentInvocations?: number
  ideCommandTimeoutMillis?: number
  monotonicNow?: () => number
  daemonVersion?: string
} = {}): Setup {
  const transport = new FakeTransport()
  const config = testConfig()
  const runtime = new Runtime({
    config,
    transport,
    capabilities: options.capabilities ?? { 'fs.read': async (call) => textResult(call.invocationId) },
    processes: options.processes ?? fakeProcessSupervisor,
    environment,
    resources: options.resources,
    update: options.update,
    ide: options.ide,
    dataBind: options.dataBind,
    ideLease: options.ideLease,
    temporaryResources: options.temporaryResources,
    heartbeatIntervalMillis: options.heartbeatIntervalMillis ?? 10_000,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
    maxConcurrentInvocations: options.maxConcurrentInvocations,
    ideCommandTimeoutMillis: options.ideCommandTimeoutMillis,
    monotonicNow: options.monotonicNow,
    daemonVersion: options.daemonVersion,
  })
  return { transport, runtime, config }
}

async function handshake(setup: Setup): Promise<void> {
  setup.runtime.start()
  await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'HELLO'))
  setup.transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
  await waitFor(() => setup.runtime.getState() === 'READY')
  expect(setup.runtime.getState()).toBe('READY')
}

describe('runtime handshake', () => {
  it('sends HELLO with the instance identity and no environment scope', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await tick()
    const hello = sent(setup.transport).find((envelope) => envelope.messageType === 'HELLO')
    expect(hello).toBeDefined()
    expect(hello?.environmentId).toBeNull()
    expect(hello?.payload.protocolVersion).toBe(PROTOCOL_VERSION)
    expect(hello?.payload.capabilityCatalogVersion).toBe('3')
    expect(hello?.payload.daemonVersion).toBe(DAEMON_VERSION)
    expect(hello?.payload.registrationToken).toBe('test-registration-token')
    expect(typeof hello?.payload.daemonInstanceId).toBe('string')
    setup.runtime.close()
  })

  it('binds the environment and sends READY on WELCOME', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    expect(setup.runtime.getBoundEnvironmentId()).toBe(ENV)
    const ready = sent(setup.transport).find((envelope) => envelope.messageType === 'READY')
    expect(ready?.environmentId).toBe(ENV)
    expect((ready?.payload as { version: number }).version).toBe(READY_CAPABILITIES_VERSION)
    expect((ready?.payload as { runtimeStatus: { revision: string } }).runtimeStatus.revision).toBe('1')
    setup.runtime.close()
  })

  it('sends HEARTBEAT only while READY', async () => {
    const setup = buildRuntime({ heartbeatIntervalMillis: 20 })
    await handshake(setup)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'HEARTBEAT'))
    setup.runtime.close()
  })

  it('rejects WELCOME for a mismatched environment after binding', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('HEARTBEAT', { environmentId: OTHER_ENV, payload: {} }))
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    setup.runtime.close()
  })
})

describe('runtime invocation lifecycle', () => {
  it('executes INVOKE and emits STARTED then COMPLETED', async () => {
    const calls: string[] = []
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => {
          calls.push(call.invocationId)
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    const types = sent(setup.transport).map((envelope) => envelope.messageType)
    expect(types).toContain('STARTED')
    expect(types).toContain('COMPLETED')
    expect(calls).toEqual(['inv-1'])
    setup.runtime.close()
  })

  it('dedups duplicate INVOKE while running and replays STARTED', async () => {
    let release: (() => void) | null = null
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    let calls = 0
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => {
          calls++
          await gate
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    const message = serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() })
    setup.transport.receive(message)
    await tick()
    setup.transport.receive(message)
    await tick()
    expect(calls).toBe(1)
    const replayed = sent(setup.transport).filter((envelope) => envelope.messageType === 'STARTED')
    expect(replayed.length).toBe(2)
    expect(replayed[1]?.payload).toEqual({ replayed: true })
    release?.()
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    setup.runtime.close()
  })

  it('replays the terminal result for a duplicate INVOKE after completion', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const message = serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() })
    setup.transport.receive(message)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    const completed = sent(setup.transport).find((envelope) => envelope.messageType === 'COMPLETED')
    setup.transport.receive(message)
    await tick()
    const completions = sent(setup.transport).filter((envelope) => envelope.messageType === 'COMPLETED')
    expect(completions.length).toBe(2)
    expect(completions[1]?.payload).toEqual(completed?.payload)
    setup.runtime.close()
  })

  it('cancels a running invocation and aborts the capability signal', async () => {
    let observedSignal: AbortSignal | null = null
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          observedSignal = context.signal
          await new Promise<void>((resolve) => context.signal.addEventListener('abort', () => resolve(), { once: true }))
          throw new Error('cancelled')
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await tick()
    setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-1', payload: {} }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED'))
    expect(observedSignal?.aborted).toBe(true)
    setup.runtime.close()
  })

  it('ignores CANCEL for an unknown invocation and replays a terminal one', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'missing', payload: {} }))
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED')).toBe(false)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-1', payload: {} }))
    await tick()
    // 终态不可变：重放 COMPLETED，绝不改成 CANCELLED。
    expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'COMPLETED').length).toBe(2)
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED')).toBe(false)
    setup.runtime.close()
  })

  // RECOVER 是同实例重连后的取回：运行中的调用回放 STARTED 并继续执行，绝不像 CANCEL 那样中止执行体。
  it('recovers a running invocation without aborting it and delivers its later result', async () => {
    let observedSignal: AbortSignal | null = null
    const release = Promise.withResolvers<void>()
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          observedSignal = context.signal
          await release.promise
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-recover', payload: invokePayload() }))
    await waitFor(() => observedSignal !== null)
    setup.transport.receive(serverMessage('RECOVER', { environmentId: ENV, invocationId: 'inv-recover', payload: {} }))
    await tick()
    const recovered = sent(setup.transport).filter((envelope) => envelope.invocationId === 'inv-recover')
    expect(recovered.filter((envelope) => envelope.messageType === 'STARTED').map((envelope) => envelope.payload)).toEqual([
      {},
      { replayed: true },
    ])
    expect(observedSignal?.aborted).toBe(false)
    release.resolve()
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED')).toBe(false)
    setup.runtime.close()
  })

  it('replays a terminal invocation with its exit confirmation on RECOVER and ignores an unknown one', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('RECOVER', { environmentId: ENV, invocationId: 'missing', payload: {} }))
      await tick()
      expect(sent(setup.transport).some((envelope) => envelope.invocationId === 'missing')).toBe(false)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-recovered', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      setup.transport.receive(serverMessage('RECOVER', { environmentId: ENV, invocationId: 'inv-recovered', payload: {} }))
      await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED').length === 2)
      const replayed = sent(setup.transport).filter((envelope) => envelope.invocationId === 'inv-recovered')
      expect(replayed.filter((envelope) => envelope.messageType === 'COMPLETED')).toHaveLength(2)
      expect(replayed.filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(2)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  // 验证超时机制：Runtime在timer到期仅发abort信号，cleanup期间维持RUNNING不发终态，release后才完成终态。
  it('times out a long-running invocation with cooperative cleanup and deferred terminal', async () => {
    let observedSignal: AbortSignal | null = null
    let execCount = 0
    const cleanupGate = Promise.withResolvers<void>()
    const startedGate = Promise.withResolvers<void>()

    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          execCount++
          observedSignal = context.signal
          startedGate.resolve()
          await new Promise<void>((resolve) =>
            context.signal.addEventListener('abort', () => resolve(), { once: true }),
          )
          await cleanupGate.promise
          throw new Error('timed out cleanup failure')
        },
      },
    })
    try {
      await handshake(setup)
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      setup.transport.receive(
        serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('fs.read', 20) }),
      )
      await startedGate.promise
      await vi.advanceTimersByTimeAsync(20)
      vi.useRealTimers()

      expect(observedSignal?.aborted).toBe(true)
      expect(observedSignal?.reason).toBe('TIMED_OUT')

      // 在 cleanupGate 释放之前：journal仍处于RUNNING且无terminal报文
      expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('RUNNING')
      expect(sent(setup.transport).some((env) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(env.messageType))).toBe(false)

      // 发送重复的 INVOKE 不会重复触发执行器，只会重放 STARTED
      setup.transport.receive(
        serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('fs.read', 20) }),
      )
      await tick()
      expect(execCount).toBe(1)
      const replayedStarted = sent(setup.transport).filter((env) => env.messageType === 'STARTED')
      expect(replayedStarted.length).toBe(2)

      // 释放 cleanupGate，执行器抛错退出
      cleanupGate.resolve()

      await waitFor(() => sent(setup.transport).some((env) => env.messageType === 'FAILED'))
      const failedEnvelopes = sent(setup.transport).filter((env) => env.messageType === 'FAILED')
      expect(failedEnvelopes.length).toBe(1)
      expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('FAILED')
      const failedPayload = failedEnvelopes[0]?.payload as { message: string }
      expect(String(failedPayload.message)).toContain('timed out after 20ms')
    } finally {
      cleanupGate.resolve()
      vi.useRealTimers()
      setup.runtime.close()
    }
  })

  // 验证可信成功结果在超时或取消竞态时不被覆盖，文件真实变更保留且发送COMPLETED终态。
  it.each([
    {
      scenario: 'timeout',
      timeoutMillis: 30,
      raceAction: async () => {
        await vi.advanceTimersByTimeAsync(30)
      },
      expectedReason: 'TIMED_OUT',
    },
    {
      scenario: 'cancel',
      timeoutMillis: 10_000,
      raceAction: async (setup: Setup, invocationId: string) => {
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId }))
      },
      expectedReason: 'CANCELLED',
    },
    {
      scenario: 'timeout-then-cancel',
      timeoutMillis: 30,
      raceAction: async (setup: Setup, invocationId: string) => {
        await vi.advanceTimersByTimeAsync(30)
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId }))
      },
      expectedReason: 'TIMED_OUT',
    },
    {
      scenario: 'cancel-then-timeout',
      timeoutMillis: 30,
      raceAction: async (setup: Setup, invocationId: string) => {
        setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId }))
        await vi.advanceTimersByTimeAsync(30)
      },
      expectedReason: 'CANCELLED',
    },
  ])(
    'preserves credible success result when $scenario races after side effect commit',
    async ({ scenario, timeoutMillis, raceAction, expectedReason }) => {
      const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-runtime-edit-')))
      const filePath = join(dir, 'test.txt')
      await writeFile(filePath, 'hello world\n', 'utf8')

      const committedGate = Promise.withResolvers<void>()
      const holdGate = Promise.withResolvers<void>()
      let observedSignal: AbortSignal | null = null

      const realEdit = fileCapabilities['fs.edit']!
      const wrappedEdit: CapabilityExecutor = async (call, context) => {
        observedSignal = context.signal
        const realResult = await realEdit(call, context)
        committedGate.resolve()
        await holdGate.promise
        return realResult
      }

      const setup = buildRuntime({
        capabilities: {
          'fs.edit': wrappedEdit,
        },
      })
      const invocationId = `inv-edit-${scenario}`

      try {
        await handshake(setup)
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

        const editArgs = {
          path: filePath,
          old_string: 'hello world',
          new_string: 'hello kk-studio',
        }
        const payload = JSON.parse(
          encodeInvoke({
            capabilityId: 'fs.edit',
            capabilityVersion: CAPABILITY_CATALOG_VERSION,
            arguments: editArgs,
            timeoutMillis,
          }),
        ) as Record<string, unknown>

        setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId, payload }))

        // 等待真实 edit 落盘 commit
        await committedGate.promise

        // 此时已 commit，触发超时或取消竞态
        await raceAction(setup, invocationId)

        expect(observedSignal?.aborted).toBe(true)
        expect(observedSignal?.reason).toBe(expectedReason)

        vi.useRealTimers()
        holdGate.resolve()

        // 验证：收到的终态是 COMPLETED 报文，绝不被 TIMED_OUT 或 CANCELLED 覆盖
        await waitFor(() =>
          sent(setup.transport).some((env) => env.invocationId === invocationId && env.messageType === 'COMPLETED'),
        )
        const completed = sent(setup.transport).find(
          (env) => env.invocationId === invocationId && env.messageType === 'COMPLETED',
        )
        expect(completed).toBeDefined()
        expect(
          sent(setup.transport).some((env) => env.invocationId === invocationId && ['FAILED', 'CANCELLED'].includes(env.messageType)),
        ).toBe(false)

        const body = completed?.payload as {
          result: {
            error: boolean
            details: { editReview?: { diff?: string; path?: string } }
          }
        }
        const resultPayload = body.result
        expect(resultPayload.error).toBe(false)
        expect(resultPayload.details.editReview?.path).toBe(filePath)
        expect(resultPayload.details.editReview?.diff).toContain('+ 1|hello kk-studio')

        const updatedContent = await readFile(filePath, 'utf8')
        expect(updatedContent).toBe('hello kk-studio\n')
      } finally {
        holdGate.resolve()
        committedGate.resolve()
        vi.useRealTimers()
        setup.runtime.close()
        await rm(dir, { recursive: true, force: true })
      }
    },
  )

  // 验证Runtime与process真实集成：捕获进度屏障后推进超时，terminate清理期间维持RUNNING，收敛后交付包含输出的TIMED_OUT终态。
  it('integrates Runtime and process capability with truthful output preservation, deferred terminal and final convergence', async () => {
    const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-runtime-proc-')))

    const cleanupGate = Promise.withResolvers<void>()
    const startedGate = Promise.withResolvers<void>()
    const progressGate = Promise.withResolvers<void>()
    const exitGate = Promise.withResolvers<ProcessExit>()

    const stdoutStream = new PassThrough()
    const stderrStream = new PassThrough()

    let terminateStarted = false
    let terminated = false

    const managedProcess: ManagedProcess = {
      pid: 9999,
      stdin: new PassThrough(),
      stdout: stdoutStream,
      stderr: stderrStream,
      exit: exitGate.promise,
      terminate: async () => {
        terminateStarted = true
        await cleanupGate.promise
        terminated = true
        stdoutStream.end()
        stderrStream.end()
        exitGate.resolve({ exitCode: null, signal: 'SIGTERM' })
      },
    }

    const supervisor: ProcessSupervisor = {
      start: async () => {
        startedGate.resolve()
        return managedProcess
      },
      close: async () => {},
    }

    const realExec = processCapabilities['process.exec']!
    const wrappedExec: CapabilityExecutor = async (call, context) => {
      const wrappedContext = {
        ...context,
        progress: async (result: CapabilityResult) => {
          await context.progress(result)
          progressGate.resolve()
        },
      }
      return realExec(call, wrappedContext)
    }

    const setup = buildRuntime({
      capabilities: {
        'process.exec': wrappedExec,
      },
      processes: supervisor,
    })

    try {
      await handshake(setup)

      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

      const invoke = JSON.parse(
        encodeInvoke({
          capabilityId: 'process.exec',
          capabilityVersion: CAPABILITY_CATALOG_VERSION,
          arguments: { command: 'sleep 100', workdir: dir },
          timeoutMillis: 80,
        }),
      ) as Record<string, unknown>

      setup.transport.receive(
        serverMessage('INVOKE', { environmentId: ENV, invocationId: 'proc-inv-1', payload: invoke }),
      )

      // 等待 supervisor.start 事件
      await startedGate.promise

      // 写入足量数据并等待 progress wrapper 屏障
      stdoutStream.write('initial output line before timeout\n' + 'x'.repeat(70_000) + '\n')
      await progressGate.promise

      // 屏障之后再推进显式预算 80ms 超时
      await vi.advanceTimersByTimeAsync(80)

      vi.useRealTimers()

      // 等待 terminate 被触发
      await waitFor(() => terminateStarted)

      // 在 cleanupGate 释放前：无 terminal 报文，journal 仍为 RUNNING
      expect(
        sent(setup.transport).some(
          (env) => env.invocationId === 'proc-inv-1' && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(env.messageType),
        ),
      ).toBe(false)
      expect(setup.runtime.getJournal().find('proc-inv-1')?.state).toBe('RUNNING')

      // 释放清理门禁
      cleanupGate.resolve()

      // 验证终态 COMPLETED
      await waitFor(() =>
        sent(setup.transport).some((env) => env.invocationId === 'proc-inv-1' && env.messageType === 'COMPLETED'),
      )
      const terminalCompleted = sent(setup.transport).find(
        (env) => env.invocationId === 'proc-inv-1' && env.messageType === 'COMPLETED',
      )
      expect(terminalCompleted).toBeDefined()

      const body = terminalCompleted?.payload as {
        result: {
          error: boolean
          details: { process?: { outcome?: string } }
          contents: Array<{ type: string; text?: string }>
        }
      }
      const resultPayload = body.result
      expect(resultPayload.error).toBe(true)
      expect(resultPayload.details.process?.outcome).toBe('TIMED_OUT')
      const combinedText = resultPayload.contents.map((c) => c.text ?? '').join('\n')
      expect(combinedText).toContain('initial output line before timeout')
      expect(combinedText).toContain('timed out after 80 ms')

      expect(terminated).toBe(true)
      expect(setup.runtime.getJournal().find('proc-inv-1')?.state).toBe('COMPLETED')
    } finally {
      cleanupGate.resolve()
      startedGate.resolve()
      progressGate.resolve()
      exitGate.resolve({ exitCode: null, signal: 'SIGTERM' })
      vi.useRealTimers()
      setup.runtime.close()
      await rm(dir, { recursive: true, force: true })
    }
  })

  it('fails unknown capabilities and mismatched capability versions', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('nope.nope') }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
    expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('FAILED')

    const mismatched = JSON.parse(
      encodeInvoke({ capabilityId: 'fs.read', capabilityVersion: '9', arguments: {}, timeoutMillis: 0 }),
    ) as Record<string, unknown>
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-2', payload: mismatched }))
    await waitFor(() => setup.runtime.getJournal().find('inv-2')?.state === 'FAILED')
    // 参数/能力确定未进入执行器：可以直接确认未执行，且每个调用至多一次。
    expect(
      sent(setup.transport)
        .filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')
        .map((envelope) => envelope.invocationId),
    ).toEqual(['inv-1', 'inv-2'])
    setup.runtime.close()
  })

  it('forwards progress frames', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          await context.progress({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'partial' }] })
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'PROGRESS'))
      expect(sent(setup.transport).find((envelope) => envelope.messageType === 'PROGRESS')?.payload).toEqual({
        result: { callId: 'inv-1', error: false, details: {}, contents: [{ type: 'text', text: 'partial' }] },
      })
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('requests cancellation when a progress result cannot be encoded', async () => {
    // 编码失败立即封存 FAILED，但执行体仍被真实 gate 持有；停止意图必须已送达，退出确认只等 body 真正离开。
    const gate = Promise.withResolvers<void>()
    let abortReason: unknown
    let bodyHeld = false
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.signal.addEventListener('abort', () => {
            abortReason = context.signal.reason
          }, { once: true })
          await context.progress({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'binary', bytes: new Uint8Array([1]), mediaType: 'text/plain' }] })
          bodyHeld = true
          await gate.promise
          bodyHeld = false
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-binary-progress', payload: invokePayload() }))
      await waitFor(() => bodyHeld)
      expect(bodyHeld).toBe(true)
      const failed = sent(setup.transport).filter((envelope) => envelope.messageType === 'FAILED')
      expect(failed).toHaveLength(1)
      expect(failed[0]?.payload).toEqual({
        message: 'cannot encode partial capability result: PROGRESS result must not contain binary content',
      })
      // 失败封存本身不确认退出：body 仍在 gate 内，不能有 ACK。
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      // 原实现缺少这一步，执行体会一直不知道自己应当停止。
      expect(abortReason).toBe('CANCELLED')
      expect(setup.runtime.getJournal().find('inv-binary-progress')?.state).toBe('FAILED')
      gate.resolve()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      expect(bodyHeld).toBe(false)
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(1)
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'FAILED')).toHaveLength(1)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED')).toBe(false)
      expect(setup.runtime.getJournal().find('inv-binary-progress')?.state).toBe('FAILED')
    } finally {
      gate.resolve()
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('fails when the capability result callId mismatches', async () => {
    const setup = buildRuntime({
      capabilities: { 'fs.read': async () => textResult('other') },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
    setup.runtime.close()
  })

  it('enforces the concurrency bound', async () => {
    const setup = buildRuntime({
      capabilities: { 'fs.read': async () => new Promise<CapabilityResult>(() => {}) },
      maxConcurrentInvocations: 1,
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await tick()
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-2', payload: invokePayload() }))
    await waitFor(() => setup.runtime.getJournal().find('inv-2')?.state === 'FAILED')
    setup.runtime.close()
  })
})

describe('runtime resource upload', () => {
  it('externalizes binary results through the resource uploader', async () => {
    const uploaded: { invocationId: string; size: number }[] = []
    const resources: ResourceUploader = {
      async upload(invocationId, content) {
        uploaded.push({ invocationId, size: content.bytes.byteLength })
        return {
          type: 'resource',
          uploadId: '77777777-7777-4777-8777-777777777777',
          mediaType: content.mediaType,
          name: content.name ?? null,
          size: content.bytes.byteLength,
          sha256: 'a'.repeat(64),
        }
      },
    }
    const setup = buildRuntime({
      resources,
      capabilities: {
        'fs.read': async (call) => ({
          callId: call.invocationId,
          error: false,
          details: {},
          contents: [{ type: 'binary', bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/octet-stream' }],
        }),
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    expect(uploaded).toEqual([{ invocationId: 'inv-1', size: 3 }])
    const completed = sent(setup.transport).find((envelope) => envelope.messageType === 'COMPLETED')
    expect(JSON.stringify(completed?.payload)).toContain('77777777-7777-4777-8777-777777777777')
    setup.runtime.close()
  })

  it('fails deterministically when binary content exceeds the announced budget', async () => {
    let uploads = 0
    const resources: ResourceUploader = {
      async upload() {
        uploads++
        throw new Error('must not be called')
      },
    }
    const setup = buildRuntime({
      resources,
      capabilities: {
        'fs.read': async (call) => ({
          callId: call.invocationId,
          error: false,
          details: {},
          contents: [{ type: 'binary', bytes: new Uint8Array(2 * 1024 * 1024), mediaType: 'application/octet-stream' }],
        }),
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
    expect(uploads).toBe(0)
    setup.runtime.close()
  })
})

describe('runtime update handling', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const command = {
    operationId,
    targetVersion: artifactFixture.targetVersion,
    artifactUrl: artifactFixture.accepted[0]!.artifactUrl,
    artifactSha256: artifactFixture.artifactSha256,
  }

  it('acknowledges ACCEPTED then reports PREPARED and dedups by operationId', async () => {
    let handled = 0
    const update: UpdatePort = {
      async handleUpdate() {
        handled++
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED'))
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await tick()
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('releases the accepted handoff permit after the receipt and retries it on replay and reconnect', async () => {
    const stderr = vi.spyOn(process.stderr, 'write').mockReturnValue(true)
    const releases: number[] = []
    let attempts = 0
    const update: UpdatePort = {
      async handleUpdate() {
        return {
          phase: 'PREPARED',
          async releasePermit(): Promise<void> {
            attempts++
            if (attempts === 1) {
              // 第一次释放失败：必须被记录为可诊断的失败，并由重放与重连重发自然重试。
              throw new Error('injected permit write failure')
            }
            releases.push(attempts)
          },
        }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    const prepared = (): boolean =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED',
      )
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(prepared)
    await waitFor(() => attempts === 1)

    // 同一 operation 的重放：既不第二次准备，也重试许可释放。
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => releases.length === 1)

    // 重连重发同样重试许可释放。
    setup.transport.disconnect()
    await waitFor(() => setup.transport.connectCount >= 2)
    setup.transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    await waitFor(() => releases.length === 2)

    expect(attempts).toBe(3)
    // 许可释放失败不是停机失败：只留下安全诊断，绝不把一次写入失败升级为 Daemon 退出失败。
    expect(setup.runtime.getShutdownFailures()).toEqual([])
    expect(stderr.mock.calls.map((call) => String(call[0])).join('')).toContain(
      'daemon update permit release failed: Error',
    )
    stderr.mockRestore()
    setup.runtime.close()
  })

  it('reports every independent shutdown release failure and still attempts the remaining releases', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const stderr = vi.spyOn(process.stderr, 'write').mockReturnValue(true)
    const connection = setup.transport.connection
    expect(connection).not.toBeNull()
    connection!.close = (): void => {
      throw new Error('injected connection close failure')
    }
    setup.transport.close = (): void => {
      throw new Error('injected transport close failure')
    }

    setup.runtime.close()
    await setup.runtime.awaitCompletion()

    expect(setup.runtime.getShutdownFailures()).toHaveLength(2)
    const diagnostics = stderr.mock.calls.map((call) => String(call[0])).join('')
    expect(diagnostics).toContain('daemon connection close failed: Error')
    expect(diagnostics).toContain('daemon transport close failed: Error')
    stderr.mockRestore()
  })

  it('keeps independent releases and the completion latch when the diagnostic output itself fails', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const stderr = vi.spyOn(process.stderr, 'write').mockImplementation(() => {
      throw new Error('injected diagnostic write failure')
    })
    const connection = setup.transport.connection
    expect(connection).not.toBeNull()
    connection!.close = (): void => {
      throw new Error('injected connection close failure')
    }

    setup.runtime.close()
    stderr.mockRestore()

    // 诊断写不出来也必须落定终止闩，绝不挂起 awaitCompletion，也绝不阻断后续独立释放。
    await setup.runtime.awaitCompletion()

    // connection 的失败与「写诊断失败」都可见，而之后的 transport 释放照常独立执行。
    expect(setup.runtime.getShutdownFailures()).toHaveLength(2)
    expect(setup.transport.closed).toBe(true)
  })

  it('never leaks a writable error name or a non-error value into the shutdown diagnostic', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const stderr = vi.spyOn(process.stderr, 'write').mockReturnValue(true)
    const connection = setup.transport.connection
    expect(connection).not.toBeNull()
    const named = new Error('boom')
    named.name = 's3cr3t-token=/home/dev/.ssh/id_rsa'
    connection!.close = (): void => {
      throw named
    }
    setup.transport.close = (): void => {
      throw 's3cr3t-thrown-string'
    }

    setup.runtime.close()
    await setup.runtime.awaitCompletion()

    const diagnostics = stderr.mock.calls.map((call) => String(call[0])).join('')
    expect(diagnostics).toContain('daemon connection close failed: Error')
    expect(diagnostics).toContain('daemon transport close failed: UnknownError')
    expect(diagnostics).not.toContain('s3cr3t')
    expect(diagnostics).not.toContain('id_rsa')
    expect(diagnostics).not.toContain('boom')
    expect(setup.runtime.getShutdownFailures()).toEqual([named, 's3cr3t-thrown-string'])
    stderr.mockRestore()
  })

  it('reports FAILED when the updater throws', async () => {
    const update: UpdatePort = {
      async handleUpdate() {
        throw new Error('download failed')
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() =>
      sent(setup.transport).some((envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'FAILED'),
    )
    setup.runtime.close()
  })

  it('rejects a different target while one is in progress and reports the active target', async () => {
    const gate = new Promise<void>(() => {})
    let handled = 0
    const update: UpdatePort = {
      async handleUpdate() {
        handled++
        await gate
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await tick()
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: {
          ...command,
          operationId: '44444444-4444-4555-8666-777777777777',
          targetVersion: '9.9.10',
          artifactUrl: command.artifactUrl.replaceAll(command.targetVersion, '9.9.10'),
        },
      }),
    )
    await tick()
    const busy = sent(setup.transport).find(
      (envelope) =>
        envelope.messageType === 'UPDATE_RESULT' &&
        (envelope.payload as { operationId: string }).operationId === '44444444-4444-4555-8666-777777777777',
    )
    expect((busy?.payload as { phase: string }).phase).toBe('FAILED')
    expect((busy?.payload as { message: string }).message).toContain(command.targetVersion)
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('returns the current snapshot for a same-target new command and rejects the same id with changed arguments', async () => {
    const gate = new Promise<void>(() => {})
    let handled = 0
    const update: UpdatePort = {
      async handleUpdate(_command, _config, _signal, reportProgress) {
        handled++
        reportProgress!({ downloadedBytes: 12, totalBytes: 100 })
        reportProgress!({ downloadedBytes: 5, totalBytes: 100 })
        await gate
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await tick()
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await tick()
    expect(handled).toBe(1)
    const status = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'RUNTIME_STATUS')
      .at(-1)
    expect((status?.payload as { status: { update: unknown } }).status.update).toMatchObject({
      targetVersion: command.targetVersion,
      downloadedBytes: 12,
      totalBytes: 100,
    })

    setup.transport.receive(
      serverMessage('UPDATE', { environmentId: ENV, payload: { ...command, artifactSha256: 'b'.repeat(64) } }),
    )
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('returns the current snapshot for an already installed target without invoking the updater', async () => {
    let handled = 0
    const update: UpdatePort = {
      async handleUpdate() {
        handled++
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: {
          ...command,
          targetVersion: DAEMON_VERSION,
          artifactUrl: command.artifactUrl.replaceAll(command.targetVersion, DAEMON_VERSION),
        },
      }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'RUNTIME_STATUS'))
    expect(handled).toBe(0)
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'UPDATE_RESULT' &&
          (envelope.payload as { operationId?: string }).operationId === command.operationId,
      ),
    ).toBe(false)
    const status = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'RUNTIME_STATUS')
      .at(-1)
    expect(decodeRuntimeStatus(JSON.stringify(status?.payload))).toEqual(setup.runtime.getRuntimeStatus())
    expect(setup.runtime.getRuntimeStatus().status.update).toBeNull()
    setup.runtime.close()
  })

  it('clears the update on explicit failure and ignores late progress callbacks', async () => {
    const gate = Promise.withResolvers<void>()
    let updateAttempts = 0
    let reportProgress: UpdateProgressReporter | null = null
    const update: UpdatePort = {
      async handleUpdate(_command, _config, _signal, reporter) {
        updateAttempts++
        reportProgress = reporter ?? null
        reporter!({ downloadedBytes: 12, totalBytes: 100 })
        await gate.promise
        throw new Error('download failed')
      },
    }
    const setup = buildRuntime({ update, heartbeatIntervalMillis: 20 })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => reportProgress !== null)
    await waitFor(() => setup.runtime.getRuntimeStatus().status.update?.downloadedBytes === 12)
    const beforeFailure = setup.runtime.getRuntimeStatus()
    gate.resolve()
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'FAILED',
        ),
    )
    const afterFailure = setup.runtime.getRuntimeStatus()
    expect(afterFailure.status.update).toBeNull()
    expect(BigInt(afterFailure.revision)).toBeGreaterThan(BigInt(beforeFailure.revision))
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'RUNTIME_STATUS' &&
          decodeRuntimeStatus(JSON.stringify(envelope.payload)).status.update === null,
      ),
    ).toBe(true)

    const statusCount = sent(setup.transport).filter((envelope) => envelope.messageType === 'RUNTIME_STATUS').length
    reportProgress!({ downloadedBytes: 1, totalBytes: 100 })
    await tick()
    expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'RUNTIME_STATUS')).toHaveLength(statusCount)
    expect(setup.runtime.getRuntimeStatus()).toEqual(afterFailure)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'HEARTBEAT'))
    const heartbeat = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'HEARTBEAT')
      .at(-1)
    expect(decodeHeartbeat(JSON.stringify(heartbeat?.payload)).runtimeStatus).toEqual(afterFailure)

    // 明确失败后同一目标的新 operation 可以重试；上一轮 update Promise 不占住准入门闩。
    const retryOperationId = '44444444-4444-4555-8666-777777777777'
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: retryOperationId },
      }),
    )
    await waitFor(() => updateAttempts === 2)
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { operationId: string }).operationId === retryOperationId &&
            (envelope.payload as { phase: string }).phase === 'FAILED',
        ),
    )
    setup.runtime.close()
  })

  it('blocks new invocations and IDE creation while an update is being prepared and releases them on failure', async () => {
    const gate = Promise.withResolvers<void>()
    let updateAttempts = 0
    let capabilityCalls = 0
    let ideCalls = 0
    const update: UpdatePort = {
      async handleUpdate() {
        updateAttempts++
        await gate.promise
        throw new Error('download failed')
      },
    }
    const setup = buildRuntime({
      update,
      capabilities: {
        'fs.read': async (call) => {
          capabilityCalls++
          return textResult(call.invocationId)
        },
      },
      ide: ideService({
        async handle() {
          ideCalls++
          return { ok: true }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'ACCEPTED',
        ),
    )

    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-blocked', payload: invokePayload() }),
    )
    setup.transport.receive(
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-blocked',
        payload: openViewPayload,
      }),
    )
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'UPDATE_RESULT' ||
          envelope.messageType === 'IDE_RESULT' ||
          envelope.messageType === 'FAILED',
      ),
    )
    await tick()
    const blockedInvocation = sent(setup.transport).find(
      (envelope) => envelope.messageType === 'FAILED' && envelope.invocationId === 'inv-blocked',
    )
    expect((blockedInvocation?.payload as { message: string }).message).toContain('update admission is held')
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'IDE_RESULT' &&
          envelope.invocationId === 'ide-blocked' &&
          (envelope.payload as { ok: boolean }).ok === false,
      ),
    ).toBe(true)
    expect(capabilityCalls).toBe(0)
    expect(ideCalls).toBe(0)

    gate.resolve()
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'FAILED',
        ),
    )
    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-after-failure', payload: invokePayload() }),
    )
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'COMPLETED' && envelope.invocationId === 'inv-after-failure',
      ),
    )
    expect(capabilityCalls).toBe(1)
    expect(updateAttempts).toBe(1)
    setup.runtime.close()
  })

  it('keeps the admission gate held after PREPARED for new calls, views, data binds, and leases', async () => {
    let updateCalls = 0
    let ideCalls = 0
    const update: UpdatePort = {
      async handleUpdate() {
        updateCalls++
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({
      update,
      ide: ideService({
        async handle() {
          ideCalls++
          return { ok: true }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )

    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-after-prepared', payload: invokePayload() }),
    )
    setup.transport.receive(
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-after-prepared',
        payload: openViewPayload,
      }),
    )
    setup.transport.receive(
      serverMessage('DATA_BIND', {
        environmentId: ENV,
        payload: {
          generation: '1',
          ticket: 'A'.repeat(43),
          streamUrl: 'https://studio.example.com/data',
        },
      }),
    )
    setup.transport.receive(
      serverMessage('IDE_LEASE', {
        environmentId: ENV,
        payload: {
          round: '1',
          bindings: [
            {
              viewId: ENV,
              documentId: '99999999-2222-4333-8444-555555555555',
              bindingEpoch: '1',
            },
          ],
        },
      }),
    )
    await waitFor(
      () =>
        sent(setup.transport).filter((envelope) => envelope.messageType === 'ERROR').length >= 2,
    )
    await tick()
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'FAILED' &&
          envelope.invocationId === 'inv-after-prepared' &&
          (envelope.payload as { message: string }).message.includes('update admission is held'),
      ),
    ).toBe(true)
    expect(
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'IDE_RESULT' &&
          envelope.invocationId === 'ide-after-prepared' &&
          (envelope.payload as { message: string }).message.includes('update is in progress'),
      ),
    ).toBe(true)
    const errors = sent(setup.transport).filter((envelope) => envelope.messageType === 'ERROR')
    expect(errors.some((envelope) => (envelope.payload as { message: string }).message.includes('not allowed while environment update'))).toBe(
      true,
    )
    expect(setup.runtime.getRuntimeStatus().status.update?.targetVersion).toBe(command.targetVersion)
    expect(ideCalls).toBe(0)

    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await tick()
    expect(updateCalls).toBe(1)
    expect(setup.runtime.getRuntimeStatus().status.update?.targetVersion).toBe(command.targetVersion)
    setup.runtime.close()
  })

  it('uses the IDE admission view to reject UPDATE before taking the gate', async () => {
    let canStart = false
    let updateCalls = 0
    const setup = buildRuntime({
      ide: ideService({
        async handle() {
          return { ok: true }
        },
        canStartUpdate: () => canStart,
      }),
      update: {
        async handleUpdate() {
          updateCalls++
          return { phase: 'PREPARED' }
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { phase: string }).phase === 'FAILED' &&
            (envelope.payload as { message: string }).message.includes('IDE views'),
        ),
    )
    expect(updateCalls).toBe(0)

    canStart = true
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await waitFor(() => updateCalls === 1)
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { operationId: string }).operationId === '44444444-4444-4555-8666-777777777777' &&
            (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )
    setup.runtime.close()
  })

  it('keeps EXECUTION_EXITED and recovery semantics while protected work blocks UPDATE admission', async () => {
    const release = Promise.withResolvers<void>()
    let capabilityCalls = 0
    let updateCalls = 0
    const setup = buildRuntime({
      update: {
        async handleUpdate() {
          updateCalls++
          return { phase: 'PREPARED' }
        },
      },
      capabilities: {
        'fs.read': async (call, context) => {
          capabilityCalls++
          context.confirmExecutionExit()
          await release.promise
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-recover', payload: invokePayload() }))
    await waitFor(() => capabilityCalls === 1)
    setup.transport.receive(serverMessage('RECOVER', { environmentId: ENV, invocationId: 'inv-recover', payload: {} }))
    await tick()
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { phase: string }).phase === 'FAILED' &&
            (envelope.payload as { message: string }).message.includes('protected execution'),
        ),
    )
    expect(updateCalls).toBe(0)

    release.resolve()
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'EXECUTION_EXITED' && envelope.invocationId === 'inv-recover',
      ),
    )
    const types = sent(setup.transport)
      .filter((envelope) => envelope.invocationId === 'inv-recover')
      .map((envelope) => envelope.messageType)
    expect(types.indexOf('COMPLETED')).toBeGreaterThanOrEqual(0)
    expect(types.indexOf('COMPLETED')).toBeLessThan(types.indexOf('EXECUTION_EXITED'))
    expect(types.filter((messageType) => messageType === 'EXECUTION_EXITED')).toHaveLength(1)

    await tick()
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { operationId: string }).operationId === '44444444-4444-4555-8666-777777777777' &&
            (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )
    expect(updateCalls).toBe(1)
    setup.runtime.close()
  })

  it('delivers heartbeat and reconnect snapshots from the same cached progress state', async () => {
    const gate = Promise.withResolvers<void>()
    let reportProgress: UpdateProgressReporter | null = null
    const update: UpdatePort = {
      async handleUpdate(_command, _config, _signal, reporter) {
        reportProgress = reporter ?? null
        reporter!({ downloadedBytes: 20, totalBytes: 100 })
        reporter!({ downloadedBytes: 10, totalBytes: 100 })
        await gate.promise
        return { phase: 'PREPARED' }
      },
    }
    const setup = buildRuntime({ update, heartbeatIntervalMillis: 20 })
    await handshake(setup)
    const ready = sent(setup.transport).find((envelope) => envelope.messageType === 'READY')
    const initial = decodeCapabilities(JSON.stringify(ready?.payload))
    expect(initial.runtimeStatus).toEqual({
      revision: '1',
      status: { daemonVersion: DAEMON_VERSION, update: null },
    })

    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(() => reportProgress !== null)
    await waitFor(() => setup.runtime.getRuntimeStatus().status.update?.downloadedBytes === 20)
    const current = setup.runtime.getRuntimeStatus()
    reportProgress!({ downloadedBytes: 10, totalBytes: 100 })
    expect(setup.runtime.getRuntimeStatus()).toEqual(current)

    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) =>
          envelope.messageType === 'HEARTBEAT' &&
          decodeHeartbeat(JSON.stringify(envelope.payload)).runtimeStatus.status.update?.downloadedBytes === 20,
      ),
    )
    const heartbeat = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'HEARTBEAT')
      .at(-1)
    expect(decodeHeartbeat(JSON.stringify(heartbeat?.payload))).toEqual({
      runtimeStatus: current,
      ideLeaseRound: null,
    })

    setup.transport.disconnect()
    await waitFor(() => setup.transport.connectCount >= 2)
    setup.transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    const reconnectReady = sent(setup.transport)
      .filter((envelope) => envelope.messageType === 'READY')
      .at(-1)
    const reconnected = decodeCapabilities(JSON.stringify(reconnectReady?.payload))
    expect(reconnected.runtimeStatus).toEqual(current)
    expect(setup.runtime.getUpdateProgress()).toEqual(current.status.update)
    gate.resolve()
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )
    setup.runtime.close()
  })

  it('uses the updater progress callback shape and the pull progress hook', async () => {
    let pulled = 0
    const gate = Promise.withResolvers<void>()
    const update: UpdatePort = {
      async handleUpdate(
        _command,
        _config,
        _signal,
        reportProgress?: (progress: { downloadedBytes: number; totalBytes: number | null }) => void,
      ) {
        // 与 r500-updater 的实际第四参数一致：进度对象，而不是两个位置参数。
        reportProgress?.({ downloadedBytes: 33, totalBytes: 99 })
        await gate.promise
        return { phase: 'PREPARED' }
      },
      getProgress() {
        pulled++
        return { downloadedBytes: 33, totalBytes: 99 }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) => envelope.messageType === 'UPDATE_RESULT' && (envelope.payload as { phase: string }).phase === 'ACCEPTED',
        ),
    )
    expect(setup.runtime.getUpdateProgress()).toEqual({
      targetVersion: command.targetVersion,
      downloadedBytes: 33,
      totalBytes: 99,
    })
    expect(pulled).toBeGreaterThan(0)
    gate.resolve()
    await tick()
    setup.runtime.close()
  })
})

describe('runtime extension handlers', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const command = {
    operationId,
    targetVersion: artifactFixture.targetVersion,
    artifactUrl: artifactFixture.accepted[0]!.artifactUrl,
    artifactSha256: artifactFixture.artifactSha256,
  }
  const binding = {
    generation: '1',
    ticket: 'A'.repeat(43),
    streamUrl: 'https://studio.example.com/data?generation=1',
  }
  const lease = {
    round: '1',
    bindings: [
      {
        viewId: ENV,
        documentId: '99999999-2222-4333-8444-555555555555',
        bindingEpoch: '1',
      },
    ],
  }

  it('reports DATA_BIND explicitly when no handler is configured', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('DATA_BIND', { environmentId: ENV, payload: binding }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR'))
    const error = sent(setup.transport).find((envelope) => envelope.messageType === 'ERROR')
    expect((error?.payload as { message: string }).message).toContain('DATA_BIND handler is not configured')
    setup.runtime.close()
  })

  it('validates DATA_BIND same-origin before invoking the configured handler', async () => {
    const seen: unknown[] = []
    const setup = buildRuntime({
      dataBind: {
        bind(value) {
          seen.push(value)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('DATA_BIND', {
        environmentId: ENV,
        payload: { ...binding, streamUrl: 'https://other.example.com/data' },
      }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR'))
    expect(seen).toEqual([])
    expect(
      (sent(setup.transport).find((envelope) => envelope.messageType === 'ERROR')?.payload as { message: string }).message,
    ).toContain('same-origin')

    setup.transport.receive(serverMessage('DATA_BIND', { environmentId: ENV, payload: binding }))
    await waitFor(() => seen.length === 1)
    expect(seen[0]).toEqual(binding)
    setup.runtime.close()
  })

  it('keeps an active data connection in the UPDATE busy check until it converges', async () => {
    const bindGate = Promise.withResolvers<void>()
    const seenSignals: AbortSignal[] = []
    let updateCalls = 0
    const setup = buildRuntime({
      dataBind: {
        async bind(_value, signal) {
          seenSignals.push(signal)
          await bindGate.promise
        },
      },
      update: {
        async handleUpdate() {
          updateCalls++
          return { phase: 'PREPARED' }
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('DATA_BIND', { environmentId: ENV, payload: binding }))
    await waitFor(() => seenSignals.length === 1)
    expect(seenSignals[0]?.aborted).toBe(false)

    setup.transport.receive(serverMessage('UPDATE', { environmentId: ENV, payload: command }))
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { phase: string }).phase === 'FAILED' &&
            (envelope.payload as { message: string }).message.includes('data connection'),
        ),
    )
    expect(updateCalls).toBe(0)

    bindGate.resolve()
    await tick()
    await tick()
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: { ...command, operationId: '44444444-4444-4555-8666-777777777777' },
      }),
    )
    await waitFor(
      () =>
        sent(setup.transport).some(
          (envelope) =>
            envelope.messageType === 'UPDATE_RESULT' &&
            (envelope.payload as { operationId: string }).operationId === '44444444-4444-4555-8666-777777777777' &&
            (envelope.payload as { phase: string }).phase === 'PREPARED',
        ),
    )
    expect(updateCalls).toBe(1)
    setup.runtime.close()
  })

  it('reports asynchronous DATA_BIND failures as control errors', async () => {
    const setup = buildRuntime({
      dataBind: {
        async bind() {
          throw new Error('stream setup failed')
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(serverMessage('DATA_BIND', { environmentId: ENV, payload: binding }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR'))
    expect(
      (sent(setup.transport).find((envelope) => envelope.messageType === 'ERROR')?.payload as { message: string }).message,
    ).toContain('DATA_BIND handler failed: stream setup failed')
    setup.runtime.close()
  })

  it('reports IDE_LEASE explicitly when no handler is configured', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR'))
    expect(
      (sent(setup.transport).find((envelope) => envelope.messageType === 'ERROR')?.payload as { message: string }).message,
    ).toContain('IDE_LEASE handler is not configured')
    setup.runtime.close()
  })

  it('issues one-use, expiring IDE lease rounds only while views are active', async () => {
    let activeViews = false
    let now = 0
    const applied: unknown[] = []
    const setup = buildRuntime({
      ideLease: {
        hasActiveViews: () => activeViews,
        apply(value, roundStartedAt) {
          applied.push({ value, roundStartedAt })
        },
        getViewState: () => null,
      },
      monotonicNow: () => now,
    })
    await handshake(setup)

    const heartbeat = (): ReturnType<typeof decodeHeartbeat> => {
      const internals = setup.runtime as unknown as { sendHeartbeat(): void }
      internals.sendHeartbeat()
      const latest = sent(setup.transport)
        .filter((envelope) => envelope.messageType === 'HEARTBEAT')
        .at(-1)
      return decodeHeartbeat(JSON.stringify(latest?.payload))
    }

    expect(heartbeat().ideLeaseRound).toBeNull()
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await tick()
    expect(applied).toEqual([])

    activeViews = true
    expect(heartbeat().ideLeaseRound).toBe('1')
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await waitFor(() => applied.length === 1)
    expect(applied[0]).toEqual({ value: lease, roundStartedAt: 0 })

    // 同一轮只接受一次证明。
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: lease }))
    await tick()
    expect(applied).toHaveLength(1)

    now = 15_000
    expect(heartbeat().ideLeaseRound).toBe('2')
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: { ...lease, round: '2' } }))
    await waitFor(() => applied.length === 2)
    // 使用证明使用轮次发出时的本机单调时间，而不是证明到达时间。
    expect(applied[1]).toEqual({ value: { ...lease, round: '2' }, roundStartedAt: 15_000 })

    now = 30_000
    expect(heartbeat().ideLeaseRound).toBe('3')
    now = 45_000
    setup.transport.receive(serverMessage('IDE_LEASE', { environmentId: ENV, payload: { ...lease, round: '3' } }))
    await tick()
    expect(applied).toHaveLength(2)
    expect(
      (sent(setup.transport).filter((envelope) => envelope.messageType === 'ERROR').at(-1)?.payload as {
        message: string
      }).message,
    ).toContain('current unexpired round')
    setup.runtime.close()
  })
})

describe('runtime IDE commands', () => {
  it('dispatches IDE_COMMAND and replies with IDE_RESULT', async () => {
    let handled = 0
    const setup = buildRuntime({
      ide: ideService({
        async handle() {
          handled++
          return { ok: true, views: [] }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'LIST_VIEWS' } }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    const result = sent(setup.transport).find((envelope) => envelope.messageType === 'IDE_RESULT')
    expect(result?.invocationId).toBe('ide-1')
    expect(result?.payload).toEqual({ ok: true, views: [] })
    expect(handled).toBe(1)
    setup.runtime.close()
  })

  it('dedups an in-flight IDE request and reports unavailable service', async () => {
    let handled = 0
    let release: (() => void) | null = null
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const setup = buildRuntime({
      ide: ideService({
        async handle() {
          handled++
          await gate
          return { ok: true }
        },
      }),
    })
    await handshake(setup)
    const message = serverMessage('IDE_COMMAND', {
      environmentId: ENV,
      invocationId: 'ide-1',
      payload: { action: 'LIST_VIEWS' },
    })
    setup.transport.receive(message)
    await tick()
    setup.transport.receive(message)
    await tick()
    expect(handled).toBe(1)
    release?.()
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    setup.runtime.close()

    const withoutService = buildRuntime()
    await handshake(withoutService)
    withoutService.transport.receive(
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-2',
        payload: {
          action: 'CLOSE_VIEW',
          viewId: ENV,
          documentId: '22222222-3333-4444-8555-666666666666',
          bindingEpoch: '1',
          discard: true,
        },
      }),
    )
    await waitFor(() => sent(withoutService.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    const result = sent(withoutService.transport).find((envelope) => envelope.messageType === 'IDE_RESULT')
    expect(result?.payload).toMatchObject({ ok: false, code: 'UNAVAILABLE' })
    withoutService.runtime.close()
  })

  it('aborts an IDE request that exceeds its deadline', async () => {
    let aborted = false
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 20,
      ide: ideService({
        async handle(_command, signal) {
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          aborted = true
          throw new Error('aborted')
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'LIST_VIEWS' } }),
    )
    await waitFor(() => aborted)
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT'))
    const result = sent(setup.transport).find((envelope) => envelope.messageType === 'IDE_RESULT')
    expect(result?.payload).toMatchObject({ ok: false })
    setup.runtime.close()
  })
})

describe('runtime connection management', () => {
  it('keeps in-flight Agent execution across a disconnect and replays after reconnect', async () => {
    let calls = 0
    let release: (() => void) | null = null
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => {
          calls++
          await gate
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    const invoke = serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload() })
    setup.transport.receive(invoke)
    await tick()
    setup.transport.disconnect()
    await tick()
    // 断开不取消在途执行：journal 仍为 RUNNING。
    expect(setup.runtime.getJournal().find('inv-1')?.state).toBe('RUNNING')
    const firstConnection = setup.transport.connection

    // 显式等待重连并完成握手状态同步，再验证同 instance 的行为。
    await waitFor(() => setup.transport.connectCount >= 2)
    setup.transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    expect(setup.transport.connection).not.toBe(firstConnection)

    // 执行仍在途：重放的 INVOKE 只得到 STARTED{replayed:true}，副作用恰好一次。
    setup.transport.receive(invoke)
    await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'STARTED').length >= 1)
    const replayed = sent(setup.transport).filter((envelope) => envelope.messageType === 'STARTED')
    expect(replayed.length).toBe(1)
    expect(replayed[0]?.payload).toEqual({ replayed: true })
    expect(calls).toBe(1)

    // 在途执行完成后，终态在新连接上送达。
    release?.()
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
    expect(calls).toBe(1)

    // 终态已落库：再次重放提供完全相同的 COMPLETED，绝不重复执行。
    setup.transport.receive(invoke)
    await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'COMPLETED').length >= 2)
    expect(calls).toBe(1)
    setup.runtime.close()
  })

  it('fails terminally on REGISTRATION_REJECTED and reconnects on RETRY_LATER', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await tick()
    setup.transport.receive(serverMessage('ERROR', { payload: { code: 'REGISTRATION_REJECTED', message: 'no' } }))
    await tick()
    expect(await setup.runtime.awaitTermination()).toBe('FAILED')
    expect(setup.runtime.getFailureReason()).toContain('registration is rejected')

    const retry = buildRuntime()
    retry.runtime.start()
    await tick()
    retry.transport.receive(serverMessage('ERROR', { payload: { code: 'RETRY_LATER' } }))
    await waitFor(() => retry.transport.connectCount >= 2)
    retry.runtime.close()
  })

  it('responds with ERROR to unknown inbound message types', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    setup.transport.receive(serverMessage('READY', { environmentId: ENV, payload: {} }))
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    setup.runtime.close()
  })

  it('isolates stale connection generations', async () => {
    const setup = buildRuntime()
    await handshake(setup)
    const firstListener = setup.transport.listener
    setup.transport.disconnect()
    await waitFor(() => setup.transport.connectCount >= 2)
    await tick()
    firstListener?.onMessage(serverMessage('HEARTBEAT', { environmentId: ENV, payload: {} }))
    await tick()
    // 旧代际的消息不应产生任何 ERROR 帧。
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(false)
    setup.runtime.close()
  })

  it('applies temporary resource policy updates', async () => {
    const swept: number[] = []
    const temporaryResources: TemporaryResourceSweeper = {
      async sweep(ttlSeconds) {
        swept.push(ttlSeconds)
      },
    }
    const setup = buildRuntime({ temporaryResources })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('TEMPORARY_RESOURCE_POLICY', {
        environmentId: ENV,
        payload: { temporaryResourceTtlSeconds: 120, temporaryResourceCleanupIntervalSeconds: 10 },
      }),
    )
    await tick()
    expect(setup.runtime.getState()).toBe('READY')
    setup.runtime.close()
  })
})

describe('runtime protocol version and scope', () => {
  it('rejects an unsupported protocol version', async () => {
    const setup = buildRuntime()
    setup.runtime.start()
    await tick()
    setup.transport.receive(
      JSON.stringify({ protocolVersion: 2, messageType: 'WELCOME', environmentId: ENV, payload: welcomePayload }),
    )
    await tick()
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'ERROR')).toBe(true)
    setup.runtime.close()
  })

  it('exposes bound environment and journal accessors', async () => {
    const setup = buildRuntime()
    expect(setup.runtime.getBoundEnvironmentId()).toBeNull()
    expect(setup.runtime.getState()).toBe('STOPPED')
    await handshake(setup)
    expect(setup.runtime.getJournal()).toBeDefined()
    setup.runtime.close()
    expect(setup.runtime.getState()).toBe('STOPPED')
  })
})

describe('runtime IDE command lifetime and shutdown convergence', () => {
  /**
   * IDE 管理请求不进入 Agent journal；连接失效即中止在途请求，且不跨代际重放。
   */
  it('aborts an in-flight IDE command on disconnect without journaling or replay', async () => {
    let aborted = false
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 60_000,
      ide: ideService({
        async handle(_command, signal) {
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          aborted = true
          return { ok: true, views: [] }
        },
      }),
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: 'ide-1', payload: { action: 'LIST_VIEWS' } }),
    )
    await tick()
    expect(setup.runtime.getJournal().find('ide-1')).toBeUndefined()
    setup.transport.disconnect()
    await waitFor(() => aborted)

    await waitFor(() => setup.transport.connectCount >= 2)
    setup.transport.receive(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
    await waitFor(() => setup.runtime.getState() === 'READY')
    expect(sent(setup.transport).some((envelope) => envelope.messageType === 'IDE_RESULT')).toBe(false)
    expect(setup.runtime.getJournal().find('ide-1')).toBeUndefined()
    setup.runtime.close()
  })

  /** 停机必须中止在途 controller，并且只有所有在途执行真正收敛后才发布完成信号。 */
  it('aborts in-flight executions on shutdown and only completes after they settle', async () => {
    let streamAborted = false
    let capabilityAborted = false
    let releaseCapability: (() => void) | null = null
    const capabilityGate = new Promise<void>((resolve) => {
      releaseCapability = resolve
    })
    const setup = buildRuntime({
      ideCommandTimeoutMillis: 60_000,
      ide: ideService({
        async handle(_command, signal) {
          await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }))
          streamAborted = true
          return { ok: false, code: 'BUSY', message: 'aborted' }
        },
      }),
      capabilities: {
        'fs.read': async (call, context) => {
          await new Promise<void>((resolve) => {
            context.signal.addEventListener(
              'abort',
              () => {
                capabilityAborted = true
                resolve()
              },
              { once: true },
            )
          })
          // 收敛延迟：验证 termination 只在 Promise.allSettled 之后发布。
          await capabilityGate
          return textResult(call.invocationId)
        },
      },
    })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('IDE_COMMAND', {
        environmentId: ENV,
        invocationId: 'ide-1',
        payload: { action: 'LIST_VIEWS' },
      }),
    )
    setup.transport.receive(
      serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-1', payload: invokePayload('fs.read', 60_000) }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'STARTED'))

    let completed = false
    const completion = setup.runtime.awaitCompletion().then(() => {
      completed = true
    })
    setup.runtime.close()
    await waitFor(() => streamAborted)
    await waitFor(() => capabilityAborted)
    await tick(3)
    expect(completed).toBe(false)

    releaseCapability?.()
    await waitFor(() => completed)
    await completion
  })

  /** 受管更新也有独立 controller；停机必须中止它，并在其收敛后才完成终止。 */
  it('aborts an in-flight managed update on shutdown and awaits its outcome', async () => {
    let updateAborted = false
    let releaseUpdate: (() => void) | null = null
    const updateGate = new Promise<void>((resolve) => {
      releaseUpdate = resolve
    })
    const update: UpdatePort = {
      async handleUpdate(_command, _config, signal) {
        await new Promise<void>((resolve) =>
          signal.addEventListener(
            'abort',
            () => {
              updateAborted = true
              resolve()
            },
            { once: true },
          ),
        )
        await updateGate
        return { phase: 'FAILED', message: 'aborted' }
      },
    }
    const setup = buildRuntime({ update })
    await handshake(setup)
    setup.transport.receive(
      serverMessage('UPDATE', {
        environmentId: ENV,
        payload: {
          operationId: '55555555-4444-4555-8666-777777777777',
          targetVersion: artifactFixture.targetVersion,
          artifactUrl: artifactFixture.accepted[1]!.artifactUrl,
          artifactSha256: artifactFixture.artifactSha256,
        },
      }),
    )
    await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'UPDATE_RESULT'))

    let completed = false
    const completion = setup.runtime.awaitCompletion().then(() => {
      completed = true
    })
    setup.runtime.close()
    await waitFor(() => updateAborted)
    await tick(3)
    expect(completed).toBe(false)

    releaseUpdate?.()
    await waitFor(() => completed)
    await completion
  })

  /**
   * 在途 IDE 请求必须有硬上限。同 ID 重复不占名额，不同 ID 洪泛到上限一律固定 busy，
   * 释放名额后可再接新请求。
   */
  it('bounds in-flight IDE commands, reports busy on overflow and accepts again after release', async () => {
    const releases: Array<() => void> = []
    const ide = ideService({
      async handle(_command, signal) {
        await new Promise<void>((resolve) => {
          releases.push(resolve)
          signal.addEventListener('abort', () => resolve(), { once: true })
        })
        return { ok: true, views: [] }
      },
    })
    const setup = buildRuntime({ ideCommandTimeoutMillis: 60_000, ide })
    await handshake(setup)
    const uuid = (n: number): string => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
    const cap = 256
    const commandPayload = { action: 'LIST_VIEWS' }
    for (let index = 0; index < cap; index++) {
      setup.transport.receive(
        serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: uuid(index), payload: commandPayload }),
      )
    }
    expect(releases.length).toBe(cap)

    // 同 ID 重复不占新名额。
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: uuid(0), payload: commandPayload }),
    )
    await tick()
    expect(releases.length).toBe(cap)

    // 不同 ID 超过上限：固定 busy，绝不执行副作用。
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: uuid(cap), payload: commandPayload }),
    )
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'IDE_RESULT' && envelope.invocationId === uuid(cap),
      ),
    )
    const busy = sent(setup.transport).find((envelope) => envelope.invocationId === uuid(cap))
    expect(busy?.payload).toMatchObject({ ok: false, message: 'too many in-flight IDE commands' })
    expect(releases.length).toBe(cap)

    // 释放一个名额后可再接新请求。
    releases[0]?.()
    await waitFor(() =>
      sent(setup.transport).some(
        (envelope) => envelope.messageType === 'IDE_RESULT' && envelope.invocationId === uuid(0),
      ),
    )
    setup.transport.receive(
      serverMessage('IDE_COMMAND', { environmentId: ENV, invocationId: uuid(cap + 1), payload: commandPayload }),
    )
    await waitFor(() => releases.length === cap + 1)
    setup.runtime.close()
  })
})

describe('runtime execution exit confirmation', () => {
  it('does not ACK until the executor body returns, even after confirmExecutionExit', async () => {
    let entered = false
    const gate = Promise.withResolvers<void>()
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          entered = true
          await gate.promise
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-exit', payload: invokePayload() }))
      // 有界等待 capability body 真正进入，不用固定 tick 数或 STARTED 猜测执行位置。
      await waitFor(() => entered)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      gate.resolve()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      const exited = sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')
      expect(exited).toHaveLength(1)
      expect(exited[0]?.payload).toEqual({})
      const types = sent(setup.transport).map((envelope) => envelope.messageType)
      expect(types.indexOf('COMPLETED')).toBeLessThan(types.indexOf('EXECUTION_EXITED'))
    } finally {
      gate.resolve()
      // close 抛错也必须真正 join，避免执行器仍停留在本次 scope 内。
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('does not ACK a terminal result that never confirmed its own execution scope', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call) => textResult(call.invocationId),
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-plain', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-plain', payload: invokePayload() }))
      // 等重放终态这一具体动作，不用固定 tick 数猜测重复 INVOKE 已处理。
      await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'COMPLETED').length === 2)
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(0)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
    expect(setup.runtime.getJournal().find('inv-plain')?.exitConfirmed).toBe(false)
  })

  it('replays a stored EXECUTION_EXITED independently of the terminal on duplicate INVOKE', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-replay', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      const before = sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED').length
      expect(before).toBe(1)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-replay', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED').length === 2)
      const replayed = sent(setup.transport).filter((envelope) => envelope.invocationId === 'inv-replay')
      expect(replayed.filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(2)
      expect(replayed.filter((envelope) => envelope.messageType === 'COMPLETED')).toHaveLength(2)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('replays a stored EXECUTION_EXITED once on a repeated CANCEL after the terminal', async () => {
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-cancel-replay', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-cancel-replay', payload: {} }))
      setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-cancel-replay', payload: {} }))
      await waitFor(() => sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED').length === 3)
      const replayed = sent(setup.transport).filter((envelope) => envelope.invocationId === 'inv-cancel-replay')
      expect(replayed.filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(3)
      expect(replayed.filter((envelope) => envelope.messageType === 'COMPLETED')).toHaveLength(3)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('ACKs only after a cancelled execution has actually left its scope', async () => {
    let entered = false
    let cancelled = false
    const gate = Promise.withResolvers<void>()
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          context.signal.addEventListener('abort', () => {
            cancelled = true
          }, { once: true })
          entered = true
          await gate.promise
          if (context.signal.aborted) {
            context.confirmExecutionExit()
            return { callId: call.invocationId, error: true, details: {}, contents: [{ type: 'text', text: 'cancelled' }] }
          }
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-cancel', payload: invokePayload() }))
      // 有界等待真实 capability body 与 abort 事件，不用固定 tick 数猜测。
      await waitFor(() => entered)
      setup.transport.receive(serverMessage('CANCEL', { environmentId: ENV, invocationId: 'inv-cancel', payload: {} }))
      await waitFor(() => cancelled)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'CANCELLED')).toBe(false)
      gate.resolve()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      const terminal = sent(setup.transport).find(
        (envelope) =>
          envelope.invocationId === 'inv-cancel' &&
          (envelope.messageType === 'CANCELLED' || envelope.messageType === 'FAILED' || envelope.messageType === 'COMPLETED'),
      )
      expect(terminal?.messageType).toBe('COMPLETED')
      expect(JSON.stringify(terminal?.payload)).toContain('cancelled')
    } finally {
      gate.resolve()
      // close 抛错也必须真正 join，避免执行器仍停留在本次 scope 内。
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('ACKs a late confirmation after the terminal removed the running entry, and only once', async () => {
    // 终态会移出 running。迟到的正向证据必须仍绑定这次调用并发送一次，不能因为 map 已空而丢失。
    let confirm: (() => void) | null = null
    const setup = buildRuntime({
      capabilities: {
        'fs.read': async (call, context) => {
          confirm = () => context.confirmExecutionExit()
          return textResult(call.invocationId)
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-late', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED'))
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      confirm?.()
      confirm?.()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(1)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('does not ACK when owned upload cleanup throws after the executor confirmed', async () => {
    // 正向证据不能抢在本次资源上传之前发出。上传抛错说明收尾未完成，终态之后也不补 ACK。
    const setup = buildRuntime({
      resources: {
        async upload() {
          throw new Error('upload failed')
        },
      },
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return {
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'binary', bytes: Uint8Array.of(1), mediaType: 'application/octet-stream' }],
          }
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-upload', payload: invokePayload() }))
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'FAILED'))
      expect(sent(setup.transport).find((envelope) => envelope.messageType === 'FAILED')?.payload).toEqual({ message: 'upload failed' })
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
    } finally {
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
    // Runtime 已真正收尾；失败的上传不能留下可在重连后重放的退出事实。
    expect(setup.runtime.getJournal().find('inv-upload')?.exitConfirmed).toBe(false)
  })

  it('ACKs only after a successful resource upload, not when confirmExecutionExit returns', async () => {
    // 同一调用的正向对照：上传成功且终态发送后，已留下的证据才变成一次 ACK。
    let uploadEntered = false
    const uploadGate = Promise.withResolvers<void>()
    const setup = buildRuntime({
      resources: {
        async upload() {
          uploadEntered = true
          await uploadGate.promise
          return {
            type: 'resource',
            uploadId: 'up-1',
            mediaType: 'application/octet-stream',
            name: null,
            size: 1,
            sha256: 'abc',
          }
        },
      },
      capabilities: {
        'fs.read': async (call, context) => {
          context.confirmExecutionExit()
          return {
            callId: call.invocationId,
            error: false,
            details: {},
            contents: [{ type: 'binary', bytes: Uint8Array.of(1), mediaType: 'application/octet-stream' }],
          }
        },
      },
    })
    try {
      await handshake(setup)
      setup.transport.receive(serverMessage('INVOKE', { environmentId: ENV, invocationId: 'inv-upload-ok', payload: invokePayload() }))
      // 有界等待真实 uploader 进入，不用 tick 数或终态猜测上传已开始。
      await waitFor(() => uploadEntered)
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      uploadGate.resolve()
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED'))
      const types = sent(setup.transport).map((envelope) => envelope.messageType)
      expect(types.indexOf('COMPLETED')).toBeLessThan(types.indexOf('EXECUTION_EXITED'))
      expect(sent(setup.transport).filter((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toHaveLength(1)
    } finally {
      uploadGate.resolve()
      // close 抛错也必须真正 join，避免执行器仍停留在本次 scope 内。
      try {
        setup.runtime.close()
      } finally {
        await setup.runtime.awaitCompletion()
      }
    }
  })

  it('does not ACK process.exec when the managed scope fails to converge', async () => {
    const setup = buildRuntime({
      capabilities: processCapabilities,
      processes: {
        async start() {
          const stdout = new PassThrough()
          const stderr = new PassThrough()
          stdout.end()
          stderr.end()
          return {
            stdin: new PassThrough(),
            stdout,
            stderr,
            exit: Promise.resolve({ exitCode: 0, signal: null }),
            terminate: async () => {
              throw new Error('scope still running')
            },
          } satisfies ManagedProcess
        },
        async close() {},
      },
    })
    let dir: string | null = null
    try {
      await handshake(setup)
      dir = await mkdtemp(join(tmpdir(), 'kk-exit-process-'))
      dir = await realpath(dir)
      setup.transport.receive(
        serverMessage('INVOKE', {
          environmentId: ENV,
          invocationId: 'inv-process',
          payload: invokePayload('process.exec', 0, { command: 'true', workdir: dir }),
        }),
      )
      await waitFor(() => sent(setup.transport).some((envelope) => envelope.messageType === 'COMPLETED' || envelope.messageType === 'FAILED'))
      expect(sent(setup.transport).some((envelope) => envelope.messageType === 'EXECUTION_EXITED')).toBe(false)
      const terminal = sent(setup.transport).find((envelope) => envelope.messageType === 'FAILED' || envelope.messageType === 'COMPLETED')
      expect(JSON.stringify(terminal?.payload)).toContain('did not converge')
    } finally {
      // close 抛错也必须真正 join；清理目录放在最内层，join 失败也尽量释放。
      try {
        setup.runtime.close()
      } finally {
        try {
          await setup.runtime.awaitCompletion()
        } finally {
          if (dir !== null) {
            await rm(dir, { recursive: true, force: true })
          }
        }
      }
    }
  })
})
