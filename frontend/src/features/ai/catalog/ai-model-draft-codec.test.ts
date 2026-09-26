import { describe, expect, it } from 'vitest'
import type { ModelDraft } from '@/features/ai/catalog/ai-console-types'
import type { AgentModelInputModality } from '@/shared/api/contracts/ai-catalog'
import {
  emptyModelDraft,
  extractContextWindow,
  extractDefaultVariantFromModel,
  extractMaxOutputTokens,
  extractVariantIdsFromModel,
  parseProtocolOptionsJson,
  toEditableModel,
  toEditableModelUpdate,
  toModelDraft,
} from '@/features/ai/catalog/ai-model-draft-codec'
import type { AgentModelConfigDTO, AgentModelDTO } from '@/shared/api/contracts/ai-catalog'

function draft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerName: 'provider-1',
    name: 'model-a',
    modelId: 'wire-model-a',
    ...overrides,
  }
}

function buildModelConfig(input: ModelDraft): AgentModelConfigDTO {
  return toEditableModel(input).config
}

function fullConfig(): AgentModelConfigDTO {
  return {
    limit: { context: 200000, output: 16000 },
    abilities: {
      tools: false,
      reasoning: true,
      inputModalities: ['TEXT', 'IMAGE', 'AUDIO'],
    },
    pricing: {
      currency: 'USD',
      pricingTier: 'batch',
      serviceTier: 'priority',
      serviceTierMultiplier: 1.25,
      version: '2026-07',
      inputPerMillionTokens: 1.1,
      outputPerMillionTokens: 2.2,
      cacheReadPerMillionTokens: 0.3,
      cacheWritePerMillionTokens: 0.4,
      cacheWriteLongPerMillionTokens: 0.5,
      reasoningPerMillionTokens: 3.6,
    },
    defaultVariant: 'quality',
    variants: [
      {
        id: 'quality',
        reasoningEffort: 'high',
      },
    ],
  }
}

