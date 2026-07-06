import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderDTO,
  AgentProviderUpdateDTO,
  AgentSessionDTO,
  AgentSessionUpdateDTO,
  BackendDateTime,
} from '@/shared/api/contracts'
import type { AgentDraft, KeyValueDraft, ModelDraft, ProviderDraft, ResourceModal, VariantDraft } from '@/features/ai/ai-console-types'
import { applyAgentModelSelection } from '@/features/ai/ai-draft-normalizers'

let draftIdSeed = 0

function nextDraftId(prefix: string): string {
  draftIdSeed += 1
  return `${prefix}-${draftIdSeed}`
}

function newKeyValueDraft(key = '', value = ''): KeyValueDraft {
  return {
    id: nextDraftId('kv'),
    key,
    value,
  }
}

function newVariantDraft(input?: Partial<Omit<VariantDraft, 'id' | 'extras'>> & { extras?: KeyValueDraft[] }): VariantDraft {
  return {
    id: nextDraftId('variant'),
    name: input?.name ?? '',
    temperature: input?.temperature ?? '',
    maxOutputTokens: input?.maxOutputTokens ?? '',
    extras: input?.extras ?? [],
  }
}

function parseScalar(value: unknown): string {
  if (value == null) {
    return ''
  }
  return String(value)
}

function parseObjectDrafts(json: string | null): KeyValueDraft[] {
  if (!json) {
    return []
  }
  try {
    const parsed = JSON.parse(json)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return []
    }
    return Object.entries(parsed).map(([key, value]) => newKeyValueDraft(key, parseScalar(value)))
  } catch {
    return []
  }
}

function parseStringList(json: string | null): string[] {
  if (!json) {
    return []
  }
  try {
    const parsed = JSON.parse(json)
    if (!Array.isArray(parsed)) {
      return []
    }
    return parsed.map((item) => parseScalar(item).trim()).filter(Boolean)
  } catch {
    return []
  }
}

function parseVariantDrafts(json: string | null): VariantDraft[] {
  if (!json) {
    return [newVariantDraft({ name: 'default' })]
  }
  try {
    const parsed = JSON.parse(json)
    if (!Array.isArray(parsed) || parsed.length === 0) {
      return [newVariantDraft({ name: 'default' })]
    }
    return parsed.map((item) => {
      const record = item && typeof item === 'object' && !Array.isArray(item) ? (item as Record<string, unknown>) : {}
      const { name, temperature, maxOutputTokens, ...rest } = record
      return newVariantDraft({
        name: parseScalar(name),
        temperature: parseScalar(temperature),
        maxOutputTokens: parseScalar(maxOutputTokens),
        extras: Object.entries(rest).map(([key, value]) => newKeyValueDraft(key, parseScalar(value))),
      })
    })
  } catch {
    return [newVariantDraft({ name: 'default' })]
  }
}

function trimToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function numberToNull(value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) {
    return null
  }
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

function coerceScalar(value: string): string | number | boolean | null {
  const trimmed = value.trim()
  if (!trimmed) {
    return ''
  }
  if (trimmed === 'true') {
    return true
  }
  if (trimmed === 'false') {
    return false
  }
  if (trimmed === 'null') {
    return null
  }
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
    return Number(trimmed)
  }
  return trimmed
}

