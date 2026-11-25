import type { ModelCapabilitiesDTO, ModelCatalogPageDTO } from '@/shared/api/generated/contracts'

export async function getModelCapabilities(provider: string, _modelId: string, signal?: AbortSignal): Promise<ModelCapabilitiesDTO> {
  signal?.throwIfAborted()
  return {
    promptCacheState: provider === 'openai' ? 'CONFIGURABLE' : 'PROVIDER_MANAGED',
    defaultPromptCacheRetention: 'NONE',
    promptCacheOptions: provider === 'openai' ? [{ retention: 'NONE' }, { retention: 'SHORT' }] : [],
  }
}

async function catalogPage(): Promise<ModelCatalogPageDTO> {
  return { generation: '0', loading: false, items: [] }
}

export const agentService = {
  listModels: async () => [],
  listModelCatalog: catalogPage,
  refreshModelCatalog: catalogPage,
}
