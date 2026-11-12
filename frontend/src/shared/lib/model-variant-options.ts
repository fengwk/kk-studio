import type { ModelDTO } from '@/shared/api/generated/contracts'

/** Model groups do not have variants; concrete model variant IDs are normalized for selection. */
export function variantOptionsFromModel(model?: ModelDTO | null): string[] {
  if (model?.type !== 'MODEL') {
    return []
  }
  return [...new Set(model.config.variants.map((variant) => variant.id.trim()).filter(Boolean))]
}
