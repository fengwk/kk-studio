import { asRecord, getString } from '@/features/ai/runtime/payload-json'
import type { ToolAttachment, ToolAttachmentType } from '@/features/ai/runtime/thread-timeline-types'
import { apiBaseUrl } from '@/shared/api/client'

const MANAGED_RESOURCE_URI_PATTERN = /^(file:|s3:)/i
const SHA256_PATTERN = /^[0-9a-f]{64}$/

export function contentText(content: Record<string, unknown>): string {
  const type = getString(content.type)
  if (type === 'text' || type === 'thinking') {
    return getString(content.text)
  }
  if (type === 'json') {
    return stringifyJsonContent(content.json)
  }
  return ''
}

/** 规范的 Tool delta：对 object/array 类型的 json 值进行序列化而非丢弃。 */
function stringifyJsonContent(value: unknown): string {
  if (typeof value === 'string') {
    return value
  }
  if (value == null) {
    return ''
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return ''
    }
  }
  return String(value)
}

/**
 * 规范的 resource content -> attachment：`{type:'resource', uri, mediaType, name, size,
 * sha256, preview?}` 与 durable `{type:'resource', blobId, name, preview?}`。
 * URI 是稳定的展示/链接标识；file:/s3: URI 不会被当作 base64 负载。
 * blobId 资源不携带 URI——预览/下载 URL 只在渲染时通过 storage service 解析。
 */
export function toResourceAttachment(content: Record<string, unknown>): ToolAttachment[] {
  const type = getString(content.type)
  const blobId = getString(content.blobId)
  if ((type === 'resource' || type === 'RESOURCE') && blobId.trim()) {
    const name = getString(content.name)
    if (!name.trim()) {
      return []
    }
    const mediaType = getString(content.mediaType)
    return [
      {
        type: resourceAttachmentType(mediaType),
        name,
        mime: mediaType,
        data: '',
        blobId,
        preview: getString(content.preview) || undefined,
        size: numberField(content, 'size', 'sizeBytes') || null,
      },
    ]
  }
  if (type !== 'resource') {
    return []
  }
  const uri = getString(content.uri)
  if (!uri.trim()) {
    return []
  }
  const mediaType = getString(content.mediaType)
  const name = getString(content.name)
  const size =
    typeof content.size === 'number' && Number.isSafeInteger(content.size) && content.size >= 0
      ? content.size
      : null
  const sha256 = getString(content.sha256) || null
  return [
    {
      type: resourceAttachmentType(mediaType),
      name,
      mime: mediaType,
      data: uri,
      preview: getString(content.preview) || undefined,
      size,
      sha256,
      downloadHref: managedResourceHref(uri, mediaType, name, size, sha256),
    },
  ]
}

function managedResourceHref(
  uri: string,
  mediaType: string,
  name: string,
  size: number | null,
  sha256: string | null,
): string | undefined {
  if (
    !MANAGED_RESOURCE_URI_PATTERN.test(uri)
    || size == null
    || !SHA256_PATTERN.test(sha256 ?? '')
    || !mediaType.trim()
  ) {
    return undefined
  }
  const query = new URLSearchParams({
    mediaType,
    size: String(size),
  })
  if (name.trim()) {
    query.set('name', name)
  }
  return `${apiBaseUrl}/ai/runtime/resources/${sha256}?${query}`
}

export function resourceAttachmentType(mediaType: string): ToolAttachmentType {
  if (mediaType.startsWith('image/')) {
    return 'image'
  }
  if (mediaType.startsWith('audio/')) {
    return 'audio'
  }
  if (mediaType.startsWith('video/')) {
    return 'video'
  }
  return 'file'
}

export function numberField(record: Record<string, unknown>, ...keys: string[]): number {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'number' && Number.isFinite(value)) {
      return Math.max(0, value)
    }
    if (typeof value === 'string' && value.trim()) {
      const parsed = Number(value)
      if (Number.isFinite(parsed)) {
        return Math.max(0, parsed)
      }
    }
  }
  return 0
}

export function formatCompactTokens(count: number): string {
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
  return `${(count / 1_000_000).toFixed(1)}M`
}

/** 从持久 ASSISTANT assistantMetadata 解析出的完整用量（全零时为 null）。 */
export interface ParsedTurnUsage {
  input: number
  output: number
  cacheRead: number
  cacheWrite: number
  /** 仅详情展示；不进摘要文本。 */
  reasoning: number
  /** Provider 报告的 total；仅详情展示，绝不与 input+output 混算。 */
  providerTotal: number
  cost: number
}

/**
 * 从持久 ASSISTANT Entry 的 `assistantMetadata` 提取完整 usage/cost。
 * 字段名以 harness 持久 codec 为准（camelCase），并容忍常见别名。
 */
export function parseAssistantUsage(metadata: Record<string, unknown>): ParsedTurnUsage | null {
  const usage = asRecord(metadata.usage)
  const costNode = metadata.cost
  const input = numberField(usage, 'inputTokens', 'input_tokens', 'promptTokens')
  const output = numberField(usage, 'outputTokens', 'output_tokens', 'completionTokens')
  const cacheRead = numberField(
    usage,
    'cacheReadTokens',
    'cache_read_tokens',
    'cacheRead',
    'cachedTokens',
  )
  const cacheWrite =
    numberField(usage, 'cacheWriteTokens', 'cache_write_tokens', 'cacheWrite')
    + numberField(usage, 'cacheWriteLongTokens', 'cache_write_long_tokens', 'cacheWriteLong')
  const reasoning = numberField(usage, 'reasoningTokens', 'reasoning_tokens')
  const providerTotal = numberField(
    usage,
    'providerTotalTokens',
    'provider_total_tokens',
    'totalTokens',
  )
  let cost = 0
  if (typeof costNode === 'number' && Number.isFinite(costNode)) {
    cost = costNode
  } else if (typeof costNode === 'string' && costNode.trim()) {
    const parsed = Number(costNode)
    if (Number.isFinite(parsed)) {
      cost = parsed
    }
  } else if (costNode && typeof costNode === 'object') {
    cost = numberField(asRecord(costNode), 'total', 'amount', 'usd')
  }
  if (
    input <= 0
    && output <= 0
    && cacheRead <= 0
    && cacheWrite <= 0
    && reasoning <= 0
    && providerTotal <= 0
    && cost <= 0
  ) {
    return null
  }
  return { input, output, cacheRead, cacheWrite, reasoning, providerTotal, cost }
}

/**
 * Turn usage 摘要文本（Conversation TurnSummary 与 Event TURN_END 共用）：
 * `↑input · ↓output · RcacheRead · WcacheWrite · $cost`；不附加 reasoning `T`
 * 或 cache hit `CH`；缺失/为零的 cache 段省略，input/output 按现有事实显示。
 */
export function formatTurnUsageText(usage: {
  input: number
  output: number
  cacheRead: number
  cacheWrite: number
  cost: number
}): string {
  const parts = [
    `↑${formatCompactTokens(usage.input)}`,
    `↓${formatCompactTokens(usage.output)}`,
  ]
  if (usage.cacheRead > 0) {
    parts.push(`R${formatCompactTokens(usage.cacheRead)}`)
  }
  if (usage.cacheWrite > 0) {
    parts.push(`W${formatCompactTokens(usage.cacheWrite)}`)
  }
  parts.push(`$${usage.cost.toFixed(3)}`)
  return parts.join(' · ')
}
