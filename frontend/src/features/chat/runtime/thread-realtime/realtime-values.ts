export function stringifyJson(value: unknown): string {
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

/** 严格遵循 Java `nullableText` 语义：只接受 string 或 null（undefined 视为格式错误）。 */
export function nullableString(value: unknown): string | null | undefined {
  if (typeof value === 'string' || value == null) {
    return value as string | null
  }
  return undefined
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}

export function hasOnlyFields(record: Record<string, unknown>, fields: readonly string[]): boolean {
  return Object.keys(record).every((key) => fields.includes(key))
}

export function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

export function toEpochMillis(value: unknown): number | null {
  if (typeof value !== 'string' || !value.trim()) {
    return null
  }
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

export function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

export function getString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}
