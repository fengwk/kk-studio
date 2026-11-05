export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function hasExactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const keys = Object.keys(value)
  return keys.length === expected.length && keys.every((key) => expected.includes(key))
}

export function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

export function getString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

const encoder = new TextEncoder()

export function utf8ByteLength(value: string): number {
  return encoder.encode(value).length
}
