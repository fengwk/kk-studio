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

function isReady(environment: LiveEnvironmentDTO): boolean {
  return String(environment.status).toUpperCase() === 'READY'
}

/** Build the unified offline-selectable tool catalog without exposing a source environment. */
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

/** Merge skills from all READY live Environments by short name. */
export function buildSkillCandidates(environments: LiveEnvironmentDTO[]): CapabilityOption[] {
  const options: CapabilityOption[] = []
  const seen = new Set<string>()
  for (const environment of environments) {
    if (!isReady(environment)) {
      continue
    }
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
