import { request } from '@/shared/api/client'
import type {
  ModelPricingDTO,
  ModelPricingPreviewDTO,
} from '@/shared/api/generated/contracts'

/** Use the same backend pricing resolver as runtime; frontend does not evaluate a parallel price. */
export function previewModelPricing(
  input: {
    pricing?: ModelPricingDTO | null
    inputTokens: number
    requestedAt?: number
    serviceTier?: string
  },
  signal?: AbortSignal,
): Promise<ModelPricingPreviewDTO> {
  return request('POST /api/platform/models/pricing-preview', {
    body: input,
  }, { signal })
}
