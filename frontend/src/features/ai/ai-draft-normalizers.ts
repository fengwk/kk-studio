import type { AgentDraft, ModelDraft } from '@/features/ai/ai-console-types'
import { extractDefaultVariantFromModel } from '@/features/ai/ai-model-draft-codec'
import type { AgentModelView } from '@/features/ai/AgentModelView'
import type { AgentProviderDTO } from '@/shared/api/contracts'
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
    variantOptionsFromDraft(draft.variants),
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

export function normalizeAgentDraftDefaultVariant(draft: AgentDraft, models: AgentModelView[]): AgentDraft {
  const selectedModel = models.find((model) => String(model.id) === draft.modelId)
  if (!selectedModel) {
    const variant = trimValue(draft.variant)
    return variant === draft.variant ? draft : { ...draft, variant }
  }

  const options = variantOptionsFromModel(selectedModel)
  const currentVariant = trimValue(draft.variant)
  const configuredDefault = extractDefaultVariantFromModel(selectedModel)
  const preferred =
    currentVariant && options.includes(currentVariant)
      ? currentVariant
      : options.includes(configuredDefault)
        ? configuredDefault
        : options[0]
  const variant = resolvePreferredVariant(preferred ?? '', options)
  return variant === draft.variant ? draft : { ...draft, variant }
}

export function normalizeAgentDraftSelection(
  draft: AgentDraft,
  models: AgentModelView[],
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

export function applyAgentModelSelection(draft: AgentDraft, modelId: string, models: AgentModelView[]): AgentDraft {
  const selectedModel = models.find((model) => String(model.id) === modelId)
  if (!selectedModel) {
    return { ...draft, modelId }
  }
  const options = variantOptionsFromModel(selectedModel)
  const configuredDefault = extractDefaultVariantFromModel(selectedModel)
  const preferred = options.includes(configuredDefault) ? configuredDefault : options[0]
  const variant = resolvePreferredVariant(preferred ?? '', options)
  return {
    ...draft,
    modelId: String(selectedModel.id),
    variant,
  }
}
