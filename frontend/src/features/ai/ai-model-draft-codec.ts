import type { ModelDraft, ModelPricingDraft, VariantDraft } from '@/features/ai/ai-console-types'
import {
  newVariantDraft,
  numberToNull,
  splitCommaSeparatedValues,
  trimToNull,
} from '@/features/ai/ai-resource-draft-primitives'
import type {
  AgentModelCreateDTO,
  AgentModelDTO,
  AgentModelUpdateDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts'

const MODEL_MODALITIES = ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT'] as const
const MODEL_MODALITY_SET = new Set<string>(MODEL_MODALITIES)

function defaultPricingDraft(): ModelPricingDraft {
  return {
    currency: 'USD',
    pricingTier: 'default',
    serviceTier: 'default',
    serviceTierMultiplier: '1',
    version: 'v1',
    inputPerMillionTokens: '0',
    outputPerMillionTokens: '0',
    cacheReadPerMillionTokens: '0',
    cacheWritePerMillionTokens: '0',
    cacheWriteLongPerMillionTokens: '0',
    reasoningPerMillionTokens: '0',
  }
}

export function emptyModelDraft(model?: AgentModelDTO, provider?: AgentProviderDTO): ModelDraft {
  const providerId = model?.providerId ? String(model.providerId) : provider ? String(provider.id) : ''
  return {
    providerId,
    name: '',
    description: '',
    contextWindow: '128000',
    maxOutputTokens: '8192',
    tools: true,
    reasoning: false,
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    defaultVariant: 'medium',
    variants: [newVariantDraft({ name: 'medium' })],
    pricing: defaultPricingDraft(),
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

function parseConfigJson(configJson: string | null | undefined): Record<string, unknown> {
  if (!configJson?.trim()) {
    return {}
  }
  try {
    return asRecord(JSON.parse(configJson) as unknown)
  } catch {
    return {}
  }
}

function parseStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map((item) => String(item ?? '').trim()).filter(Boolean)
}

function parseModalities(value: unknown, fallback: string[]): string[] {
  const modalities = Array.from(
    new Set(parseStringArray(value).map((item) => item.toUpperCase()).filter((item) => MODEL_MODALITY_SET.has(item))),
  )
  return modalities.length > 0 ? modalities : fallback
}

function parseOptionalScalar(value: unknown): string {
  return value == null ? '' : String(value)
}

function parseStopSequences(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map((item) => String(item ?? '').trim()).filter(Boolean).join(', ')
  }
  return parseOptionalScalar(value)
}

function parseVariants(config: Record<string, unknown>): VariantDraft[] {
  const raw = config.variants
  if (!Array.isArray(raw)) {
    return [newVariantDraft({ name: 'medium' })]
  }
  const variants = raw
    .map((item) => {
      const record = asRecord(item)
      // 新 schema 用 id；旧数据可能仍是 name。
      const name = String(record.id ?? record.name ?? '').trim()
      if (!name) {
        return null
      }
      return newVariantDraft({
        name,
        reasoningEffort: parseOptionalScalar(record.reasoningEffort ?? record.thinkingLevel),
        maxOutputTokens: parseOptionalScalar(record.maxOutputTokens),
        temperature: parseOptionalScalar(record.temperature),
        topP: parseOptionalScalar(record.topP),
        topK: parseOptionalScalar(record.topK),
        frequencyPenalty: parseOptionalScalar(record.frequencyPenalty),
        presencePenalty: parseOptionalScalar(record.presencePenalty),
        stopSequences: parseStopSequences(record.stopSequences),
      })
    })
    .filter((variant): variant is VariantDraft => variant !== null)
  return variants.length > 0 ? variants : [newVariantDraft({ name: 'medium' })]
}

function parsePricing(config: Record<string, unknown>): ModelPricingDraft {
  const pricing = asRecord(config.pricing)
  const base = defaultPricingDraft()
  return {
    currency: String(pricing.currency ?? base.currency),
    pricingTier: String(pricing.pricingTier ?? base.pricingTier),
    serviceTier: String(pricing.serviceTier ?? base.serviceTier),
    serviceTierMultiplier: String(pricing.serviceTierMultiplier ?? base.serviceTierMultiplier),
    version: String(pricing.version ?? base.version),
    inputPerMillionTokens: String(pricing.inputPerMillionTokens ?? base.inputPerMillionTokens),
    outputPerMillionTokens: String(pricing.outputPerMillionTokens ?? base.outputPerMillionTokens),
    cacheReadPerMillionTokens: String(pricing.cacheReadPerMillionTokens ?? base.cacheReadPerMillionTokens),
    cacheWritePerMillionTokens: String(pricing.cacheWritePerMillionTokens ?? base.cacheWritePerMillionTokens),
    cacheWriteLongPerMillionTokens: String(
      pricing.cacheWriteLongPerMillionTokens ?? base.cacheWriteLongPerMillionTokens,
    ),
    reasoningPerMillionTokens: String(pricing.reasoningPerMillionTokens ?? base.reasoningPerMillionTokens),
  }
}

export function toModelDraft(model: AgentModelDTO): ModelDraft {
  const config = parseConfigJson(model.configJson)
  const limit = asRecord(config.limit)
  const abilities = asRecord(config.abilities)
  const modalities = asRecord(abilities.modalities)
  const variants = parseVariants(config)
  const variantNames = variants.map((variant) => variant.name)
  const configuredDefaultVariant = String(config.defaultVariant ?? '').trim()
  const defaultVariant = variantNames.includes(configuredDefaultVariant)
    ? configuredDefaultVariant
    : (variantNames[0] ?? 'medium')
  const base = emptyModelDraft(model)
  // 兼容旧 config：顶层 contextWindow/maxOutputTokens/inputModalities/reasoning。
  const contextWindow = String(limit.context ?? config.contextWindow ?? base.contextWindow)
  const maxOutputTokens = String(limit.output ?? config.maxOutputTokens ?? base.maxOutputTokens)
  const capabilities = parseStringArray(
    (() => {
      try {
        return model.capabilitiesJson ? JSON.parse(model.capabilitiesJson) : []
      } catch {
        return []
      }
    })(),
  )
  const tools =
    typeof abilities.tools === 'boolean' ? abilities.tools : capabilities.includes('TOOLS')
  const reasoning =
    typeof abilities.reasoning === 'boolean'
      ? abilities.reasoning
      : Boolean(config.reasoning) || capabilities.includes('THINKING')
  const inputModalities = parseModalities(
    modalities.input ?? config.inputModalities,
    base.inputModalities,
  )
  const outputModalities = parseModalities(modalities.output, base.outputModalities)

  return {
    providerId: String(model.providerId ?? ''),
    name: model.name,
    description: model.description || '',
    contextWindow,
    maxOutputTokens,
    tools,
    reasoning,
    inputModalities,
    outputModalities,
    defaultVariant,
    variants,
    pricing: parsePricing(config),
  }
}

function requirePositiveInt(value: string, field: string): number {
  const parsed = numberToNull(value)
  if (parsed == null || !Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${field} must be a positive integer`)
  }
  return parsed
}

function requireNumber(value: string, field: string): number {
  const parsed = numberToNull(value)
  if (parsed == null) {
    throw new Error(`${field} must be a number`)
  }
  return parsed
}

function requireNonNegativeNumber(value: string, field: string): number {
  const parsed = requireNumber(value, field)
  if (parsed < 0) {
    throw new Error(`${field} must not be negative`)
  }
  return parsed
}

function optionalNumber(value: string, field: string): number | null {
  if (!value.trim()) {
    return null
  }
  return requireNumber(value, field)
}

function optionalPositiveInt(value: string, field: string): number | null {
  if (!value.trim()) {
    return null
  }
  return requirePositiveInt(value, field)
}

type SerializedVariant = Record<string, string | number | string[]>

function serializeVariants(variants: VariantDraft[], reasoning: boolean): SerializedVariant[] {
  if (variants.length === 0) {
    throw new Error('at least one variant is required')
  }
  const names = new Set<string>()
  return variants.map((variant, index) => {
    const name = variant.name.trim()
    if (!name) {
      throw new Error(`variant ${index + 1} id is required`)
    }
    if (names.has(name)) {
      throw new Error(`duplicate variant id: ${name}`)
    }
    names.add(name)

    const payload: SerializedVariant = { id: name }
    const reasoningEffort = variant.reasoningEffort.trim()
    if (reasoning) {
      if (!reasoningEffort) {
        throw new Error(`variant ${name} reasoningEffort is required when reasoning is enabled`)
      }
      if (reasoningEffort.toLowerCase() !== 'off') {
        payload.reasoningEffort = reasoningEffort
      }
    }

    const maxOutputTokens = optionalPositiveInt(
      variant.maxOutputTokens,
      `variant ${name} maxOutputTokens`,
    )
    if (maxOutputTokens !== null) {
      payload.maxOutputTokens = maxOutputTokens
    }

    const optionalNumbers: Array<[keyof VariantDraft, string]> = [
      ['temperature', 'temperature'],
      ['topP', 'topP'],
      ['frequencyPenalty', 'frequencyPenalty'],
      ['presencePenalty', 'presencePenalty'],
    ]
    for (const [field, jsonField] of optionalNumbers) {
      const value = optionalNumber(variant[field], `variant ${name} ${jsonField}`)
      if (value !== null) {
        payload[jsonField] = value
      }
    }

    const topK = optionalPositiveInt(variant.topK, `variant ${name} topK`)
    if (topK !== null) {
      payload.topK = topK
    }
    const stopSequences = splitCommaSeparatedValues(variant.stopSequences)
    if (stopSequences.length > 0) {
      payload.stopSequences = stopSequences
    }
    return payload
  })
}

function normalizeModalities(values: string[], field: string, fallback: string[]): string[] {
  const normalized = Array.from(new Set(values.map((item) => item.trim().toUpperCase()).filter(Boolean)))
  const unsupported = normalized.filter((item) => !MODEL_MODALITY_SET.has(item))
  if (unsupported.length > 0) {
    throw new Error(`${field} contains unsupported modalities: ${unsupported.join(', ')}`)
  }
  return normalized.length > 0 ? normalized : fallback
}

export function buildModelConfigJson(draft: ModelDraft): string {
  const contextWindow = requirePositiveInt(draft.contextWindow, 'contextWindow')
  const maxOutputTokens = requirePositiveInt(draft.maxOutputTokens, 'maxOutputTokens')
  if (maxOutputTokens > contextWindow) {
    throw new Error('maxOutputTokens must not exceed contextWindow')
  }

  const variants = serializeVariants(draft.variants, draft.reasoning)
  for (const variant of variants) {
    const variantMax = variant.maxOutputTokens
    if (typeof variantMax === 'number' && variantMax > maxOutputTokens) {
      throw new Error(`variant ${variant.id} maxOutputTokens exceeds model maxOutputTokens`)
    }
  }
  const defaultVariant = draft.defaultVariant.trim()
  if (!defaultVariant || !variants.some((variant) => variant.id === defaultVariant)) {
    throw new Error('defaultVariant must match a variant id')
  }

  const inputModalities = normalizeModalities(draft.inputModalities, 'inputModalities', ['TEXT'])
  const outputModalities = normalizeModalities(draft.outputModalities, 'outputModalities', ['TEXT'])
  const pricing = draft.pricing
  return JSON.stringify({
    limit: {
      context: contextWindow,
      output: maxOutputTokens,
    },
    abilities: {
      tools: draft.tools,
      reasoning: draft.reasoning,
      modalities: {
        input: inputModalities,
        output: outputModalities,
      },
    },
    // 账本元数据固定默认（currency/tier/version 不进 UI）；仅单价由用户配置。
    pricing: {
      currency: 'USD',
      pricingTier: 'default',
      serviceTier: 'default',
      serviceTierMultiplier: 1,
      version: 'v1',
      inputPerMillionTokens: requireNonNegativeNumber(
        pricing.inputPerMillionTokens,
        'inputPerMillionTokens',
      ),
      outputPerMillionTokens: requireNonNegativeNumber(
        pricing.outputPerMillionTokens,
        'outputPerMillionTokens',
      ),
      cacheReadPerMillionTokens: requireNonNegativeNumber(
        pricing.cacheReadPerMillionTokens,
        'cacheReadPerMillionTokens',
      ),
      cacheWritePerMillionTokens: requireNonNegativeNumber(
        pricing.cacheWritePerMillionTokens,
        'cacheWritePerMillionTokens',
      ),
      cacheWriteLongPerMillionTokens: requireNonNegativeNumber(
        pricing.cacheWriteLongPerMillionTokens,
        'cacheWriteLongPerMillionTokens',
      ),
      reasoningPerMillionTokens: requireNonNegativeNumber(
        pricing.reasoningPerMillionTokens,
        'reasoningPerMillionTokens',
      ),
    },
    defaultVariant,
    variants,
  })
}

export function buildModelCapabilitiesJson(draft: ModelDraft): string {
  const inputModalities = normalizeModalities(draft.inputModalities, 'inputModalities', ['TEXT'])
  const capabilities: string[] = []
  if (inputModalities.includes('TEXT')) {
    capabilities.push('TEXT')
  }
  if (draft.tools) {
    capabilities.push('TOOLS')
  }
  if (draft.reasoning) {
    capabilities.push('THINKING')
  }
  if (inputModalities.includes('IMAGE')) {
    capabilities.push('VISION')
  }
  if (inputModalities.includes('AUDIO')) {
    capabilities.push('AUDIO')
  }
  return JSON.stringify(capabilities)
}

export function toEditableModel(draft: ModelDraft): AgentModelCreateDTO {
  const providerId = draft.providerId.trim()
  if (!providerId) {
    throw new Error('providerId is required')
  }
  return {
    providerId,
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    capabilitiesJson: buildModelCapabilitiesJson(draft),
    configJson: buildModelConfigJson(draft),
  }
}

export function toEditableModelUpdate(draft: ModelDraft): AgentModelUpdateDTO {
  const data = toEditableModel(draft)
  return {
    name: data.name,
    description: data.description,
    capabilitiesJson: data.capabilitiesJson,
    configJson: data.configJson,
  }
}

export function extractVariantNamesFromModel(model?: AgentModelDTO | null): string[] {
  if (!model?.configJson) {
    return ['medium']
  }
  return parseVariants(parseConfigJson(model.configJson)).map((variant) => variant.name)
}

export function extractDefaultVariantFromModel(model?: AgentModelDTO | null): string {
  const names = extractVariantNamesFromModel(model)
  if (!model?.configJson) {
    return names[0] ?? 'medium'
  }
  const configured = String(parseConfigJson(model.configJson).defaultVariant ?? '').trim()
  return names.includes(configured) ? configured : (names[0] ?? 'medium')
}

export function extractContextWindow(model?: AgentModelDTO | null): number | undefined {
  if (!model?.configJson) {
    return undefined
  }
  const config = parseConfigJson(model.configJson)
  const limit = asRecord(config.limit)
  const value = Number(limit.context ?? config.contextWindow)
  return Number.isFinite(value) && value > 0 ? value : undefined
}

export function extractMaxOutputTokens(model?: AgentModelDTO | null): number | undefined {
  if (!model?.configJson) {
    return undefined
  }
  const config = parseConfigJson(model.configJson)
  const limit = asRecord(config.limit)
  const value = Number(limit.output ?? config.maxOutputTokens)
  return Number.isFinite(value) && value > 0 ? value : undefined
}
