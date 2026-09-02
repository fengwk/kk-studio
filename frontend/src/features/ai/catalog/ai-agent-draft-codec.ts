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

function normalizeCapabilityValues(
  items: string[] | null | undefined,
  kind: 'toolIds' | 'skills' | 'subagents',
): string[] {
  const values = normalizeNames(items)
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const value of values) {
    if (seen.has(value)) {
      duplicates.add(value)
    }
    seen.add(value)
  }
  if (duplicates.size > 0) {
    throw new Error(
      translate('ai.catalog.validation.capabilityDuplicate', {
        kind,
        names: [...duplicates].join(', '),
      }),
    )
  }
  return values
}

function toConfig(draft: AgentDraft): AgentDefinitionConfigDTO {
  return {
    toolIds: normalizeCapabilityValues(draft.toolIds, 'toolIds'),
    skills: normalizeCapabilityValues(draft.skills, 'skills'),
    subagents: normalizeCapabilityValues(draft.subagents, 'subagents'),
  }
}

export function emptyAgentDraft(model?: AgentModelDTO): AgentDraft {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    model: model ? modelRef(model) : '',
    // 空值 = 不覆盖；runtime 使用 model.defaultVariant。
    variant: '',
    environmentId: '',
    toolIds: [],
    skills: [],
    subagents: [],
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
    environmentId: agent.environmentId || '',
    toolIds: normalizeNames(config.toolIds),
    skills: normalizeNames(config.skills),
    subagents: normalizeNames(config.subagents),
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
  // 允许空覆盖；后端在需要时回退到 model.defaultVariant。
  const variant = trimToNull(draft.variant)
  return {
    name,
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    model,
    variant,
    environmentId: trimToNull(draft.environmentId ?? ''),
    config: toConfig(draft),
  }
}

export function toEditableAgentUpdate(draft: AgentDraft): AgentDefinitionEditablePropertiesDTO {
  const model = draft.model.trim()
  if (!model) {
    throw new Error('model must not be blank')
  }
  return {
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    model,
    variant: trimToNull(draft.variant),
    environmentId: trimToNull(draft.environmentId ?? ''),
    config: toConfig(draft),
  }
}
