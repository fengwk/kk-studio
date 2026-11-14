import type { AgentDraft } from '@/features/catalog/ai-console-types'
import {
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  skillRefToKey,
  withSelectedOrphans,
  withSelectedSkillOrphans,
  type CapabilityOption,
} from '@/features/catalog/agent-capability-candidates'
import type {
  AgentDTO,
  ModelDTO,
  SkillPackageSummaryDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/generated/contracts'

/** 三个能力面板的候选与「当前已选但仍可见的项」；孤儿项由 withSelected* 保留为可移除。 */
export interface AgentCapabilityCandidates {
  tools: CapabilityOption[]
  selectedTools: string[]
  skills: CapabilityOption[]
  selectedSkills: string[]
  subagents: CapabilityOption[]
}

/**
 * 计算 Agent 表单的三组能力候选：
 * - tools 按运行时工具目录；
 * - skills 按技能包清单，选中值用 `packageName/name` 键表达；
 * - subagents 按全局 Agent 目录，create 模式下隐藏与自身同名的候选（该行尚不存在），
 *   edit 模式下自身已存在所以正常展示。
 */
export function agentCapabilityCandidates(input: {
  draft: AgentDraft
  mode: 'create' | 'edit'
  toolCatalog: ToolCatalogEntryDTO[]
  agents: AgentDTO[]
  skills: SkillPackageSummaryDTO[]
}): AgentCapabilityCandidates {
  const { draft, mode, toolCatalog, agents, skills } = input

  const tools = withSelectedOrphans(buildToolCandidates(toolCatalog), draft.tools)
  const visibleToolValues = new Set(tools.map((option) => option.value))
  const selectedTools = draft.tools.filter((id) => visibleToolValues.has(id.trim()))

  const skillCandidates = withSelectedSkillOrphans(buildSkillCandidates(skills), draft.skills)
  const visibleSkillValues = new Set(skillCandidates.map((option) => option.value))
  const selectedSkills = draft.skills
    .map(skillRefToKey)
    .filter((key) => visibleSkillValues.has(key))

  const subagentCatalog = buildSubagentCandidates(agents)
  const selfName = draft.name.trim()
  const subagents = withSelectedOrphans(
    mode === 'create' && selfName
      ? subagentCatalog.filter((candidate) => candidate.name !== selfName)
      : subagentCatalog,
    draft.subagents,
  )

  return { tools, selectedTools, skills: skillCandidates, selectedSkills, subagents }
}

/** 默认模型是否已下线：编辑一个引用了已删模型的 Agent 时，字段要呈现为不可用。 */
export function isAgentModelUnavailable(
  draft: AgentDraft,
  models: ModelDTO[],
  mode: 'create' | 'edit',
): boolean {
  return mode === 'edit' && Boolean(draft.model) && !models.some((m) => m.name === draft.model)
}
