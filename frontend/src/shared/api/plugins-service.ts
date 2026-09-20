import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  PluginAuthCompleteRequestDTO,
  PluginAuthPrepareDTO,
  PluginAuthPrepareRequestDTO,
  PluginDTO,
} from '@/shared/api/contracts/ai-plugin'

export function createPluginsService(client: HttpClient = apiClient) {
  return {
    listPlugins: (): Promise<PluginDTO[]> => client.get('/plugins'),

    getPlugin: (pluginId: string): Promise<PluginDTO> =>
      client.get(`/plugins/${encodeURIComponent(pluginId)}`),

    prepareAuth: (
      pluginId: string,
      data: PluginAuthPrepareRequestDTO,
    ): Promise<PluginAuthPrepareDTO> =>
      client.post(`/plugins/${encodeURIComponent(pluginId)}/auth/prepare`, data),

    completeAuth: (
      pluginId: string,
      data: PluginAuthCompleteRequestDTO,
    ): Promise<PluginDTO | void> =>
      client.post(`/plugins/${encodeURIComponent(pluginId)}/auth/complete`, data),

    disconnectAuth: (pluginId: string): Promise<void> =>
      client.delete(`/plugins/${encodeURIComponent(pluginId)}/auth`),
  }
}

export const pluginsService = createPluginsService()
