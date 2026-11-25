import { describe, expect, it } from 'vitest'
import {
  branchDraftFromBranchSettings,
  branchDraftsEqual,
  buildBranchDiffCommands,
  compareDecimalStrings,
  projectPendingTarget,
} from '@/features/thread/thread-settings'
import type { BranchDraft } from '@/features/thread/thread-settings'
import { createBuiltinExecution } from '@/shared/api/contracts/ai-runtime'
import type { HarnessBranchSettingsDTO, HarnessThreadCommandDTO, ThreadCommandType } from '@/shared/api/generated/contracts'

function draftWith(
  overrides: Partial<BranchDraft> = {},
): BranchDraft {
  return {
    agentName: 'assistant',
    execution: createBuiltinExecution('MiniMax', 'default'),
    environmentName: null,
    yoloEnabled: false,
    ...overrides,
  }
}

function queuedSettingCommand(
  sequence: string,
  type: ThreadCommandType,
  payload: Record<string, unknown>,
): HarnessThreadCommandDTO {
  return {
    threadId: 't1',
    sequence,
    type,
    state: 'QUEUED',
    idempotencyKey: `queued-${sequence}`,
    payload,
    cancelledAt: null,
    createdAt: 0,
  }
}

describe('BranchDraft conversion and diff semantics', () => {
  function createSettings(): HarnessBranchSettingsDTO {
    return {
      agentName: 'assistant',
      execution: createBuiltinExecution('MiniMax', 'default'),
      environmentName: null,
    }
  }

  it('converts HarnessBranchSettingsDTO to BranchDraft via branchDraftFromBranchSettings', () => {
    const draft = branchDraftFromBranchSettings(createSettings(), true)
    expect(draft).toEqual({
      agentName: 'assistant',
      execution: createBuiltinExecution('MiniMax', 'default'),
      environmentName: null,
      yoloEnabled: true,
    })
  })

  it('compares fields in branchDraftsEqual', () => {
    expect(branchDraftsEqual(draftWith({ agentName: 'a' }), draftWith({ agentName: 'a' }))).toBe(true)
    expect(branchDraftsEqual(draftWith({ agentName: 'a' }), draftWith({ agentName: 'b' }))).toBe(false)
    expect(branchDraftsEqual(draftWith({ yoloEnabled: true }), draftWith({ yoloEnabled: false }))).toBe(false)
    expect(branchDraftsEqual(draftWith({ environmentName: 'env-1' }), draftWith({ environmentName: 'env-1' }))).toBe(true)
    expect(branchDraftsEqual(draftWith({ environmentName: 'env-1' }), draftWith({ environmentName: 'env-2' }))).toBe(false)
    expect(branchDraftsEqual(draftWith({ environmentName: 'env-1' }), draftWith({ environmentName: null }))).toBe(false)
  })

  it('emits only surviving durable settings in the branch diff', () => {
    const ids = (() => {
      let next = 0
      return () => `cid-${++next}`
    })()
    expect(buildBranchDiffCommands(draftWith(), draftWith({ agentName: 'coder' }), ids)).toEqual([])
    expect(buildBranchDiffCommands(
      draftWith(),
      draftWith({ execution: createBuiltinExecution('MiniMax', 'pro') }),
      ids,
    )).toEqual([{
      type: 'SET_EXECUTION',
      idempotencyKey: 'cid-1',
      execution: createBuiltinExecution('MiniMax', 'pro'),
    }])
  })

  /**
   * 测试意图：验证 buildBranchDiffCommands 支持仅 environment 变更，以及从非 null 到 null 的解绑清除。
   */
  it('emits SET_ENVIRONMENT for environment changes including clearing to null', () => {
    const ids = (() => {
      let next = 0
      return () => `cid-${++next}`
    })()
    // 仅 environment 变更：从 null 到 'docker-env'
    const envAdded = buildBranchDiffCommands(
      draftWith({ environmentName: null }),
      draftWith({ environmentName: 'docker-env' }),
      ids,
    )
    expect(envAdded).toEqual([{
      type: 'SET_ENVIRONMENT',
      idempotencyKey: 'cid-1',
      environmentName: 'docker-env',
    }])

    // 从 non-null 到 null 解除环境
    const envCleared = buildBranchDiffCommands(
      draftWith({ environmentName: 'docker-env' }),
      draftWith({ environmentName: null }),
      ids,
    )
    expect(envCleared).toEqual([{
      type: 'SET_ENVIRONMENT',
      idempotencyKey: 'cid-2',
      environmentName: null,
    }])
  })

  it('emits every settings diff in the fixed order and never a SET_YOLO command', () => {
    const ids = (() => {
      let next = 0
      return () => `cid-${++next}`
    })()
    const commands = buildBranchDiffCommands(
      draftWith({ environmentName: 'old-env' }),
      draftWith({
        agentName: 'coder',
        execution: createBuiltinExecution('Other', 'v2'),
        environmentName: 'new-env',
        yoloEnabled: true,
      }),
      ids,
    )
    expect(commands.map((command) => command.type)).toEqual([
      'SET_EXECUTION',
      'SET_ENVIRONMENT',
    ])
    expect(commands[1]).toEqual({
      type: 'SET_ENVIRONMENT',
      idempotencyKey: 'cid-2',
      environmentName: 'new-env',
    })
    const commandTypes: string[] = commands.map((command) => command.type)
    expect(commandTypes).not.toContain('SET_YOLO')
  })
})

