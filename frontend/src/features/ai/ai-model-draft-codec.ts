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

/**
 * Default pricing metadata for freshly created models. The form does not surface these fields;
 * existing models keep whatever metadata was persisted.
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
  model?: AgentModelDTO,
  provider?: { id: AgentResourceId | string } | null,
): ModelDraft {
  const providerId = model?.providerId
    ? String(model.providerId)
    : provider
      ? String(provider.id)
      : ''
  const limit = model?.config.limit
  const abilities = model?.config.abilities
  return {
    providerId,
    name: '',
    description: '',
    contextWindow: limit ? String(limit.context) : '128000',
    maxOutputTokens: limit ? String(limit.output) : '8192',
    tools: typeof abilities?.tools === 'boolean' ? abilities.tools : true,
    reasoning: typeof abilities?.reasoning === 'boolean' ? abilities.reasoning : false,
    // New models default to TEXT-only; existing models surface whatever the persisted config
    // claims (preserving operators who intentionally enabled multimodal models).
    inputModalities: model
      ? readInputModalities(abilities?.inputModalities ?? ['TEXT'])
      : ['TEXT'],
    pricing: defaultPricingDraft(),
    defaultVariant: 'medium',
    variants: [newVariantDraft({ name: 'medium' })],
  }
}

function readInputModalities(raw: readonly AgentModelInputModality[]): AgentModelInputModality[] {
  const ordered: AgentModelInputModality[] = []
  const seen = new Set<AgentModelInputModality>()
  for (const item of raw) {
    if (AGENT_MODEL_MODALITY_SET.has(item) && !seen.has(item)) {
      seen.add(item)
      ordered.push(item)
    }
  }
  // Always keep TEXT; if everything was filtered out the model still accepts text prompts.
  if (ordered.length === 0) {
    ordered.push('TEXT')
  }
  return ordered
}

function modalitiesToList(set: ReadonlyArray<AgentModelInputModality>): AgentModelInputModality[] {
  // Preserve the draft's declared order so toggling UI selections matches persisted selections.
  const seen = new Set<AgentModelInputModality>()
  const result: AgentModelInputModality[] = []
  for (const item of set) {
    if (AGENT_MODEL_MODALITY_SET.has(item) && !seen.has(item)) {
      seen.add(item)
      result.push(item)
    }
  }
  return result
}

function parseVariant(variant: AgentModelVariantDTO): VariantDraft {
  const id = (variant.id ?? '').trim()
  const stopRaw = variant.stopSequences
  const stopSequences = Array.isArray(stopRaw)
    ? stopRaw
        .map((item) => String(item ?? '').trim())
        .filter(Boolean)
        .join(', ')
    : ''
  return newVariantDraft({
    name: id,
    reasoningEffort: variant.reasoningEffort == null ? '' : String(variant.reasoningEffort),
    maxOutputTokens: variant.maxOutputTokens == null ? '' : String(variant.maxOutputTokens),
    temperature: variant.temperature == null ? '' : String(variant.temperature),
    topP: variant.topP == null ? '' : String(variant.topP),
    topK: variant.topK == null ? '' : String(variant.topK),
    frequencyPenalty:
      variant.frequencyPenalty == null ? '' : String(variant.frequencyPenalty),
    presencePenalty:
      variant.presencePenalty == null ? '' : String(variant.presencePenalty),
    stopSequences,
  })
}

function parsePricing(pricing: AgentModelPricingDTO): ModelPricingDraft {
  const base = defaultPricingDraft()
  return {
    currency: pricing.currency || base.currency,
    pricingTier: pricing.pricingTier || base.pricingTier,
    serviceTier: pricing.serviceTier || base.serviceTier,
    serviceTierMultiplier:
      pricing.serviceTierMultiplier == null
        ? base.serviceTierMultiplier
        : String(pricing.serviceTierMultiplier),
    version: pricing.version || base.version,
    inputPerMillionTokens:
      pricing.inputPerMillionTokens == null
        ? base.inputPerMillionTokens
        : String(pricing.inputPerMillionTokens),
    outputPerMillionTokens:
      pricing.outputPerMillionTokens == null
        ? base.outputPerMillionTokens
        : String(pricing.outputPerMillionTokens),
    cacheReadPerMillionTokens:
      pricing.cacheReadPerMillionTokens == null
        ? base.cacheReadPerMillionTokens
        : String(pricing.cacheReadPerMillionTokens),
    cacheWritePerMillionTokens:
      pricing.cacheWritePerMillionTokens == null
        ? base.cacheWritePerMillionTokens
        : String(pricing.cacheWritePerMillionTokens),
    cacheWriteLongPerMillionTokens:
      pricing.cacheWriteLongPerMillionTokens == null
        ? base.cacheWriteLongPerMillionTokens
        : String(pricing.cacheWriteLongPerMillionTokens),
    reasoningPerMillionTokens:
      pricing.reasoningPerMillionTokens == null
        ? base.reasoningPerMillionTokens
        : String(pricing.reasoningPerMillionTokens),
  }
}

export function toModelDraft(model: AgentModelDTO): ModelDraft {
  const base = emptyModelDraft(model)
  const config = model.config
  const variantIds = config.variants.map((variant) => (variant.id ?? '').trim()).filter(Boolean)
  const configuredDefault = (config.defaultVariant ?? '').trim()
  const defaultVariant = variantIds.includes(configuredDefault) ? configuredDefault : variantIds[0] ?? base.defaultVariant

  const tools = config.abilities.tools
  const reasoning = config.abilities.reasoning
  const inputModalities = readInputModalities(config.abilities.inputModalities)

  return {
    providerId: model.providerId ? String(model.providerId) : base.providerId,
    name: model.name,
    description: model.description || '',
    contextWindow: config.limit.context != null ? String(config.limit.context) : base.contextWindow,
    maxOutputTokens: config.limit.output != null ? String(config.limit.output) : base.maxOutputTokens,
    tools: typeof tools === 'boolean' ? tools : base.tools,
    reasoning: typeof reasoning === 'boolean' ? reasoning : base.reasoning,
    inputModalities: inputModalities.length > 0 ? inputModalities : base.inputModalities,
    defaultVariant,
    variants: config.variants
      .map((variant) => parseVariant(variant))
      .filter((variant) => variant.name.trim().length > 0),
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
  values: ReadonlyArray<AgentModelInputModality>,
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

    const maxOutputTokens = optionalPositiveInt(
      variant.maxOutputTokens,
      `variant ${name} maxOutputTokens`,
    )
    if (maxOutputTokens !== null) {
      payload.maxOutputTokens = maxOutputTokens
    }

    const optionalNumbers: ReadonlyArray<{
      draftField: keyof VariantDraft
      wireField: keyof AgentModelVariantDTO
    }> = [
      { draftField: 'temperature', wireField: 'temperature' },
      { draftField: 'topP', wireField: 'topP' },
      { draftField: 'frequencyPenalty', wireField: 'frequencyPenalty' },
      { draftField: 'presencePenalty', wireField: 'presencePenalty' },
    ]
    for (const { draftField, wireField } of optionalNumbers) {
      const value = optionalNumber(
        variant[draftField] as string,
        `variant ${name} ${wireField}`,
      )
      if (value !== null) {
        // Cast: wireField is constrained to optional numeric VariantDraftDTO keys by
        // the optionalNumbers table, so this assignment is well-typed.
        (payload as unknown as Record<string, number | string | null>)[wireField] = value
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
    currency: trimToNull(draft.currency) ?? 'USD',
    pricingTier: trimToNull(draft.pricingTier) ?? 'default',
    serviceTier: trimToNull(draft.serviceTier) ?? 'default',
    serviceTierMultiplier: multiplier,
    version: trimToNull(draft.version) ?? 'v1',
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
  const name = draft.name.trim()
  if (!name) {
    throw new Error('config.name must not be blank')
  }
  return {
    providerId,
    name,
    description: trimToNull(draft.description),
    config: buildModelConfig(draft),
  }
}

export function toEditableModelUpdate(draft: ModelDraft): AgentModelUpdateDTO {
  const name = draft.name.trim()
  if (!name) {
    throw new Error('config.name must not be blank')
  }
  return {
    name,
    description: trimToNull(draft.description),
    config: buildModelConfig(draft),
  }
}

export function extractVariantNamesFromModel(model?: AgentModelDTO | null): string[] {
  if (!model?.config) {
    return ['medium']
  }
  const raw = model.config.variants
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
  const configured = (model?.config.defaultVariant ?? '').trim()
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
  return extractLimitNumber(model?.config.limit.context)
}

export function extractMaxOutputTokens(model?: AgentModelDTO | null): number | undefined {
  return extractLimitNumber(model?.config.limit.output)
}