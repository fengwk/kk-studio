import { create } from '@bufbuild/protobuf'
import { describe, expect, it } from 'vitest'

import type { DaemonConfig, IdeService } from '../src/contracts.js'
import {
  EnvelopeSchema,
  MessageType,
  UpdatePhase,
  UpdateSchema,
  type Envelope,
  type RuntimeSnapshot,
  type Update,
  type UpdateResult,
} from '../src/generated/control_pb.js'
import type { UpdatePort } from '../src/runtime-ports.js'
import { UpdateRuntime, type UpdateRuntimeOptions } from '../src/runtime-update.js'

function makeCommand(overrides: Partial<Update> = {}): Update {
  return create(UpdateSchema, {
    operationId: 'op-1',
    targetVersion: '1.0.1',
    ...overrides,
  })
}

function makeUpdateEnvelope(command: Update, environmentId = 'env-test'): Envelope {
  return create(EnvelopeSchema, {
    messageType: MessageType.UPDATE,
    environmentId,
    payload: {
      case: 'update',
      value: command,
    },
  })
}

interface TestContext {
  runtime: UpdateRuntime
  sentResults: UpdateResult[]
  sentStatuses: RuntimeSnapshot[]
  trackedPromises: Promise<void>[]
  diagnostics: { phase: string; error: unknown }[]
  options: UpdateRuntimeOptions
  flushTracks(): Promise<void>
}

function createTestRuntime(overrides: Partial<UpdateRuntimeOptions> = {}): TestContext {
  const sentResults: UpdateResult[] = []
  const sentStatuses: RuntimeSnapshot[] = []
  const trackedPromises: Promise<void>[] = []
  const diagnostics: { phase: string; error: unknown }[] = []

  const outbound = {
    isReady: true,
    canSend: true,
    sendUpdateResult: (result: UpdateResult) => {
      sentResults.push(result)
      return true
    },
    sendRuntimeStatus: (status: RuntimeSnapshot) => {
      sentStatuses.push(status)
      return true
    },
  }

  const defaultUpdate: UpdatePort = {
    handleUpdate: async () => ({ phase: 'PREPARED' as const, message: 'ready' }),
  }

  const defaultIde = {
    stopForUpdate: async () => {},
    canStartUpdate: () => true,
  }

  const options: UpdateRuntimeOptions = {
    update: defaultUpdate,
    ide: defaultIde as unknown as IdeService,
    config: {} as DaemonConfig,
    daemonVersion: '1.0.0',
    outbound: outbound as unknown as UpdateRuntimeOptions['outbound'],
    hasProtectedExecutions: () => false,
    ideCommandCount: () => 0,
    track: (promise: Promise<void>) => {
      trackedPromises.push(promise)
    },
    collectDiagnostic: (phase: string, error: unknown) => {
      diagnostics.push({ phase, error })
    },
    ...overrides,
  }

  const runtime = new UpdateRuntime(options)

  return {
    runtime,
    sentResults,
    sentStatuses,
    trackedPromises,
    diagnostics,
    options,
    async flushTracks() {
      let index = 0
      while (index < trackedPromises.length) {
        await trackedPromises[index]
        index++
      }
    },
  }
}

