import { asRecord, getString } from '@/features/ai/runtime/payload-json'
import type { ToolAttachment, ToolAttachmentType } from '@/features/ai/runtime/thread-timeline-types'

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

/** Canonical Tool delta: stringify object/array json values instead of dropping them. */
export function stringifyJsonContent(value: unknown): string {
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

export function toArtifactAttachment(content: Record<string, unknown>): ToolAttachment[] {
  if (getString(content.type) !== 'artifact') {
    return []
  }
  const mediaType = getString(content.mediaType)
  const type = artifactType(mediaType)
  const artifactId = getString(content.artifactId)
  if (!artifactId) {
    return []
  }
  return [{ type, name: artifactId, mime: mediaType, data: `/api/ai/runtime/artifacts/${encodeURIComponent(artifactId)}` }]
}

export function artifactType(mediaType: string): ToolAttachmentType {
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

export function asRecordOrEmpty(value: unknown): Record<string, unknown> {
  return asRecord(value)
}
