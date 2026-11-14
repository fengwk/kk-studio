import { getString } from '@/shared/lib/value'
import type {
  ToolAttachment,
  ToolAttachmentType,
  ToolContent,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'

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

/**
 * 规范的 Tool content -> 有序展示内容。
 *
 * 只有非空 text、显式 json 字段与合法 resource 会保留；思考等其它类型不进入工具展示。
 * resource 的 URI/名称/媒体类型原样保留，媒体 URL 只在渲染时解析或直接使用 data:。
 */
function toToolContent(content: Record<string, unknown>): ToolContent | null {
  const type = getString(content.type)
  if (type === 'text') {
    const text = getString(content.text)
    return text ? { type: 'text', text } : null
  }
  if (type === 'json') {
    return 'json' in content ? { type: 'json', value: content.json } : null
  }
  const attachment = toResourceAttachment(content)[0]
  return attachment ? { type: 'resource', attachment } : null
}

/** 投影一组规范 Tool contents，保持顺序并丢弃空内容。 */
export function toToolContents(contents: readonly Record<string, unknown>[]): ToolContent[] {
  const projected: ToolContent[] = []
  for (const content of contents) {
    const item = toToolContent(content)
    if (item != null) {
      projected.push(item)
    }
  }
  return projected
}

/** json 内容的格式化正文：仅在结果声明为 json 时缩进展示，纯文本绝不猜测。 */
export function formatJsonContent(value: unknown): string {
  try {
    const encoded = JSON.stringify(value, null, 2)
    return encoded === undefined ? String(value) : encoded
  } catch {
    return String(value)
  }
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
    },
  ]
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

// 冻结契约：thread-panel 与 browser-tests 按本模块路径引用这些用量投影，实现见
// turn-usage-format / usage-cost，新增调用方请直接引用实现模块。
export {
  calculateCacheHitRate,
  calculateDecodeTokensPerSecond,
  formatTurnUsageText,
} from '@/features/thread/runtime/thread-timeline/turn-usage-format'
export { formatUsageCost } from '@/features/thread/runtime/thread-timeline/usage-cost'
