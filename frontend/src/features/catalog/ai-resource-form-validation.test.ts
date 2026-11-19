import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { newVariantDraft } from '@/features/catalog/ai-resource-draft-primitives'
import type {
  AgentDraft,
  ModelDraft,
  ModelGroupDraft,
  ProviderDraft,
  ResourceModal,
} from '@/features/catalog/ai-console-types'
import {
  validateResourceDraft,
} from '@/features/catalog/ai-resource-form-validation'
import { setLocale } from '@/shared/i18n'

const EMPTY_AGENT_DRAFT = {
  name: '',
  description: '',
  systemPrompt: '',
  model: '',
  variant: '',
  tools: [],
  skills: [],
  subagents: [],
}

function draft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  const empty = emptyModelDraft()
  return {
    ...empty,
    variants: empty.variants.map((variant) => ({ ...variant, promptCacheRetention: 'NONE' })),
    providerName: 'provider-1',
    name: 'model',
    modelId: 'wire-model',
    ...overrides,
  }
}

function providerDraft(): ProviderDraft {
  return {
    name: 'p',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    credentialConfigured: false,
    clearCredential: false,
    modelCallTimeoutMillis: '',
    modelCallIdleTimeoutMillis: '',
    modelHttpRetryStatusCodes: null,
  }
}

function provider(overrides: Partial<ProviderDraft> = {}): ProviderDraft {
  return {
    name: 'provider',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    credentialConfigured: false,
    clearCredential: false,
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
    modelHttpRetryStatusCodes: null,
    ...overrides,
  }
}

function validVariant(overrides: Parameters<typeof newVariantDraft>[0] = {}) {
  return newVariantDraft({ promptCacheRetention: 'NONE', ...overrides })
}

function model(overrides: Partial<ModelDraft> = {}): ModelDraft {
  const empty = emptyModelDraft({ name: 'minimax' })
  return {
    ...empty,
    name: 'model',
    modelId: 'wire-model',
    variants: empty.variants.map((variant) => ({ ...variant, promptCacheRetention: 'NONE' })),
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
    modelGroupDraft?: ModelGroupDraft
    agentDraft?: AgentDraft
  } = {},
) {
  return validateResourceDraft(modal, {
    providerDraft: overrides.providerDraft ?? provider(),
    modelDraft: overrides.modelDraft ?? model(),
    modelGroupDraft: overrides.modelGroupDraft ?? emptyModelGroupDraft(),
    agentDraft: overrides.agentDraft ?? agent(),
  })
}

