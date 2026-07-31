import type { VariantDraft } from '@/features/ai/catalog/ai-console-types'
import { newVariantDraft } from '@/features/ai/catalog/ai-resource-draft-primitives'

export function blankVariant(id = 'medium', reasoningEffort = ''): VariantDraft {
  return newVariantDraft({
    id: id.trim() || 'medium',
    reasoningEffort,
  })
}
