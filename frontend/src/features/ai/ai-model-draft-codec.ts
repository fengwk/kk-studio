import type { AgentModelCreateDTO, AgentModelDTO, AgentModelUpdateDTO, AgentProviderDTO } from '@/shared/api/contracts'
import type { ModelDraft } from '@/features/ai/ai-console-types'
import { parseVariantDrafts } from '@/features/ai/ai-resource-draft-parsers'
import { newVariantDraft, trimToNull } from '@/features/ai/ai-resource-draft-primitives'
import { serializeVariantDrafts } from '@/features/ai/ai-resource-draft-serializers'

export function emptyModelDraft(model?: AgentModelDTO, provider?: AgentProviderDTO): ModelDraft {
  return {
    provider: model?.providerName || provider?.name || '',
    name: '',
    description: '',
    defaultVariant: model?.defaultVariant || 'default',
    variants: [newVariantDraft({ name: model?.defaultVariant || 'default' })],
  }
}

export function toModelDraft(model: AgentModelDTO): ModelDraft {
  return {
    provider: model.providerName,
    name: model.name,
    description: model.description || '',
    defaultVariant: model.defaultVariant || 'default',
    variants: parseVariantDrafts(model.variantsJson),
  }
}

export function toEditableModel(draft: ModelDraft): AgentModelCreateDTO {
  return {
    provider: draft.provider.trim(),
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    defaultVariant: trimToNull(draft.defaultVariant),
    variantsJson: serializeVariantDrafts(draft.variants, draft.defaultVariant),
  }
}

export function toEditableModelUpdate(draft: ModelDraft): AgentModelUpdateDTO {
  const data = toEditableModel(draft)
  return {
    description: data.description,
    defaultVariant: data.defaultVariant,
    variantsJson: data.variantsJson,
    name: data.name,
  }
}
