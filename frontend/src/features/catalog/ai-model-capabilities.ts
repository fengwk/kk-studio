import { request } from '@/shared/api/client'
import type { ModelCapabilitiesDTO } from '@/shared/api/generated/contracts'

/** Resolve model-specific cache options from the backend; never infer them from an ID. */
export function getModelCapabilities(
  providerName: string,
  modelId: string,
  signal?: AbortSignal,
): Promise<ModelCapabilitiesDTO> {
  return request('GET /api/platform/models/capabilities', {
    query: { providerName, modelId },
  }, { signal })
}
