import type { AgentDraft } from '@/features/catalog/ai-console-types'
import { trimToNull } from '@/features/catalog/ai-resource-draft-primitives'
import { createBuiltinExecution, getBuiltinModelSelection } from '@/shared/api/contracts/ai-runtime'
import { translate } from '@/shared/i18n'
import type {
  AgentConfigDTO,
  AgentCreateDTO,
  AgentDTO,
  AgentUpdateDTO,
  ModelDTO,
  SkillRefDTO,
} from '@/shared/api/generated/contracts'

function normalizeNames(items: string[] | null | undefined): string[] {
  if (!items?.length) {
    return []
  }
  return items.map((item) => item.trim()).filter(Boolean)
}

function normalizeCapabilityValues(
  items: string[] | null | undefined,
  kind: 'tools' | 'subagents',
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

function normalizeSkills(items: SkillRefDTO[] | null | undefined): SkillRefDTO[] {
  if (!items?.length) {
    return []
  }
  const result: SkillRefDTO[] = []
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const item of items) {
    const packageName = item.packageName?.trim()
    const name = item.name?.trim()
    if (!packageName || !name) {
      continue
    }
    const key = `${packageName}:${name}`
    const displayName = `${packageName} / ${name}`
    if (seen.has(key)) {
      duplicates.add(displayName)
    }
    seen.add(key)
    result.push({ packageName, name })
  }
  if (duplicates.size > 0) {
    throw new Error(
      translate('ai.catalog.validation.capabilityDuplicate', {
        kind: 'skills',
        names: [...duplicates].join(', '),
      }),
    )
  }
  return result
}

function toConfig(draft: AgentDraft): AgentConfigDTO {
  return {
    tools: normalizeCapabilityValues(draft.tools, 'tools'),
    skills: normalizeSkills(draft.skills),
    subagents: normalizeCapabilityValues(draft.subagents, 'subagents'),
  }
}

export function emptyAgentDraft(model?: ModelDTO): AgentDraft {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    model: model?.name ?? '',
    // 空值 = 不覆盖；runtime 使用 model.defaultVariant。
    variant: '',
    tools: [],
    skills: [],
    subagents: [],
  }
}

export function toAgentDraft(agent: AgentDTO): AgentDraft {
  const config = agent.config
  const selection = getBuiltinModelSelection(agent.execution)
  return {
    name: agent.name,
    description: agent.description || '',
    systemPrompt: agent.systemPrompt || '',
    execution: agent.execution,
    model: selection?.modelName ?? '',
    variant: selection?.variant?.trim() || '',
    tools: normalizeNames(config.tools),
    skills: (config.skills || []).map((s) => ({
      packageName: s.packageName.trim(),
      name: s.name.trim(),
    })),
    subagents: normalizeNames(config.subagents),
  }
}

export function toEditableAgent(draft: AgentDraft): AgentCreateDTO {
  const name = draft.name.trim()
  if (!name) {
    throw new Error('name must not be blank')
  }
  return {
    name,
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    execution: draftExecution(draft),
    config: toConfig(draft),
  }
}

function draftExecution(draft: AgentDraft) {
  if (draft.execution && draft.execution.driver !== 'builtin') {
    return draft.execution
  }
  const model = draft.model.trim()
  if (!model) {
    throw new Error('model must not be blank')
  }
  return createBuiltinExecution(model, trimToNull(draft.variant))
}

/** 构建 Agent PUT 请求体；请求体只包含可编辑字段。 */
export function toEditableAgentUpdate(
  draft: AgentDraft,
): Omit<AgentUpdateDTO, 'expectedVersion'> {
  return {
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    execution: draftExecution(draft),
    config: toConfig(draft),
  }
}
