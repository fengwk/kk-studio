import type {
  AgentDefinitionDTO,
  ModelUsageSummaryDTO,
  SessionYoloDTO,
} from '@/shared/api/contracts'
import type { SessionTimeline } from '@/features/ai/session-events'

/**
 * pi-style single-line status footer (all left, | separated):
 * agent:name | (provider) model · variant | YOLO? | ↑in ↓out Rread Wwrite CHhit% $cost used/limit
 */
export function SessionStatusFooter({
  agentName,
  providerName,
  modelName,
  variantName,
  yolo,
  usage,
  contextWindow,
}: {
  agent?: AgentDefinitionDTO
  agentName?: string
  providerName?: string
  modelName?: string
  variantName?: string
  timeline?: SessionTimeline
  yolo?: SessionYoloDTO
  usage?: ModelUsageSummaryDTO
  contextWindow?: number
}) {
  const agentLabel = clean(agentName) || 'agent'
  const provider = clean(providerName)
  const model = clean(modelName)
  const variant = clean(variantName) || 'default'
  const yoloOn = Boolean(yolo?.enabled)

  const input = asInt(usage?.inputTokens)
  const output = asInt(usage?.outputTokens)
  const cacheRead = asInt(usage?.cacheReadTokens)
  const cacheWrite = asInt(usage?.cacheWriteTokens) + asInt(usage?.cacheWriteLongTokens)
  const used = input + output
  const limit = contextWindow && contextWindow > 0 ? contextWindow : 0
  const hitRatio = asNumber(usage?.cacheHitRatio)
  const hitPercent = hitRatio > 1 ? hitRatio : hitRatio * 100
  const cost = (usage?.costs ?? []).reduce((sum, item) => sum + asNumber(item.total), 0)

  const modelPart = provider
    ? `(${provider}) ${model || '-'} · ${variant}`
    : model
      ? `${model} · ${variant}`
      : variant

  const parts = [`agent:${agentLabel}`, modelPart]
  if (yoloOn) {
    parts.push('YOLO')
  }

  const stats = [
    `↑${formatTokens(input)}`,
    `↓${formatTokens(output)}`,
    `R${formatTokens(cacheRead)}`,
    `W${formatTokens(cacheWrite)}`,
  ]
  if (cacheRead > 0 || cacheWrite > 0) {
    stats.push(`CH${hitPercent.toFixed(1)}%`)
  }
  stats.push(`$${cost.toFixed(3)}`)
  stats.push(limit > 0 ? `${formatTokens(used)}/${formatTokens(limit)}` : formatTokens(used))

  const line = `${parts.join(' | ')} | ${stats.join(' ')}`

  return (
    <footer className="session-status-footer" aria-label="会话状态">
      <div className="session-status-line">{line}</div>
    </footer>
  )
}

function clean(value?: string | null): string {
  const text = (value ?? '').trim()
  if (!text || text === '-' || text === 'undefined' || text === 'null') {
    return ''
  }
  return text
}

function formatTokens(count: number): string {
  if (!Number.isFinite(count) || count <= 0) {
    return '0'
  }
  if (count < 1000) {
    return String(Math.round(count))
  }
  if (count < 10_000) {
    return `${(count / 1000).toFixed(1)}k`
  }
  if (count < 1_000_000) {
    return `${Math.round(count / 1000)}k`
  }
  if (count < 10_000_000) {
    return `${(count / 1_000_000).toFixed(1)}M`
  }
  return `${Math.round(count / 1_000_000)}M`
}

function asInt(value: unknown): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed)) : 0
}

function asNumber(value: unknown): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}