describe('ai-resource-form-validation', () => {
  it('rejects a model draft with a blank provider', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
      {
        providerDraft: { ...providerDraft(), name: '' },
        modelDraft: draft({ providerName: '   ' }),
        modelGroupDraft: emptyModelGroupDraft(),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.providerName).toBeDefined()
  })

  it('rejects a model draft with a blank name', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
      {
        providerDraft: providerDraft(),
        modelDraft: draft({ name: '   ' }),
        modelGroupDraft: emptyModelGroupDraft(),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.name).toBeDefined()
  })

  it('rejects empty input modalities', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
      {
        providerDraft: providerDraft(),
        modelDraft: draft({ inputModalities: [] }),
        modelGroupDraft: emptyModelGroupDraft(),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.inputModalities).toBeDefined()
  })

  it('rejects a defaultVariant that does not exist', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
      {
        providerDraft: providerDraft(),
        modelDraft: draft({
          defaultVariant: 'stale',
          variants: [
            validVariant({
              id: 'fast',
              reasoningEffort: '',
              protocolOptionsJson: '',
            }),
            validVariant({
              id: 'creative',
              reasoningEffort: '',
              protocolOptionsJson: '',
            }),
          ],
        }),
        modelGroupDraft: emptyModelGroupDraft(),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.defaultVariant).toBeDefined()
  })

  /** Reasoning 开启时全部 variant 的空思考强度仍然合法：空值表示不覆盖协议默认。 */
  it('accepts empty reasoning effort as the protocol default when reasoning is enabled', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
      {
        providerDraft: providerDraft(),
        modelDraft: draft({
          reasoning: true,
          variants: [
            validVariant({
              id: 'medium',
              reasoningEffort: '',
              protocolOptionsJson: '',
            }),
          ],
        }),
        modelGroupDraft: emptyModelGroupDraft(),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(true)
    expect(result.fields).toEqual({})
  })

  /** 校验不得写回或补全 reasoningEffort：draft 必须保持用户输入的原样。 */
  it('does not mutate the model draft while validating', () => {
    const modelDraft = draft({
      reasoning: true,
      variants: [validVariant({ id: 'medium', reasoningEffort: '', protocolOptionsJson: '' })],
    })
    const snapshot = JSON.stringify(modelDraft)
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
      {
        providerDraft: providerDraft(),
        modelDraft,
        modelGroupDraft: emptyModelGroupDraft(),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(true)
    expect(JSON.stringify(modelDraft)).toBe(snapshot)
  })

  it('translates known backend errors into user-facing Chinese', () => {
    expect(
      toUserFacingErrorMessage(new Error('config.limit.context must be a positive integer')),
    ).toMatch(/上下文窗口/)
    expect(
      toUserFacingErrorMessage(new Error('config.defaultVariant must match a variant id')),
    ).toMatch(/默认 Variant/)
    expect(toUserFacingErrorMessage(new Error('duplicate variant id: fast'))).toMatch(/不能重复/)
    expect(toUserFacingErrorMessage(new ApiError('Unauthorized', 401))).toMatch(/没有权限/)
  })

  it('passes Chinese text through unchanged', () => {
    expect(toUserFacingErrorMessage(new Error('已开启 Reasoning'))).toMatch(/Reasoning/)
  })

  it('maps reasoning effort and agent variant errors to their fields', () => {
    expect(
      toUserFacingErrorMessage(new Error('variant medium reasoningEffort must not exceed 64 characters')),
    ).toMatch(/思考强度|Reasoning/)
    expect(toUserFacingErrorMessage(new Error('variant must not be blank'))).toMatch(/Variant/)
    expect(toUserFacingErrorMessage(new Error('unsupported provider type wire value: OPENAI'))).toMatch(
      /Provider Type/,
    )
  })

  describe('protocolOptions form validation', () => {
    it('accepts valid protocolOptions JSON object on create', () => {
      const result = validateResourceDraft(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
        {
          providerDraft: providerDraft(),
          modelDraft: draft({
            variants: [
              validVariant({
                id: 'medium',
                reasoningEffort: '',
                protocolOptionsJson: '{\n  "temperature": 0.7\n}',
              }),
            ],
          }),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(result.ok).toBe(true)
      expect(result.fields).toEqual({})
    })

    it('rejects invalid protocolOptions JSON and surfaces localized variant-specific error', () => {
      setLocale('zh-CN')
      const result = validateResourceDraft(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
        {
          providerDraft: providerDraft(),
          modelDraft: draft({
            defaultVariant: 'fast',
            variants: [
              validVariant({
                id: 'fast',
                reasoningEffort: '',
                protocolOptionsJson: '{ not valid json }',
              }),
            ],
          }),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(result.ok).toBe(false)
      expect(result.message).toContain('Variant "fast"')
      expect(result.message).toContain('protocolOptions 必须是有效的 JSON')
      expect(result.fields.variants).toBe(result.message)
    })

    it('rejects non-object root (array/scalar/null) with localized variant-specific error', () => {
      setLocale('zh-CN')
      const result = validateResourceDraft(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
        {
          providerDraft: providerDraft(),
          modelDraft: draft({
            defaultVariant: 'fast',
            variants: [
              validVariant({
                id: 'fast',
                reasoningEffort: '',
                protocolOptionsJson: '[1, 2, 3]',
              }),
            ],
          }),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(result.ok).toBe(false)
      expect(result.message).toContain('Variant "fast"')
      expect(result.message).toContain('protocolOptions 必须是 JSON 对象')
      expect(result.fields.variants).toBe(result.message)
    })

    it('validates protocolOptions even when model reasoning is disabled', () => {
      setLocale('zh-CN')
      const result = validateResourceDraft(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
        {
          providerDraft: providerDraft(),
          modelDraft: draft({
            reasoning: false,
            defaultVariant: 'standard',
            variants: [
              validVariant({
                id: 'standard',
                reasoningEffort: '',
                protocolOptionsJson: 'null',
              }),
            ],
          }),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(result.ok).toBe(false)
      expect(result.message).toContain('Variant "standard"')
      expect(result.message).toContain('protocolOptions 必须是 JSON 对象')
      expect(result.fields.variants).toBe(result.message)
    })

    it('supports English locale for protocolOptions validation error messages', () => {
      setLocale('en-US')
      expect(
        toUserFacingErrorMessage(new Error('variant fast protocolOptionsJson must be valid JSON')),
      ).toBe('Variant "fast" protocolOptions must be valid JSON')
      expect(
        toUserFacingErrorMessage(new Error('variant fast protocolOptionsJson must be a JSON object')),
      ).toBe('Variant "fast" protocolOptions must be a JSON object')
      setLocale('zh-CN')
    })
  })

  describe('provider timeout validation', () => {
    // 超时是本地可控输入：非法值必须是可读的整数提示，不能被显示成网络故障。
    it('reports the integer requirement instead of a network failure', () => {
      setLocale('zh-CN')
      const invalid = validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: {
            ...providerDraft(),
            name: 'timeout-provider',
            modelCallTimeoutMillis: '1,800,000',
          },
          modelDraft: draft(),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(invalid.ok).toBe(false)
      expect(invalid.message).toBe('模型调用超时必须是整数毫秒')
      expect(invalid.fields.general).toBe(invalid.message)

      setLocale('en-US')
      const invalidEnglish = validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: {
            ...providerDraft(),
            name: 'timeout-provider',
            modelCallIdleTimeoutMillis: '1.5',
          },
          modelDraft: draft(),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(invalidEnglish.ok).toBe(false)
      expect(invalidEnglish.message).toBe('Model call timeouts must be whole milliseconds')
      setLocale('zh-CN')
    })
  })

  describe('provider http retry policy validation', () => {
    it('accepts inherit (null), disabled ([]), and valid custom status codes', () => {
      // Inherit
      const inheritResult = validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: { ...providerDraft(), name: 'valid-provider', modelHttpRetryStatusCodes: null },
          modelDraft: draft(),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(inheritResult.ok).toBe(true)

      // Disabled ([])
      const disabledResult = validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: { ...providerDraft(), name: 'valid-provider', modelHttpRetryStatusCodes: [] },
          modelDraft: draft(),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(disabledResult.ok).toBe(true)

      // Custom valid list
      const customResult = validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: {
            ...providerDraft(),
            name: 'valid-provider',
            modelHttpRetryStatusCodes: [408, 429, 500, 502, 503, 504],
          },
          modelDraft: draft(),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(customResult.ok).toBe(true)
    })

    it('blocks save when invalid status codes are staged in provider draft', () => {
      // Out of range (< 400 or > 599)
      const outOfRangeResult = validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: { ...providerDraft(), name: 'valid-provider', modelHttpRetryStatusCodes: [200] },
          modelDraft: draft(),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(outOfRangeResult.ok).toBe(false)

      // Non-integer string
      const nonIntResult = validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: { ...providerDraft(), name: 'valid-provider', modelHttpRetryStatusCodes: [429, 'xyz'] },
          modelDraft: draft(),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(nonIntResult.ok).toBe(false)

      // Duplicate
      const dupResult = validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: { ...providerDraft(), name: 'valid-provider', modelHttpRetryStatusCodes: [429, 429] },
          modelDraft: draft(),
          modelGroupDraft: emptyModelGroupDraft(),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(dupResult.ok).toBe(false)
    })
  })

  describe('additional branches', () => {
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
      ['providerName is required', /Provider/],
      ['providerType is invalid', /Provider Type/],
      ['pricing.inputPerMillionTokens must not be negative', /价格/],
      ['modelName is required', /Model/],
      ['baseUrl is invalid', /Base URL/],
      ['tools 重名', /Tools/],
      ['skills 去前缀后存在重名', /Skills/],
    ])('translates %s', (message, expected) => {
      expect(toUserFacingErrorMessage(new Error(message))).toMatch(expected)
    })

    // 测试意图：HTTP 失败只按结构化 status/code 分类，标题文本不参与判断。
    it.each([
      [new ApiError('Request failed', undefined, 'network_error'), /网络异常/],
      [new ApiError('Request timeout', undefined, 'timeout'), /网络异常/],
      [new ApiError('Unauthorized', 401), /没有权限/],
      [new ApiError('Forbidden', 403), /没有权限/],
      [new ApiError('Not Found', 404), /不存在/],
      [new ApiError('Conflict', 409), /冲突/],
      [new ApiError('Internal Server Error', 500), /服务暂时异常/],
      // 标题伪装成别的分类也不影响判断：状态码才是事实。
      [new ApiError('Conflict', 500), /服务暂时异常/],
    ])('classifies api error %# by status/code', (error, expected) => {
      expect(toUserFacingErrorMessage(error)).toMatch(expected)
    })

    // 测试意图：普通 Error 只服务本地表单校验；带状态码字样的文本不再被当成 HTTP 失败。
    it('does not classify plain error text as an HTTP failure', () => {
      expect(toUserFacingErrorMessage(new Error('401 Unauthorized'))).toBe(
        '保存失败，请检查必填项后重试',
      )
      expect(toUserFacingErrorMessage(new Error('409 Conflict'))).toBe(
        '保存失败，请检查必填项后重试',
      )
    })

    it('handles empty, localized, and unknown non-Error values', () => {
      expect(toUserFacingErrorMessage(undefined)).toBe('保存失败，请检查表单后重试')
      expect(toUserFacingErrorMessage('服务返回了中文错误')).toBe('服务返回了中文错误')
      expect(toUserFacingErrorMessage('opaque failure')).toBe('保存失败，请检查必填项后重试')
    })

    it('surfaces model name conflicts under a provider as readable Chinese', () => {
      expect(
        toUserFacingErrorMessage(
          new Error('model name already exists: MiniMax-M2.7'),
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
      { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
      { kind: 'model', mode: 'edit', resourceKind: 'MODEL', name: 'model' },
      { kind: 'agent', mode: 'create' },
      { kind: 'agent', mode: 'edit', name: 'agent' },
    ] as ResourceModal[])('accepts a complete $kind $mode body', (modal) => {
      expect(validate(modal)).toEqual({ ok: true, message: '', fields: {} })
    })

    /** 空思考强度表示协议默认，不被自动补全；显式 effort 原样保留。 */
    it('keeps blank reasoning effort as the protocol default and preserves explicit effort', () => {
      const withBlankEffort = model({ reasoning: true })
      const blankResult = validate(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
        { modelDraft: withBlankEffort },
      )
      expect(blankResult.ok).toBe(true)
      expect(withBlankEffort.variants[0]!.reasoningEffort).toBe('')

      const withExplicitEffort = model({
        reasoning: true,
        variants: [{ ...model().variants[0]!, reasoningEffort: 'high' }],
      })
      expect(
        validate(
          { kind: 'model', mode: 'edit', resourceKind: 'MODEL', name: 'model', expectedVersion: '1' },
          { modelDraft: withExplicitEffort },
        ).ok,
      ).toBe(true)
    })

    it('rejects duplicate input modalities through the serialization boundary', () => {
      // validateResourceDraft 的本地预检只拦截空模态列表；重复项由 codec 序列化时拒绝。
      // toUserFacingErrorMessage 将 "inputModalities ... duplicate" 翻译为输入类型文案，
      // 但 mapError 无任何规则命中该原文（/modality/ 不匹配复数 inputModalities），落到 general。
      const result = validate(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
        { modelDraft: model({ inputModalities: ['TEXT', 'IMAGE', 'TEXT'] }) },
      )
      expect(result.ok).toBe(false)
      expect(result.fields.general).toBe('请至少选择一种输入类型，建议保留 TEXT')
    })

    it('rejects one blank variant among valid ones', () => {
      // 空白 variant id 在 codec 序列化时被拒绝（variant 2 id is required），
      // mapError 将其同时映射到 variants 与 defaultVariant。
      const result = validate(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
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

    it('rejects invalid base rates without defaulting an unpriced model to zero', () => {
      const invalid = model()
      invalid.pricing.definition.base.input = '-1'
      const result = validate(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
        { modelDraft: invalid },
      )
      expect(result.ok).toBe(false)
      expect(result.fields.pricing).toBeTruthy()
      expect(validate(
        { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
        { modelDraft: model() },
      ).ok).toBe(true)
    })

    it.each([
      [model({ variants: [] }), 'variants'],
      [model({ contextWindow: '0' }), 'contextWindow'],
      [model({ maxOutputTokens: '999999' }), 'maxOutputTokens'],
      [model({ modelId: '   ' }), 'modelId'],
      [model({ reasoning: true, variants: [{ ...model().variants[0]!, reasoningEffort: 'a'.repeat(65) }] }), 'variants'],
      [model({ pricing: {
        ...model().pricing,
        definition: { ...model().pricing.definition, base: { ...model().pricing.definition.base, input: '-1' } },
      } }), 'pricing'],
    ] as Array<[ModelDraft, string]>)('maps invalid model body %# to %s', (modelDraft, field) => {
      const result = validate({ kind: 'model', mode: 'create', resourceKind: 'MODEL' }, { modelDraft })
      expect(result.ok).toBe(false)
      expect(result.fields).toHaveProperty(field)
    })

    it.each([
      // 空 variant 是合法的「使用 model 默认」覆盖
      [agent({ tools: ['read', 'read'] }), 'tools'],
      [agent({ skills: [{ packageName: 'pkg', name: 'dev' }, { packageName: 'pkg', name: 'dev' }] }), 'skills'],
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

    it('requires a configured model on agent edit', () => {
      expect(
        validate(
          { kind: 'agent', mode: 'edit', name: 'assistant', expectedVersion: '1' },
          { agentDraft: agent({ model: '' }) },
        ).fields,
      ).toHaveProperty('model')
    })
  })
})
