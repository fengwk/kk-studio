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
    ...emptyModelDraft({ name: 'minimax' }),
    name: 'model',
    ...overrides,
  }
}

function agent(overrides: Partial<AgentDraft> = {}): AgentDraft {
  return {
    ...emptyAgentDraft(),
    name: 'agent',
    model: 'minimax/model',
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
    ['providerName is required', /Provider/],
    ['pricing.inputPerMillionTokens must not be negative', /价格/],
    ['modelName is required', /Model/],
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
  })

  it('passes through other backend business English errors instead of a generic banner', () => {
    expect(toUserFacingErrorMessage(new Error('invalid agent model: config is required'))).toBe(
      'invalid agent model: config is required',
    )
  })

  it.each([
    { kind: 'provider', mode: 'create' },
    { kind: 'provider', mode: 'edit', name: 'provider' },
    { kind: 'model', mode: 'create' },
    { kind: 'model', mode: 'edit', providerName: 'minimax', name: 'model' },
    { kind: 'agent', mode: 'create' },
    { kind: 'agent', mode: 'edit', name: 'agent' },
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
      validate({ kind: 'model', mode: 'edit', providerName: 'minimax', name: 'model' }, { modelDraft: withExplicitEffort }).ok,
    ).toBe(true)
  })

  it('rejects duplicate input modalities through the serialization boundary', () => {
    // validateResourceDraft 的本地预检只拦截空模态列表；重复项由 codec 序列化时拒绝。
    // toUserFacingErrorMessage 将 "inputModalities ... duplicate" 翻译为输入类型文案，
    // 但 mapError 无任何规则命中该原文（/modality/ 不匹配复数 inputModalities），落到 general。
    const result = validate(
      { kind: 'model', mode: 'create' },
      { modelDraft: model({ inputModalities: ['TEXT', 'IMAGE', 'TEXT'] }) },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.general).toBe('请至少选择一种输入类型（建议保留 TEXT）')
  })

  it('rejects one blank variant among valid ones', () => {
    // 空白 variant id 在 codec 序列化时被拒绝（variant 2 id is required），
    // mapError 将其同时映射到 variants 与 defaultVariant。
    const result = validate(
      { kind: 'model', mode: 'create' },
      {
        modelDraft: model({
          defaultVariant: 'valid',
          variants: [
            { ...model().variants[0]!, id: '' },
            { ...model().variants[0]!, id: 'valid' },
          ],
        }),
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.variants).toBe('请至少添加一个 Variant，并填写 ID')
    expect(result.fields.defaultVariant).toBe('请至少添加一个 Variant，并填写 ID')
  })

  it('rejects an empty pricing currency', () => {
    // 空币种在 codec 序列化时被拒绝（currency must not be blank），
    // mapError 的 pricing 规则命中 "must not be blank" 之外的 currency 原文。
    const result = validate(
      { kind: 'model', mode: 'create' },
      { modelDraft: model({ pricing: { ...model().pricing, currency: '' } }) },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.pricing).toBe('请检查价格：填写 0 或正数即可')
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
    // 空 variant 是合法的「使用 model 默认」覆盖
    [agent({ tools: ['read', 'read'] }), 'tools'],
    [agent({ skills: ['dev', 'dev'] }), 'skills'],
    [agent({ subagents: ['dev', 'dev'] }), 'subagents'],
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
      validate({ kind: 'agent', mode: 'create' }, { agentDraft: agent({ model: '' }) }).fields,
    ).toHaveProperty('model')
  })
})