function splitCommaSeparatedValues(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function serializeCapabilitiesDrafts(entries: KeyValueDraft[]): string | null {
  const result = entries.reduce<Record<string, unknown>>((acc, entry) => {
    const key = entry.key.trim()
    if (!key) {
      return acc
    }

    const value = entry.value.trim()
    if (key === 'tools') {
      acc[key] = value === 'true'
      return acc
    }

    if (key === 'input' || key === 'output') {
      const list = splitCommaSeparatedValues(value)
      if (list.length > 0) {
        acc[key] = list
      }
      return acc
    }

    if (!value) {
      return acc
    }
    acc[key] = coerceScalar(value)
    return acc
  }, {})
  return Object.keys(result).length > 0 ? JSON.stringify(result) : null
}

function serializeNumericMetadataDrafts(entries: KeyValueDraft[]): string | null {
  const result = entries.reduce<Record<string, string | number | boolean | null>>((acc, entry) => {
    const key = entry.key.trim()
    if (!key) {
      return acc
    }

    const numeric = numberToNull(entry.value)
    if (numeric !== null) {
      acc[key] = numeric
      return acc
    }

    const value = entry.value.trim()
    if (!value) {
      return acc
    }
    acc[key] = coerceScalar(value)
    return acc
  }, {})
  return Object.keys(result).length > 0 ? JSON.stringify(result) : null
}

function serializeStringList(items: string[]): string | null {
  const trimmed = items.map((item) => item.trim()).filter(Boolean)
  return trimmed.length > 0 ? JSON.stringify(trimmed) : null
}

function serializeVariantDrafts(variants: VariantDraft[], fallbackVariant?: string): string | null {
  const normalized = variants
    .map((variant) => {
      const name = variant.name.trim()
      if (!name) {
        return null
      }
      const payload: Record<string, string | number | boolean | null> = { name }
      const temperature = numberToNull(variant.temperature)
      if (temperature !== null) {
        payload.temperature = temperature
      }
      const maxOutputTokens = numberToNull(variant.maxOutputTokens)
      if (maxOutputTokens !== null) {
        payload.maxOutputTokens = maxOutputTokens
      }
      for (const extra of variant.extras) {
        const key = extra.key.trim()
        if (key) {
          payload[key] = coerceScalar(extra.value)
        }
      }
      return payload
    })
    .filter((variant): variant is Record<string, string | number | boolean | null> => Boolean(variant))

  if (normalized.length > 0) {
    return JSON.stringify(normalized)
  }

  const fallback = fallbackVariant?.trim()
  return fallback ? JSON.stringify([{ name: fallback }]) : null
}

export function emptyProviderDraft(): ProviderDraft {
  return {
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    apiKey: '',
    timeoutMillis: '60000',
    streamIdleTimeoutMillis: '60000',
  }
}

export function emptyModelDraft(model?: AgentModelDTO, provider?: AgentProviderDTO): ModelDraft {
  return {
    provider: model?.providerName || provider?.name || '',
    name: '',
    description: '',
    defaultVariant: model?.defaultVariant || 'default',
    variants: [newVariantDraft({ name: model?.defaultVariant || 'default' })],
    capabilities: [],
    limits: [],
    pricing: [],
  }
}

export function emptyAgentDraft(model?: AgentModelDTO): AgentDraft {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    defaultProvider: model?.providerName || '',
    defaultModel: model?.name || '',
    defaultVariant: model?.defaultVariant || 'default',
    tools: [],
    subagents: [],
    skills: [],
  }
}

export function toProviderDraft(provider: AgentProviderDTO): ProviderDraft {
  return {
    name: provider.name,
    description: provider.description || '',
    providerType: provider.providerType || 'openai',
    baseUrl: provider.baseUrl || '',
    apiKey: provider.apiKey || '',
    timeoutMillis: provider.timeoutMillis ? String(provider.timeoutMillis) : '',
    streamIdleTimeoutMillis: provider.streamIdleTimeoutMillis ? String(provider.streamIdleTimeoutMillis) : '',
  }
}

export function toModelDraft(model: AgentModelDTO): ModelDraft {
  return {
    provider: model.providerName,
    name: model.name,
    description: model.description || '',
    defaultVariant: model.defaultVariant || 'default',
    variants: parseVariantDrafts(model.variantsJson),
    capabilities: parseObjectDrafts(model.capabilitiesJson),
    limits: parseObjectDrafts(model.limitJson),
    pricing: parseObjectDrafts(model.pricingJson),
  }
}

export function toAgentDraft(agent: AgentDefinitionDTO): AgentDraft {
  return {
    name: agent.name,
    description: agent.description || '',
    systemPrompt: agent.systemPrompt || '',
    defaultProvider: agent.defaultProviderName,
    defaultModel: agent.defaultModelName,
    defaultVariant: agent.defaultVariant || 'default',
    tools: parseStringList(agent.toolsJson),
    subagents: parseStringList(agent.subagentsJson),
    skills: parseStringList(agent.skillsJson),
  }
}

export function toEditableProvider(draft: ProviderDraft): AgentProviderCreateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    providerType: draft.providerType.trim(),
    baseUrl: trimToNull(draft.baseUrl),
    apiKey: trimToNull(draft.apiKey),
    timeoutMillis: numberToNull(draft.timeoutMillis),
    streamIdleTimeoutMillis: numberToNull(draft.streamIdleTimeoutMillis),
  }
}

