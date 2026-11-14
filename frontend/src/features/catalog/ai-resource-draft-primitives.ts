import type { VariantDraft } from '@/features/catalog/ai-console-types'

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
    protocolOptionsJson: input?.protocolOptionsJson ?? '',
    promptCacheRetention: input?.promptCacheRetention ?? 'NONE',
  }
}

export function trimToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}