describe('UpdateRuntime', () => {
  describe('UpdateRuntime 公开接口更新执行与回执流转', () => {
    it('准入并成功准备：先停止 IDE，依次产出 ACCEPTED 与 PREPARED 回执并持有门闩', async () => {
      let ideStopped = false
      let permitReleased = false

      const ctx = createTestRuntime({
        ide: {
          stopForUpdate: async () => {
            ideStopped = true
          },
          canStartUpdate: () => true,
        } as unknown as IdeService,
        update: {
          handleUpdate: async () => ({
            phase: 'PREPARED' as const,
            message: 'ready',
            releasePermit: async () => {
              permitReleased = true
            },
          }),
        },
      })

      const cmd = makeCommand({ operationId: 'op-1', targetVersion: '1.0.1' })
      ctx.runtime.handle(makeUpdateEnvelope(cmd))

      // 准入时立即发送 ACCEPTED 回执并持有门闩
      expect(ctx.sentResults.length).toBe(1)
      expect(ctx.sentResults[0]?.phase).toBe(UpdatePhase.UPDATE_ACCEPTED)
      expect(ctx.sentResults[0]?.operationId).toBe('op-1')
      expect(ctx.runtime.isAdmissionHeld).toBe(true)

      // 后台执行完成
      await ctx.flushTracks()
      expect(ideStopped).toBe(true)
      expect(permitReleased).toBe(true)
      expect(ctx.sentResults.length).toBe(2)
      expect(ctx.sentResults[1]?.phase).toBe(UpdatePhase.UPDATE_PREPARED)
      expect(ctx.sentResults[1]?.message).toBe('ready')
      expect(ctx.runtime.isAdmissionHeld).toBe(true) // PREPARED 维持门闩持有
    })

    it('更新器明确返回 FAILED 时产出 UPDATE_FAILED 回执并释放门闩', async () => {
      const ctx = createTestRuntime({
        update: {
          handleUpdate: async () => ({ phase: 'FAILED' as const, message: 'checksum mismatch' }),
        },
      })

      ctx.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-fail' })))
      expect(ctx.sentResults[0]?.phase).toBe(UpdatePhase.UPDATE_ACCEPTED)

      await ctx.flushTracks()
      expect(ctx.sentResults.length).toBe(2)
      expect(ctx.sentResults[1]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctx.sentResults[1]?.message).toBe('checksum mismatch')
      expect(ctx.runtime.isAdmissionHeld).toBe(false)
    })

    it('更新器抛出 Error 时产出带对应错误消息的 UPDATE_FAILED 回执并释放门闩', async () => {
      // 抛出普通 download failed 错误
      const ctx1 = createTestRuntime({
        update: {
          handleUpdate: async () => {
            throw new Error('download failed')
          },
        },
      })
      ctx1.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-err-download' })))
      await ctx1.flushTracks()
      expect(ctx1.sentResults[1]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctx1.sentResults[1]?.message).toBe('download failed')
      expect(ctx1.runtime.isAdmissionHeld).toBe(false)

      // 抛出 disk full 错误
      const ctx2 = createTestRuntime({
        update: {
          handleUpdate: async () => {
            throw new Error('disk full')
          },
        },
      })
      ctx2.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-err-disk' })))
      await ctx2.flushTracks()
      expect(ctx2.sentResults[1]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctx2.sentResults[1]?.message).toBe('disk full')
      expect(ctx2.runtime.isAdmissionHeld).toBe(false)
    })

    it('更新器抛出空白错误时产出兜底安全消息 capability execution failed', async () => {
      const ctx = createTestRuntime({
        update: {
          handleUpdate: async () => {
            throw new Error('   ')
          },
        },
      })
      ctx.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-empty-err' })))
      await ctx.flushTracks()
      expect(ctx.sentResults[1]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctx.sentResults[1]?.message).toBe('capability execution failed')
    })

  })

  describe('UpdateRuntime 公开接口准入判定与参数一致性 (decideUpdate)', () => {
    it('active 状态下的相同参数重发、参数漂移、同 target 刷新与不同 target 忙碌拒绝', async () => {
      // 1. 已 prepared 的 active 相同参数重放
      const ctxPrepared = createTestRuntime()
      const cmd = makeCommand({ operationId: 'op-active', targetVersion: '1.0.1' })
      ctxPrepared.runtime.handle(makeUpdateEnvelope(cmd))
      await ctxPrepared.flushTracks()
      expect(ctxPrepared.sentResults.length).toBe(2) // ACCEPTED + PREPARED

      // 再次发送相同 command -> 重发 PREPARED 回执
      ctxPrepared.runtime.handle(makeUpdateEnvelope(cmd))
      expect(ctxPrepared.sentResults.length).toBe(3)
      expect(ctxPrepared.sentResults[2]?.phase).toBe(UpdatePhase.UPDATE_PREPARED)

      // 2. 相同 operationId 但参数漂移 -> 抛出错误
      expect(() => {
        ctxPrepared.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-active', targetVersion: '1.0.2' })))
      }).toThrow(/the same update operationId must not change its arguments/)

      // 3. active 处于执行中（未 prepared）时相同 operationId -> 刷新状态，不重复执行
      let resolveUpdate!: () => void
      const pendingPromise = new Promise<{ phase: 'PREPARED'; message: string }>((r) => {
        resolveUpdate = () => r({ phase: 'PREPARED', message: 'ready' })
      })
      const ctxPending = createTestRuntime({
        update: { handleUpdate: () => pendingPromise },
      })
      const cmdPending = makeCommand({ operationId: 'op-pending', targetVersion: '1.0.1' })
      ctxPending.runtime.handle(makeUpdateEnvelope(cmdPending))
      expect(ctxPending.sentResults.length).toBe(1) // 准入 ACCEPTED
      const statusCountBefore = ctxPending.sentStatuses.length

      ctxPending.runtime.handle(makeUpdateEnvelope(cmdPending))
      expect(ctxPending.sentResults.length).toBe(1) // 不产生重复回执
      expect(ctxPending.sentStatuses.length).toBe(statusCountBefore + 1) // 刷新状态

      // 4. active 执行中时不同 operationId 但相同 targetVersion -> 刷新状态
      const diffOpSameTarget = makeCommand({ operationId: 'op-pending-2', targetVersion: '1.0.1' })
      ctxPending.runtime.handle(makeUpdateEnvelope(diffOpSameTarget))
      expect(ctxPending.sentResults.length).toBe(1)
      expect(ctxPending.sentStatuses.length).toBe(statusCountBefore + 2)

      // 5. active 执行中时不同 operationId 且不同 targetVersion -> reject 忙碌
      const diffTarget = makeCommand({ operationId: 'op-diff-target', targetVersion: '1.0.2' })
      ctxPending.runtime.handle(makeUpdateEnvelope(diffTarget))
      expect(ctxPending.sentResults.length).toBe(2)
      expect(ctxPending.sentResults[1]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctxPending.sentResults[1]?.message).toBe('update is busy for target 1.0.1')

      resolveUpdate()
      await ctxPending.flushTracks()
    })

    it('已完成回执缓存命中回放与缓存参数漂移校验', async () => {
      const ctx = createTestRuntime({
        update: {
          handleUpdate: async () => ({ phase: 'FAILED' as const, message: 'checksum mismatch' }),
        },
      })
      const cmd = makeCommand({ operationId: 'op-cached', targetVersion: '1.0.1' })
      ctx.runtime.handle(makeUpdateEnvelope(cmd))
      await ctx.flushTracks()
      expect(ctx.runtime.isAdmissionHeld).toBe(false)
      expect(ctx.sentResults.length).toBe(2) // ACCEPTED + FAILED

      // 缓存命中回放相同结果
      ctx.runtime.handle(makeUpdateEnvelope(cmd))
      expect(ctx.sentResults.length).toBe(3)
      expect(ctx.sentResults[2]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctx.sentResults[2]?.message).toBe('checksum mismatch')

      // 相同 operationId 但目标版本漂移 -> 抛出错误
      expect(() => {
        ctx.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-cached', targetVersion: '1.0.2' })))
      }).toThrow(/the same update operationId must not change its arguments/)
    })

    it('当前版本无须更新、各种忙碌原因、未配置更新器及正常准入', () => {
      // 1. 已经是当前版本 -> 只刷新状态，不启动更新
      const ctxSameVer = createTestRuntime({ daemonVersion: '1.0.0' })
      ctxSameVer.runtime.handle(makeUpdateEnvelope(makeCommand({ targetVersion: '1.0.0' })))
      expect(ctxSameVer.sentResults.length).toBe(0)
      expect(ctxSameVer.sentStatuses.length).toBe(1)

      // 2. 忙碌原因：受保护执行进行中
      const ctxBusyProtected = createTestRuntime({ hasProtectedExecutions: () => true })
      ctxBusyProtected.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-busy-1' })))
      expect(ctxBusyProtected.sentResults[0]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctxBusyProtected.sentResults[0]?.message).toBe('update is busy: protected execution is still running')

      // 3. 忙碌原因：IDE 命令进行中
      const ctxBusyIdeCmd = createTestRuntime({ ideCommandCount: () => 1 })
      ctxBusyIdeCmd.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-busy-2' })))
      expect(ctxBusyIdeCmd.sentResults[0]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctxBusyIdeCmd.sentResults[0]?.message).toBe('update is busy: IDE command is in progress')

      // 4. 忙碌原因：IDE 视图/状态不允许更新
      const ctxBusyIdeState = createTestRuntime({
        ide: {
          canStartUpdate: () => false,
          stopForUpdate: async () => {},
        } as unknown as IdeService,
      })
      ctxBusyIdeState.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-busy-3' })))
      expect(ctxBusyIdeState.sentResults[0]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctxBusyIdeState.sentResults[0]?.message).toBe('update is busy: IDE views, cleanup or data streams are active')

      // 5. 未配置受管更新
      const ctxUnconfigured = createTestRuntime({ update: null })
      ctxUnconfigured.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-unconf' })))
      expect(ctxUnconfigured.sentResults[0]?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctxUnconfigured.sentResults[0]?.message).toBe('managed update is not configured')

      // 6. 正常准入
      const ctxAdmit = createTestRuntime()
      ctxAdmit.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-admit', targetVersion: '1.0.1' })))
      expect(ctxAdmit.runtime.isAdmissionHeld).toBe(true)
      expect(ctxAdmit.sentResults[0]?.phase).toBe(UpdatePhase.UPDATE_ACCEPTED)
    })
  })

  describe('UpdateRuntime 公开接口历史回执管理与重发', () => {
    it('按插入顺序淘汰超出上限的受管更新回执', () => {
      // 在未配置更新模式下，每个请求都会被 reject 并写入 results 缓存（容量上限为 8）
      const ctx = createTestRuntime({ update: null })
      for (let i = 0; i < 12; i++) {
        const cmd = makeCommand({ operationId: `op-${i}`, targetVersion: '1.0.1' })
        ctx.runtime.handle(makeUpdateEnvelope(cmd))
      }

      // op-11 应当在缓存中：尝试使用漂移参数重新调用，必须抛出参数漂移错误
      expect(() => {
        ctx.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-11', targetVersion: '1.0.2' })))
      }).toThrow(/the same update operationId must not change its arguments/)

      // op-4 是 12 条中最旧的一条保留项（12 - 8 = 4），也必须在缓存中
      expect(() => {
        ctx.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-4', targetVersion: '1.0.2' })))
      }).toThrow(/the same update operationId must not change its arguments/)

      // op-0 到 op-3 应当已经被淘汰：使用漂移参数重新调用时不会抛出参数漂移错误，而是被作为全新请求处理
      expect(() => {
        ctx.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-0', targetVersion: '1.0.2' })))
      }).not.toThrow()
    })

    it('resendResults 重发选择：优先 active，否则重发最近一条完成回执，无历史时不发送', async () => {
      // 1. 无任何历史与 active 时：不发送任何回执
      const ctxEmpty = createTestRuntime()
      ctxEmpty.runtime.resendResults()
      expect(ctxEmpty.sentResults.length).toBe(0)

      // 2. 有 active 正在执行时：重发 active 的当前回执 (ACCEPTED)
      let resolveUpdate!: () => void
      const pendingPromise = new Promise<{ phase: 'PREPARED'; message: string }>((r) => {
        resolveUpdate = () => r({ phase: 'PREPARED', message: 'ready' })
      })
      const ctxActive = createTestRuntime({
        update: { handleUpdate: () => pendingPromise },
      })
      ctxActive.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-resend-active' })))
      expect(ctxActive.sentResults.length).toBe(1) // ACCEPTED
      ctxActive.runtime.resendResults()
      expect(ctxActive.sentResults.length).toBe(2)
      expect(ctxActive.sentResults[1]?.phase).toBe(UpdatePhase.UPDATE_ACCEPTED)
      resolveUpdate()
      await ctxActive.flushTracks()

      // 3. active 为 null 但有缓存历史时：重发最近一条已完成回执
      const ctxCached = createTestRuntime({
        update: {
          handleUpdate: async () => ({ phase: 'FAILED' as const, message: 'connection lost' }),
        },
      })
      ctxCached.runtime.handle(makeUpdateEnvelope(makeCommand({ operationId: 'op-cached-fail' })))
      await ctxCached.flushTracks()
      expect(ctxCached.runtime.isAdmissionHeld).toBe(false)
      const countBefore = ctxCached.sentResults.length

      ctxCached.runtime.resendResults()
      expect(ctxCached.sentResults.length).toBe(countBefore + 1)
      expect(ctxCached.sentResults.at(-1)?.phase).toBe(UpdatePhase.UPDATE_FAILED)
      expect(ctxCached.sentResults.at(-1)?.message).toBe('connection lost')
    })

    it('协议前置检查：未就绪连接或缺少 environmentId 时拒绝 handle', () => {
      const ctx = createTestRuntime()
      const outbound = ctx.options.outbound as { isReady: boolean }

      // 未就绪连接
      outbound.isReady = false
      expect(() => {
        ctx.runtime.handle(makeUpdateEnvelope(makeCommand()))
      }).toThrow(/requires a READY bound connection/)

      // 缺少 environmentId
      outbound.isReady = true
      const envelopeWithoutEnv = create(EnvelopeSchema, {
        messageType: MessageType.UPDATE,
        payload: {
          case: 'update',
          value: makeCommand(),
        },
      })
      expect(() => {
        ctx.runtime.handle(envelopeWithoutEnv)
      }).toThrow(/requires a READY bound connection/)
    })
  })
})
