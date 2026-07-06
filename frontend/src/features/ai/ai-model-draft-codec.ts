import type { AgentModelCreateDTO, AgentModelDTO, AgentModelUpdateDTO, AgentProviderDTO } from '@/shared/api/contracts'
import type { ModelDraft } from '@/features/ai/ai-console-types'
import { parseObjectDrafts, parseVariantDrafts } from '@/features/ai/ai-resource-draft-parsers'
import { newVariantDraft, trimToNull } from '@/features/ai/ai-resource-draft-primitives'
import {
  serializeCapabilitiesDrafts,
  serializeNumericMetadataDrafts,
  serializeVariantDrafts,
} from '@/features/ai/ai-resource-draft-serializers'

export function emptyModelDraft(model?: AgentModelDTO, provider?: AgentProviderDTO): ModelDraft {
  return {
    provider: model?.providerName || provider?.name || '',
    name: '',
    description: '',
    defaultVariant: model?.defaultVariant || 'default',
    variants: [newVariantDraft({ name: model?.defaultVariant || 'default' })],
    capabilities: [],
    limits: [],
    pricing: [],
  }
}

export function toModelDraft(model: AgentModelDTO): ModelDraft {
  return {
    provider: model.providerName,
    name: model.name,
    description: model.description || '',
    defaultVariant: model.defaultVariant || 'default',
    variants: parseVariantDrafts(model.variantsJson),
    capabilities: parseObjectDrafts(model.capabilitiesJson),
    limits: parseObjectDrafts(model.limitJson),
    pricing: parseObjectDrafts(model.pricingJson),
  }
}

export function toEditableModel(draft: ModelDraft): AgentModelCreateDTO {
  return {
    provider: draft.provider.trim(),
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    capabilitiesJson: serializeCapabilitiesDrafts(draft.capabilities),
    limitJson: serializeNumericMetadataDrafts(draft.limits),
    pricingJson: serializeNumericMetadataDrafts(draft.pricing),
    defaultVariant: trimToNull(draft.defaultVariant),
    variantsJson: serializeVariantDrafts(draft.variants, draft.defaultVariant),
  }
}

export function toEditableModelUpdate(draft: ModelDraft): AgentModelUpdateDTO {
  const data = toEditableModel(draft)
  return {
    description: data.description,
    capabilitiesJson: data.capabilitiesJson,
    limitJson: data.limitJson,
    pricingJson: data.pricingJson,
    defaultVariant: data.defaultVariant,
    variantsJson: data.variantsJson,
    name: data.name,
  }
}
