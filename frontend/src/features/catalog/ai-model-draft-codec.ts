import type { ModelDraft, VariantDraft } from '@/features/catalog/ai-console-types'
import {
  newVariantDraft,
  trimToNull,
} from '@/features/catalog/ai-resource-draft-primitives'
import {
  clonePricing,
  emptyPricingDraft,
  normalizePricing,
} from '@/features/catalog/ai-model-pricing-codec'
import { defaultPromptCacheRetention } from '@/shared/api/contracts/ai-catalog'
import type {
  ModelConfigDTO,
  ModelDTO,
  ModelInputModality,
  ModelVariantDTO,
  ProviderDTO,
  SingleModelCreateDTO,
  SingleModelDTO,
} from '@/shared/api/generated/contracts'

const AGENT_MODEL_MODALITIES: ModelInputModality[] = [
  'TEXT',
  'IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]
const AGENT_MODEL_MODALITY_SET = new Set<string>(AGENT_MODEL_MODALITIES)

export function emptyModelDraft(
  provider?: ({ name: string } & Partial<Pick<ProviderDTO, 'promptCacheRetentions'>>) | null,
): ModelDraft {
  const retention = defaultPromptCacheRetention(
    provider?.promptCacheRetentions ? { promptCacheRetentions: provider.promptCacheRetentions } : null,
  )
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
    pricing: emptyPricingDraft(),
    defaultVariant: 'medium',
    variants: [newVariantDraft({ id: 'medium', promptCacheRetention: retention })],
  }
}

/** 只借助 JSON.parse 校验语法与根节点；绝不序列化解析后的浮点数。 */
export function parseProtocolOptionsJson(
  raw: string | undefined,
  variantId: string,
): string | undefined {
  if (!raw || !raw.trim()) {
    return undefined
  }
  const trimmed = raw.trim()
  if (new TextEncoder().encode(trimmed).length > 65536) {
    throw new Error(`variant ${variantId} protocolOptionsJson must not exceed 65536 UTF-8 bytes`)
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    throw new Error(`variant ${variantId} protocolOptionsJson must be valid JSON`)
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error(`variant ${variantId} protocolOptionsJson must be a JSON object`)
  }
  return trimmed
}

function parseVariant(variant: ModelVariantDTO): VariantDraft {
  return newVariantDraft({
    id: variant.id,
    reasoningEffort: variant.reasoningEffort == null ? '' : String(variant.reasoningEffort),
    protocolOptionsJson: variant.protocolOptionsJson ?? '',
    promptCacheRetention: variant.promptCacheRetention,
  })
}

export function toModelDraft(model: ModelDTO): ModelDraft {
  if (model.type !== 'MODEL') {
    throw new Error(`${model.name} is not a single model`)
  }
  const config = model.config
  // 单 Model 的 config 总是完整的；pricing/defaultVariant 只在组的派生摘要里为空。
  if (!config?.pricing || !config.defaultVariant) {
    throw new Error(`MODEL ${model.name} is missing config`)
  }

  return {
    providerName: model.providerName ?? '',
    name: model.name,
    modelId: model.modelId ?? '',
    description: model.description || '',
    contextWindow: String(config.limit.context),
    maxOutputTokens: String(config.limit.output),
    tools: config.abilities.tools,
    reasoning: config.abilities.reasoning,
    inputModalities: [...config.abilities.inputModalities],
    defaultVariant: config.defaultVariant,
    variants: config.variants.map(parseVariant),
    pricing: clonePricing(config.pricing),
  }
}

function requirePositiveInt(value: string, field: string): number {
  const normalized = value.trim()
  if (!/^[1-9]\d*$/.test(normalized)) {
    throw new Error(`${field} must be a positive integer`)
  }
  const parsed = Number(normalized)
  if (!Number.isSafeInteger(parsed)) {
    throw new Error(`${field} must be a safe positive integer`)
  }
  return parsed
}

function normalizeModalities(
  values: ReadonlyArray<ModelInputModality>,
  field: string,
): ModelInputModality[] {
  if (values.length === 0) {
    throw new Error(`${field} must contain at least one input modality`)
  }
  const seen = new Set<ModelInputModality>()
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
): ModelVariantDTO[] {
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

    const payload: ModelVariantDTO = {
      id,
      promptCacheRetention: variant.promptCacheRetention,
    }
    const reasoningEffort = variant.reasoningEffort.trim()
    if (reasoningEnabled && reasoningEffort) {
      if (reasoningEffort.length > 64) {
        throw new Error(`variant ${id} reasoningEffort must not exceed 64 characters`)
      }
      payload.reasoningEffort = reasoningEffort
    }
    const protocolOptionsJson = parseProtocolOptionsJson(variant.protocolOptionsJson, id)
    if (protocolOptionsJson !== undefined) {
      payload.protocolOptionsJson = protocolOptionsJson
    }
    return payload
  })
}

export function buildModelConfig(draft: ModelDraft): ModelConfigDTO {
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
    pricing: normalizePricing(draft.pricing),
    defaultVariant,
    variants,
  }
}

function requireNonBlank(value: string, field: string): string {
  const normalized = trimToNull(value)
  if (!normalized) {
    throw new Error(`${field} must not be blank`)
  }
  return normalized
}

/** MODEL 的完整可编辑属性：创建与 PUT 都整体替换，Provider 是必填属性而不是路径身份。 */
export function toEditableModel(draft: ModelDraft): SingleModelCreateDTO {
  const providerName = draft.providerName.trim()
  if (!providerName) {
    throw new Error('providerName is required')
  }
  return {
    name: requireNonBlank(draft.name, 'name'),
    type: 'MODEL',
    description: trimToNull(draft.description),
    providerName,
    modelId: requireNonBlank(draft.modelId, 'modelId'),
    config: buildModelConfig(draft),
  }
}

function asSingleModel(model?: ModelDTO | null): SingleModelDTO | null {
  return model?.type === 'MODEL' ? model : null
}

export function extractVariantIdsFromModel(model?: ModelDTO | null): string[] {
  return asSingleModel(model)?.config.variants.map((variant) => variant.id) ?? []
}

export function extractDefaultVariantFromModel(model?: ModelDTO | null): string {
  return asSingleModel(model)?.config.defaultVariant ?? ''
}

export function extractContextWindow(model?: ModelDTO | null): number | undefined {
  return asSingleModel(model)?.config.limit.context
}

export function extractMaxOutputTokens(model?: ModelDTO | null): number | undefined {
  return asSingleModel(model)?.config.limit.output
}
