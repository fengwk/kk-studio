import { isRecord } from '@/shared/lib/value'

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
  return isRecord(value) ? value : {}
}

export function getRecordList(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map(asRecord).filter((item) => Object.keys(item).length > 0)
}
