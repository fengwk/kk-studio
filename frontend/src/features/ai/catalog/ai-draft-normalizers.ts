import type { AgentDraft, ModelDraft } from '@/features/ai/catalog/ai-console-types'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type { AgentProviderDTO } from '@/shared/api/contracts'
import {
  resolvePreferredVariant,
  trimValue,
  variantOptionsFromDraft,
  variantOptionsFromModel,
} from '@/features/ai/catalog/ai-draft-variant-options'

export { variantOptionsFromDraft, variantOptionsFromModel } from '@/features/ai/catalog/ai-draft-variant-options'

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
  // Empty override is intentional: use model.defaultVariant at runtime.
  if (!currentVariant) {
    return currentVariant === draft.variant ? draft : { ...draft, variant: '' }
  }
  // Drop invalid overrides so the form falls back to model default.
  if (!options.includes(currentVariant)) {
    return draft.variant === '' ? draft : { ...draft, variant: '' }
  }
  return currentVariant === draft.variant ? draft : { ...draft, variant: currentVariant }
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
    return normalizeAgentDraftDefaultVariant(
      {
        ...draft,
        modelId: String(preferred.id),
        // Switching default model clears override so the new model default applies.
        variant: '',
      },
      models,
    )
  }

  const fallback = models[0]
  if (!fallback) {
    return normalizeAgentDraftDefaultVariant(draft, models)
  }
  return normalizeAgentDraftDefaultVariant(
    {
      ...draft,
      modelId: String(fallback.id),
      variant: '',
    },
    models,
  )
}

export function applyAgentModelSelection(draft: AgentDraft, modelId: string, models: AgentModelView[]): AgentDraft {
  const selectedModel = models.find((model) => String(model.id) === modelId)
  if (!selectedModel) {
    return { ...draft, modelId, variant: '' }
  }
  return {
    ...draft,
    modelId: String(selectedModel.id),
    // Clear override when model changes; model.defaultVariant is used unless user re-overrides.
    variant: '',
  }
}
