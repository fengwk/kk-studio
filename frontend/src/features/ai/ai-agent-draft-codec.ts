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

function normalizeUniqueNames(
  items: string[] | null | undefined,
  field: string,
): string[] {
  const names = normalizeNames(items)
  const seen = new Set<string>()
  for (const name of names) {
    if (seen.has(name)) {
      throw new Error(`${field} 不能重复：${name}`)
    }
    seen.add(name)
  }
  return names
}

/** UI 可展示为 env/name；持久化给 Agent 时去掉前缀，只保留短名。 */
export function stripCapabilityPrefix(raw: string): string {
  const trimmed = raw.trim()
  if (!trimmed) {
    return ''
  }
  const slash = trimmed.indexOf('/')
  if (slash < 0) {
    return trimmed
  }
  // `<env>/<name>`：只取第一个 `/` 之后的短名。
  return trimmed.slice(slash + 1).trim()
}

/**
 * 保存时去掉 env 前缀，并校验短名唯一。
 * 最终写入 Agent config 的永远是无前缀短名。
 */
export function normalizeCapabilityShortNames(
  items: string[] | null | undefined,
  kind: 'tools' | 'skills',
): string[] {
  const shortNames = normalizeNames(items).map(stripCapabilityPrefix).filter(Boolean)
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const name of shortNames) {
    if (seen.has(name)) {
      duplicates.add(name)
    }
    seen.add(name)
  }
  if (duplicates.size > 0) {
    throw new Error(`${kind} 去前缀后存在重名：${[...duplicates].join(', ')}`)
  }
  return shortNames
}

function normalizePolicy(policy: AgentExecutionPolicyDTO | null | undefined): AgentDraft['executionPolicy'] {
  return {
    maxTurns: policy?.maxTurns != null ? String(policy.maxTurns) : '',
    maxDepth: policy?.maxDepth != null ? String(policy.maxDepth) : '',
    maxDirectSubagents: policy?.maxDirectSubagents != null ? String(policy.maxDirectSubagents) : '',
    maxTotalSubagents: policy?.maxTotalSubagents != null ? String(policy.maxTotalSubagents) : '',
  }
}

function parseOptionalPositiveInt(value: string, field: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) {
    return null
  }
  const parsed = Number(trimmed)
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${field} must be a positive integer`)
  }
  return parsed
}

function toConfig(draft: AgentDraft): AgentDefinitionConfigDTO {
  const executionPolicy: AgentExecutionPolicyDTO = {
    maxTurns: parseOptionalPositiveInt(draft.executionPolicy.maxTurns, 'executionPolicy.maxTurns'),
    maxDepth: parseOptionalPositiveInt(draft.executionPolicy.maxDepth, 'executionPolicy.maxDepth'),
    maxDirectSubagents: parseOptionalPositiveInt(
      draft.executionPolicy.maxDirectSubagents,
      'executionPolicy.maxDirectSubagents',
    ),
    maxTotalSubagents: parseOptionalPositiveInt(
      draft.executionPolicy.maxTotalSubagents,
      'executionPolicy.maxTotalSubagents',
    ),
  }
  return {
    environmentName: trimToNull(draft.environmentName),
    tools: normalizeCapabilityShortNames(draft.tools, 'tools'),
    skills: normalizeCapabilityShortNames(draft.skills, 'skills'),
    allowedSubagents: normalizeUniqueNames(draft.allowedSubagents, 'allowedSubagents'),
    executionPolicy,
  }
}

export function emptyAgentDraft(model?: AgentModelDTO): AgentDraft {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    modelId: model ? String(model.id) : '',
    // Empty = no override; runtime uses model.defaultVariant.
    variant: '',
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
    modelId: agent.modelId,
    variant: agent.variant?.trim() || '',
    environmentName: config.environmentName || '',
    tools: normalizeNames(config.tools),
    skills: normalizeNames(config.skills),
    allowedSubagents: normalizeNames(config.allowedSubagents),
    executionPolicy: normalizePolicy(config.executionPolicy),
  }
}

export function toEditableAgent(draft: AgentDraft): AgentDefinitionCreateDTO {
  const name = draft.name.trim()
  if (!name) {
    throw new Error('name must not be blank')
  }
  const modelId = draft.modelId.trim()
  if (!modelId) {
    throw new Error('modelId must not be blank')
  }
  // Blank override is allowed; backend resolves model.defaultVariant when needed.
  const variant = trimToNull(draft.variant)
  return {
    name,
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    modelId,
    variant,
    config: toConfig(draft),
  }
}

export function toEditableAgentUpdate(draft: AgentDraft): AgentDefinitionUpdateDTO {
  return toEditableAgent(draft)
}
