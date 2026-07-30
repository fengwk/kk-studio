import { describe, expect, it } from 'vitest'
import {
  emptyAgentDraft,
  normalizeCapabilityShortNames,
  stripCapabilityPrefix,
  toAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
} from '@/features/ai/catalog/ai-agent-draft-codec'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import type { AgentDefinitionDTO, AgentModelDTO } from '@/shared/api/contracts'

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
    environmentName: ' local ',
    tools: ['platform/read', 'local/bash'],
    skills: ['platform/dev'],
    ...overrides,
  }
}

describe('ai-agent-draft-codec', () => {
  it('creates empty drafts from an optional model without inventing a variant', () => {
    expect(emptyAgentDraft(model())).toMatchObject({ modelId: 'model-1', variant: '' })
    expect(emptyAgentDraft()).toMatchObject({ modelId: '', variant: '' })
  })

  it('normalizes short capability names and rejects collisions', () => {
    expect(stripCapabilityPrefix('')).toBe('')
    expect(stripCapabilityPrefix(' read ')).toBe('read')
    expect(stripCapabilityPrefix('local/bash')).toBe('bash')
    expect(normalizeCapabilityShortNames(null, 'tools')).toEqual([])
    expect(normalizeCapabilityShortNames(['platform/read', ' local/bash ', ''], 'tools')).toEqual([
      'read',
      'bash',
    ])
    expect(() => normalizeCapabilityShortNames(['a/read', 'b/read'], 'tools')).toThrow(/重名/)
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
        environmentName: null,
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
      environmentName: '',
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
        environmentName: 'local',
        tools: ['read', 'bash'],
        skills: ['dev'],
      },
    }
    expect(toEditableAgent(draft())).toEqual(expected)
    expect(toEditableAgentUpdate(draft())).toEqual(expected)

    expect(
      toEditableAgent(
        draft({ description: '', systemPrompt: '', environmentName: '' }),
      ),
    ).toMatchObject({
      description: null,
      systemPrompt: null,
      config: { environmentName: null },
    })
    expect(toEditableAgent(draft({ variant: ' ' })).variant).toBeNull()
  })

  it.each([
    [{ name: ' ' }, /name/],
    [{ modelId: ' ' }, /modelId/],
    [{ tools: ['one/read', 'two/read'] }, /tools/],
    [{ skills: ['one/dev', 'two/dev'] }, /skills/],
  ] as Array<[Partial<AgentDraft>, RegExp]>)(
    'rejects invalid complete bodies %#',
    (patch, message) => {
      expect(() => toEditableAgent(draft(patch))).toThrow(message)
    },
  )

  it('omits an unset environmentName entirely from the wire payload', () => {
    expect(toEditableAgent(draft({ environmentName: '' })).config).toEqual({
      environmentName: null,
      tools: ['read', 'bash'],
      skills: ['dev'],
    })
  })
})
