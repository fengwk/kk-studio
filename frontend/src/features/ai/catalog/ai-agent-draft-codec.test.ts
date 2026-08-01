import { describe, expect, it } from 'vitest'
import {
  emptyAgentDraft,
  toAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
} from '@/features/ai/catalog/ai-agent-draft-codec'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import type { AgentDefinitionDTO, AgentModelDTO } from '@/shared/api/contracts/ai-catalog'

function model(): AgentModelDTO {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    name: 'model',
    description: null,
    config: {
      limit: { context: 128000, output: 8192 },
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
      defaultVariant: 'quality',
      variants: [{ id: 'quality' }],
    },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function draft(overrides: Partial<AgentDraft> = {}): AgentDraft {
  return {
    ...emptyAgentDraft(model()),
    name: ' assistant ',
    description: ' description ',
    systemPrompt: ' prompt ',
    variant: 'quality',
    tools: [' read ', 'bash'],
    skills: [' dev '],
    ...overrides,
  }
}

describe('ai-agent-draft-codec', () => {
  it('creates empty drafts from an optional model without inventing a variant', () => {
    expect(emptyAgentDraft(model())).toMatchObject({ modelId: 'model-1', variant: '' })
    expect(emptyAgentDraft()).toMatchObject({ modelId: '', variant: '' })
  })

  it('normalizes short capability names and rejects collisions in create payloads', () => {
    expect(toEditableAgent(draft()).config).toMatchObject({
      tools: ['read', 'bash'],
      skills: ['dev'],
    })
    expect(() => toEditableAgent(draft({ tools: ['read', 'read'] }))).toThrow(
      /名称不能重复/,
    )
  })

  it('projects persisted definitions and normalizes nullable values', () => {
    const agent: AgentDefinitionDTO = {
      id: 'agent-1',
      name: 'assistant',
      description: null,
      systemPrompt: null,
      modelId: 'model-1',
      variant: 'quality',
      config: {
        tools: [' read ', ''],
        skills: [],
      },
      version: '1',
      createTime: null,
      updateTime: null,
    }

    expect(toAgentDraft(agent)).toEqual({
      name: 'assistant',
      description: '',
      systemPrompt: '',
      modelId: 'model-1',
      variant: 'quality',
      tools: ['read'],
      skills: [],
    })

    expect(
      toAgentDraft({
        ...agent,
        variant: null,
      }),
    ).toMatchObject({ variant: '' })
  })

  it('builds complete create and update bodies', () => {
    const expected = {
      name: 'assistant',
      description: 'description',
      systemPrompt: 'prompt',
      modelId: 'model-1',
      variant: 'quality',
      config: {
        tools: ['read', 'bash'],
        skills: ['dev'],
      },
    }
    expect(toEditableAgent(draft())).toEqual(expected)
    expect(toEditableAgentUpdate(draft())).toEqual(expected)

    expect(
      toEditableAgent(
        draft({ description: '', systemPrompt: '' }),
      ),
    ).toMatchObject({
      description: null,
      systemPrompt: null,
      config: { tools: ['read', 'bash'], skills: ['dev'] },
    })
    expect(toEditableAgent(draft({ variant: ' ' })).variant).toBeNull()
  })

  it.each([
    [{ name: ' ' }, /name/],
    [{ modelId: ' ' }, /modelId/],
    [{ tools: ['read', 'read'] }, /tools/],
    [{ skills: ['dev', 'dev'] }, /skills/],
  ] as Array<[Partial<AgentDraft>, RegExp]>)(
    'rejects invalid complete bodies %#',
    (patch, message) => {
      expect(() => toEditableAgent(draft(patch))).toThrow(message)
    },
  )

  it('writes the strict config shape with only tools and skills', () => {
    expect(toEditableAgent(draft()).config).toEqual({
      tools: ['read', 'bash'],
      skills: ['dev'],
    })
  })
})
