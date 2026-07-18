import type { ModelUsageSummaryDTO } from '@/shared/api/contracts'

/**
 * pi-style single-line status footer (all left, | separated):
 * agent:name | (provider) model · variant | YOLO? | ↑in ↓out Rread Wwrite CHhit% $cost used/limit
 */
export function ThreadStatusFooter({
  agentName,
  providerName,
  modelName,
  variantName,
  yoloEnabled,
  usage,
  contextWindow,
}: {
  agentName?: string
  providerName?: string
  modelName?: string
  variantName?: string
  yoloEnabled?: boolean
  usage?: ModelUsageSummaryDTO
  contextWindow?: number
}) {
  const agentLabel = clean(agentName) || 'agent'
  const provider = clean(providerName)
  const model = clean(modelName)
  const variant = clean(variantName) || 'default'
  const yoloOn = Boolean(yoloEnabled)

  const input = asInt(usage?.inputTokens)
  const output = asInt(usage?.outputTokens)
  const cacheRead = asInt(usage?.cacheReadTokens)
  const cacheWrite = asInt(usage?.cacheWriteTokens) + asInt(usage?.cacheWriteLongTokens)
  const used = input + output
  const limit = contextWindow && contextWindow > 0 ? contextWindow : 0
  const hitPercent = resolveCacheHitPercent(usage, cacheRead, input)
  const cost = (usage?.costs ?? []).reduce((sum, item) => sum + asNumber(item.total), 0)

  const modelPart = [
    provider ? `(${provider})` : '',
    model || 'unknown-model',
    `· ${variant}`,
  ]
    .filter(Boolean)
    .join(' ')

  const parts = [`agent:${agentLabel}`, modelPart]
  if (yoloOn) {
    parts.push('YOLO')
  }

  const stats = [
    `↑${formatTokens(input)}`,
    `↓${formatTokens(output)}`,
    `R${formatTokens(cacheRead)}`,
    `W${formatTokens(cacheWrite)}`,
    `CH${hitPercent.toFixed(1)}%`,
    `$${cost.toFixed(3)}`,
    limit > 0 ? `${formatTokens(used)}/${formatTokens(limit)}` : formatTokens(used),
  ]

  const line = `${parts.join(' | ')} | ${stats.join(' ')}`

  return (
    <footer className="thread-status-footer" aria-label="会话状态">
      <div className="thread-status-line">{line}</div>
    </footer>
  )
}

function resolveCacheHitPercent(
  usage: ModelUsageSummaryDTO | undefined,
  cacheRead: number,
  input: number,
): number {
  const ratio = asNumber(usage?.cacheHitRatio)
  if (ratio > 0) {
    return ratio > 1 ? ratio : ratio * 100
  }
  const hits = asInt(usage?.cacheHitRecordCount)
  const eligible = asInt(usage?.cacheEligibleRecordCount)
  if (eligible > 0) {
    return (hits / eligible) * 100
  }
  const prompt = input + cacheRead
  if (prompt > 0 && cacheRead > 0) {
    return (cacheRead / prompt) * 100
  }
  return 0
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
