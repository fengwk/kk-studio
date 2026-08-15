import { describe, expect, it } from 'vitest'
import {
  activeToolsFromAgent,
  branchDraftFromBranchSettings,
  branchDraftsEqual,
  buildBranchDiffCommands,
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

function agent(
  tools: string[],
  skills: string[],
  subagents: string[],
): AgentDefinitionDTO {
  return {
    name: 'root',
    description: null,
    systemPrompt: null,
    model: 'provider/model',
    variant: null,
    config: { tools, skills, subagents },
    version: '1',
    createTime: '2026-08-09T00:00:00Z',
    updateTime: '2026-08-09T00:00:00Z',
  }
}

describe('activeToolsFromAgent', () => {
  it('adds the internal skill and task tools from Agent capabilities exactly once', () => {
    expect(
      activeToolsFromAgent(
        agent(['read', 'load_skill', 'task'], ['review'], ['coder']),
      ),
    ).toEqual(['read', 'load_skill', 'task'])
  })

  it('keeps internal tools absent when the corresponding capability list is empty', () => {
    expect(activeToolsFromAgent(agent(['read'], [], []))).toEqual(['read'])
  })

  it('uses the complete derived tool set when materializing a root Thread branch', () => {
    expect(
      materializeBlankBranchDraft(
        agent(['read'], ['review'], ['coder']),
        false,
        [model],
      )?.activeTools,
    ).toEqual(['read', 'load_skill', 'task'])
  })
})

describe('EnvironmentBinding atomic semantics in BranchDraft', () => {
  const binding: EnvironmentBindingDTO = { name: 'local', workspacePath: 'proj/a' }
  const otherBinding: EnvironmentBindingDTO = { name: 'local', workspacePath: 'proj/b' }

  function draftWith(environment: EnvironmentBindingDTO | null): BranchDraft {
    return {
      environment,
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      activeTools: [],
      yoloEnabled: false,
    }
  }

  function settingsWith(environment: EnvironmentBindingDTO | null): HarnessBranchSettingsDTO {
    return {
      environment,
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      activeTools: [],
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

    const fromSettings = branchDraftFromBranchSettings(settingsWith(binding), false)
    expect(fromSettings.environment).toEqual(binding)
    expect(fromSettings.environment).not.toBe(binding)
    expect(branchDraftFromBranchSettings(settingsWith(null), false).environment).toBeNull()
  })

  it('compares the entire binding atomically (name or workspacePath changes)', () => {
    expect(branchDraftsEqual(draftWith(binding), draftWith({ ...binding }))).toBe(true)
    expect(branchDraftsEqual(draftWith(binding), draftWith(otherBinding))).toBe(false)
    expect(branchDraftsEqual(draftWith(binding), draftWith(null))).toBe(false)
    expect(branchDraftsEqual(draftWith(null), draftWith(null))).toBe(true)
    expect(branchDraftsEqual(draftWith(null), draftWith({ name: 'local', workspacePath: '.' }))).toBe(false)
  })

  it('emits SET_ENVIRONMENT with the exact whole-binding payload and skips equal bindings', () => {
    const ids = (() => {
      let next = 0
      return () => `cid-${++next}`
    })()
    const commands = buildBranchDiffCommands(
      draftWith(binding),
      draftWith(otherBinding),
      ids,
    )
    expect(commands).toHaveLength(1)
    expect(commands[0]).toEqual({
      type: 'SET_ENVIRONMENT',
      clientCommandId: 'cid-1',
      environment: { name: 'local', workspacePath: 'proj/b' },
    })
    // 显式清空：payload.environment 为 null，绝不携带裸 name。
    const cleared = buildBranchDiffCommands(draftWith(binding), draftWith(null), ids)
    expect(cleared[0]).toEqual({
      type: 'SET_ENVIRONMENT',
      clientCommandId: 'cid-2',
      environment: null,
    })
    // 相同 binding（即使不同对象引用）不产生 diff。
    expect(buildBranchDiffCommands(draftWith(binding), draftWith({ ...binding }), ids)).toEqual([])
  })

  it('projects a queued SET_ENVIRONMENT payload as the whole binding', () => {
    const base = draftWith(null)
    const queued: HarnessThreadCommandDTO[] = [
      queuedSettingCommand('1', { environment: otherBinding }),
      queuedSettingCommand('2', { environment: null }),
    ]
    expect(projectPendingTarget(base, queued).environment).toBeNull()

    const onlyFirst = projectPendingTarget(base, [queued[0]!])
    expect(onlyFirst.environment).toEqual(otherBinding)
    // 复制而非共享引用：草稿后续编辑不会反向污染队列命令对象。
    expect(onlyFirst.environment).not.toBe(otherBinding)
    // 非法/不完整 binding 绝不把 name 单独透传：保持 base 不变。
    const malformed = projectPendingTarget(base, [
      queuedSettingCommand('1', { environment: { name: 'local' } }),
    ])
    expect(malformed.environment).toBeNull()
  })
})

function queuedSettingCommand(
  sequence: string,
  payload: Record<string, unknown>,
): HarnessThreadCommandDTO {
  return {
    threadId: 't1',
    sequence,
    type: 'SET_ENVIRONMENT',
    state: 'QUEUED',
    clientCommandId: `queued-${sequence}`,
    requestHash: '0123456789abcdef'.repeat(4),
    payloadJson: JSON.stringify(payload),
    consumedTurnStartEntryId: null,
    cancelledAt: null,
    createTime: null,
  }
}
