import type { VariantDraft } from '@/features/ai/catalog/ai-console-types'

let draftIdSeed = 0

function nextDraftId(prefix: string): string {
  draftIdSeed += 1
  return `${prefix}-${draftIdSeed}`
}

export function newVariantDraft(input?: Partial<Omit<VariantDraft, 'draftId'>>): VariantDraft {
  return {
    draftId: nextDraftId('variant'),
    id: input?.id ?? 'medium',
    reasoningEffort: input?.reasoningEffort ?? '',
    maxOutputTokens: input?.maxOutputTokens ?? '',
    temperature: input?.temperature ?? '',
    topP: input?.topP ?? '',
    topK: input?.topK ?? '',
    frequencyPenalty: input?.frequencyPenalty ?? '',
    presencePenalty: input?.presencePenalty ?? '',
    stopSequences: input?.stopSequences ?? '',
  }
}

export function trimToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

export function numberToNull(value: string | number | null | undefined): number | null {
  if (value == null) {
    return null
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null
  }
  const trimmed = String(value).trim().replace(/,/g, '')
  if (!trimmed) {
    return null
  }
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

export function splitCommaSeparatedValues(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}
