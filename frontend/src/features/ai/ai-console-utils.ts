import { modelRef, type AgentModelView } from '@/features/ai/AgentModelView'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
  BackendDateTime,
  ChatDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts'
import type { ResourceModal } from '@/features/ai/ai-console-types'

export function resourceTitle(modal: ResourceModal): string {
  const prefix = modal.mode === 'create' ? '新建' : '编辑'
  if (modal.kind === 'provider') {
    return `${prefix} Provider`
  }
  if (modal.kind === 'model') {
    return `${prefix} Model`
  }
  return `${prefix} Agent`
}

/** Natural name order: case-insensitive, numeric-aware (`m2` < `m10`). */
export function naturalNameCompare(left: string, right: string): number {
  return left.localeCompare(right, undefined, { numeric: true, sensitivity: 'base' })
}

function backendTimeValue(value: BackendDateTime | unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value.map((part) => Number(part))
    const time = Date.UTC(year, (month || 1) - 1, day || 1, hour, minute, second)
    return Number.isFinite(time) ? time : 0
  }
  return 0
}

export function filterAgents(agents: AgentDefinitionDTO[], search: string): AgentDefinitionDTO[] {
  return agents
    .filter((agent) => includesSearch(agent.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

/** Model search/sort use the canonical display identity `provider/model`. */
export function filterModels(models: AgentModelView[], search: string): AgentModelView[] {
  return models
    .filter((model) => includesSearch(modelRef(model), search))
    .sort((left, right) => naturalNameCompare(modelRef(left), modelRef(right)))
}

export function filterProviders(providers: AgentProviderDTO[], search: string): AgentProviderDTO[] {
  return providers
    .filter((provider) => includesSearch(provider.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

export function filterEnvironments(
  environments: LiveEnvironmentDTO[],
  search: string,
): LiveEnvironmentDTO[] {
  return environments
    .filter((environment) => includesSearch(environment.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

/** Chat list: name search only; newest createTime first. */
export function filterChats(chats: ChatDTO[], search: string): ChatDTO[] {
  return chats
    .filter((chat) => includesSearch(chat.title ?? '', search))
    .sort((left, right) => {
      const delta = backendTimeValue(right.createTime) - backendTimeValue(left.createTime)
      if (delta !== 0) {
        return delta
      }
      return String(right.id).localeCompare(String(left.id), undefined, { numeric: true })
    })
}

export function includesSearch(value: string, search: string): boolean {
  const needle = search.trim().toLowerCase()
  return !needle || value.toLowerCase().includes(needle)
}

export function formatBackendDate(value: BackendDateTime): string {
  if (!value) {
    return '-'
  }
  if (Array.isArray(value)) {
    const [year, month = 1, day = 1, hour = 0, minute = 0] = value
    if (!Number.isFinite(year)) {
      return '-'
    }
    return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
  }
  return value.replace('T', ' ').slice(0, 16)
}
