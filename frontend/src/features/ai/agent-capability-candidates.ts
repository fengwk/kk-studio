import type { LiveEnvironmentDTO, LiveEnvironmentSkillDTO, LiveEnvironmentToolDTO } from '@/shared/api/contracts'

export const PLATFORM_ENVIRONMENT_NAME = 'platform'

export interface CapabilityOption {
  name: string
  source: string
  description: string | null
  offline?: boolean
}

function isReady(environment: LiveEnvironmentDTO | undefined): boolean {
  return Boolean(environment && String(environment.status).toUpperCase() === 'READY')
}

/**
 * Platform-first tool/skill candidates: READY platform first, then selected env
 * entries that do not collide with platform names.
 */
export function buildCapabilityCandidates(
  environments: LiveEnvironmentDTO[],
  environmentName: string | null | undefined,
  kind: 'tools' | 'skills',
): CapabilityOption[] {
  const byName = new Map(environments.map((environment) => [environment.name, environment]))
  const platform = byName.get(PLATFORM_ENVIRONMENT_NAME)
  const selectedName = environmentName?.trim() || ''
  const selected = selectedName && selectedName !== PLATFORM_ENVIRONMENT_NAME ? byName.get(selectedName) : undefined

  const options: CapabilityOption[] = []
  const seen = new Set<string>()

  const append = (
    environment: LiveEnvironmentDTO | undefined,
    items: Array<LiveEnvironmentToolDTO | LiveEnvironmentSkillDTO> | undefined,
    offline: boolean,
  ) => {
    if (!environment || !items) {
      return
    }
    for (const item of items) {
      const name = item.name?.trim()
      if (!name || seen.has(name)) {
        continue
      }
      seen.add(name)
      options.push({
        name,
        source: environment.name,
        description: item.description ?? null,
        offline,
      })
    }
  }

  if (isReady(platform)) {
    append(platform, platform?.[kind], false)
  }
  if (selected) {
    append(selected, selected[kind], !isReady(selected))
  }
  return options
}

export function markInvalidSelections(
  selected: string[],
  candidates: CapabilityOption[],
): Array<{ name: string; invalid: boolean; offline: boolean }> {
  const byName = new Map(candidates.map((item) => [item.name, item]))
  return selected.map((name) => {
    const candidate = byName.get(name)
    return {
      name,
      invalid: !candidate,
      offline: Boolean(candidate?.offline),
    }
  })
}
