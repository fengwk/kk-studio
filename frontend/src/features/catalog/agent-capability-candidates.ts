import type {
  AgentDefinitionDTO,
  SkillPackageDTO,
  SkillRefDTO,
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

export function skillRefToKey(ref: SkillRefDTO): string {
  return `${ref.packageName}:${ref.name}`
}

export function keyToSkillRef(key: string): SkillRefDTO {
  const idx = key.indexOf(':')
  if (idx < 0) {
    return { packageName: '', name: key }
  }
  return {
    packageName: key.slice(0, idx),
    name: key.slice(idx + 1),
  }
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
 * 构建来自 Platform Skill Packages 目录的 skill 候选。
 * 候选显示格式为 `package / name`。
 */
export function buildSkillCandidates(
  packages: SkillPackageDTO[] | null | undefined,
): CapabilityOption[] {
  if (!packages?.length) {
    return []
  }
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const pkg of packages) {
    const packageName = pkg.packageName?.trim()
    if (!packageName || !pkg.skills?.length) {
      continue
    }
    for (const skill of pkg.skills) {
      const name = skill.name?.trim()
      if (!name) {
        continue
      }
      const key = `${packageName}:${name}`
      if (seen.has(key)) {
        continue
      }
      seen.add(key)
      options.push({
        value: key,
        name: `${packageName} / ${name}`,
        description: skill.description?.trim() || null,
      })
    }
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
 * 把已勾选但不在候选中的 SkillRef 并入列表（置灰展示）。
 */
export function withSelectedSkillOrphans(
  candidates: CapabilityOption[],
  selected: SkillRefDTO[],
): CapabilityOption[] {
  const byValue = new Map(candidates.map((item) => [item.value, item]))
  const merged = [...candidates]
  for (const ref of selected) {
    const key = skillRefToKey(ref)
    if (!key || byValue.has(key)) {
      continue
    }
    const orphan: CapabilityOption = {
      value: key,
      name: `${ref.packageName} / ${ref.name}`,
      description: null,
      offline: true,
      missing: true,
    }
    byValue.set(key, orphan)
    merged.push(orphan)
  }
  return merged
}
