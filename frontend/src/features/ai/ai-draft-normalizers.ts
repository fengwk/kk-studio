import type { AgentDraft, ModelDraft, VariantDraft } from '@/features/ai/ai-console-types'
import type { AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'

function trimValue(value: string | null | undefined): string {
  return typeof value === 'string' ? value.trim() : ''
}

function uniqueNonEmpty(values: string[]): string[] {
  return Array.from(new Set(values.map((value) => value.trim()).filter(Boolean)))
}

function resolvePreferredVariant(preferred: string, options: string[]): string {
  const normalized = preferred.trim()
  if (normalized && options.includes(normalized)) {
    return normalized
  }
  return options[0] ?? 'default'
}

function parseVariantNames(json: string | null): string[] {
  if (!json) {
    return []
  }
  try {
    const parsed = JSON.parse(json)
    if (!Array.isArray(parsed)) {
      return []
    }
    return uniqueNonEmpty(
      parsed.map((item) => {
        if (!item || typeof item !== 'object' || Array.isArray(item)) {
          return ''
        }
        return trimValue((item as Record<string, unknown>).name as string | undefined)
      }),
    )
  } catch {
    return []
  }
}

function findSelectedModel(models: AgentModelDTO[], providerName: string, modelName: string): AgentModelDTO | undefined {
  return models.find((model) => model.providerName === providerName && model.name === modelName)
}

function findUniqueModelByName(models: AgentModelDTO[], modelName: string): AgentModelDTO | undefined {
  const normalizedName = trimValue(modelName)
  if (!normalizedName) {
    return undefined
  }
  const matches = models.filter((model) => model.name === normalizedName)
  return matches.length === 1 ? matches[0] : undefined
}

function findOnlyModelByProvider(models: AgentModelDTO[], providerName: string): AgentModelDTO | undefined {
  const normalizedProvider = trimValue(providerName)
  if (!normalizedProvider) {
    return undefined
  }
  const matches = models.filter((model) => model.providerName === normalizedProvider)
  return matches.length === 1 ? matches[0] : undefined
}

export function variantOptionsFromDraft(variants: VariantDraft[], fallbackVariant?: string): string[] {
  const options = uniqueNonEmpty(variants.map((variant) => variant.name))
  if (options.length > 0) {
    return options
  }
  const fallback = trimValue(fallbackVariant) || 'default'
  return [fallback]
}

export function variantOptionsFromModel(model?: Pick<AgentModelDTO, 'variantsJson' | 'defaultVariant'> | null): string[] {
  const options = parseVariantNames(model?.variantsJson ?? null)
  if (options.length > 0) {
    return options
  }
  const fallback = trimValue(model?.defaultVariant) || 'default'
  return [fallback]
}

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
  const selectedModel = findSelectedModel(models, draft.defaultProvider, draft.defaultModel)
  if (!selectedModel) {
    const defaultVariant = trimValue(draft.defaultVariant) || 'default'
    return defaultVariant === draft.defaultVariant ? draft : { ...draft, defaultVariant }
  }

  const options = variantOptionsFromModel(selectedModel)
  const currentVariant = trimValue(draft.defaultVariant)
  const preferred = currentVariant && options.includes(currentVariant) ? currentVariant : trimValue(selectedModel.defaultVariant)
  const defaultVariant = resolvePreferredVariant(preferred, options)
  return defaultVariant === draft.defaultVariant ? draft : { ...draft, defaultVariant }
}

export function normalizeAgentDraftSelection(
  draft: AgentDraft,
  models: AgentModelDTO[],
  preferredSelection?: {
    defaultProvider: string
    defaultModel: string
    defaultVariant?: string | null
  },
): AgentDraft {
  if (findSelectedModel(models, draft.defaultProvider, draft.defaultModel)) {
    return normalizeAgentDraftDefaultVariant(draft, models)
  }

  const preferredModel = preferredSelection
    ? findSelectedModel(models, preferredSelection.defaultProvider, preferredSelection.defaultModel)
    : undefined
  if (preferredModel) {
    return normalizeAgentDraftDefaultVariant(
      {
        ...draft,
        defaultProvider: preferredModel.providerName,
        defaultModel: preferredModel.name,
        defaultVariant: trimValue(draft.defaultVariant) || trimValue(preferredSelection?.defaultVariant) || draft.defaultVariant,
      },
      models,
    )
  }

  const uniqueModelByName = findUniqueModelByName(models, draft.defaultModel)
  if (uniqueModelByName) {
    return normalizeAgentDraftDefaultVariant(
      {
        ...draft,
        defaultProvider: uniqueModelByName.providerName,
        defaultModel: uniqueModelByName.name,
      },
      models,
    )
  }

  const onlyModelByProvider = findOnlyModelByProvider(models, draft.defaultProvider)
  if (onlyModelByProvider) {
    return normalizeAgentDraftDefaultVariant(
      {
        ...draft,
        defaultProvider: onlyModelByProvider.providerName,
        defaultModel: onlyModelByProvider.name,
      },
      models,
    )
  }

  return normalizeAgentDraftDefaultVariant(draft, models)
}

export function applyAgentModelSelection(draft: AgentDraft, value: string, models: AgentModelDTO[]): AgentDraft {
  const slashIndex = value.indexOf('/')
  if (slashIndex < 0) {
    return draft
  }

  const defaultProvider = value.slice(0, slashIndex)
  const defaultModel = value.slice(slashIndex + 1)
  const selectedModel = findSelectedModel(models, defaultProvider, defaultModel)
  if (!selectedModel) {
    return {
      ...draft,
      defaultProvider,
      defaultModel,
    }
  }

  const options = variantOptionsFromModel(selectedModel)
  const defaultVariant = resolvePreferredVariant(trimValue(selectedModel.defaultVariant), options)
  return {
    ...draft,
    defaultProvider,
    defaultModel,
    defaultVariant,
  }
}
