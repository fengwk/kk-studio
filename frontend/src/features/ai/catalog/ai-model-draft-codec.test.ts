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
import type {
  AgentModelConfigDTO,
  AgentModelDTO,
  AgentModelPricingDefinitionDTO,
} from '@/shared/api/contracts/ai-catalog'

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
  return toEditableModel(input).config!
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
      base: { input: '1.1', output: '2.2', cacheRead: '0.3', cacheWrite: '0.4', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
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
    kind: 'MODEL',
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
        base: {
          input: '1.1',
          output: '2.2',
          cacheRead: '0.3',
          cacheWrite: '0.4',
          cacheWriteByTtl: {},
          reasoning: null,
        },
        overrides: [],
        multipliers: [],
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
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
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

  /** 厂商自定义 reasoningEffort（如 MAX、xHigh）保留原始大小写且只 trim 空白，全空白被省略。 */
  it('accepts arbitrary provider reasoning efforts with exact casing and omits blanks', () => {
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
      { id: 'v1', reasoningEffort: 'MAX' },
      { id: 'v2', reasoningEffort: 'xHigh' },
      { id: 'v3' },
    ])
  })

  /** reasoningEffort 大小写保真（HIGH / high / xHigh），在 toEditableModel 保存 -> toModelDraft 重新编辑往返中保持不变。 */
  it('preserves exact casing for HIGH, high, and xHigh across toEditableModel and toModelDraft round-trip', () => {
    const base = emptyModelDraft().variants[0]
    const input = draft({
      reasoning: true,
      variants: [
        { ...base, id: 'v-high-upper', reasoningEffort: '  HIGH  ' },
        { ...base, id: 'v-high-lower', reasoningEffort: 'high' },
        { ...base, id: 'v-xhigh-mixed', reasoningEffort: 'xHigh' },
      ],
      defaultVariant: 'v-high-upper',
    })

    const createPayload = toEditableModel(input)
    expect(createPayload.config!.variants).toEqual([
      { id: 'v-high-upper', reasoningEffort: 'HIGH' },
      { id: 'v-high-lower', reasoningEffort: 'high' },
      { id: 'v-xhigh-mixed', reasoningEffort: 'xHigh' },
    ])

    const savedModel: AgentModelDTO = {
      providerName: createPayload.providerName,
      kind: 'MODEL',
      name: createPayload.name,
      modelId: createPayload.modelId,
      description: createPayload.description,
      config: createPayload.config,
      version: '1',
      createTime: null,
      updateTime: null,
    }
    const editDraft = toModelDraft(savedModel)
    expect(
      editDraft.variants.map((v) => ({ id: v.id, reasoningEffort: v.reasoningEffort })),
    ).toEqual([
      { id: 'v-high-upper', reasoningEffort: 'HIGH' },
      { id: 'v-high-lower', reasoningEffort: 'high' },
      { id: 'v-xhigh-mixed', reasoningEffort: 'xHigh' },
    ])

    const reSaved = toEditableModel(editDraft)
    expect(reSaved.config!.variants).toEqual(createPayload.config!.variants)
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

  /** 价格定义完整往返：基础费率、TTL、显式 0、覆盖与倍率规则均原样保留。 */
  it('round-trips the full pricing definition across toModelDraft -> buildModelConfig', () => {
    const pricing: AgentModelPricingDefinitionDTO = {
      base: {
        input: '9',
        output: '11',
        cacheRead: '1.25',
        cacheWrite: '2.5',
        cacheWriteByTtl: { '3600': '3.5' },
        reasoning: '0',
      },
      overrides: [
        {
          id: 'batch-cache',
          when: { serviceTier: ['batch'] },
          rates: { output: '5', reasoning: null },
        },
      ],
      multipliers: [
        {
          id: 'priority',
          when: { context: { gt: 200000 } },
          factors: { input: '1.25' },
        },
      ],
    }
    const source = model({ ...fullConfig(), pricing })
    const draftFromModel = toModelDraft(source)
    expect(buildModelConfig(draftFromModel).pricing).toEqual(pricing)
  })

  /** 价格校验拒绝非法 decimal，且不会把空 reasoning 静默写成 0。 */
  it('rejects invalid decimal rates instead of coercing them', () => {
    const base = draft()
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, base: { ...base.pricing.base, input: '-1' } } }),
    ).toThrow(/pricing\.base\.input/)
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, base: { ...base.pricing.base, output: 'NaN' } } }),
    ).toThrow(/pricing\.base\.output/)
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, base: { ...base.pricing.base, reasoning: '' } } }),
    ).toThrow(/pricing\.base\.reasoning/)
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

  /** 价格基础字段为必填项，编辑时不得静默补 0。 */
  it('rejects blank base pricing fields', () => {
    const base = draft()
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, base: { ...base.pricing.base, cacheRead: '' } } }),
    ).toThrow(/pricing\.base\.cacheRead/)
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, base: { ...base.pricing.base, cacheWriteByTtl: { '0': '1' } } } }),
    ).toThrow(/TTL keys/)
  })

  /** 缺失的 model 不会凭空生成 Variant ID。 */
  it('returns no variant data for missing models', () => {
    expect(extractVariantIdsFromModel(undefined)).toEqual([])
    expect(extractDefaultVariantFromModel(undefined)).toBe('')
  })

  /** 缺失 config 的不可信模型输入不得让投影崩溃。 */
  it('tolerates models whose structured config is missing', () => {
    const incomplete = { name: 'broken', providerName: 'broken' }
    expect(Reflect.apply(extractVariantIdsFromModel, undefined, [incomplete])).toEqual([])
    expect(Reflect.apply(extractDefaultVariantFromModel, undefined, [incomplete])).toBe('')
    expect(Reflect.apply(extractContextWindow, undefined, [incomplete])).toBeUndefined()
    expect(Reflect.apply(extractMaxOutputTokens, undefined, [incomplete])).toBeUndefined()
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
