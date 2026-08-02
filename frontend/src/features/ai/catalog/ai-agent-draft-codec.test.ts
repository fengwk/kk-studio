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
    providerName: 'minimax',
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
    expect(emptyAgentDraft(model())).toMatchObject({ model: 'minimax/model', variant: '' })
    expect(emptyAgentDraft()).toMatchObject({ model: '', variant: '' })
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
      name: 'assistant',
      description: null,
      systemPrompt: null,
      model: 'minimax/model',
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
      model: 'minimax/model',
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
      model: 'minimax/model',
      variant: 'quality',
      config: {
        tools: ['read', 'bash'],
        skills: ['dev'],
      },
    }
    expect(toEditableAgent(draft())).toEqual(expected)
    expect(toEditableAgentUpdate(draft())).toEqual({
      description: 'description',
      systemPrompt: 'prompt',
      variant: 'quality',
      config: {
        tools: ['read', 'bash'],
        skills: ['dev'],
      },
    })

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
    [{ model: ' ' }, /model/],
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
