import type { AgentDraft, ModelDraft } from '@/features/ai/ai-console-types'
import type { AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'
import {
  resolvePreferredVariant,
  trimValue,
  variantOptionsFromDraft,
  variantOptionsFromModel,
} from '@/features/ai/ai-draft-variant-options'

export { variantOptionsFromDraft, variantOptionsFromModel } from '@/features/ai/ai-draft-variant-options'

export function normalizeModelDraftDefaultVariant(draft: ModelDraft): ModelDraft {
  const defaultVariant = resolvePreferredVariant(
    draft.defaultVariant,
    variantOptionsFromDraft(draft.variants, draft.defaultVariant),
  )
  return defaultVariant === draft.defaultVariant ? draft : { ...draft, defaultVariant }
}

export function normalizeModelDraftProvider(
  draft: ModelDraft,
  providers: AgentProviderDTO[],
  preferredProviderId?: string | null,
): ModelDraft {
  const currentProviderId = trimValue(draft.providerId)
  if (currentProviderId && providers.some((provider) => String(provider.id) === currentProviderId)) {
    return draft
  }

  const preferred = trimValue(preferredProviderId)
  if (preferred && providers.some((provider) => String(provider.id) === preferred)) {
    return preferred === draft.providerId ? draft : { ...draft, providerId: preferred }
  }

  const fallbackProviderId = providers[0] ? String(providers[0].id) : ''
  if (!fallbackProviderId || fallbackProviderId === draft.providerId) {
    return draft
  }
  return { ...draft, providerId: fallbackProviderId }
}

export function normalizeAgentDraftDefaultVariant(draft: AgentDraft, models: AgentModelDTO[]): AgentDraft {
  const selectedModel = models.find((model) => String(model.id) === draft.modelId)
  if (!selectedModel) {
    const variant = trimValue(draft.variant) || 'default'
    return variant === draft.variant ? draft : { ...draft, variant }
  }

  const options = variantOptionsFromModel(selectedModel)
  const currentVariant = trimValue(draft.variant)
  const legacyDefault = trimValue((selectedModel as { defaultVariant?: string | null }).defaultVariant)
  const preferred =
    currentVariant && options.includes(currentVariant)
      ? currentVariant
      : legacyDefault && options.includes(legacyDefault)
        ? legacyDefault
        : options[0]
  const variant = resolvePreferredVariant(preferred ?? 'default', options)
  return variant === draft.variant ? draft : { ...draft, variant }
}

export function normalizeAgentDraftSelection(
  draft: AgentDraft,
  models: AgentModelDTO[],
  preferredModelId?: string | null,
): AgentDraft {
  if (models.some((model) => String(model.id) === draft.modelId)) {
    return normalizeAgentDraftDefaultVariant(draft, models)
  }

  const preferred = preferredModelId
    ? models.find((model) => String(model.id) === preferredModelId)
    : undefined
  if (preferred) {
    const options = variantOptionsFromModel(preferred)
    return normalizeAgentDraftDefaultVariant(
      {
        ...draft,
        modelId: String(preferred.id),
        variant: trimValue(draft.variant) || options[0] || draft.variant,
      },
      models,
    )
  }

  const fallback = models[0]
  if (!fallback) {
    return normalizeAgentDraftDefaultVariant(draft, models)
  }
  const options = variantOptionsFromModel(fallback)
  return normalizeAgentDraftDefaultVariant(
    {
      ...draft,
      modelId: String(fallback.id),
      variant: trimValue(draft.variant) || options[0] || draft.variant,
    },
    models,
  )
}

export function applyAgentModelSelection(draft: AgentDraft, modelId: string, models: AgentModelDTO[]): AgentDraft {
  const selectedModel = models.find((model) => String(model.id) === modelId)
  if (!selectedModel) {
    return { ...draft, modelId }
  }
  const options = variantOptionsFromModel(selectedModel)
  const legacyDefault = trimValue((selectedModel as { defaultVariant?: string | null }).defaultVariant)
  const preferred = legacyDefault && options.includes(legacyDefault) ? legacyDefault : options[0]
  const variant = resolvePreferredVariant(preferred ?? 'default', options)
  return {
    ...draft,
    modelId: String(selectedModel.id),
    variant,
  }
}
