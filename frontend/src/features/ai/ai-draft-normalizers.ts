import type { AgentDraft, ModelDraft } from '@/features/ai/ai-console-types'
import type { AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'
import { resolvePreferredVariant, trimValue, variantOptionsFromDraft, variantOptionsFromModel } from '@/features/ai/ai-draft-variant-options'

export { variantOptionsFromDraft, variantOptionsFromModel } from '@/features/ai/ai-draft-variant-options'

export function normalizeModelDraftDefaultVariant(draft: ModelDraft): ModelDraft {
  const defaultVariant = resolvePreferredVariant(draft.defaultVariant, variantOptionsFromDraft(draft.variants, draft.defaultVariant))
  return defaultVariant === draft.defaultVariant ? draft : { ...draft, defaultVariant }
}

export function normalizeModelDraftProvider(draft: ModelDraft, providers: AgentProviderDTO[], preferredProviderName?: string | null): ModelDraft {
  const currentProvider = trimValue(draft.provider)
  if (currentProvider && providers.some((provider) => provider.name === currentProvider)) {
    return draft
  }

  const preferredProvider = trimValue(preferredProviderName)
  if (preferredProvider && providers.some((provider) => provider.name === preferredProvider)) {
    return preferredProvider === draft.provider ? draft : { ...draft, provider: preferredProvider }
  }

  const fallbackProvider = providers[0]?.name
  if (!fallbackProvider || fallbackProvider === draft.provider) {
    return draft
  }
  return { ...draft, provider: fallbackProvider }
}

export function normalizeAgentDraftDefaultVariant(draft: AgentDraft, models: AgentModelDTO[]): AgentDraft {
  const selectedModel = models.find((model) => String(model.id) === draft.modelId)
  if (!selectedModel) {
    const variant = trimValue(draft.variant) || 'default'
    return variant === draft.variant ? draft : { ...draft, variant }
  }

  const options = variantOptionsFromModel(selectedModel)
  const currentVariant = trimValue(draft.variant)
  const preferred = currentVariant && options.includes(currentVariant) ? currentVariant : trimValue(selectedModel.defaultVariant)
  const variant = resolvePreferredVariant(preferred, options)
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
    return normalizeAgentDraftDefaultVariant(
      {
        ...draft,
        modelId: String(preferred.id),
        variant: trimValue(draft.variant) || preferred.defaultVariant || draft.variant,
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
      variant: trimValue(draft.variant) || fallback.defaultVariant || draft.variant,
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
  const variant = resolvePreferredVariant(trimValue(selectedModel.defaultVariant), options)
  return {
    ...draft,
    modelId: String(selectedModel.id),
    variant,
  }
}
