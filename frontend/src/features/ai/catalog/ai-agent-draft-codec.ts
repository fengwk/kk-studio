import type {
  AgentDefinitionConfigDTO,
  AgentDefinitionCreateDTO,
  AgentDefinitionDTO,
  AgentDefinitionEditablePropertiesDTO,
  AgentModelDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import { modelRef } from '@/features/ai/catalog/AgentModelView'
import { trimToNull } from '@/features/ai/catalog/ai-resource-draft-primitives'
import { translate } from '@/shared/i18n'

function normalizeNames(items: string[] | null | undefined): string[] {
  if (!items?.length) {
    return []
  }
  return items.map((item) => item.trim()).filter(Boolean)
}

function normalizeCapabilityShortNames(
  items: string[] | null | undefined,
  kind: 'tools' | 'skills',
): string[] {
  const shortNames = normalizeNames(items)
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const name of shortNames) {
    if (seen.has(name)) {
      duplicates.add(name)
    }
    seen.add(name)
  }
  if (duplicates.size > 0) {
    throw new Error(
      translate('ai.catalog.validation.capabilityDuplicate', {
        kind,
        names: [...duplicates].join(', '),
      }),
    )
  }
  return shortNames
}

function toConfig(draft: AgentDraft): AgentDefinitionConfigDTO {
  return {
    tools: normalizeCapabilityShortNames(draft.tools, 'tools'),
    skills: normalizeCapabilityShortNames(draft.skills, 'skills'),
  }
}

export function emptyAgentDraft(model?: AgentModelDTO): AgentDraft {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    model: model ? modelRef(model) : '',
    // Empty = no override; runtime uses model.defaultVariant.
    variant: '',
    tools: [],
    skills: [],
  }
}

export function toAgentDraft(agent: AgentDefinitionDTO): AgentDraft {
  const config = agent.config
  return {
    name: agent.name,
    description: agent.description || '',
    systemPrompt: agent.systemPrompt || '',
    model: agent.model,
    variant: agent.variant?.trim() || '',
    tools: normalizeNames(config.tools),
    skills: normalizeNames(config.skills),
  }
}

export function toEditableAgent(draft: AgentDraft): AgentDefinitionCreateDTO {
  const name = draft.name.trim()
  if (!name) {
    throw new Error('name must not be blank')
  }
  const model = draft.model.trim()
  if (!model) {
    throw new Error('model must not be blank')
  }
  // Blank override is allowed; backend resolves model.defaultVariant when needed.
  const variant = trimToNull(draft.variant)
  return {
    name,
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    model,
    variant,
    config: toConfig(draft),
  }
}

export function toEditableAgentUpdate(draft: AgentDraft): AgentDefinitionEditablePropertiesDTO {
  return {
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    variant: trimToNull(draft.variant),
    config: toConfig(draft),
  }
}
