import type {
  AgentDefinitionDTO,
  AgentSkillRefDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentSkillDTO } from '@/shared/api/contracts/ai-environment'

export interface CapabilityOption {
  value: string
  name: string
  description: string | null
  offline?: boolean
  /** 已配置但不在当前候选中（仍展示，可取消勾选）。 */
  missing?: boolean
}

export interface SkillCandidateOption {
  ref: AgentSkillRefDTO
  sourceId: string
  name: string
  description: string | null
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
 * 构建来自 Environment usable durable inventory 的 skill 候选。
 *
 * 不读取 EnvironmentCardDTO.skills，且不依赖 environment.ready。
 * 即便 Environment 离线，只要持久 inventory 存在且可用即可选择。
 * 针对 (sourceId, name) 复合身份去重；来自不同 sourceId 的同名技能保持独立候选。
 */
export function buildSkillCandidates(
  skills: EnvironmentSkillDTO[] | null | undefined,
): SkillCandidateOption[] {
  if (!skills?.length) {
    return []
  }
  const options: SkillCandidateOption[] = []
  const seen = new Set<string>()
  for (const skill of skills) {
    const sourceId = skill.sourceId?.trim()
    const name = skill.name?.trim()
    if (!sourceId || !name) {
      continue
    }
    const key = `${sourceId}::${name}`
    if (seen.has(key)) {
      continue
    }
    seen.add(key)
    options.push({
      ref: { sourceId, name },
      sourceId,
      name,
      description: skill.description ?? null,
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

/**
 * 把已保存但在当前 inventory 中缺失的 skill refs 并入候选列表展示为不可用/可移除项。
 * 针对 (sourceId, name) 复合身份去重，不折叠来自不同 sourceId 的同名技能。
 */
export function withSelectedSkillOrphans(
  candidates: SkillCandidateOption[],
  selected: AgentSkillRefDTO[],
): SkillCandidateOption[] {
  const byKey = new Map(
    candidates.map((item) => [`${item.ref.sourceId}::${item.ref.name}`, item]),
  )
  const merged = [...candidates]
  for (const raw of selected) {
    const sourceId = raw?.sourceId?.trim()
    const name = raw?.name?.trim()
    if (!sourceId || !name) {
      continue
    }
    const key = `${sourceId}::${name}`
    if (byKey.has(key)) {
      continue
    }
    const orphan: SkillCandidateOption = {
      ref: { sourceId, name },
      sourceId,
      name,
      description: null,
      missing: true,
    }
    byKey.set(key, orphan)
    merged.push(orphan)
  }
  return merged
}

export function isSameSkillRef(a: AgentSkillRefDTO, b: AgentSkillRefDTO): boolean {
  return a.sourceId.trim() === b.sourceId.trim() && a.name.trim() === b.name.trim()
}

export function toggleSkillRef(
  items: AgentSkillRefDTO[],
  target: AgentSkillRefDTO,
): AgentSkillRefDTO[] {
  const exists = items.some((item) => isSameSkillRef(item, target))
  if (exists) {
    return items.filter((item) => !isSameSkillRef(item, target))
  }
  return [...items, { sourceId: target.sourceId.trim(), name: target.name.trim() }]
}
