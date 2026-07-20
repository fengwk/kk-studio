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
    tools: normalizeCapabilityShortNames(draft.tools, 'tools'),
    skills: normalizeCapabilityShortNames(draft.skills, 'skills'),
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
    // 读回时也做去前缀归一，兼容历史/误写的 env/name。
    tools: normalizeNames(config?.tools).map(stripCapabilityPrefix).filter(Boolean),
    skills: normalizeNames(config?.skills).map(stripCapabilityPrefix).filter(Boolean),
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
