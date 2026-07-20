import type { ModelDraft, ModelPricingDraft, VariantDraft } from '@/features/ai/ai-console-types'
import {
  newVariantDraft,
  numberToNull,
  splitCommaSeparatedValues,
  trimToNull,
} from '@/features/ai/ai-resource-draft-primitives'
import type {
  AgentModelConfigDTO,
  AgentModelCreateDTO,
  AgentModelDTO,
  AgentModelInputModality,
  AgentModelPricingDTO,
  AgentModelUpdateDTO,
  AgentModelVariantDTO,
  AgentResourceId,
} from '@/shared/api/contracts'

const AGENT_MODEL_MODALITIES: AgentModelInputModality[] = [
  'TEXT',
  'IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]
const AGENT_MODEL_MODALITY_SET = new Set<string>(AGENT_MODEL_MODALITIES)

/** Default tiers/version for newly created entries; the form does not expose metadata. */
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

export function emptyModelDraft(
  model?: AgentModelDTO,
  provider?: { id: AgentResourceId | string } | null,
): ModelDraft {
  const providerId = model?.providerId
    ? String(model.providerId)
    : provider
      ? String(provider.id)
      : ''
  const limit = model?.config?.limit
  const abilities = model?.config?.abilities
  return {
    providerId,
    name: '',
    description: '',
    contextWindow: limit && limit.context != null ? String(limit.context) : '128000',
    maxOutputTokens: limit && limit.output != null ? String(limit.output) : '8192',
    tools: typeof abilities?.tools === 'boolean' ? abilities.tools : true,
    reasoning: typeof abilities?.reasoning === 'boolean' ? abilities.reasoning : false,
    inputModalities: new Set(AGENT_MODEL_MODALITIES),
    pricing: defaultPricingDraft(),
    defaultVariant: 'medium',
    variants: [newVariantDraft({ name: 'medium' })],
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

function toStringSet(value: unknown): Set<AgentModelInputModality> {
  if (!Array.isArray(value)) {
    return new Set()
  }
  return new Set(
    value
      .map((item) => String(item ?? '').trim().toUpperCase())
      .filter((item) => AGENT_MODEL_MODALITY_SET.has(item))
      .map((item) => item as AgentModelInputModality),
  )
}

function modalitiesToList(set: Set<AgentModelInputModality>): AgentModelInputModality[] {
  return AGENT_MODEL_MODALITIES.filter((m) => set.has(m))
}

function parseVariant(record: Record<string, unknown>): VariantDraft {
  // New schema uses `id`; tolerate empty `name` so legacy data is shown but flagged.
  const id = String(record.id ?? record.name ?? '').trim()
  const stopRaw = record.stopSequences
  const stopSequences = Array.isArray(stopRaw)
    ? stopRaw
        .map((item) => String(item ?? '').trim())
        .filter(Boolean)
        .join(', ')
    : ''
  return newVariantDraft({
    name: id,
    reasoningEffort: record.reasoningEffort == null ? '' : String(record.reasoningEffort),
    maxOutputTokens: record.maxOutputTokens == null ? '' : String(record.maxOutputTokens),
    temperature: record.temperature == null ? '' : String(record.temperature),
    topP: record.topP == null ? '' : String(record.topP),
    topK: record.topK == null ? '' : String(record.topK),
    frequencyPenalty:
      record.frequencyPenalty == null ? '' : String(record.frequencyPenalty),
    presencePenalty:
      record.presencePenalty == null ? '' : String(record.presencePenalty),
    stopSequences,
  })
}

function parsePricing(record: Record<string, unknown>): ModelPricingDraft {
  const base = defaultPricingDraft()
  return {
    currency: String(record.currency ?? base.currency),
    pricingTier: String(record.pricingTier ?? base.pricingTier),
    serviceTier: String(record.serviceTier ?? base.serviceTier),
    serviceTierMultiplier:
      record.serviceTierMultiplier == null
        ? base.serviceTierMultiplier
        : String(record.serviceTierMultiplier),
    version: String(record.version ?? base.version),
    inputPerMillionTokens:
      record.inputPerMillionTokens == null
        ? base.inputPerMillionTokens
        : String(record.inputPerMillionTokens),
    outputPerMillionTokens:
      record.outputPerMillionTokens == null
        ? base.outputPerMillionTokens
        : String(record.outputPerMillionTokens),
    cacheReadPerMillionTokens:
      record.cacheReadPerMillionTokens == null
        ? base.cacheReadPerMillionTokens
        : String(record.cacheReadPerMillionTokens),
    cacheWritePerMillionTokens:
      record.cacheWritePerMillionTokens == null
        ? base.cacheWritePerMillionTokens
        : String(record.cacheWritePerMillionTokens),
    cacheWriteLongPerMillionTokens:
      record.cacheWriteLongPerMillionTokens == null
        ? base.cacheWriteLongPerMillionTokens
        : String(record.cacheWriteLongPerMillionTokens),
    reasoningPerMillionTokens:
      record.reasoningPerMillionTokens == null
        ? base.reasoningPerMillionTokens
        : String(record.reasoningPerMillionTokens),
  }
}

function parseVariants(config: Record<string, unknown>): VariantDraft[] {
  const raw = config.variants
  if (!Array.isArray(raw)) {
    return [newVariantDraft({ name: 'medium' })]
  }
  const variants = raw
    .map((item) => parseVariant(asRecord(item)))
    .filter((variant) => variant.name.trim().length > 0)
  return variants.length > 0
    ? variants
    : [newVariantDraft({ name: 'medium' })]
}

export function toModelDraft(model: AgentModelDTO): ModelDraft {
  const base = emptyModelDraft(model)
  const config = asRecord(model.config as unknown)
  if (!config || Object.keys(config).length === 0) {
    return base
  }
  const limit = asRecord(config.limit)
  const contextWindow = trimToNull(stringField(limit, 'context')) ?? base.contextWindow
  const maxOutputTokens = trimToNull(stringField(limit, 'output')) ?? base.maxOutputTokens
  const abilities = asRecord(config.abilities)
  const variantIds = parseVariants(config).map((variant) => variant.name)
  const configuredDefault = trimToNull(stringField(config, 'defaultVariant')) ?? ''
  const defaultVariant = variantIds.includes(configuredDefault) ? configuredDefault : variantIds[0] ?? base.defaultVariant

  const tools = abilities.tools
  const reasoning = abilities.reasoning
  const inputModalities = toStringSet(abilities.inputModalities)
  return {
    providerId: model.providerId ? String(model.providerId) : base.providerId,
    name: model.name,
    description: model.description || '',
    contextWindow,
    maxOutputTokens,
    tools: typeof tools === 'boolean' ? tools : base.tools,
    reasoning: typeof reasoning === 'boolean' ? reasoning : base.reasoning,
    inputModalities: inputModalities.size > 0 ? inputModalities : base.inputModalities,
    defaultVariant,
    variants: parseVariants(config),
    pricing: parsePricing(asRecord(config.pricing)),
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

function normalizeModalities(
  values: Set<AgentModelInputModality>,
  field: string,
): AgentModelInputModality[] {
  const allowed = modalitiesToList(values)
  if (allowed.length === 0) {
    throw new Error(`${field} must contain at least one input modality`)
  }
  return allowed
}

function serializeVariants(
  variants: VariantDraft[],
  reasoning: boolean,
): AgentModelVariantDTO[] {
  if (variants.length === 0) {
    throw new Error('at least one variant is required')
  }
  const ids = new Set<string>()
  return variants.map((variant, index) => {
    const name = variant.name.trim()
    if (!name) {
      throw new Error(`variant ${index + 1} id is required`)
    }
    if (ids.has(name)) {
      throw new Error(`duplicate variant id: ${name}`)
    }
    ids.add(name)

    const payload: AgentModelVariantDTO = { id: name }
    const reasoningEffort = variant.reasoningEffort.trim()
    if (reasoning) {
      if (!reasoningEffort) {
        throw new Error(
          `variant ${name} reasoningEffort is required when reasoning is enabled`,
        )
      }
      if (reasoningEffort.toLowerCase() !== 'off') {
        payload.reasoningEffort = reasoningEffort
      }
    }
    // Reasoning disabled: drop any stale hidden reasoning-effort values.
    else if (reasoningEffort && reasoningEffort.toLowerCase() === 'off') {
      // Provider explicitly requested "off"; preserve as null but the serializer
      // already collapses empty strings, so omit it here.
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
        payload[jsonField as keyof AgentModelVariantDTO] = value as never
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

export function buildModelConfig(draft: ModelDraft): AgentModelConfigDTO {
  const contextWindow = requirePositiveInt(draft.contextWindow, 'configJson.limit.context')
  const maxOutputTokens = requirePositiveInt(
    draft.maxOutputTokens,
    'configJson.limit.output',
  )
  if (maxOutputTokens > contextWindow) {
    throw new Error('configJson.limit.output must not exceed limit.context')
  }
  const modalities = normalizeModalities(draft.inputModalities, 'inputModalities')

  const variants = serializeVariants(draft.variants, draft.reasoning)
  for (const variant of variants) {
    if (
      typeof variant.maxOutputTokens === 'number' &&
      variant.maxOutputTokens > maxOutputTokens
    ) {
      throw new Error(`variant ${variant.id} maxOutputTokens exceeds model maxOutputTokens`)
    }
  }
  const defaultVariant = draft.defaultVariant.trim()
  if (!defaultVariant || !variants.some((variant) => variant.id === defaultVariant)) {
    throw new Error('configJson.defaultVariant must match a variant id')
  }

  const pricing = pricingFromDraft(draft.pricing)
  return {
    limit: { context: contextWindow, output: maxOutputTokens },
    abilities: {
      tools: draft.tools,
      reasoning: draft.reasoning,
      inputModalities: modalities,
    },
    pricing,
    defaultVariant,
    variants,
  }
}

function pricingFromDraft(draft: ModelPricingDraft): AgentModelPricingDTO {
  return {
    currency: 'USD',
    pricingTier: 'default',
    serviceTier: 'default',
    serviceTierMultiplier: requirePositiveNumber(
      draft.serviceTierMultiplier,
      'pricing.serviceTierMultiplier',
    ),
    version: 'v1',
    inputPerMillionTokens: requireNonNegativeNumber(
      draft.inputPerMillionTokens,
      'pricing.inputPerMillionTokens',
    ),
    outputPerMillionTokens: requireNonNegativeNumber(
      draft.outputPerMillionTokens,
      'pricing.outputPerMillionTokens',
    ),
    cacheReadPerMillionTokens: requireNonNegativeNumber(
      draft.cacheReadPerMillionTokens,
      'pricing.cacheReadPerMillionTokens',
    ),
    cacheWritePerMillionTokens: requireNonNegativeNumber(
      draft.cacheWritePerMillionTokens,
      'pricing.cacheWritePerMillionTokens',
    ),
    cacheWriteLongPerMillionTokens: requireNonNegativeNumber(
      draft.cacheWriteLongPerMillionTokens,
      'pricing.cacheWriteLongPerMillionTokens',
    ),
    reasoningPerMillionTokens: requireNonNegativeNumber(
      draft.reasoningPerMillionTokens,
      'pricing.reasoningPerMillionTokens',
    ),
  }
}

function requirePositiveNumber(value: string, field: string): number {
  const parsed = requireNumber(value, field)
  if (parsed <= 0) {
    throw new Error(`${field} must be positive`)
  }
  return parsed
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
    config: buildModelConfig(draft),
  }
}

export function toEditableModelUpdate(draft: ModelDraft): AgentModelUpdateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    config: buildModelConfig(draft),
  }
}

export function extractVariantNamesFromModel(model?: AgentModelDTO | null): string[] {
  if (!model?.config) {
    return ['medium']
  }
  const raw = (model.config as AgentModelConfigDTO).variants
  if (!Array.isArray(raw) || raw.length === 0) {
    return ['medium']
  }
  const names = raw
    .map((variant) => String(variant.id ?? '').trim())
    .filter(Boolean)
  return names.length > 0 ? names : ['medium']
}

export function extractDefaultVariantFromModel(model?: AgentModelDTO | null): string {
  const names = extractVariantNamesFromModel(model)
  const configured = model?.config?.defaultVariant?.trim() ?? ''
  return names.includes(configured) ? configured : names[0] ?? 'medium'
}

function extractLimitNumber(value: number | string | null | undefined): number | undefined {
  if (value == null) {
    return undefined
  }
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

export function extractContextWindow(model?: AgentModelDTO | null): number | undefined {
  return extractLimitNumber(model?.config?.limit?.context ?? null)
}

export function extractMaxOutputTokens(model?: AgentModelDTO | null): number | undefined {
  return extractLimitNumber(model?.config?.limit?.output ?? null)
}

function stringField(record: Record<string, unknown>, key: string): string {
  const value = record[key]
  return value == null ? '' : String(value)
}
