import { describe, expect, it } from 'vitest'
import {
  branchDraftFromBranchSettings,
  branchDraftsEqual,
  buildBranchDiffCommands,
  compareDecimalStrings,
  materializeAgentBranchDraft,
  materializeBlankBranchDraft,
  projectPendingTarget,
} from '@/features/ai/chat/branch-draft'
import type {
  AgentDefinitionDTO,
  AgentModelDTO,
} from '@/shared/api/contracts/ai-catalog'
import type {
  EnvironmentBindingDTO,
} from '@/shared/api/contracts/ai-environment'
import type {
  HarnessBranchSettingsDTO,
  HarnessThreadCommandDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'

const model: AgentModelDTO = {
  providerName: 'provider',
  name: 'model',
  description: null,
  config: {
    limit: { context: 32_000, output: 4_000 },
    abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
    pricing: {
      currency: 'USD',
      pricingTier: 'default',
      serviceTier: 'default',
      serviceTierMultiplier: 1,
      version: 'v1',
      inputPerMillionTokens: 0,
      outputPerMillionTokens: 0,
      cacheReadPerMillionTokens: 0,
      cacheWritePerMillionTokens: 0,
      cacheWriteLongPerMillionTokens: 0,
      reasoningPerMillionTokens: 0,
    },
    defaultVariant: 'default',
    variants: [{ id: 'default' }],
  },
  version: '1',
  createTime: '2026-08-09T00:00:00Z',
  updateTime: '2026-08-09T00:00:00Z',
}

/** defaultVariant 缺失且 variants 为空：materialize 必须失败而不是产出空选择。 */
const modelWithoutDefaultVariant: AgentModelDTO = {
  ...model,
  config: {
    ...model.config,
    defaultVariant: '',
    variants: [],
  },
}

/** model ref 解析不到：agent 在 catalog 中不可用（如已删除）。 */
const modelWithoutCatalogEntry: AgentModelDTO = {
  ...model,
  providerName: 'another',
  name: 'other-model',
}

function agent(
  toolIds: string[],
  skills: string[],
  subagents: string[],
): AgentDefinitionDTO {
  return {
    name: 'root',
    description: null,
    systemPrompt: null,
    model: 'provider/model',
    variant: null,
    config: { toolIds, skills, subagents },
    version: '1',
    createTime: '2026-08-09T00:00:00Z',
    updateTime: '2026-08-09T00:00:00Z',
  }
}

function agentWithoutModel(): AgentDefinitionDTO {
  return { ...agent([], [], []), model: '' }
}

function draftWith(
  overrides: Partial<BranchDraft> = {},
): BranchDraft {
  return {
    environment: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    yoloEnabled: false,
    ...overrides,
  }
}

function queuedSettingCommand(
  sequence: string,
  type: string,
  payload: Record<string, unknown>,
): HarnessThreadCommandDTO {
  return {
    threadId: 't1',
    sequence,
    type,
    state: 'QUEUED',
    idempotencyKey: `queued-${sequence}`,
    payloadJson: JSON.stringify(payload),
    appliedTurnStartEntryId: null,
    cancelledAt: null,
    createTime: null,
  }
}

describe('BranchDraft materialization failures', () => {
  it('returns null when the Agent is missing or has no model ref', () => {
    expect(materializeBlankBranchDraft(undefined, false, [model])).toBeNull()
    expect(materializeBlankBranchDraft(agentWithoutModel(), false, [model])).toBeNull()
    // materializeAgentBranchDraft 同样遵循完整 materialization 规则。
    expect(materializeAgentBranchDraft(agentWithoutModel(), [model], null)).toBeNull()
  })

  it('returns null when the Agent model ref has no catalog entry', () => {
    const missing = agent([], [], [])
    expect(materializeBlankBranchDraft(missing, false, [modelWithoutCatalogEntry])).toBeNull()
    expect(materializeAgentBranchDraft(missing, [modelWithoutCatalogEntry], null)).toBeNull()
  })

  it('returns null when the Agent variant or the model defaultVariant is unresolvable', () => {
    const withUnknownVariant = agent([], [], [])
    expect(
      materializeBlankBranchDraft(withUnknownVariant, false, [modelWithoutDefaultVariant]),
    ).toBeNull()
    // 显式指定 variants 中不存在的 variant 同样失败。
    const missingVariantAgent = { ...withUnknownVariant, variant: 'missing' }
    expect(materializeBlankBranchDraft(missingVariantAgent, false, [model])).toBeNull()
  })
})

describe('EnvironmentBinding atomic semantics in BranchDraft', () => {
  const binding: EnvironmentBindingDTO = { name: 'local', workspacePath: 'proj/a' }
  const otherBinding: EnvironmentBindingDTO = { name: 'local', workspacePath: 'proj/b' }

  function draftWithBinding(environment: EnvironmentBindingDTO | null): BranchDraft {
    return draftWith({ environment })
  }

  function settingsWith(environment: EnvironmentBindingDTO | null): HarnessBranchSettingsDTO {
    return {
      environment,
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    }
  }

  it('materializes the whole binding from Chat defaults and from branch settings', () => {
    const materialized = materializeBlankBranchDraft(
      agent(['read'], [], []),
      true,
      [model],
      binding,
    )
    expect(materialized?.environment).toEqual(binding)
    // 复制而非共享引用：后续编辑不会反向影响调用方对象。
    expect(materialized?.environment).not.toBe(binding)

    const rematerialized = materializeAgentBranchDraft(
      agent(['read'], [], []),
      [model],
      null,
      true,
      binding,
    )
    expect(rematerialized?.yoloEnabled).toBe(true)
    expect(rematerialized?.environment).toEqual(binding)
    expect(rematerialized?.environment).not.toBe(binding)

    const fromSettings = branchDraftFromBranchSettings(settingsWith(binding), false)
    expect(fromSettings.environment).toEqual(binding)
    expect(fromSettings.environment).not.toBe(binding)
    expect(branchDraftFromBranchSettings(settingsWith(null), false).environment).toBeNull()
  })

  it('compares the entire binding atomically (name or workspacePath changes)', () => {
    expect(branchDraftsEqual(draftWithBinding(binding), draftWithBinding({ ...binding }))).toBe(true)
    expect(branchDraftsEqual(draftWithBinding(binding), draftWithBinding(otherBinding))).toBe(false)
    expect(branchDraftsEqual(draftWithBinding(binding), draftWithBinding(null))).toBe(false)
    expect(branchDraftsEqual(draftWithBinding(null), draftWithBinding(null))).toBe(true)
    expect(branchDraftsEqual(draftWithBinding(null), draftWithBinding({ name: 'local', workspacePath: '.' }))).toBe(false)
  })

  it('emits SET_ENVIRONMENT with the exact whole-binding payload and skips equal bindings', () => {
    const ids = (() => {
      let next = 0
      return () => `cid-${++next}`
    })()
    const commands = buildBranchDiffCommands(
      draftWithBinding(binding),
      draftWithBinding(otherBinding),
      ids,
    )
    expect(commands).toHaveLength(1)
    expect(commands[0]).toEqual({
      type: 'SET_ENVIRONMENT',
      idempotencyKey: 'cid-1',
      environment: { name: 'local', workspacePath: 'proj/b' },
    })
    // 显式清空：payload.environment 为 null，绝不携带裸 name。
    const cleared = buildBranchDiffCommands(draftWithBinding(binding), draftWithBinding(null), ids)
    expect(cleared[0]).toEqual({
      type: 'SET_ENVIRONMENT',
      idempotencyKey: 'cid-2',
      environment: null,
    })
    // 相同 binding（即使不同对象引用）不产生 diff。
    expect(buildBranchDiffCommands(draftWithBinding(binding), draftWithBinding({ ...binding }), ids)).toEqual([])
  })

  it('emits SET_AGENT/SET_MODEL only for the actually changed field', () => {
    const ids = (() => {
      let next = 0
      return () => `cid-${++next}`
    })()
    const commands = buildBranchDiffCommands(
      draftWith(),
      draftWith({ agentName: 'coder' }),
      ids,
    )
    expect(commands).toEqual([{
      type: 'SET_AGENT',
      idempotencyKey: 'cid-1',
      agentName: 'coder',
    }])

    const modelChanged = buildBranchDiffCommands(
      draftWith(),
      draftWith({ model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'pro' } }),
      ids,
    )
    expect(modelChanged).toEqual([{
      type: 'SET_MODEL',
      idempotencyKey: 'cid-2',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'pro' },
    }])

  })

  it('emits every settings diff in the fixed order and never a SET_YOLO command', () => {
    const ids = (() => {
      let next = 0
      return () => `cid-${++next}`
    })()
    const commands = buildBranchDiffCommands(
      draftWith(),
      draftWith({
        environment: binding,
        agentName: 'coder',
        model: { providerName: 'other', modelName: 'Other', variant: 'v2' },
        yoloEnabled: true,
      }),
      ids,
    )
    expect(commands.map((command) => command.type)).toEqual([
      'SET_ENVIRONMENT',
      'SET_AGENT',
      'SET_MODEL',
    ])
    // YOLO 是 Thread 级直接控制面：diff 命令中绝不出现 SET_YOLO。
    expect(commands.some((command) => command.type === 'SET_YOLO')).toBe(false)
  })

  it('projects a queued SET_ENVIRONMENT payload as the whole binding', () => {
    const base = draftWithBinding(null)
    const queued: HarnessThreadCommandDTO[] = [
      queuedSettingCommand('1', 'SET_ENVIRONMENT', { environment: otherBinding }),
      queuedSettingCommand('2', 'SET_ENVIRONMENT', { environment: null }),
    ]
    expect(projectPendingTarget(base, queued).environment).toBeNull()

    const onlyFirst = projectPendingTarget(base, [queued[0]!])
    expect(onlyFirst.environment).toEqual(otherBinding)
    // 复制而非共享引用：草稿后续编辑不会反向污染队列命令对象。
    expect(onlyFirst.environment).not.toBe(otherBinding)
    // 非法/不完整 binding 绝不把 name 单独透传：保持 base 不变。
    const malformed = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_ENVIRONMENT', { environment: { name: 'local' } }),
    ])
    expect(malformed.environment).toBeNull()
    // payload.environment 非对象（如字符串）同样保持 base 不变。
    const notRecord = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_ENVIRONMENT', { environment: 'local' }),
    ])
    expect(notRecord.environment).toBeNull()
  })
})

