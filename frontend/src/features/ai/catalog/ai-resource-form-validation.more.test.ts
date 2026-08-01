import { describe, expect, it } from 'vitest'
import { toUserFacingErrorMessage } from '@/features/ai/ai-user-facing-error'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import type {
  AgentDraft,
  ModelDraft,
  ProviderDraft,
  ResourceModal,
} from '@/features/ai/catalog/ai-console-types'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import {
  validateResourceDraft,
} from '@/features/ai/catalog/ai-resource-form-validation'

function provider(overrides: Partial<ProviderDraft> = {}): ProviderDraft {
  return {
    name: 'provider',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
    ...overrides,
  }
}

function model(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft({ id: 'provider-1' }),
    name: 'model',
    ...overrides,
  }
}

function agent(overrides: Partial<AgentDraft> = {}): AgentDraft {
  return {
    ...emptyAgentDraft(),
    name: 'agent',
    modelId: 'model-1',
    variant: 'medium',
    ...overrides,
  }
}

function validate(
  modal: ResourceModal,
  overrides: {
    providerDraft?: ProviderDraft
    modelDraft?: ModelDraft
    agentDraft?: AgentDraft
  } = {},
) {
  return validateResourceDraft(modal, {
    providerDraft: overrides.providerDraft ?? provider(),
    modelDraft: overrides.modelDraft ?? model(),
    agentDraft: overrides.agentDraft ?? agent(),
  })
}

describe('ai-resource-form-validation additional branches', () => {
  it.each([
    ['reasoningEffort is required', /思考强度/],
    ['variant must not be blank', /有效的 Variant/],
    ['at least one variant is required', /至少添加一个 Variant/],
    ['duplicate variant id', /不能重复/],
    ['config.defaultVariant is invalid', /默认 Variant/],
    ['maxOutputTokens must not exceed context', /不能超过上下文窗口/],
    ['contextWindow must be positive', /上下文窗口/],
    ['maxOutputTokens must be positive', /最大输出长度/],
    ['input modality is required', /输入类型/],
    ['temperature must not be negative', /Temperature/],
    ['topP must be in range', /Top P/],
    ['topK must be positive', /Top K/],
    ['frequencyPenalty must be a number', /Penalty/],
    ['providerId is required', /Provider/],
    ['pricing.inputPerMillionTokens must not be negative', /价格/],
    ['modelId is required', /Model/],
    ['baseUrl is invalid', /Base URL/],
    ['tools 去前缀后存在重名', /Tools/],
    ['skills 去前缀后存在重名', /Skills/],
    ['Network Error', /网络异常/],
    ['401 Unauthorized', /没有权限/],
    ['403 Forbidden', /没有权限/],
    ['404 Not Found', /不存在/],
    ['409 Conflict', /冲突/],
    ['500 Internal Server Error', /服务暂时异常/],
  ])('translates %s', (message, expected) => {
    expect(toUserFacingErrorMessage(new Error(message))).toMatch(expected)
  })

  it('handles empty, localized, and unknown non-Error values', () => {
    expect(toUserFacingErrorMessage(undefined)).toBe('保存失败，请检查表单后重试')
    expect(toUserFacingErrorMessage('服务返回了中文错误')).toBe('服务返回了中文错误')
    expect(toUserFacingErrorMessage('opaque failure')).toBe('保存失败，请检查必填项后重试')
  })

  it('surfaces model name conflicts under a provider as readable Chinese', () => {
    expect(
      toUserFacingErrorMessage(
        new Error('agent model name already exists under this provider: MiniMax-M2.7'),
      ),
    ).toBe('当前 Provider 下已存在同名 Model，请换一个名称')
    // 兼容旧文案，避免升级中的后端仍返回全局唯一错误时前端吞掉
    expect(toUserFacingErrorMessage(new Error('agent model name already exists: MiniMax-M2.7'))).toBe(
      '当前 Provider 下已存在同名 Model，请换一个名称',
    )
  })

  it('passes through other backend business English errors instead of a generic banner', () => {
    expect(toUserFacingErrorMessage(new Error('invalid agent model: config is required'))).toBe(
      'invalid agent model: config is required',
    )
  })

  it.each([
    { kind: 'provider', mode: 'create' },
    { kind: 'provider', mode: 'edit', id: 'provider-1' },
    { kind: 'model', mode: 'create' },
    { kind: 'model', mode: 'edit', id: 'model-1' },
    { kind: 'agent', mode: 'create' },
    { kind: 'agent', mode: 'edit', id: 'agent-1' },
  ] as ResourceModal[])('accepts a complete $kind $mode body', (modal) => {
    expect(validate(modal)).toEqual({ ok: true, message: '', fields: {} })
  })

  it('auto-fills missing reasoning effort and preserves explicit effort', () => {
    const withBlankEffort = model({ reasoning: true })
    const blankResult = validate(
      { kind: 'model', mode: 'create' },
      { modelDraft: withBlankEffort },
    )
    expect(blankResult.ok).toBe(true)

    const withExplicitEffort = model({
      reasoning: true,
      variants: [{ ...model().variants[0]!, reasoningEffort: 'high' }],
    })
    expect(
      validate({ kind: 'model', mode: 'edit', id: 'model-1' }, { modelDraft: withExplicitEffort }).ok,
    ).toBe(true)
  })

  it.each([
    [model({ variants: [] }), 'variants'],
    [model({ contextWindow: '0' }), 'contextWindow'],
    [model({ maxOutputTokens: '999999' }), 'maxOutputTokens'],
    [model({ variants: [{ ...model().variants[0]!, temperature: '-1' }] }), 'variants'],
    [model({ variants: [{ ...model().variants[0]!, topP: '2' }] }), 'variants'],
    [model({ variants: [{ ...model().variants[0]!, topK: '0' }] }), 'variants'],
    [model({ pricing: { ...model().pricing, inputPerMillionTokens: '-1' } }), 'pricing'],
  ] as Array<[ModelDraft, string]>)('maps invalid model body %# to %s', (modelDraft, field) => {
    const result = validate({ kind: 'model', mode: 'create' }, { modelDraft })
    expect(result.ok).toBe(false)
    expect(result.fields).toHaveProperty(field)
  })

  it.each([
    // empty variant is a valid "use model default" override
    [agent({ tools: ['read', 'read'] }), 'tools'],
    [agent({ skills: ['dev', 'dev'] }), 'skills'],
  ] as Array<[AgentDraft, string]>)('maps invalid agent body %# to %s', (agentDraft, field) => {
    const result = validate({ kind: 'agent', mode: 'create' }, { agentDraft })
    expect(result.ok).toBe(false)
    expect(result.fields).toHaveProperty(field)
  })

  it('rejects missing provider/agent required fields before serialization', () => {
    expect(
      validate({ kind: 'provider', mode: 'create' }, { providerDraft: provider({ name: '' }) }).fields,
    ).toHaveProperty('name')
    expect(validate({ kind: 'agent', mode: 'create' }, { agentDraft: agent({ name: '' }) }).fields).toHaveProperty(
      'name',
    )
    expect(
      validate({ kind: 'agent', mode: 'create' }, { agentDraft: agent({ modelId: '' }) }).fields,
    ).toHaveProperty('modelId')
  })
})
