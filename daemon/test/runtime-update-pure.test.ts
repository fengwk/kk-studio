import { create } from '@bufbuild/protobuf'
import { describe, expect, it } from 'vitest'

import {
  UpdatePhase,
  UpdateSchema,
  type Update,
} from '../src/generated/control_pb.js'
import {
  acceptedReceipt,
  decideUpdate,
  executionFailureReceipt,
  executeUpdateAttempt,
  failedReceipt,
  normalizeUpdateResult,
  preparedReceipt,
  receiptToResend,
  rememberUpdateResult,
  updateCommandKey,
  type ActiveUpdate,
  type UpdateReceipt,
} from '../src/runtime-update.js'

function makeCommand(overrides: Partial<Update> = {}): Update {
  return create(UpdateSchema, {
    operationId: 'op-1',
    targetVersion: '6.0.1',
    artifactUrl: 'https://example.com/v6.0.1.tar.gz',
    artifactSha256: 'a'.repeat(64),
    ...overrides,
  })
}

describe('runtime-update.ts pure unit tests', () => {
  it('handles update receipts and outcome recording', async () => {
    const cmd = makeCommand()
    const accepted = acceptedReceipt(cmd)
    expect(accepted.result.phase).toBe(UpdatePhase.UPDATE_ACCEPTED)

    const failed = failedReceipt(cmd, 'bad error')
    expect(failed.result.phase).toBe(UpdatePhase.UPDATE_FAILED)
    expect(failed.result.message).toBe('bad error')

    const active: ActiveUpdate = {
      operationId: 'op-1',
      commandKey: updateCommandKey(cmd),
      targetVersion: '6.0.1',
      controller: new AbortController(),
      receipt: accepted,
      prepared: false,
    }

    const prepared = preparedReceipt(active, cmd, { phase: 'PREPARED', message: 'ready' })
    expect(prepared.result.phase).toBe(UpdatePhase.UPDATE_PREPARED)

    const execFailed = executionFailureReceipt(active, cmd, new Error('download failed'))
    expect(execFailed.result.message).toBe('download failed')

    const execFailedUnknown = executionFailureReceipt(active, cmd, null)
    expect(execFailedUnknown.result.message).toBe('update failed')

    const execFailedEmpty = executionFailureReceipt(active, cmd, new Error('   '))
    expect(execFailedEmpty.result.message).toBe('capability execution failed')

    const normalizedFailed = normalizeUpdateResult({ phase: 'FAILED', message: 'err' }, 'op-1')
    expect(normalizedFailed.phase).toBe(UpdatePhase.UPDATE_FAILED)

    // 测试 executeUpdateAttempt 成功与失败
    let ideStopped = false
    const mockIde = {
      stopForUpdate: async () => {
        ideStopped = true
      },
    }
    const mockUpdate = {
      handleUpdate: async () => ({ phase: 'PREPARED' as const, message: 'ready' }),
    }
    const execSuccess = await executeUpdateAttempt(active, cmd, mockUpdate as any, mockIde, {} as any, () => {})
    expect(ideStopped).toBe(true)
    expect(execSuccess.prepared?.phase).toBe('PREPARED')

    const mockUpdateFailed = {
      handleUpdate: async () => ({ phase: 'FAILED' as const, message: 'checksum mismatch' }),
    }
    const execFailedResult = await executeUpdateAttempt(active, cmd, mockUpdateFailed as any, null, {} as any, () => {})
    expect(execFailedResult.failure?.message).toBe('checksum mismatch')

    const mockUpdateThrow = {
      handleUpdate: async () => {
        throw new Error('disk full')
      },
    }
    const execThrowResult = await executeUpdateAttempt(active, cmd, mockUpdateThrow as any, null, {} as any, () => {})
    expect(execThrowResult.failure?.message).toBe('disk full')
  })

  describe('decideUpdate', () => {
    it('handles active update with same or different parameters', () => {
      const cmd = makeCommand()
      const active: ActiveUpdate = {
        operationId: 'op-1',
        commandKey: updateCommandKey(cmd),
        targetVersion: '6.0.1',
        controller: new AbortController(),
        receipt: acceptedReceipt(cmd),
        prepared: true,
      }

      // 相同的 operationId 且已 prepared -> replay-active
      const replay = decideUpdate({
        command: cmd,
        active,
        cached: undefined,
        currentVersion: '6.0.0',
        busyReason: null,
        configured: true,
      })
      expect(replay.kind).toBe('replay-active')

      // 相同的 operationId 但参数变化 -> 抛出错误
      const drifted = makeCommand({ targetVersion: '6.0.2' })
      expect(() =>
        decideUpdate({
          command: drifted,
          active,
          cached: undefined,
          currentVersion: '6.0.0',
          busyReason: null,
          configured: true,
        }),
      ).toThrow(/must not change its arguments/)

      // 不同的 operationId 但相同的 targetVersion -> status
      const diffOpSameTarget = makeCommand({ operationId: 'op-2' })
      const statusRes = decideUpdate({
        command: diffOpSameTarget,
        active,
        cached: undefined,
        currentVersion: '6.0.0',
        busyReason: null,
        configured: true,
      })
      expect(statusRes.kind).toBe('status')

      // 不同的 operationId 且不同的 targetVersion -> reject (busy)
      const diffTarget = makeCommand({ operationId: 'op-2', targetVersion: '6.0.2' })
      const rejectBusy = decideUpdate({
        command: diffTarget,
        active,
        cached: undefined,
        currentVersion: '6.0.0',
        busyReason: null,
        configured: true,
      })
      expect(rejectBusy.kind).toBe('reject')
    })

    it('handles cached update and arguments drift check', () => {
      const cmd = makeCommand()
      const cached = acceptedReceipt(cmd)

      // 缓存命中回放
      const replay = decideUpdate({
        command: cmd,
        active: null,
        cached,
        currentVersion: '6.0.0',
        busyReason: null,
        configured: true,
      })
      expect(replay.kind).toBe('replay-cached')

      // 缓存参数漂移
      const drifted = makeCommand({ targetVersion: '6.0.2' })
      expect(() =>
        decideUpdate({
          command: drifted,
          active: null,
          cached,
          currentVersion: '6.0.0',
          busyReason: null,
          configured: true,
        }),
      ).toThrow(/must not change its arguments/)
    })

    it('handles current version, busy reason, not configured and normal admit', () => {
      const cmd = makeCommand({ targetVersion: '6.0.0' })
      // 已经是当前版本
      expect(
        decideUpdate({
          command: cmd,
          active: null,
          cached: undefined,
          currentVersion: '6.0.0',
          busyReason: null,
          configured: true,
        }).kind,
      ).toBe('status')

      // 忙碌
      const cmdNew = makeCommand({ targetVersion: '6.0.1' })
      expect(
        decideUpdate({
          command: cmdNew,
          active: null,
          cached: undefined,
          currentVersion: '6.0.0',
          busyReason: 'running tests',
          configured: true,
        }).kind,
      ).toBe('reject')

      // 未配置
      expect(
        decideUpdate({
          command: cmdNew,
          active: null,
          cached: undefined,
          currentVersion: '6.0.0',
          busyReason: null,
          configured: false,
        }).kind,
      ).toBe('reject')

      // 正常准入
      const admit = decideUpdate({
        command: cmdNew,
        active: null,
        cached: undefined,
        currentVersion: '6.0.0',
        busyReason: null,
        configured: true,
      })
      expect(admit.kind).toBe('admit')
    })
  })

  describe('rememberUpdateResult and receiptToResend', () => {
    it('manages LRU eviction of remembered results', () => {
      const results = new Map<string, UpdateReceipt>()
      for (let i = 0; i < 12; i++) {
        const cmd = makeCommand({ operationId: `op-${i}` })
        rememberUpdateResult(results, `op-${i}`, acceptedReceipt(cmd))
      }
      expect(results.size).toBe(8) // 上限 8 条
      expect(results.has('op-0')).toBe(false)
      expect(results.has('op-11')).toBe(true)
    })

    it('selects receipt to resend', () => {
      const cmd = makeCommand()
      const receipt = acceptedReceipt(cmd)
      const active: ActiveUpdate = {
        operationId: 'op-1',
        commandKey: updateCommandKey(cmd),
        targetVersion: '6.0.1',
        controller: new AbortController(),
        receipt,
        prepared: true,
      }

      // 优先重发 active
      expect(receiptToResend(active, new Map())).toBe(receipt)

      // active 为 null 时重发最近的一条
      const map = new Map<string, UpdateReceipt>()
      expect(receiptToResend(null, map)).toBeNull()

      map.set('op-1', receipt)
      expect(receiptToResend(null, map)).toBe(receipt)
    })
  })
})
