import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderDTO,
  AgentProviderUpdateDTO,
} from '@/shared/api/contracts'
import type { AgentDraft, ModelDraft, ProviderDraft } from '@/features/ai/ai-console-types'
import { parseObjectDrafts, parseStringList, parseVariantDrafts } from '@/features/ai/ai-resource-draft-parsers'
import { newVariantDraft, numberToNull, trimToNull } from '@/features/ai/ai-resource-draft-primitives'
import { serializeCapabilitiesDrafts, serializeNumericMetadataDrafts, serializeStringList, serializeVariantDrafts } from '@/features/ai/ai-resource-draft-serializers'

export function emptyProviderDraft(): ProviderDraft {
  return {
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    apiKey: '',
    timeoutMillis: '60000',
    streamIdleTimeoutMillis: '60000',
  }
}

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

export function emptyAgentDraft(model?: AgentModelDTO): AgentDraft {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    defaultProvider: model?.providerName || '',
    defaultModel: model?.name || '',
    defaultVariant: model?.defaultVariant || 'default',
    tools: [],
    subagents: [],
    skills: [],
  }
}

export function toProviderDraft(provider: AgentProviderDTO): ProviderDraft {
  return {
    name: provider.name,
    description: provider.description || '',
    providerType: provider.providerType || 'openai',
    baseUrl: provider.baseUrl || '',
    apiKey: provider.apiKey || '',
    timeoutMillis: provider.timeoutMillis ? String(provider.timeoutMillis) : '',
    streamIdleTimeoutMillis: provider.streamIdleTimeoutMillis ? String(provider.streamIdleTimeoutMillis) : '',
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

export function toAgentDraft(agent: AgentDefinitionDTO): AgentDraft {
  return {
    name: agent.name,
    description: agent.description || '',
    systemPrompt: agent.systemPrompt || '',
    defaultProvider: agent.defaultProviderName,
    defaultModel: agent.defaultModelName,
    defaultVariant: agent.defaultVariant || 'default',
    tools: parseStringList(agent.toolsJson),
    subagents: parseStringList(agent.subagentsJson),
    skills: parseStringList(agent.skillsJson),
  }
}

export function toEditableProvider(draft: ProviderDraft): AgentProviderCreateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    providerType: draft.providerType.trim(),
    baseUrl: trimToNull(draft.baseUrl),
    apiKey: trimToNull(draft.apiKey),
    timeoutMillis: numberToNull(draft.timeoutMillis),
    streamIdleTimeoutMillis: numberToNull(draft.streamIdleTimeoutMillis),
  }
}

export function toEditableProviderUpdate(draft: ProviderDraft): AgentProviderUpdateDTO {
  return toEditableProvider(draft)
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

export function toEditableAgent(draft: AgentDraft): AgentDefinitionCreateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    defaultProvider: draft.defaultProvider.trim(),
    defaultModel: draft.defaultModel.trim(),
    defaultVariant: trimToNull(draft.defaultVariant),
    toolsJson: serializeStringList(draft.tools),
    subagentsJson: serializeStringList(draft.subagents),
    skillsJson: serializeStringList(draft.skills),
  }
}

export function toEditableAgentUpdate(draft: AgentDraft): AgentDefinitionUpdateDTO {
  const data = toEditableAgent(draft)
  return {
    description: data.description,
    systemPrompt: data.systemPrompt,
    defaultVariant: data.defaultVariant,
    toolsJson: data.toolsJson,
    subagentsJson: data.subagentsJson,
    skillsJson: data.skillsJson,
    name: data.name,
    defaultProvider: data.defaultProvider,
    defaultModel: data.defaultModel,
  }
}
