import type { ThreadUsageSummary } from '@/features/ai/runtime/thread-panel/thread-status-types'

export interface ThreadStatusSegment {
  key: 'agent' | 'model' | 'usage'
  className: string
  text: string
  title: string
  onClick?: () => void
  onSecondaryClick?: () => void
}

export interface ThreadStatusModel {
  agentLabel: string
  provider: string
  model: string
  variant: string
  yoloOn: boolean
  agentText: string
  modelText: string
  usageText: string
  segments: ThreadStatusSegment[]
}

export interface ThreadStatusModelInput {
  agentName?: string
  providerName?: string
  modelName?: string
  variantName?: string
  yoloEnabled?: boolean
  usage?: ThreadUsageSummary
  contextWindow?: number
  onAgentClick?: () => void
  onModelClick?: () => void
  onVariantClick?: () => void
}

/** Normalize blank placeholder strings; returns "" for null/undefined/'undefined'/'null'/'-'. */
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

function resolveCacheHitPercent(
  usage: ThreadUsageSummary | undefined,
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

/** Build the stable text-only status model from panel inputs. No layout, no DOM. */
export function buildThreadStatusModel(input: ThreadStatusModelInput): ThreadStatusModel {
  const agentLabel = clean(input.agentName) || 'agent'
  const provider = clean(input.providerName)
  const model = clean(input.modelName) || 'unknown-model'
  const variant = clean(input.variantName) || 'unknown-variant'
  const yoloOn = Boolean(input.yoloEnabled)

  const tokensIn = asInt(input.usage?.inputTokens)
  const tokensOut = asInt(input.usage?.outputTokens)
  const cacheRead = asInt(input.usage?.cacheReadTokens)
  const cacheWrite =
    asInt(input.usage?.cacheWriteTokens) + asInt(input.usage?.cacheWriteLongTokens)
  const used = tokensIn + tokensOut
  const limit = input.contextWindow && input.contextWindow > 0 ? input.contextWindow : 0
  const hitPercent = resolveCacheHitPercent(input.usage, cacheRead, tokensIn)
  const cost = (input.usage?.costs ?? []).reduce((sum, item) => sum + asNumber(item.total), 0)

  // modelName may already be the canonical provider/model ref from callers.
  const composedModelRef =
    provider && model && !model.startsWith(`${provider}/`) ? `${provider}/${model}` : model
  const modelText = `${composedModelRef} · ${variant}`
  const agentText = yoloOn ? `agent:${agentLabel} · YOLO` : `agent:${agentLabel}`
  // 未开对话 / 零用量也展示，便于看到 context 上限与费用位（与 pi/opencode 一致）
  const usageText = [
    `↑${formatTokens(tokensIn)}`,
    `↓${formatTokens(tokensOut)}`,
    `R${formatTokens(cacheRead)}`,
    `W${formatTokens(cacheWrite)}`,
    `CH${hitPercent.toFixed(1)}%`,
    limit > 0 ? `${formatTokens(used)}/${formatTokens(limit)}` : formatTokens(used),
    `$${cost.toFixed(3)}`,
  ].join(' · ')

  const segments: ThreadStatusSegment[] = [
    {
      key: 'agent',
      className: 'thread-status-agent',
      text: agentText,
      title: input.onAgentClick ? `${agentText} · 点击切换 Agent` : agentText,
      onClick: input.onAgentClick,
    },
    {
      key: 'model',
      className: 'thread-status-model',
      text: modelText,
      title:
        input.onModelClick || input.onVariantClick
          ? `${modelText} · 点击切换 Model，右键切换 Variant`
          : modelText,
      onClick: input.onModelClick,
      onSecondaryClick: input.onVariantClick,
    },
    {
      key: 'usage',
      className: 'thread-status-usage',
      text: usageText,
      title: usageText,
    },
  ]

  return {
    agentLabel,
    provider,
    model,
    variant,
    yoloOn,
    agentText,
    modelText,
    usageText,
    segments,
  }
}
