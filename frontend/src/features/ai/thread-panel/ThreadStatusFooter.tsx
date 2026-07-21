import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { ModelUsageSummaryDTO } from '@/shared/api/contracts'

type StatusSegment = {
  key: string
  className: string
  text: string
  title: string
}

/** 与 CSS `.thread-status-sep { margin: 0 8px }` + `|` 宽大致一致，略偏小以多装一点 */
const SEP_SLOT_PX = 16

/**
 * Footer：agent | model | usage
 * - 行装箱：够宽 1 行；变窄 2+1 → 1+1+1
 * - │ 只画在行内
 * - 能 1 行绝不拆成多行（去掉反向滞回卡死）
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
  const model = clean(modelName) || 'unknown-model'
  const variant = clean(variantName) || 'unknown-variant'
  const yoloOn = Boolean(yoloEnabled)

  const input = asInt(usage?.inputTokens)
  const output = asInt(usage?.outputTokens)
  const cacheRead = asInt(usage?.cacheReadTokens)
  const cacheWrite = asInt(usage?.cacheWriteTokens) + asInt(usage?.cacheWriteLongTokens)
  const used = input + output
  const limit = contextWindow && contextWindow > 0 ? contextWindow : 0
  const hitPercent = resolveCacheHitPercent(usage, cacheRead, input)
  const cost = (usage?.costs ?? []).reduce((sum, item) => sum + asNumber(item.total), 0)

  const modelRef = provider ? `${provider}/${model}` : model
  const modelText = `${modelRef} · ${variant}`
  const agentText = yoloOn ? `agent:${agentLabel} · YOLO` : `agent:${agentLabel}`
  // 未开对话 / 零用量也展示，便于看到 context 上限与费用位（与 pi/opencode 一致）
  const usageText = [
    `↑${formatTokens(input)}`,
    `↓${formatTokens(output)}`,
    `R${formatTokens(cacheRead)}`,
    `W${formatTokens(cacheWrite)}`,
    `CH${hitPercent.toFixed(1)}%`,
    limit > 0 ? `${formatTokens(used)}/${formatTokens(limit)}` : formatTokens(used),
    `$${cost.toFixed(3)}`,
  ].join(' · ')

  const segments = useMemo(
    () => [
      { key: 'agent', className: 'thread-status-agent', text: agentText, title: agentText },
      { key: 'model', className: 'thread-status-model', text: modelText, title: modelText },
      { key: 'usage', className: 'thread-status-usage', text: usageText, title: usageText },
    ],
    [agentText, modelText, usageText],
  )

  return (
    <footer className="thread-status-footer" aria-label="会话状态">
      <StatusSegmentRows segments={segments} />
    </footer>
  )
}

function StatusSegmentRows({ segments }: { segments: StatusSegment[] }) {
  const hostRef = useRef<HTMLDivElement>(null)
  const [rows, setRows] = useState<number[][]>(() => [segments.map((_, i) => i)])

  useLayoutEffect(() => {
    const host = hostRef.current
    if (!host) {
      return
    }

    const repack = () => {
      const containerWidth = Math.max(0, host.clientWidth)
      // 尚无布局宽度时先按单行，避免用错误宽度锁死成 1+1+1
      if (containerWidth < 8) {
        const single = [segments.map((_, i) => i)]
        setRows((prev) => (sameRows(prev, single) ? prev : single))
        return
      }

      const font = readLineFont(host)
      const widths = segments.map((segment) => measureTextWidth(segment.text, font))
      const next = packRows(widths, containerWidth)
      setRows((prev) => (sameRows(prev, next) ? prev : next))
    }

    repack()
    const ro = new ResizeObserver(() => repack())
    ro.observe(host)
    if (document.fonts?.ready) {
      void document.fonts.ready.then(repack)
    }
    window.addEventListener('resize', repack)
    return () => {
      ro.disconnect()
      window.removeEventListener('resize', repack)
    }
  }, [segments])

  return (
    <div ref={hostRef} className="thread-status-line">
      {rows.map((row, rowIndex) => (
        <div key={`row-${rowIndex}-${row.join('-')}`} className="thread-status-row">
          {row.map((segIndex, pos) => {
            const segment = segments[segIndex]
            if (!segment) {
              return null
            }
            return (
              <span key={segment.key} className="thread-status-unit">
                {pos > 0 ? (
                  <span className="thread-status-sep" aria-hidden="true">
                    |
                  </span>
                ) : null}
                <span className={`thread-status-seg ${segment.className}`} title={segment.title}>
                  {segment.text}
                </span>
              </span>
            )
          })}
        </div>
      ))}
    </div>
  )
}

function readLineFont(host: HTMLElement): string {
  const style = window.getComputedStyle(host)
  const size = style.fontSize || '12px'
  const family = style.fontFamily || 'Inter, ui-sans-serif, system-ui, sans-serif'
  const weight = style.fontWeight || '400'
  return `${weight} ${size} ${family}`
}

function measureTextWidth(text: string, font: string): number {
  const canvas = measureTextWidth.canvas || (measureTextWidth.canvas = document.createElement('canvas'))
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return text.length * 7
  }
  ctx.font = font
  return Math.ceil(ctx.measureText(text).width)
}
measureTextWidth.canvas = null as HTMLCanvasElement | null

/** 从左到右装箱；装不下开新行（后段先掉下去 → 3 / 2+1 / 1+1+1） */
function packRows(naturalWidths: number[], containerWidth: number): number[][] {
  if (naturalWidths.length === 0) {
    return [[0]]
  }
  const rows: number[][] = []
  let current: number[] = []
  let rowWidth = 0

  for (let i = 0; i < naturalWidths.length; i += 1) {
    // 单段允许占满整行（再由 CSS ellipsis）
    const segW = Math.min(Math.max(naturalWidths[i] ?? 0, 1), containerWidth)
    if (current.length === 0) {
      current = [i]
      rowWidth = segW
      continue
    }
    const need = rowWidth + SEP_SLOT_PX + segW
    if (need > containerWidth) {
      rows.push(current)
      current = [i]
      rowWidth = segW
    } else {
      current.push(i)
      rowWidth = need
    }
  }
  if (current.length > 0) {
    rows.push(current)
  }
  return rows
}

function sameRows(left: number[][], right: number[][]): boolean {
  if (left.length !== right.length) {
    return false
  }
  return left.every(
    (row, index) =>
      row.length === right[index]?.length && row.every((value, j) => value === right[index]?.[j]),
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
