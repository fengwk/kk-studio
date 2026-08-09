import { describe, expect, it } from 'vitest'
import {
  activeToolsFromAgent,
  materializeBlankBranchDraft,
} from '@/features/ai/chat/branch-draft'
import type {
  AgentDefinitionDTO,
  AgentModelDTO,
} from '@/shared/api/contracts/ai-catalog'

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
