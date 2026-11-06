import { isRecord } from '@/shared/lib/value'

export function asRecord(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {}
}

export function getRecordList(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map(asRecord).filter((item) => Object.keys(item).length > 0)
}

/** 格式化契约明确为 JSON 原文的字段；非法原文仍可供检查。 */
export function formatRawJsonText(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** 解码明确承载 JSON 原文的内容；无效文本原样保留以便展示。 */
export function parseRawJsonText(raw: string): unknown {
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return raw
  }
}
