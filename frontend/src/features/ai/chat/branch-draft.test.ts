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
  toolIds: string[],
  skills: string[],
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
    workspacePath: null,
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

describe('workspacePath semantics in BranchDraft and Agent switching', () => {
  function settingsWith(workspacePath: string | null): HarnessBranchSettingsDTO {
    return {
      workspacePath,
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    }
  }

  it('converts HarnessBranchSettingsDTO to BranchDraft via branchDraftFromBranchSettings', () => {
    const draft = branchDraftFromBranchSettings(settingsWith('proj/a'), true)
    expect(draft).toEqual({
      workspacePath: 'proj/a',
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      yoloEnabled: true,
    })
  })

  it('materializes workspacePath when agent has environmentId, but forces null when agent has no environmentId', () => {
    const withEnv = agent(['read'], [], [], 'env-uuid-1')
    const noEnv = agent(['read'], [], [], null)

    const materializedWithEnv = materializeBlankBranchDraft(
      withEnv,
      true,
      [model],
      'proj/a',
    )
    expect(materializedWithEnv?.workspacePath).toBe('proj/a')

    const materializedNoEnv = materializeBlankBranchDraft(
      noEnv,
      true,
      [model],
      'proj/a',
    )
    // 无 environmentId 的 Agent 强制为 null 工作目录
    expect(materializedNoEnv?.workspacePath).toBeNull()
  })

  it('resets workspacePath to null when switching to an agent with a different environmentId or no environmentId', () => {
    const envAgent1 = { ...agent([], [], [], 'env-uuid-1'), name: 'agent1' }
    const envAgent2 = { ...agent([], [], [], 'env-uuid-2'), name: 'agent2' }
    const envAgent1Clone = { ...agent([], [], [], 'env-uuid-1'), name: 'agent1-alt' }
    const noEnvAgent = { ...agent([], [], [], null), name: 'no-env-agent' }

    const existingDraft = draftWith({
      agentName: 'agent1',
      workspacePath: 'my-proj',
    })

    // 1. 切换到不同 environmentId 的 agent -> workspacePath 归零
    const switchedDifferent = materializeAgentBranchDraft(
      envAgent2,
      [model],
      existingDraft,
      false,
      null,
      envAgent1,
    )
    expect(switchedDifferent?.workspacePath).toBeNull()

    // 2. 切换到无 environmentId 的 agent -> workspacePath 归零
    const switchedToNoEnv = materializeAgentBranchDraft(
      noEnvAgent,
      [model],
      existingDraft,
      false,
      null,
      envAgent1,
    )
    expect(switchedToNoEnv?.workspacePath).toBeNull()

    // 3. 切换到相同 environmentId 的 agent -> 保留已有 workspacePath
    const switchedSame = materializeAgentBranchDraft(
      envAgent1Clone,
      [model],
      existingDraft,
      false,
      null,
      envAgent1,
    )
    expect(switchedSame?.workspacePath).toBe('my-proj')
  })

  it('compares workspacePath in branchDraftsEqual', () => {
    expect(branchDraftsEqual(draftWith({ workspacePath: 'proj/a' }), draftWith({ workspacePath: 'proj/a' }))).toBe(true)
    expect(branchDraftsEqual(draftWith({ workspacePath: 'proj/a' }), draftWith({ workspacePath: 'proj/b' }))).toBe(false)
    expect(branchDraftsEqual(draftWith({ workspacePath: 'proj/a' }), draftWith({ workspacePath: null }))).toBe(false)
    expect(branchDraftsEqual(draftWith({ workspacePath: null }), draftWith({ workspacePath: null }))).toBe(true)
  })

  it('emits SET_ENVIRONMENT with the exact workspacePath payload and skips equal workspacePath', () => {
    const ids = (() => {
      let next = 0
      return () => `cid-${++next}`
    })()
    const commands = buildBranchDiffCommands(
      draftWith({ workspacePath: 'proj/a' }),
      draftWith({ workspacePath: 'proj/b' }),
      ids,
    )
    expect(commands).toHaveLength(1)
    expect(commands[0]).toEqual({
      type: 'SET_ENVIRONMENT',
      idempotencyKey: 'cid-1',
      workspacePath: 'proj/b',
    })

    const cleared = buildBranchDiffCommands(draftWith({ workspacePath: 'proj/a' }), draftWith({ workspacePath: null }), ids)
    expect(cleared[0]).toEqual({
      type: 'SET_ENVIRONMENT',
      idempotencyKey: 'cid-2',
      workspacePath: null,
    })

    expect(buildBranchDiffCommands(draftWith({ workspacePath: 'proj/a' }), draftWith({ workspacePath: 'proj/a' }), ids)).toEqual([])
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
        workspacePath: 'proj/a',
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
    expect(commands.some((command) => command.type === 'SET_YOLO')).toBe(false)
  })

  it('projects a queued SET_ENVIRONMENT payload as workspacePath', () => {
    const base = draftWith({ workspacePath: null })
    const queued: HarnessThreadCommandDTO[] = [
      queuedSettingCommand('1', 'SET_ENVIRONMENT', { workspacePath: 'proj/sub' }),
      queuedSettingCommand('2', 'SET_ENVIRONMENT', { workspacePath: null }),
    ]
    expect(projectPendingTarget(base, queued).workspacePath).toBeNull()

    const onlyFirst = projectPendingTarget(base, [queued[0]!])
    expect(onlyFirst.workspacePath).toBe('proj/sub')
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
