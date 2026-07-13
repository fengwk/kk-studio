import type { AgentDefinitionCreateDTO, AgentDefinitionDTO, AgentDefinitionUpdateDTO, AgentModelDTO } from '@/shared/api/contracts'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import { parseStringList } from '@/features/ai/ai-resource-draft-parsers'
import { trimToNull } from '@/features/ai/ai-resource-draft-primitives'
import { serializeStringList } from '@/features/ai/ai-resource-draft-serializers'

export function emptyAgentDraft(model?: AgentModelDTO): AgentDraft {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    defaultProvider: model?.providerName || '',
    defaultModel: model?.name || '',
    defaultVariant: model?.defaultVariant || 'default',
    tools: [],
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
  }
}

export function toEditableAgentUpdate(draft: AgentDraft): AgentDefinitionUpdateDTO {
  const data = toEditableAgent(draft)
  return {
    description: data.description,
    systemPrompt: data.systemPrompt,
    defaultVariant: data.defaultVariant,
    toolsJson: data.toolsJson,
    name: data.name,
    defaultProvider: data.defaultProvider,
    defaultModel: data.defaultModel,
  }
}
