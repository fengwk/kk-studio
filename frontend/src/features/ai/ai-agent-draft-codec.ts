import type {
  AgentDefinitionConfigDTO,
  AgentDefinitionCreateDTO,
  AgentDefinitionDTO,
  AgentDefinitionUpdateDTO,
  AgentExecutionPolicyDTO,
  AgentModelDTO,
} from '@/shared/api/contracts'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import { trimToNull } from '@/features/ai/ai-resource-draft-primitives'

function normalizeNames(items: string[] | null | undefined): string[] {
  if (!items?.length) {
    return []
  }
  return items.map((item) => item.trim()).filter(Boolean)
}

function normalizePolicy(policy: AgentExecutionPolicyDTO | null | undefined): AgentDraft['executionPolicy'] {
  return {
    maxTurns: policy?.maxTurns != null ? String(policy.maxTurns) : '',
    maxDepth: policy?.maxDepth != null ? String(policy.maxDepth) : '',
    maxDirectSubagents: policy?.maxDirectSubagents != null ? String(policy.maxDirectSubagents) : '',
    maxTotalSubagents: policy?.maxTotalSubagents != null ? String(policy.maxTotalSubagents) : '',
  }
}

function parseOptionalInt(value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) {
    return null
  }
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null
}

function toConfig(draft: AgentDraft): AgentDefinitionConfigDTO {
  const executionPolicy: AgentExecutionPolicyDTO = {
    maxTurns: parseOptionalInt(draft.executionPolicy.maxTurns),
    maxDepth: parseOptionalInt(draft.executionPolicy.maxDepth),
    maxDirectSubagents: parseOptionalInt(draft.executionPolicy.maxDirectSubagents),
    maxTotalSubagents: parseOptionalInt(draft.executionPolicy.maxTotalSubagents),
  }
  const hasPolicy = Object.values(executionPolicy).some((value) => value != null)
  return {
    environmentName: trimToNull(draft.environmentName),
    tools: normalizeNames(draft.tools),
    skills: normalizeNames(draft.skills),
    allowedSubagents: normalizeNames(draft.allowedSubagents),
    executionPolicy: hasPolicy ? executionPolicy : null,
  }
}

export function emptyAgentDraft(model?: AgentModelDTO): AgentDraft {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    modelId: model ? String(model.id) : '',
    variant: model?.defaultVariant || 'default',
    environmentName: '',
    tools: [],
    skills: [],
    allowedSubagents: [],
    executionPolicy: {
      maxTurns: '',
      maxDepth: '',
      maxDirectSubagents: '',
      maxTotalSubagents: '',
    },
  }
}

export function toAgentDraft(agent: AgentDefinitionDTO): AgentDraft {
  const config = agent.config
  return {
    name: agent.name,
    description: agent.description || '',
    systemPrompt: agent.systemPrompt || '',
    modelId: agent.modelId ? String(agent.modelId) : '',
    variant: agent.variant || 'default',
    environmentName: config?.environmentName || '',
    tools: normalizeNames(config?.tools),
    skills: normalizeNames(config?.skills),
    allowedSubagents: normalizeNames(config?.allowedSubagents),
    executionPolicy: normalizePolicy(config?.executionPolicy),
  }
}

export function toEditableAgent(draft: AgentDraft): AgentDefinitionCreateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    modelId: draft.modelId.trim(),
    variant: trimToNull(draft.variant) || 'default',
    config: toConfig(draft),
  }
}

export function toEditableAgentUpdate(draft: AgentDraft): AgentDefinitionUpdateDTO {
  const data = toEditableAgent(draft)
  return {
    name: data.name,
    description: data.description,
    systemPrompt: data.systemPrompt,
    modelId: data.modelId,
    variant: data.variant,
    config: data.config,
  }
}