describe('projectPendingTarget setting projection', () => {
  it('projects surviving settings by sequence order while preserving persistent agentName', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('2', 'SET_ENVIRONMENT', { environmentName: 'new-env' }),
      queuedSettingCommand('1', 'SET_EXECUTION', { execution: createBuiltinExecution('Other', 'v2') }),
    ])
    expect(projected.agentName).toBe(base.agentName)
    expect(projected.execution).toEqual(createBuiltinExecution('Other', 'v2'))
    expect(projected.environmentName).toBe('new-env')
  })

  it('ignores non-SET_ and non-QUEUED commands while keeping base values', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'USER_MESSAGE', { message: { role: 'USER' } }),
      queuedSettingCommand('2', 'SET_EXECUTION', { execution: createBuiltinExecution('Other', null) }),
      {
        ...queuedSettingCommand('3', 'SET_EXECUTION', { execution: createBuiltinExecution('applied', null) }),
        state: 'APPLIED',
      },
    ])
    expect(projected.agentName).toBe(base.agentName)
    expect(projected.execution).toEqual(createBuiltinExecution('Other', null))
  })

  it('keeps the base model fields when a SET_EXECUTION payload is not a record', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_EXECUTION', { execution: 'minimax/MiniMax' }),
    ])
    expect(projected.execution).toEqual(base.execution)
  })

  it('fills missing SET_EXECUTION fields from the base selection', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_EXECUTION', {
        execution: { driver: 'builtin', settings: { modelName: 'New' } },
      }),
    ])
    expect(projected.execution).toEqual(createBuiltinExecution('New', 'default'))
  })

  /**
   * 测试意图：验证 projectPendingTarget 投影 SET_ENVIRONMENT：
   * - 字符串文本值选中该环境；
   * - 显式 null 清除该环境（置为 null）；
   * - 缺少键、非字符串且非 null（如数值、对象、undefined 等）保持原有 base 值不变；
   * - 仅作用于 QUEUED 状态命令，并严格按 sequence 升序应用。
   */
  it('projects queued SET_ENVIRONMENT string text and explicit null, and ignores malformed payload', () => {
    const base = draftWith({ environmentName: 'base-env' })
    // 字符串设置环境
    const setProjected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_ENVIRONMENT', { environmentName: 'new-env' }),
    ])
    expect(setProjected.environmentName).toBe('new-env')

    // 显式 null 清空环境
    const clearedProjected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_ENVIRONMENT', { environmentName: null }),
    ])
    expect(clearedProjected.environmentName).toBeNull()

    // 畸形载荷保持原值不变
    for (const malformed of [
      {}, // 缺少 environmentName 键
      { environmentName: 123 }, // 数值
      { environmentName: { name: 'nested' } }, // 对象
      { environmentName: undefined }, // undefined
    ]) {
      const untouched = projectPendingTarget(base, [
        queuedSettingCommand('1', 'SET_ENVIRONMENT', malformed),
      ])
      expect(untouched.environmentName).toBe('base-env')
    }

    // 按 sequence 顺序应用多条命令（sequence 1 为 env-1，sequence 2 清空为 null）
    const sequential = projectPendingTarget(base, [
      queuedSettingCommand('2', 'SET_ENVIRONMENT', { environmentName: null }),
      queuedSettingCommand('1', 'SET_ENVIRONMENT', { environmentName: 'env-1' }),
    ])
    expect(sequential.environmentName).toBeNull()
  })

  it('treats an unknown command type as a no-op', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_UNKNOWN' as ThreadCommandType, { agentName: 'coder' }),
    ])
    expect(projected).toEqual(base)
  })
})

describe('compareDecimalStrings', () => {
  it('orders decimal command sequences numerically', () => {
    expect(compareDecimalStrings('1', '2')).toBe(-1)
    expect(compareDecimalStrings('2', '1')).toBe(1)
    expect(compareDecimalStrings('10', '9')).toBe(1)
    expect(compareDecimalStrings('42', '42')).toBe(0)
  })

  it('rejects non-canonical sequences instead of ordering them silently', () => {
    // 输入只来自服务端 canonical 十进制字符串；非法值必须暴露，而不是当成相等。
    expect(() => compareDecimalStrings('abc', '42')).toThrow()
  })
})
