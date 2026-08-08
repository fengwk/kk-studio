import type { ToolCatalogEntryDTO } from '@/shared/api/contracts/ai-catalog'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'

export interface CapabilityOption {
  name: string
  version?: string | null
  description: string | null
  offline?: boolean
  /** 已配置但不在当前 live 候选中（仍展示，可取消勾选）。 */
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
      name,
      version: tool.version ?? null,
      description: tool.description ?? null,
    })
  }
  return options
}

/**
 * 构建单个选中 live Environment 的 skill 候选。
 *
 * 只接受至多一个 Environment（API 形状上不可能合并多个来源）；未选择或 {@code ready !== true} 一律返回空列表。
 * ready 是服务端统一可用性标记（READY + 连接打开 + 心跳未过期），status 文本只用于展示，不作为可用性判断。
 */
export function buildSkillCandidates(
  environment: LiveEnvironmentDTO | undefined,
): CapabilityOption[] {
  if (!environment || environment.ready !== true) {
    return []
  }
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const skill of environment.skills ?? []) {
    const name = skill.name?.trim()
    if (!name || seen.has(name)) {
      continue
    }
    seen.add(name)
    options.push({
      name,
      description: skill.description ?? null,
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
  const byName = new Map(candidates.map((item) => [item.name, item]))
  const merged = [...candidates]
  for (const raw of selected) {
    const name = raw.trim()
    if (!name || byName.has(name)) {
      continue
    }
    const orphan: CapabilityOption = {
      name,
      description: null,
      offline: true,
      missing: true,
    }
    byName.set(name, orphan)
    merged.push(orphan)
  }
  return merged
}
