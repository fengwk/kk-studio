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
  AgentSkillRefDTO,
} from '@/shared/api/contracts/ai-catalog'
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

const modelWithoutDefaultVariant: AgentModelDTO = {
  ...model,
  config: {
    ...model.config,
    defaultVariant: '',
    variants: [],
  },
}

const modelWithoutCatalogEntry: AgentModelDTO = {
  ...model,
  providerName: 'another',
  name: 'other-model',
}

function agent(
  tools: string[],
  skills: AgentSkillRefDTO[],
  subagents: string[],
  environmentId: string | null = 'env-uuid-1',
): AgentDefinitionDTO {
  return {
    name: 'root',
    description: null,
    systemPrompt: null,
    model: 'provider/model',
    variant: null,
    environmentId,
    config: { inheritParentEnvironment: true, tools, skills, subagents },
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
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    environmentName: null,
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
    cancelledAt: null,
    createTime: null,
  }
}

describe('BranchDraft materialization failures', () => {
  it('returns null when the Agent is missing or has no model ref', () => {
    expect(materializeBlankBranchDraft(undefined, false, [model])).toBeNull()
    expect(materializeBlankBranchDraft(agentWithoutModel(), false, [model])).toBeNull()
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
    const missingVariantAgent = { ...withUnknownVariant, variant: 'missing' }
    expect(materializeBlankBranchDraft(missingVariantAgent, false, [model])).toBeNull()
  })
})

describe('BranchDraft conversion and diff semantics', () => {
  function createSettings(): HarnessBranchSettingsDTO {
    return {
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
    }
  }

  it('converts HarnessBranchSettingsDTO to BranchDraft via branchDraftFromBranchSettings', () => {
    const draft = branchDraftFromBranchSettings(createSettings(), true)
    expect(draft).toEqual({
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
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

  /**
   * 测试意图：验证切换 Agent 时保留已有 draft 的 environmentName，新构建 draft 则默认为 null。
   */
  it('preserves existing environmentName when switching agent via materializeAgentBranchDraft', () => {
    const rootAgent = agent([], [], [])
    // 初始没有 existing 时默认为 null
    const fresh = materializeAgentBranchDraft(rootAgent, [model], null)
    expect(fresh?.environmentName).toBeNull()

    // 存在已有 draft 时切换 agent 保持已选 environmentName 不变
    const existing = draftWith({
      environmentName: 'my-custom-env',
      model: { providerName: 'provider', modelName: 'model', variant: 'default' },
    })
    const switched = materializeAgentBranchDraft(rootAgent, [model], existing)
    expect(switched?.environmentName).toBe('my-custom-env')

    // 即使 existing 的 model 处于未就绪/空状态，environmentName 依然保留
    const existingEmptyModel = draftWith({
      environmentName: 'preserved-env',
      model: { providerName: '', modelName: '', variant: '' },
    })
    const switchedFromEmpty = materializeAgentBranchDraft(rootAgent, [model], existingEmptyModel)
    expect(switchedFromEmpty?.environmentName).toBe('preserved-env')
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
        model: { providerName: 'other', modelName: 'Other', variant: 'v2' },
        environmentName: 'new-env',
        yoloEnabled: true,
      }),
      ids,
    )
    expect(commands.map((command) => command.type)).toEqual([
      'SET_AGENT',
      'SET_MODEL',
      'SET_ENVIRONMENT',
    ])
    expect(commands[2]).toEqual({
      type: 'SET_ENVIRONMENT',
      idempotencyKey: 'cid-3',
      environmentName: 'new-env',
    })
    expect(commands.some((command) => command.type === 'SET_YOLO')).toBe(false)
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
