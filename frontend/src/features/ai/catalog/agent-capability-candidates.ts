import type {
  AgentDefinitionDTO,
  SkillDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'

export interface CapabilityOption {
  value: string
  name: string
  description: string | null
  offline?: boolean
  /** 已配置但不在当前候选中（仍展示，可取消勾选）。 */
  missing?: boolean
}

/** 构建统一的离线可选 tool 目录，不暴露来源环境。 */
export function buildToolCandidates(tools: ToolCatalogEntryDTO[]): CapabilityOption[] {
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const tool of tools) {
    const name = tool.name?.trim()
    if (!name || seen.has(name)) {
      continue
    }
    seen.add(name)
    options.push({
      value: name,
      name,
      description: tool.description?.trim() || null,
    })
  }
  return options
}

/** 构建供 permission 使用的模型可见 tool 候选。 */
export function buildPermissionToolCandidates(
  tools: ToolCatalogEntryDTO[],
): CapabilityOption[] {
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const tool of tools) {
    const name = tool.name?.trim()
    if (!name || seen.has(name)) {
      continue
    }
    seen.add(name)
    options.push({
      value: name,
      name,
      description: tool.description?.trim() || null,
    })
  }
  return options
}

/**
 * 构建来自 Platform 全局生效 Skill 目录的 skill 候选。
 */
export function buildSkillCandidates(
  skills: SkillDTO[] | null | undefined,
): CapabilityOption[] {
  if (!skills?.length) {
    return []
  }
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const skill of skills) {
    const name = skill.name?.trim()
    if (!name || seen.has(name)) {
      continue
    }
    seen.add(name)
    options.push({
      value: name,
      name,
      description: skill.description?.trim() || null,
    })
  }
  return options
}

/**
 * 构建当前全局 Agent catalog 的 subagent 候选。
 *
 * 候选来自全局 Agent 定义（名称 + 描述）；create 模式下的「与当前 draft.name 同名」排除由表单负责，
 * 这里只做名称去重与空名过滤。
 */
export function buildSubagentCandidates(agents: AgentDefinitionDTO[]): CapabilityOption[] {
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const agent of agents) {
    const name = agent.name?.trim()
    if (!name || seen.has(name)) {
      continue
    }
    seen.add(name)
    options.push({
      value: name,
      name,
      description: agent.description ?? null,
    })
  }
  return options
}

/**
 * 把已勾选但不在候选中的项并入列表（置灰展示），避免「暂无候选」时直接消失。
 * 不自动清理；用户可取消勾选后保存。
 */
export function withSelectedOrphans(
  candidates: CapabilityOption[],
  selected: string[],
): CapabilityOption[] {
  const byValue = new Map(candidates.map((item) => [item.value, item]))
  const merged = [...candidates]
  for (const raw of selected) {
    const value = raw.trim()
    if (!value || byValue.has(value)) {
      continue
    }
    const orphan: CapabilityOption = {
      value,
      name: value,
      description: null,
      offline: true,
      missing: true,
    }
    byValue.set(value, orphan)
    merged.push(orphan)
  }
  return merged
}
