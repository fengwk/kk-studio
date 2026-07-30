import type { ToolAttachmentType } from '@/features/ai/runtime/thread-timeline-types'

export function parsePayload(payloadJson: string | null): Record<string, unknown> {
  if (!payloadJson) {
    return {}
  }
  try {
    const parsed = JSON.parse(payloadJson)
    return asRecord(parsed)
  } catch {
    return {}
  }
}

export function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

export function getRecordList(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map(asRecord).filter((item) => Object.keys(item).length > 0)
}

export function getString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

export function getInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) ? value : null
}

export function getToolContentType(value: unknown): 'text' | ToolAttachmentType | null {
  if (value === 'text' || value === 'image' || value === 'audio' || value === 'video') {
    return value
  }
  return null
}
