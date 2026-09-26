import type { ModelDraft, ModelPricingDraft, VariantDraft } from '@/features/ai/catalog/ai-console-types'
import {
  newVariantDraft,
  numberToNull,
  trimToNull,
} from '@/features/ai/catalog/ai-resource-draft-primitives'
import type {
  AgentModelConfigDTO,
  AgentModelCreateDTO,
  AgentModelDTO,
  AgentModelEditablePropertiesDTO,
  AgentModelInputModality,
  AgentModelPricingDTO,
  AgentModelVariantDTO,
} from '@/shared/api/contracts/ai-catalog'

const AGENT_MODEL_MODALITIES: AgentModelInputModality[] = [
  'TEXT',
  'IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]
const AGENT_MODEL_MODALITY_SET = new Set<string>(AGENT_MODEL_MODALITIES)

/**
 * 新建 model 的默认 pricing 元数据。表单不展示这些字段；
 * 已有 model 保留其之前持久化的元数据。
 */
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
  provider?: { name: string } | null,
): ModelDraft {
  return {
    providerName: provider?.name ?? '',
    name: '',
    modelId: '',
    description: '',
    contextWindow: '128000',
    maxOutputTokens: '8192',
    tools: true,
    reasoning: false,
    inputModalities: ['TEXT'],
    pricing: defaultPricingDraft(),
    defaultVariant: 'medium',
    variants: [newVariantDraft({ id: 'medium' })],
  }
}

export function formatProtocolOptions(
  options?: Record<string, unknown> | null,
): string {
  if (!options || typeof options !== 'object' || Array.isArray(options)) {
    return ''
  }
  try {
    return JSON.stringify(options, null, 2)
  } catch {
    return ''
  }
}

export function parseProtocolOptions(
  raw: string | undefined,
  variantId: string,
): Record<string, unknown> | undefined {
  if (!raw || !raw.trim()) {
    return undefined
  }
  const trimmed = raw.trim()
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    throw new Error(`variant ${variantId} protocolOptions must be valid JSON`)
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error(`variant ${variantId} protocolOptions must be a JSON object`)
  }
  return parsed as Record<string, unknown>
}

function parseVariant(variant: AgentModelVariantDTO): VariantDraft {
  return newVariantDraft({
    id: variant.id,
    reasoningEffort: variant.reasoningEffort == null ? '' : String(variant.reasoningEffort),
    protocolOptions: formatProtocolOptions(variant.protocolOptions),
  })
}

function parsePricing(pricing: AgentModelPricingDTO): ModelPricingDraft {
  return {
    currency: pricing.currency,
    pricingTier: pricing.pricingTier,
    serviceTier: pricing.serviceTier,
    serviceTierMultiplier: String(pricing.serviceTierMultiplier),
    version: pricing.version,
    inputPerMillionTokens: String(pricing.inputPerMillionTokens),
    outputPerMillionTokens: String(pricing.outputPerMillionTokens),
    cacheReadPerMillionTokens: String(pricing.cacheReadPerMillionTokens),
    cacheWritePerMillionTokens: String(pricing.cacheWritePerMillionTokens),
    cacheWriteLongPerMillionTokens: String(pricing.cacheWriteLongPerMillionTokens),
    reasoningPerMillionTokens: String(pricing.reasoningPerMillionTokens),
  }
}