describe('projectPendingTarget setting projection', () => {
  it('projects queued SET_AGENT / SET_MODEL by sequence order', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('2', 'SET_MODEL', {
        model: { providerName: 'other', modelName: 'Other', variant: 'v2' },
      }),
      queuedSettingCommand('1', 'SET_AGENT', { agentName: 'coder' }),
    ])
    expect(projected.agentName).toBe('coder')
    expect(projected.model).toEqual({ providerName: 'other', modelName: 'Other', variant: 'v2' })
  })

  it('ignores non-SET_ and non-QUEUED commands while keeping base values', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'USER_MESSAGE', { message: { role: 'USER' } }),
      queuedSettingCommand('2', 'SET_AGENT', { agentName: 'coder' }),
      {
        ...queuedSettingCommand('3', 'SET_AGENT', { agentName: 'applied' }),
        state: 'APPLIED',
      },
    ])
    expect(projected.agentName).toBe('coder')
  })

  it('keeps the base model fields when a SET_MODEL payload is not a record', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_MODEL', { model: 'minimax/MiniMax' }),
    ])
    expect(projected.model).toEqual(base.model)
  })

  it('fills missing SET_MODEL fields from the base selection', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_MODEL', { model: { modelName: 'New' } }),
    ])
    expect(projected.model).toEqual({
      providerName: 'minimax',
      modelName: 'New',
      variant: 'default',
    })
  })

  it('treats an unknown command type as a no-op', () => {
    const base = draftWith()
    const projected = projectPendingTarget(base, [
      queuedSettingCommand('1', 'SET_UNKNOWN', { agentName: 'coder' }),
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

  it('treats non-numeric sequences as equal', () => {
    expect(compareDecimalStrings('abc', '42')).toBe(0)
  })
})
