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
 * 编辑模式持有持久化的 provider 身份。provider 列表仅作为展示来源，当 provider 被删除或不在当前页面时，
 * 其中可能不含该不可变身份。
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
  // 空覆盖是有意为之：runtime 使用 model.defaultVariant。
  if (!currentVariant) {
    return currentVariant === draft.variant ? draft : { ...draft, variant: '' }
  }
  // 丢弃无效覆盖，以便表单回退到 model 默认值。
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
        // 切换默认 model 时清空覆盖，以便应用新 model 的默认值。
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
 * 编辑模式持有持久化的 model 引用。当该 model 未加载时，保持 variant 不变，因为没有可信的 model config
 * 可用于推导选项。
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
    // model 切换时清空覆盖；除非用户重新覆盖，否则使用 model.defaultVariant。
    variant: '',
  }
}
