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
    environmentId: ' env-uuid-1 ',
    tools: [' read ', 'bash'],
    skills: [{ sourceId: ' src-1 ', name: ' dev ' }],
    subagents: [' helper '],
    ...overrides,
  }
}

describe('ai-agent-draft-codec', () => {
  it('creates empty drafts from an optional model without inventing a variant', () => {
    expect(emptyAgentDraft(model())).toMatchObject({
      model: 'minimax/model',
      variant: '',
      environmentId: '',
      subagents: [],
    })
    expect(emptyAgentDraft()).toMatchObject({ model: '', variant: '', environmentId: '', subagents: [] })
  })

  it('normalizes tool names and capability names and rejects collisions in create payloads', () => {
    expect(toEditableAgent(draft()).config).toMatchObject({
      tools: ['read', 'bash'],
      skills: [{ sourceId: 'src-1', name: 'dev' }],
      subagents: ['helper'],
    })
    expect(() => toEditableAgent(draft({ tools: ['read', 'read'] }))).toThrow(
      /名称不能重复/,
    )
    expect(() => toEditableAgent(draft({ subagents: ['helper', 'helper'] }))).toThrow(
      /名称不能重复/,
    )
    expect(() =>
      toEditableAgent(
        draft({
          skills: [
            { sourceId: 'src-1', name: 'dev' },
            { sourceId: 'src-1', name: 'dev' },
          ],
        }),
      ),
    ).toThrow(/名称不能重复/)
  })

  it('allows same-name skills from different sourceIds and rejects blank components', () => {
    const multiSourceDraft = draft({
      skills: [
        { sourceId: 'src-1', name: 'search' },
        { sourceId: 'src-2', name: 'search' },
      ],
    })
    expect(toEditableAgent(multiSourceDraft).config.skills).toEqual([
      { sourceId: 'src-1', name: 'search' },
      { sourceId: 'src-2', name: 'search' },
    ])

    expect(() =>
      toEditableAgent(draft({ skills: [{ sourceId: '   ', name: 'search' }] })),
    ).toThrow(/skills/)
    expect(() =>
      toEditableAgent(draft({ skills: [{ sourceId: 'src-1', name: '   ' }] })),
    ).toThrow(/skills/)
  })

  it('projects persisted definitions and normalizes nullable values', () => {
    const agent: AgentDefinitionDTO = {
      name: 'assistant',
      description: null,
      systemPrompt: null,
      model: 'minimax/model',
      variant: 'quality',
      environmentId: 'env-uuid-1',
      config: {
        tools: [' read ', ''],
        skills: [{ sourceId: ' src-1 ', name: ' dev ' }],
        subagents: [' writer ', ''],
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
      environmentId: 'env-uuid-1',
      tools: ['read'],
      skills: [{ sourceId: 'src-1', name: 'dev' }],
      subagents: ['writer'],
    })

    expect(() =>
      toAgentDraft({
        ...agent,
        config: {
          ...agent.config,
          skills: [
            { sourceId: 'src-1', name: 'dev' },
            { sourceId: 'src-1', name: 'dev' },
          ],
        },
      }),
    ).toThrow(/名称不能重复/)

    expect(() =>
      toAgentDraft({
        ...agent,
        config: {
          ...agent.config,
          skills: [{ sourceId: ' ', name: 'dev' }],
        },
      }),
    ).toThrow(/skills/)

    expect(
      toAgentDraft({
        ...agent,
        variant: null,
        environmentId: null,
      }),
    ).toMatchObject({ variant: '', environmentId: '' })
  })

  it('builds complete create and update bodies with environmentId', () => {
    const expected = {
      name: 'assistant',
      description: 'description',
      systemPrompt: 'prompt',
      model: 'minimax/model',
      variant: 'quality',
      environmentId: 'env-uuid-1',
      config: {
        tools: ['read', 'bash'],
        skills: [{ sourceId: 'src-1', name: 'dev' }],
        subagents: ['helper'],
      },
    }
    expect(toEditableAgent(draft())).toEqual(expected)
    expect(toEditableAgentUpdate(draft())).toEqual({
      description: 'description',
      systemPrompt: 'prompt',
      model: 'minimax/model',
      variant: 'quality',
      environmentId: 'env-uuid-1',
      config: {
        tools: ['read', 'bash'],
        skills: [{ sourceId: 'src-1', name: 'dev' }],
        subagents: ['helper'],
      },
    })

    expect(
      toEditableAgent(
        draft({ description: '', systemPrompt: '', environmentId: '   ' }),
      ),
    ).toMatchObject({
      description: null,
      systemPrompt: null,
      environmentId: null,
      config: {
        tools: ['read', 'bash'],
        skills: [{ sourceId: 'src-1', name: 'dev' }],
        subagents: ['helper'],
      },
    })
    expect(toEditableAgent(draft({ variant: ' ' })).variant).toBeNull()
  })

  it('preserves embedded newlines in multiline description payloads', () => {
    const multiline = '执行环境内的 shell 命令\n并返回捕获的输出'
    // trimToNull 只裁剪首尾空白，绝不折叠内部换行。
    expect(toEditableAgent(draft({ description: multiline })).description).toBe(multiline)
    expect(
      toEditableAgentUpdate(draft({ description: ` ${multiline}\n ` })).description,
    ).toBe(multiline)
  })

  it.each([
    [{ name: ' ' }, /name/],
    [{ model: ' ' }, /model/],
    [{ tools: ['read', 'read'] }, /tools/],
    [{ skills: [{ sourceId: 'src-1', name: 'dev' }, { sourceId: 'src-1', name: 'dev' }] }, /skills/],
    [{ skills: [{ sourceId: '', name: 'dev' }] }, /skills/],
    [{ skills: [{ sourceId: 'src-1', name: '' }] }, /skills/],
    [{ subagents: ['helper', 'helper'] }, /subagents/],
  ] as Array<[Partial<AgentDraft>, RegExp]>)(
    'rejects invalid complete bodies %#',
    (patch, message) => {
      expect(() => toEditableAgent(draft(patch))).toThrow(message)
      if ('model' in patch) {
        expect(() => toEditableAgentUpdate(draft(patch))).toThrow(message)
      }
    },
  )

  it('writes the strict config shape with only tools, skills, and subagents', () => {
    expect(toEditableAgent(draft()).config).toEqual({
      tools: ['read', 'bash'],
      skills: [{ sourceId: 'src-1', name: 'dev' }],
      subagents: ['helper'],
    })
  })
})