function model(configOverride?: AgentModelConfigDTO): AgentModelDTO {
  return {
    providerName: 'provider-1',
    name: 'model-a',
    modelId: 'wire-model-a',
    description: 'desc',
    config: configOverride ?? fullConfig(),
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

describe('ai-model-draft-codec', () => {
  /** 完整的持久化 config 必须能往返覆盖表单使用的每一个嵌套 schema 字段。 */
  it('reads nested limit, abilities, pricing, defaultVariant, and variant fields', () => {
    const source = model()

    expect(toModelDraft(source)).toMatchObject({
      providerName: 'provider-1',
      name: 'model-a',
      contextWindow: '200000',
      maxOutputTokens: '16000',
      tools: false,
      reasoning: true,
      inputModalities: ['TEXT', 'IMAGE', 'AUDIO'],
      defaultVariant: 'quality',
      variants: [
        {
          id: 'quality',
          reasoningEffort: 'high',
        },
      ],
      pricing: {
        currency: 'USD',
        serviceTierMultiplier: '1.25',
        reasoningPerMillionTokens: '3.6',
      },
    })
    expect(extractContextWindow(source)).toBe(200000)
    expect(extractVariantIdsFromModel(source)).toEqual(['quality'])
    expect(extractDefaultVariantFromModel(source)).toBe('quality')
  })

  /** 新建 model 草稿默认仅 TEXT（而非包含所有 modality）。 */
  it('defaults to TEXT-only input modalities on empty drafts', () => {
    expect(emptyModelDraft().inputModalities).toEqual<AgentModelInputModality[]>(['TEXT'])
  })

  /** 序列化使用结构化 config 对象，并省略空的 optional variant 字段。 */
  it('writes the structured config and omits empty optional variant fields', () => {
    const base = emptyModelDraft().variants[0]
    const input = draft({
      description: ' model desc ',
      contextWindow: '200000',
      maxOutputTokens: '16000',
      tools: true,
      reasoning: true,
      inputModalities: ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO'],
      defaultVariant: 'quality',
      variants: [
        {
          ...base,
          id: 'quality',
          reasoningEffort: 'high',
        },
        {
          ...base,
          id: 'provider-defaults',
          reasoningEffort: '',
        },
      ],
    })

    const editable = toEditableModel(input)
    const config = buildModelConfig(input)
    expect(config).toEqual({
      limit: { context: 200000, output: 16000 },
      abilities: {
        tools: true,
        reasoning: true,
        inputModalities: ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO'],
      },
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
      variants: [
        {
          id: 'quality',
          reasoningEffort: 'high',
        },
        { id: 'provider-defaults' },
      ],
    })
    expect(editable.config).toEqual(config)
    expect(editable.name).toBe('model-a')
    expect(editable.modelId).toBe('wire-model-a')
    expect(editable.description).toBe('model desc')
  })
  /** 关闭 reasoning 时必须防止陈旧的隐藏 reasoning-effort 值传到 providers。 */
  it('omits reasoningEffort when reasoning is disabled', () => {
    const base = emptyModelDraft().variants[0]
    const input = draft({
      reasoning: false,
      variants: [{ ...base, reasoningEffort: 'high' }],
    })

    const config = buildModelConfig(input)
    expect(config.variants).toEqual([{ id: 'medium' }])
  })

  /** 在发起 mutation 请求前，必须拒绝无效的 variant/default 关系。 */
  it('rejects empty, duplicate, unmatched, and invalid optional variants', () => {
    const base = emptyModelDraft().variants[0]
    expect(() => buildModelConfig(draft({ variants: [] }))).toThrow(/at least one variant/i)
    expect(() =>
      buildModelConfig(
        draft({ variants: [base, { ...base, id: base.id }] }),
      ),
    ).toThrow('duplicate variant id')
    expect(() => buildModelConfig(draft({ defaultVariant: 'missing' }))).toThrow(
      /defaultVariant must match/i,
    )
    expect(() =>
      buildModelConfig(
        draft({ reasoning: true, variants: [{ ...base, reasoningEffort: 'a'.repeat(65) }] }),
      ),
    ).toThrow(/reasoningEffort must not exceed 64 characters/)
    expect(() =>
      buildModelConfig(draft({ inputModalities: ['TEXT', 'TEXT'] })),
    ).toThrow('contains duplicate value')
  })

  /** 厂商自定义 reasoningEffort（如 max、xhigh）被正常保留并归一化小写，空白被省略。 */
  it('accepts and normalizes arbitrary provider reasoning efforts like max and xhigh', () => {
    const base = emptyModelDraft().variants[0]
    const input = draft({
      reasoning: true,
      variants: [
        { ...base, id: 'v1', reasoningEffort: '  MAX ' },
        { ...base, id: 'v2', reasoningEffort: 'xHigh' },
        { ...base, id: 'v3', reasoningEffort: '   ' },
      ],
      defaultVariant: 'v1',
    })
    const config = buildModelConfig(input)
    expect(config.variants).toEqual([
      { id: 'v1', reasoningEffort: 'max' },
      { id: 'v2', reasoningEffort: 'xhigh' },
      { id: 'v3' },
    ])
  })

  /** 自定义 reasoningEffort 在 toModelDraft -> buildModelConfig 往返中完整保留。 */
  it('round-trips custom reasoning effort across toModelDraft -> buildModelConfig', () => {
    const source = model({
      ...fullConfig(),
      variants: [
        { id: 'max-variant', reasoningEffort: 'max' },
        { id: 'xhigh-variant', reasoningEffort: 'xhigh' },
      ],
      defaultVariant: 'max-variant',
    })
    const draftFromModel = toModelDraft(source)
    const rebuilt = buildModelConfig(draftFromModel)
    expect(rebuilt.variants).toEqual([
      { id: 'max-variant', reasoningEffort: 'max' },
      { id: 'xhigh-variant', reasoningEffort: 'xhigh' },
    ])
  })

  /** 创建/更新 DTO 不使用字符串编码的 JSON 构建。 */
  it('produces structured create / update payloads', () => {
    const input = draft({ name: 'stub', contextWindow: '4096', maxOutputTokens: '512' })
    const config = buildModelConfig(input)
    const create = toEditableModel(input)
    expect(create.providerName).toBe('provider-1')
    expect(create.name).toBe('stub')
    expect(create.modelId).toBe('wire-model-a')
    expect(create.config).toEqual(config)
    expect(toEditableModelUpdate(input)).not.toHaveProperty('providerName')
    expect(toEditableModelUpdate(input).name).toBe('stub')
    expect(toEditableModelUpdate(input).modelId).toBe('wire-model-a')
    expect(() => toEditableModelUpdate(draft({ name: '   ' }))).toThrow('name must not be blank')
  })

  /** 通过不可变数组切换时，永远不会丢失最后一个 input modality。 */
  it('keeps at least one input modality when toggling', () => {
    const base = emptyModelDraft()
    const onlyText = base.inputModalities.filter((m) => m !== 'TEXT')
    const toggled = onlyText.length > 0 ? onlyText : ['TEXT']
    expect(toggled).toEqual<AgentModelInputModality[]>(['TEXT'])
  })

  /** 编辑时必须保留每个持久化的 pricing 元数据字段；新建草稿使用规范默认值。 */
  it('round-trips pricing metadata across toModelDraft -> buildModelConfig', () => {
    const source = model({
      ...fullConfig(),
      pricing: {
        currency: 'EUR',
        pricingTier: 'enterprise',
        serviceTier: 'premium',
        serviceTierMultiplier: 2.5,
        version: '2026-09',
        inputPerMillionTokens: 9,
        outputPerMillionTokens: 11,
        cacheReadPerMillionTokens: 1.25,
        cacheWritePerMillionTokens: 2.5,
        cacheWriteLongPerMillionTokens: 3.5,
        reasoningPerMillionTokens: 4.5,
      },
    })
    const draftFromModel = toModelDraft(source)
    const rebuilt = buildModelConfig(draftFromModel)
    expect(rebuilt.pricing).toEqual({
      currency: 'EUR',
      pricingTier: 'enterprise',
      serviceTier: 'premium',
      serviceTierMultiplier: 2.5,
      version: '2026-09',
      inputPerMillionTokens: 9,
      outputPerMillionTokens: 11,
      cacheReadPerMillionTokens: 1.25,
      cacheWritePerMillionTokens: 2.5,
      cacheWriteLongPerMillionTokens: 3.5,
      reasoningPerMillionTokens: 4.5,
    })
  })

  /** buildModelConfig 拒绝非正 multiplier 和负的每百万 token 价格。 */
  it('rejects non-positive multiplier and negative prices', () => {
    const base = draft()
    expect(() => buildModelConfig({ ...base, pricing: { ...base.pricing, serviceTierMultiplier: '0' } })).toThrow(
      /serviceTierMultiplier must be positive/,
    )
    expect(() => buildModelConfig({ ...base, pricing: { ...base.pricing, inputPerMillionTokens: '-1' } })).toThrow(
      /inputPerMillionTokens must not be negative/,
    )
    expect(() => buildModelConfig({ ...base, pricing: { ...base.pricing, outputPerMillionTokens: 'NaN' } })).toThrow(
      /outputPerMillionTokens must be a number/,
    )
  })

  /** toEditableModel 与 toEditableModelUpdate 均拒绝 blank name。 */
  it('rejects blank name in toEditableModel and toEditableModelUpdate', () => {
    expect(() => toEditableModel(draft({ name: '   ' }))).toThrow(/name/)
    expect(() => toEditableModelUpdate(draft({ name: '   ' }))).toThrow(/name/)
  })

  /** toEditableModel 拒绝空的 providerName。 */
  it('rejects blank providerName in toEditableModel', () => {
    expect(() => toEditableModel(draft({ providerName: '' }))).toThrow(/providerName is required/)
  })

  /** pricing 元数据为必填项，编辑时不得静默重建。 */
  it('rejects blank pricing metadata', () => {
    const base = draft()
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, currency: '' } }),
    ).toThrow(/pricing\.currency must not be blank/)
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, version: '' } }),
    ).toThrow(/pricing\.version must not be blank/)
  })

  /** 缺失的 model 不会凭空生成 Variant ID。 */
  it('returns no variant data for missing models', () => {
    expect(extractVariantIdsFromModel(undefined)).toEqual([])
    expect(extractDefaultVariantFromModel(undefined)).toBe('')
  })

  /** 不完整的 wire model 不得让列表/过滤投影崩溃。 */
  it('tolerates models whose structured config is missing', () => {
    const incomplete = { id: '1', name: 'broken' } as AgentModelDTO
    expect(extractVariantIdsFromModel(incomplete)).toEqual([])
    expect(extractDefaultVariantFromModel(incomplete)).toBe('')
    expect(extractContextWindow(incomplete)).toBeUndefined()
    expect(extractMaxOutputTokens(incomplete)).toBeUndefined()
  })

  /** emptyModelDraft 在未提供 model 或 provider 时，保留空的 providerName 且仅 TEXT。 */
  it('returns empty providerName when neither model nor provider is supplied', () => {
    const blank = emptyModelDraft(null)
    expect(blank.providerName).toBe('')
    expect(blank.inputModalities).toEqual(['TEXT'])
  })

  /** emptyModelDraft 遵循显式传入的 provider 参数。 */
  it('uses the explicit provider argument when no model is supplied', () => {
    const seeded = emptyModelDraft({ name: 'provider-x' })
    expect(seeded.providerName).toBe('provider-x')
  })

  describe('protocolOptionsJson codec and validation', () => {
    /** 只检查语法与根节点，绝不把被 JS 浮点舍入的数值序列化回请求。 */
    it('preserves arbitrary precision numeric JSON text across loading and submission', () => {
      const json = '{"large":9007199254740993,"beyond":9223372036854775808,"nested":{"decimal":0.123456789012345678901234567890,"exp":1.234567890123456789e+45}}'
      expect(parseProtocolOptionsJson(` \n${json}\t`, 'quality')).toBe(json)
      const source = model({ ...fullConfig(), variants: [{ id: 'quality', protocolOptionsJson: json }] })
      const loaded = toModelDraft(source)
      expect(loaded.variants[0]?.protocolOptionsJson).toBe(json)
      expect(buildModelConfig(loaded).variants[0]?.protocolOptionsJson).toBe(json)
      expect(JSON.stringify(buildModelConfig(loaded))).toContain('9007199254740993')
    })

    /** 空白交给后端默认空 object，显式空 object 保留为文本。 */
    it('accepts empty input and explicit empty object', () => {
      expect(parseProtocolOptionsJson(' \n ', 'v1')).toBeUndefined()
      expect(parseProtocolOptionsJson(' {} ', 'v1')).toBe('{}')
    })

    /** 格式错误、trailing tokens 与非 object 根在提交前被本地拒绝。 */
    it.each(['{bad}', '{} true'])('rejects invalid syntax %s', (raw) => {
      expect(() => parseProtocolOptionsJson(raw, 'v1')).toThrow('protocolOptionsJson must be valid JSON')
    })
    it.each(['null', '[]', '1', '"text"', 'true'])('rejects non-object root %s', (raw) => {
      expect(() => parseProtocolOptionsJson(raw, 'v1')).toThrow('protocolOptionsJson must be a JSON object')
    })

    /** 上限使用 UTF-8 字节而非 JS 字符个数；错误文案不携带原始值。 */
    it('rejects overlarge UTF-8 input without echoing the payload', () => {
      const raw = `{"x":"${'测'.repeat(22000)}"}`
      expect(() => parseProtocolOptionsJson(raw, 'v1')).toThrow('protocolOptionsJson must not exceed 65536 UTF-8 bytes')
    })

    /** reasoning 禁用也能保留选项，清空选项则省略该字段。 */
    it('retains options when reasoning is disabled and omits blank values', () => {
      const input = draft({
        reasoning: false,
        variants: [{ ...emptyModelDraft().variants[0]!, protocolOptionsJson: '{"temperature":0.2}' }],
      })
      expect(buildModelConfig(input).variants[0]?.protocolOptionsJson).toBe('{"temperature":0.2}')
      input.variants[0]!.protocolOptionsJson = '  '
      expect(buildModelConfig(input).variants[0]).not.toHaveProperty('protocolOptionsJson')
    })
  })
})
