import { describe, expect, it } from 'vitest'
import {
  emptyAgentDraft,
  toAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
} from '@/features/catalog/ai-agent-draft-codec'
import type { AgentDraft } from '@/features/catalog/ai-console-types'
import type { AgentDTO, AgentModelDTO } from '@/shared/api/generated/contracts'

function model(): AgentModelDTO {
  return {
    providerName: 'minimax',
    kind: 'MODEL',
    name: 'model',
    modelId: 'model-id',
    description: null,
    config: {
      limit: { context: 128000, output: 8192 },
      abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
      pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
      defaultVariant: 'quality',
      variants: [{ id: 'quality', promptCacheRetention: 'NONE' }],
    },
    version: '1',
    createTime: 0,
    updateTime: 0,
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
    skills: [{ packageName: 'core', name: 'dev' }],
    subagents: [' helper '],
    ...overrides,
  }
}

describe('ai-agent-draft-codec', () => {
  it('creates empty drafts from an optional model without inventing a variant', () => {
    expect(emptyAgentDraft(model())).toMatchObject({
      model: 'model',
      variant: '',
      subagents: [],
      skills: [],
    })
    expect(emptyAgentDraft()).toMatchObject({
      model: '',
      variant: '',
      subagents: [],
      skills: [],
    })
  })

  it('normalizes tool names, skills, and capability names and rejects collisions in create payloads', () => {
    expect(toEditableAgent(draft()).config).toMatchObject({
      tools: ['read', 'bash'],
      skills: [{ packageName: 'core', name: 'dev' }],
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
            { packageName: 'core', name: 'dev' },
            { packageName: 'core', name: 'dev' },
          ],
        }),
      ),
    ).toThrow(/名称不能重复/)
  })

  it('projects persisted definitions and normalizes nullable values', () => {
    const agent: AgentDTO = {
      name: 'assistant',
      description: null,
      systemPrompt: null,
      type: 'USER',
      execution: { driver: 'builtin', settings: { modelName: 'model', variant: 'quality' } },
      config: {
        tools: [' read ', ''],
        skills: [{ packageName: ' core ', name: ' dev ' }],
        subagents: [' writer ', ''],
      },
      version: '1',
      createdAt: 0,
      updatedAt: 0,
    }

    expect(toAgentDraft(agent)).toEqual({
      name: 'assistant',
      description: '',
      systemPrompt: '',
      execution: { driver: 'builtin', settings: { modelName: 'model', variant: 'quality' } },
      model: 'model',
      variant: 'quality',
      tools: ['read'],
      skills: [{ packageName: 'core', name: 'dev' }],
      subagents: ['writer'],
    })

    expect(
      toAgentDraft({
        ...agent,
        execution: { driver: 'builtin', settings: { modelName: 'model', variant: null } },
      }),
    ).toMatchObject({ variant: '' })
  })

  it('builds complete create and update bodies without environmentId', () => {
    const expected = {
      name: 'assistant',
      description: 'description',
      systemPrompt: 'prompt',
      execution: { driver: 'builtin', settings: { modelName: 'model', variant: 'quality' } },
      config: {
        tools: ['read', 'bash'],
        skills: [{ packageName: 'core', name: 'dev' }],
        subagents: ['helper'],
      },
    }
    expect(toEditableAgent(draft())).toEqual(expected)
    expect(toEditableAgentUpdate(draft())).toEqual({
      description: 'description',
      systemPrompt: 'prompt',
      execution: { driver: 'builtin', settings: { modelName: 'model', variant: 'quality' } },
      config: {
        tools: ['read', 'bash'],
        skills: [{ packageName: 'core', name: 'dev' }],
        subagents: ['helper'],
      },
    })

    expect(
      toEditableAgent(
        draft({ description: '', systemPrompt: '' }),
      ),
    ).toMatchObject({
      description: null,
      systemPrompt: null,
      config: {
        tools: ['read', 'bash'],
        skills: [{ packageName: 'core', name: 'dev' }],
        subagents: ['helper'],
      },
    })
    expect(toEditableAgent(draft({ variant: ' ' })).execution).toEqual({
      driver: 'builtin', settings: { modelName: 'model', variant: null },
    })
  })

  it('preserves unknown driver settings until explicitly changed', () => {
    const execution = { driver: 'future', settings: { opaque: 'value' } }
    expect(toEditableAgentUpdate(draft({ execution, model: '' })).execution).toEqual(execution)
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
    [{ skills: [{ packageName: 'core', name: 'dev' }, { packageName: 'core', name: 'dev' }] }, /skills/],
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

  it('writes the strict config shape with tools, skills, and subagents', () => {
    expect(toEditableAgent(draft()).config).toEqual({
      tools: ['read', 'bash'],
      skills: [{ packageName: 'core', name: 'dev' }],
      subagents: ['helper'],
    })
  })

  it('projects an unconfigured built-in model as empty and only BUILTIN may write null without leaking type', () => {
    const builtin: AgentDTO = {
      name: 'compaction',
      description: null,
      systemPrompt: null,
      type: 'BUILTIN',
      execution: null,
      config: {
        tools: [],
        skills: [],
        subagents: [],
      },
      version: '1',
      createdAt: 0,
      updatedAt: 0,
    }

    // 未配置模型投影为空字符串，绝不回退到父/第一个 model。
    expect(toAgentDraft(builtin).model).toBe('')

    // USER 路径仍要求模型：默认允许空模型导致抛错，且请求体从不包含 type。
    expect(() => toEditableAgentUpdate(toAgentDraft(builtin))).toThrow(/model/)
    const builtinUpdate = toEditableAgentUpdate(toAgentDraft(builtin), true)
    expect(builtinUpdate.execution).toBeNull()
    expect(builtinUpdate).not.toHaveProperty('type')
  })
})
