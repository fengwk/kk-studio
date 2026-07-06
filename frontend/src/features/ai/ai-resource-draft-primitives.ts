import type { KeyValueDraft, VariantDraft } from '@/features/ai/ai-console-types'

let draftIdSeed = 0

function nextDraftId(prefix: string): string {
  draftIdSeed += 1
  return `${prefix}-${draftIdSeed}`
}

export function newKeyValueDraft(key = '', value = ''): KeyValueDraft {
  return {
    id: nextDraftId('kv'),
    key,
    value,
  }
}

export function newVariantDraft(input?: Partial<Omit<VariantDraft, 'id' | 'extras'>> & { extras?: KeyValueDraft[] }): VariantDraft {
  return {
    id: nextDraftId('variant'),
    name: input?.name ?? '',
    temperature: input?.temperature ?? '',
    maxOutputTokens: input?.maxOutputTokens ?? '',
    extras: input?.extras ?? [],
  }
}

export function parseScalar(value: unknown): string {
  if (value == null) {
    return ''
  }
  return String(value)
}

export function trimToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

export function numberToNull(value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) {
    return null
  }
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

export function coerceScalar(value: string): string | number | boolean | null {
  const trimmed = value.trim()
  if (!trimmed) {
    return ''
  }
  if (trimmed === 'true') {
    return true
  }
  if (trimmed === 'false') {
    return false
  }
  if (trimmed === 'null') {
    return null
  }
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
    return Number(trimmed)
  }
  return trimmed
}

export function splitCommaSeparatedValues(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}
