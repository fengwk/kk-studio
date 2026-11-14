import { asRecord } from '@/features/thread/runtime/payload-json'
import { getString } from '@/shared/lib/value'
import { formatBackendDateSeconds } from '@/shared/lib/date-format'
import { translate } from '@/shared/i18n'
import type { ThreadEventDetailRow } from '@/features/thread/runtime/thread-events-types'
import {
  accumulateTurnUsage,
  parseAssistantUsage,
} from '@/features/thread/runtime/thread-timeline/turn-usage-format'
import type { TurnUsage } from '@/features/thread/runtime/thread-timeline-types'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

export function compactJson(value: unknown): string {
  try {
    return JSON.stringify(value) ?? '{}'
  } catch {
    return '{}'
  }
}

export function prettyJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2) ?? '{}'
  } catch {
    return '{}'
  }
}

/** 事件原始 JSON 的展示文本（active DTO / realtime stream 的 pretty JSON）。 */
export function formatDisplayJson(value: unknown): string {
  if (value == null) {
    return ''
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/**
 * 一条 durable Entry 是否携带真实模型输出 metadata（assistantMetadata）。
 *
 * 它与后端历史预览的准入完全一致：只有真正发生过 provider 调用的 ASSISTANT / COMPACTION
 * 输出才带它，USER/TOOL 通知等没有可重建的请求前缀。
 */
export function hasAssistantMetadata(payload: Record<string, unknown>): boolean {
  return Object.keys(asRecord(payload.assistantMetadata)).length > 0
}

/**
 * 把一条真实模型输出（ASSISTANT MESSAGE 或 COMPACTION 结果）的 canonical metadata 与
 * 读取投影费用并入当前 Turn 的挂起 usage；无事实时原样返回；缺 usage 时未知传播。
 */
export function accumulateUsage(
  pending: TurnUsage | null,
  payload: Record<string, unknown>,
  entry: HarnessSessionEntryDTO,
): TurnUsage | null {
  const parsed = parseAssistantUsage(payload.assistantMetadata, entry.usageCost ?? null)
  return accumulateTurnUsage(pending, parsed)
}

/** 截断为单行摘要（首行 + 140 字符上限）。 */
export function summarizeField(value: string, fallback: string): string {
  const line = value.split('\n')[0]?.trim() ?? ''
  if (!line) {
    return fallback
  }
  return line.length > 140 ? `${line.slice(0, 140)}…` : line
}

/** 字符串或数字（JSON number）统一转文本。 */
export function scalarText(value: unknown): string {
  return typeof value === 'number' ? String(value) : getString(value)
}

export function withTime(
  details: ThreadEventDetailRow[],
  createdAt: number,
): ThreadEventDetailRow[] {
  return [
    ...details,
    { label: translate('ai.runtime.event.detail.createdAt'), value: formatBackendDateSeconds(createdAt) },
  ]
}
