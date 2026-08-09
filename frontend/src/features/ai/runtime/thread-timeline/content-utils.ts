import { getString } from '@/features/ai/runtime/payload-json'
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
 * sha256, preview?}`。URI 是稳定的展示/链接标识；file:/s3: URI 不会被当作 base64 负载。
 */
export function toResourceAttachment(content: Record<string, unknown>): ToolAttachment[] {
  if (getString(content.type) !== 'resource') {
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