export function toEditableProviderUpdate(draft: ProviderDraft): AgentProviderUpdateDTO {
  return toEditableProvider(draft)
}

export function toEditableModel(draft: ModelDraft): AgentModelCreateDTO {
  return {
    provider: draft.provider.trim(),
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    capabilitiesJson: serializeCapabilitiesDrafts(draft.capabilities),
    limitJson: serializeNumericMetadataDrafts(draft.limits),
    pricingJson: serializeNumericMetadataDrafts(draft.pricing),
    defaultVariant: trimToNull(draft.defaultVariant),
    variantsJson: serializeVariantDrafts(draft.variants, draft.defaultVariant),
  }
}

export function toEditableModelUpdate(draft: ModelDraft): AgentModelUpdateDTO {
  const data = toEditableModel(draft)
  return {
    description: data.description,
    capabilitiesJson: data.capabilitiesJson,
    limitJson: data.limitJson,
    pricingJson: data.pricingJson,
    defaultVariant: data.defaultVariant,
    variantsJson: data.variantsJson,
    name: data.name,
  }
}

export function toEditableAgent(draft: AgentDraft): AgentDefinitionCreateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    systemPrompt: trimToNull(draft.systemPrompt),
    defaultProvider: draft.defaultProvider.trim(),
    defaultModel: draft.defaultModel.trim(),
    defaultVariant: trimToNull(draft.defaultVariant),
    toolsJson: serializeStringList(draft.tools),
    subagentsJson: serializeStringList(draft.subagents),
    skillsJson: serializeStringList(draft.skills),
  }
}

export function toEditableAgentUpdate(draft: AgentDraft): AgentDefinitionUpdateDTO {
  const data = toEditableAgent(draft)
  return {
    description: data.description,
    systemPrompt: data.systemPrompt,
    defaultVariant: data.defaultVariant,
    toolsJson: data.toolsJson,
    subagentsJson: data.subagentsJson,
    skillsJson: data.skillsJson,
    name: data.name,
    defaultProvider: data.defaultProvider,
    defaultModel: data.defaultModel,
  }
}

export function toSessionTitleUpdate(title: string): AgentSessionUpdateDTO {
  return {
    title: title.trim() || null,
  }
}

export function applyModelSelection(draft: AgentDraft, value: string, models: AgentModelDTO[] = []): AgentDraft {
  return applyAgentModelSelection(draft, value, models)
}

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

export function filterSessions(sessions: AgentSessionDTO[], agentsByName: Map<string, AgentDefinitionDTO>, search: string): AgentSessionDTO[] {
  return sessions.filter((session) => {
    const agent = agentsByName.get(session.agentName)
    return includesSearch(`${session.sessionId} ${session.title ?? ''} ${session.status} ${session.agentName} ${agent?.name ?? ''}`, search)
  })
}

export function filterAgents(agents: AgentDefinitionDTO[], search: string): AgentDefinitionDTO[] {
  return agents.filter((agent) =>
    includesSearch(
      `${agent.name} ${agent.description ?? ''} ${agent.defaultProviderName} ${agent.defaultModelName} ${agent.defaultVariant ?? ''}`,
      search,
    ),
  )
}

export function filterModels(models: AgentModelDTO[], search: string): AgentModelDTO[] {
  return models.filter((model) => includesSearch(`${model.providerName} ${model.name} ${model.description ?? ''} ${model.defaultVariant ?? ''}`, search))
}

export function filterProviders(providers: AgentProviderDTO[], search: string): AgentProviderDTO[] {
  return providers.filter((provider) => includesSearch(`${provider.name} ${provider.description ?? ''} ${provider.providerType} ${provider.baseUrl ?? ''}`, search))
}

export function includesSearch(value: string, search: string): boolean {
  return !search || value.toLowerCase().includes(search)
}

export function formatJsonSummary(json: string | null): string {
  if (!json) {
    return 'default'
  }
  try {
    const parsed = JSON.parse(json)
    if (Array.isArray(parsed)) {
      return `${parsed.length} item${parsed.length === 1 ? '' : 's'}`
    }
    if (parsed && typeof parsed === 'object') {
      return `${Object.keys(parsed).length} keys`
    }
  } catch {
    return 'invalid json'
  }
  return 'default'
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
