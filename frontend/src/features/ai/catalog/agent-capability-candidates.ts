import type {
  AgentDefinitionDTO,
  AgentSkillRefDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentSkillDTO } from '@/shared/api/contracts/ai-environment'

export interface CapabilityOption {
  value: string
  name: string
  version?: string | null
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

/**
 * 校验工具目录条目与指定环境是否兼容：
 * 1. 非环境工具（environmentRequired=false）始终可用；
 * 2. 要求环境的工具（environmentRequired=true）在未选择有效（非空白）环境 ID 时隐藏；
 * 3. 通用环境工具（environmentRequired=true 且 environmentId 为空/null）在已选任意环境时均可用；
 * 4. 精确环境工具（environmentRequired=true 且有 environmentId）仅在其 environmentId 等于已选 ID 时可用。
 */
export function isToolCompatibleWithEnvironment(
  tool: ToolCatalogEntryDTO,
  environmentId: string | null | undefined,
): boolean {
  if (!tool.environmentRequired) {
    return true
  }
  const selectedEnvId = environmentId?.trim() || null
  if (!selectedEnvId) {
    return false
  }
  const toolEnvId = tool.environmentId?.trim() || null
  if (!toolEnvId) {
    return true
  }
  return toolEnvId === selectedEnvId
}

/**
 * 根据新选择的环境过滤已选工具 ID：
 * 1. 保留未知工具 ID（不在 catalog 中的 orphan）；
 * 2. 保留非环境工具（host 工具）；
 * 3. 切换环境（非空到非空）时：保留通用环境工具，移除来自旧环境的精确环境工具；
 * 4. 解绑环境（切换到空/null）时：移除所有已知要求环境的工具（通用和精确均移除）。
 */
export function filterToolIdsForEnvironment(
  toolIds: string[],
  catalog: ToolCatalogEntryDTO[],
  newEnvironmentId: string | null | undefined,
): string[] {
  const catalogById = new Map<string, ToolCatalogEntryDTO>()
  for (const tool of catalog) {
    const id = tool.id?.trim()
    if (id) {
      catalogById.set(id, tool)
    }
  }
  return toolIds.filter((id) => {
    const trimmedId = id.trim()
    const tool = catalogById.get(trimmedId)
    if (!tool) {
      return true
    }
    return isToolCompatibleWithEnvironment(tool, newEnvironmentId)
  })
}

/** 构建统一的离线可选 tool 目录，不暴露来源环境。若提供 environmentId 则按环境兼容过滤。 */
export function buildToolCandidates(
  tools: ToolCatalogEntryDTO[],
  environmentId?: string | null,
): CapabilityOption[] {
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const tool of tools) {
    const value = tool.id?.trim()
    const name = tool.name?.trim()
    if (!value || !name || seen.has(value)) {
      continue
    }
    if (
      environmentId !== undefined &&
      !isToolCompatibleWithEnvironment(tool, environmentId)
    ) {
      continue
    }
    seen.add(value)
    options.push({
      value,
      name,
      version: tool.version,
      description: tool.description,
    })
  }
  return options
}

/** 构建供 permission 使用的稳定 tool ID 候选；模型可见名称仅作为补充描述。 */
export function buildPermissionToolCandidates(
  tools: ToolCatalogEntryDTO[],
): CapabilityOption[] {
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const tool of tools) {
    const id = tool.id?.trim()
    if (!id || seen.has(id)) {
      continue
    }
    seen.add(id)
    options.push({
      value: id,
      name: id,
      version: tool.version,
      description:
        [tool.name?.trim(), tool.description?.trim()].filter(Boolean).join(' — ') || null,
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
 * 把已勾选但不在候选中的 tool 项并入列表（置灰展示）。
 * 仅保留真正未知的已选工具 ID（不在已知 toolCatalog 中）作为 orphan 候选；
 * 属于已知 toolCatalog 但与当前环境不兼容的环境工具不作为 orphan 重新展示。
 */
export function withSelectedToolOrphans(
  candidates: CapabilityOption[],
  selected: string[],
  catalog: ToolCatalogEntryDTO[],
): CapabilityOption[] {
  const byValue = new Map(candidates.map((item) => [item.value, item]))
  const knownCatalogIds = new Set(catalog.map((tool) => tool.id?.trim()).filter(Boolean))
  const merged = [...candidates]
  for (const raw of selected) {
    const value = raw.trim()
    if (!value || byValue.has(value)) {
      continue
    }
    if (knownCatalogIds.has(value)) {
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
