import type { AgentDraft, ModelDraft } from '@/features/ai/catalog/ai-console-types'
import { modelRef, type AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type { AgentProviderDTO } from '@/shared/api/contracts/ai-catalog'
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

export function normalizeCreateModelDraftProvider(
  draft: ModelDraft,
  providers: AgentProviderDTO[],
  preferredProviderName?: string | null,
): ModelDraft {
  const currentProviderName = trimValue(draft.providerName)
  if (currentProviderName && providers.some((provider) => provider.name === currentProviderName)) {
    return draft
  }

  const preferred = trimValue(preferredProviderName)
  if (preferred && providers.some((provider) => provider.name === preferred)) {
    return preferred === draft.providerName ? draft : { ...draft, providerName: preferred }
  }

  const fallbackProviderName = providers[0]?.name ?? ''
  if (!fallbackProviderName || fallbackProviderName === draft.providerName) {
    return draft
  }
  return { ...draft, providerName: fallbackProviderName }
}

/**
 * Edit mode owns the persisted provider identity. The provider list is only a display source and
 * may not contain the immutable identity when a provider is deleted or outside the current page.
 */
export function normalizeEditModelDraftProvider(
  draft: ModelDraft,
  immutableProviderName: string,
): ModelDraft {
  return draft.providerName === immutableProviderName
    ? draft
    : { ...draft, providerName: immutableProviderName }
}

export function normalizeAgentDraftDefaultVariant(draft: AgentDraft, models: AgentModelView[]): AgentDraft {
  const selectedModel = models.find((model) => modelRef(model) === draft.model)
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

export function normalizeCreateAgentDraftSelection(
  draft: AgentDraft,
  models: AgentModelView[],
  preferredModel?: string | null,
): AgentDraft {
  if (models.some((model) => modelRef(model) === draft.model)) {
    return normalizeAgentDraftDefaultVariant(draft, models)
  }

  const preferredModelValue = preferredModel?.trim()
  const preferred = preferredModelValue
    ? models.find((model) => modelRef(model) === preferredModelValue)
    : undefined
  if (preferred) {
    return normalizeAgentDraftDefaultVariant(
      {
        ...draft,
        model: modelRef(preferred),
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
      model: modelRef(fallback),
      variant: '',
    },
    models,
  )
}

/**
 * Edit mode owns the persisted model reference. When that model is not loaded, leave variant
 * untouched because there is no trustworthy model config from which to derive options.
 */
export function normalizeEditAgentDraftSelection(
  draft: AgentDraft,
  immutableModelRef: string,
  models: AgentModelView[],
): AgentDraft {
  const identityDraft =
    draft.model === immutableModelRef
      ? draft
      : { ...draft, model: immutableModelRef }
  const selectedModel = models.find((model) => modelRef(model) === immutableModelRef)
  return selectedModel
    ? normalizeAgentDraftDefaultVariant(identityDraft, models)
    : identityDraft
}

export function applyAgentModelSelection(
  draft: AgentDraft,
  model: string,
  models: AgentModelView[],
): AgentDraft {
  const selectedModel = models.find((item) => modelRef(item) === model)
  if (!selectedModel) {
    return { ...draft, model, variant: '' }
  }
  return {
    ...draft,
    model: modelRef(selectedModel),
    // Clear override when model changes; model.defaultVariant is used unless user re-overrides.
    variant: '',
  }
}
