import type { VariantDraft } from '@/features/ai/catalog/ai-console-types'
import { newVariantDraft } from '@/features/ai/catalog/ai-resource-draft-primitives'

/** 新增 variant 的思考强度默认留空，表示不覆盖协议默认。 */
export function blankVariant(id = 'medium'): VariantDraft {
  return newVariantDraft({ id: id.trim() || 'medium' })
}