export function toModelDraft(model: AgentModelDTO): ModelDraft {
  const config = model.config

  return {
    providerName: model.providerName,
    name: model.name,
    modelId: model.modelId,
    description: model.description || '',
    contextWindow: String(config.limit.context),
    maxOutputTokens: String(config.limit.output),
    tools: config.abilities.tools,
    reasoning: config.abilities.reasoning,
    inputModalities: [...config.abilities.inputModalities],
    defaultVariant: config.defaultVariant,
    variants: config.variants.map(parseVariant),
    pricing: parsePricing(config.pricing),
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

function normalizeModalities(
  values: ReadonlyArray<AgentModelInputModality>,
  field: string,
): AgentModelInputModality[] {
  if (values.length === 0) {
    throw new Error(`${field} must contain at least one input modality`)
  }
  const seen = new Set<AgentModelInputModality>()
  for (const value of values) {
    if (!AGENT_MODEL_MODALITY_SET.has(value)) {
      throw new Error(`${field} contains unsupported value: ${value}`)
    }
    if (seen.has(value)) {
      throw new Error(`${field} contains duplicate value: ${value}`)
    }
    seen.add(value)
  }
  return [...values]
}

function serializeVariants(
  variants: VariantDraft[],
  reasoningEnabled = true,
): AgentModelVariantDTO[] {
  if (variants.length === 0) {
    throw new Error('at least one variant is required')
  }
  const ids = new Set<string>()
  return variants.map((variant, index) => {
    const id = variant.id.trim()
    if (!id) {
      throw new Error(`variant ${index + 1} id is required`)
    }
    if (ids.has(id)) {
      throw new Error(`duplicate variant id: ${id}`)
    }
    ids.add(id)

    const payload: AgentModelVariantDTO = { id }
    const reasoningEffort = variant.reasoningEffort.trim().toLowerCase()
    if (reasoningEnabled && reasoningEffort) {
      if (reasoningEffort.length > 64) {
        throw new Error(`variant ${id} reasoningEffort must not exceed 64 characters`)
      }
      payload.reasoningEffort = reasoningEffort
    }
    const protocolOptions = parseProtocolOptions(variant.protocolOptions, id)
    if (protocolOptions !== undefined) {
      payload.protocolOptions = protocolOptions
    }
    return payload
  })
}

function buildModelConfig(draft: ModelDraft): AgentModelConfigDTO {
  const contextWindow = requirePositiveInt(draft.contextWindow, 'config.limit.context')
  const maxOutputTokens = requirePositiveInt(
    draft.maxOutputTokens,
    'config.limit.output',
  )
  if (maxOutputTokens > contextWindow) {
    throw new Error('config.limit.output must not exceed limit.context')
  }
  const modalities = normalizeModalities(draft.inputModalities, 'config.abilities.inputModalities')

  const variants = serializeVariants(draft.variants, draft.reasoning)
  const defaultVariant = draft.defaultVariant.trim()
  if (!defaultVariant || !variants.some((variant) => variant.id === defaultVariant)) {
    throw new Error('config.defaultVariant must match a variant id')
  }

  return {
    limit: { context: contextWindow, output: maxOutputTokens },
    abilities: {
      tools: draft.tools,
      reasoning: draft.reasoning,
      inputModalities: modalities,
    },
    pricing: pricingFromDraft(draft.pricing),
    defaultVariant,
    variants,
  }
}

function pricingFromDraft(draft: ModelPricingDraft): AgentModelPricingDTO {
  const multiplier = requirePositiveNumber(
    draft.serviceTierMultiplier,
    'config.pricing.serviceTierMultiplier',
  )
  return {
    currency: requireNonBlank(draft.currency, 'config.pricing.currency'),
    pricingTier: requireNonBlank(draft.pricingTier, 'config.pricing.pricingTier'),
    serviceTier: requireNonBlank(draft.serviceTier, 'config.pricing.serviceTier'),
    serviceTierMultiplier: multiplier,
    version: requireNonBlank(draft.version, 'config.pricing.version'),
    inputPerMillionTokens: requireNonNegativeNumber(
      draft.inputPerMillionTokens,
      'config.pricing.inputPerMillionTokens',
    ),
    outputPerMillionTokens: requireNonNegativeNumber(
      draft.outputPerMillionTokens,
      'config.pricing.outputPerMillionTokens',
    ),
    cacheReadPerMillionTokens: requireNonNegativeNumber(
      draft.cacheReadPerMillionTokens,
      'config.pricing.cacheReadPerMillionTokens',
    ),
    cacheWritePerMillionTokens: requireNonNegativeNumber(
      draft.cacheWritePerMillionTokens,
      'config.pricing.cacheWritePerMillionTokens',
    ),
    cacheWriteLongPerMillionTokens: requireNonNegativeNumber(
      draft.cacheWriteLongPerMillionTokens,
      'config.pricing.cacheWriteLongPerMillionTokens',
    ),
    reasoningPerMillionTokens: requireNonNegativeNumber(
      draft.reasoningPerMillionTokens,
      'config.pricing.reasoningPerMillionTokens',
    ),
  }
}

function requireNonBlank(value: string, field: string): string {
  const normalized = trimToNull(value)
  if (!normalized) {
    throw new Error(`${field} must not be blank`)
  }
  return normalized
}

function requirePositiveNumber(value: string, field: string): number {
  const parsed = requireNumber(value, field)
  if (parsed <= 0) {
    throw new Error(`${field} must be positive`)
  }
  return parsed
}

export function toEditableModel(draft: ModelDraft): AgentModelCreateDTO {
  const providerName = draft.providerName.trim()
  if (!providerName) {
    throw new Error('providerName is required')
  }
  return {
    providerName,
    name: requireNonBlank(draft.name, 'name'),
    modelId: requireNonBlank(draft.modelId, 'modelId'),
    description: trimToNull(draft.description),
    config: buildModelConfig(draft),
  }
}

export function toEditableModelUpdate(draft: ModelDraft): AgentModelEditablePropertiesDTO {
  return {
    name: requireNonBlank(draft.name, 'name'),
    modelId: requireNonBlank(draft.modelId, 'modelId'),
    description: trimToNull(draft.description),
    config: buildModelConfig(draft),
  }
}

export function extractVariantIdsFromModel(model?: AgentModelDTO | null): string[] {
  return model?.config?.variants?.map((variant) => variant.id) ?? []
}

export function extractDefaultVariantFromModel(model?: AgentModelDTO | null): string {
  return model?.config?.defaultVariant ?? ''
}

export function extractContextWindow(model?: AgentModelDTO | null): number | undefined {
  return model?.config?.limit?.context
}

export function extractMaxOutputTokens(model?: AgentModelDTO | null): number | undefined {
  return model?.config?.limit?.output
}
