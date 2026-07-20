import type {
  AgentModelCreateDTO,
  AgentModelDTO,
  AgentModelUpdateDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts'
import type { ModelDraft, ModelPricingDraft, VariantDraft } from '@/features/ai/ai-console-types'
import {
  findKnownModelDefault,
  genericModelDefaults,
  type KnownModelDefault,
} from '@/features/ai/known-model-catalog'
import { newVariantDraft, numberToNull, trimToNull } from '@/features/ai/ai-resource-draft-primitives'

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

function pricingFromKnown(known: KnownModelDefault): ModelPricingDraft {
  const p = known.pricing
  return {
    currency: p.currency,
    pricingTier: p.pricingTier,
    serviceTier: p.serviceTier,
    serviceTierMultiplier: String(p.serviceTierMultiplier),
    version: p.version,
    inputPerMillionTokens: String(p.inputPerMillionTokens),
    outputPerMillionTokens: String(p.outputPerMillionTokens),
    cacheReadPerMillionTokens: String(p.cacheReadPerMillionTokens),
    cacheWritePerMillionTokens: String(p.cacheWritePerMillionTokens),
    cacheWriteLongPerMillionTokens: String(p.cacheWriteLongPerMillionTokens),
    reasoningPerMillionTokens: String(p.reasoningPerMillionTokens),
  }
}

function variantsFromKnown(known: KnownModelDefault): VariantDraft[] {
  return known.variants.map((variant) =>
    newVariantDraft({
      name: variant.name,
      thinkingLevel: variant.thinkingLevel,
      temperature: variant.temperature != null ? String(variant.temperature) : '',
      maxOutputTokens: variant.maxOutputTokens != null ? String(variant.maxOutputTokens) : '',
    }),
  )
}

export function modelDraftFromKnown(
  known: KnownModelDefault,
  providerId = '',
  preserveName = true,
): ModelDraft {
  return {
    providerId,
    name: preserveName ? known.displayName : '',
    description: '',
    contextWindow: String(known.contextWindow),
    maxOutputTokens: String(known.maxOutputTokens),
    inputModalities: [...known.inputModalities],
    capabilities: [...known.capabilities],
    reasoning: known.reasoning,
    defaultVariant: known.defaultVariant,
    variants: variantsFromKnown(known),
    pricing: pricingFromKnown(known),
  }
}

export function emptyModelDraft(model?: AgentModelDTO, provider?: AgentProviderDTO): ModelDraft {
  const providerId = model?.providerId ? String(model.providerId) : provider ? String(provider.id) : ''
  const known = findKnownModelDefault(model?.name) ?? genericModelDefaults('custom-model')
  return modelDraftFromKnown(known, providerId, false)
}

function parseConfigJson(configJson: string | null | undefined): Record<string, unknown> {
  if (!configJson?.trim()) {
    return {}
  }
  try {
    const parsed = JSON.parse(configJson) as unknown
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {}
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

function parseVariants(config: Record<string, unknown>): VariantDraft[] {
  const raw = config.variants
  if (!Array.isArray(raw) || raw.length === 0) {
    return [newVariantDraft({ name: 'default', thinkingLevel: 'off' })]
  }
  return raw.map((item) => {
    const record = item && typeof item === 'object' && !Array.isArray(item) ? (item as Record<string, unknown>) : {}
    const name = String(record.name ?? '').trim() || 'default'
    const thinkingLevel = String(record.thinkingLevel ?? name).trim() || 'off'
    return newVariantDraft({
      name,
      thinkingLevel,
      temperature: record.temperature == null ? '' : String(record.temperature),
      maxOutputTokens: record.maxOutputTokens == null ? '' : String(record.maxOutputTokens),
    })
  })
}

function parsePricing(config: Record<string, unknown>): ModelPricingDraft {
  const pricing =
    config.pricing && typeof config.pricing === 'object' && !Array.isArray(config.pricing)
      ? (config.pricing as Record<string, unknown>)
      : {}
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
  const variants = parseVariants(config)
  const defaultVariant =
    variants.find((item) => item.name === 'default')?.name
    ?? variants[0]?.name
    ?? 'default'
  const capabilities = parseStringArray(
    (() => {
      try {
        return model.capabilitiesJson ? JSON.parse(model.capabilitiesJson) : []
      } catch {
        return []
      }
    })(),
  )
  return {
    providerId: String(model.providerId ?? ''),
    name: model.name,
    description: model.description || '',
    contextWindow: String(config.contextWindow ?? ''),
    maxOutputTokens: String(config.maxOutputTokens ?? ''),
    inputModalities: parseStringArray(config.inputModalities).length
      ? parseStringArray(config.inputModalities)
      : ['TEXT'],
    capabilities: capabilities.length ? capabilities : ['TEXT', 'TOOLS'],
    reasoning: Boolean(config.reasoning ?? capabilities.includes('REASONING')),
    defaultVariant,
    variants,
    pricing: parsePricing(config),
  }
}

/** Apply known-model catalog defaults for the current name (used by form auto-fill / reset). */
export function applyKnownModelDefaults(draft: ModelDraft): ModelDraft {
  const known = findKnownModelDefault(draft.name) ?? genericModelDefaults(draft.name.trim() || 'custom-model')
  return {
    ...modelDraftFromKnown(known, draft.providerId, true),
    name: draft.name.trim() || known.displayName,
    description: draft.description,
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

function serializeVariants(variants: VariantDraft[]) {
  const normalized = variants
    .map((variant) => {
      const name = variant.name.trim()
      if (!name) {
        return null
      }
      const payload: Record<string, string | number> = {
        name,
        thinkingLevel: (variant.thinkingLevel.trim() || name).toLowerCase(),
      }
      const temperature = numberToNull(variant.temperature)
      if (temperature !== null) {
        payload.temperature = temperature
      }
      const maxOutputTokens = numberToNull(variant.maxOutputTokens)
      if (maxOutputTokens !== null) {
        payload.maxOutputTokens = maxOutputTokens
      }
      return payload
    })
    .filter((item): item is Record<string, string | number> => Boolean(item))
  if (normalized.length === 0) {
    throw new Error('at least one variant is required')
  }
  return normalized
}

export function buildModelConfigJson(draft: ModelDraft): string {
  const contextWindow = requirePositiveInt(draft.contextWindow, 'contextWindow')
  const maxOutputTokens = requirePositiveInt(draft.maxOutputTokens, 'maxOutputTokens')
  if (maxOutputTokens > contextWindow) {
    throw new Error('maxOutputTokens must not exceed contextWindow')
  }
  const variants = serializeVariants(draft.variants)
  for (const variant of variants) {
    const variantMax = variant.maxOutputTokens
    if (typeof variantMax === 'number' && variantMax > maxOutputTokens) {
      throw new Error(`variant ${variant.name} maxOutputTokens exceeds model maxOutputTokens`)
    }
  }
  const pricing = draft.pricing
  return JSON.stringify({
    contextWindow,
    maxOutputTokens,
    reasoning: draft.reasoning,
    inputModalities: draft.inputModalities.length ? draft.inputModalities : ['TEXT'],
    variants,
    pricing: {
      currency: pricing.currency.trim() || 'USD',
      pricingTier: pricing.pricingTier.trim() || 'default',
      serviceTier: pricing.serviceTier.trim() || 'default',
      serviceTierMultiplier: requireNumber(pricing.serviceTierMultiplier, 'serviceTierMultiplier'),
      version: pricing.version.trim() || 'v1',
      inputPerMillionTokens: requireNumber(pricing.inputPerMillionTokens, 'inputPerMillionTokens'),
      outputPerMillionTokens: requireNumber(pricing.outputPerMillionTokens, 'outputPerMillionTokens'),
      cacheReadPerMillionTokens: requireNumber(pricing.cacheReadPerMillionTokens, 'cacheReadPerMillionTokens'),
      cacheWritePerMillionTokens: requireNumber(pricing.cacheWritePerMillionTokens, 'cacheWritePerMillionTokens'),
      cacheWriteLongPerMillionTokens: requireNumber(
        pricing.cacheWriteLongPerMillionTokens,
        'cacheWriteLongPerMillionTokens',
      ),
      reasoningPerMillionTokens: requireNumber(pricing.reasoningPerMillionTokens, 'reasoningPerMillionTokens'),
    },
  })
}

export function buildModelCapabilitiesJson(draft: ModelDraft): string {
  const capabilities = draft.capabilities.map((item) => item.trim()).filter(Boolean)
  if (capabilities.length === 0) {
    throw new Error('capabilities must not be empty')
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
  if (model?.configJson) {
    const config = parseConfigJson(model.configJson)
    const variants = parseVariants(config)
    const names = variants.map((item) => item.name.trim()).filter(Boolean)
    if (names.length > 0) {
      return names
    }
  }
  // Legacy test fixtures / transitional clients may still provide variantsJson.
  const legacy = (model as { variantsJson?: string | null } | null | undefined)?.variantsJson
  if (legacy) {
    try {
      const parsed = JSON.parse(legacy) as unknown
      if (Array.isArray(parsed)) {
        const names = parsed
          .map((item) => {
            if (!item || typeof item !== 'object' || Array.isArray(item)) {
              return ''
            }
            return String((item as { name?: unknown }).name ?? '').trim()
          })
          .filter(Boolean)
        if (names.length > 0) {
          return names
        }
      }
    } catch {
      // ignore
    }
  }
  const legacyDefault = String(
    (model as { defaultVariant?: string | null } | null | undefined)?.defaultVariant ?? '',
  ).trim()
  return [legacyDefault || 'default']
}

export function extractContextWindow(model?: AgentModelDTO | null): number | undefined {
  if (!model?.configJson) {
    return undefined
  }
  const config = parseConfigJson(model.configJson)
  const value = Number(config.contextWindow)
  return Number.isFinite(value) && value > 0 ? value : undefined
}
