import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  PluginAuthCompleteRequestDTO,
  PluginAuthPrepareDTO,
  PluginAuthPrepareRequestDTO,
  PluginConfigurationUpdateRequestDTO,
  PluginDTO,
} from '@/shared/api/contracts/ai-plugin'
import {
  decodePluginAuthPrepareDTO,
  decodePluginDTO,
  decodePluginList,
} from '@/shared/api/ai-plugin-codec'

export function createPluginsService(client: HttpClient = apiClient) {
  return {
    listPlugins: async (): Promise<PluginDTO[]> => {
      const data = await client.get<unknown>('/plugins')
      return decodePluginList(data)
    },

    getPlugin: async (pluginId: string): Promise<PluginDTO> => {
      const data = await client.get<unknown>(`/plugins/${encodeURIComponent(pluginId)}`)
      return decodePluginDTO(data)
    },

    prepareAuth: async (
      pluginId: string,
      data: PluginAuthPrepareRequestDTO,
    ): Promise<PluginAuthPrepareDTO> => {
      const resp = await client.post<unknown>(
        `/plugins/${encodeURIComponent(pluginId)}/auth/prepare`,
        data,
      )
      return decodePluginAuthPrepareDTO(resp)
    },

    completeAuth: async (
      pluginId: string,
      data: PluginAuthCompleteRequestDTO,
    ): Promise<PluginDTO> => {
      const resp = await client.post<unknown>(
        `/plugins/${encodeURIComponent(pluginId)}/auth/complete`,
        data,
      )
      return decodePluginDTO(resp)
    },

    updateConfiguration: async (
      pluginId: string,
      data: PluginConfigurationUpdateRequestDTO,
    ): Promise<PluginDTO> => {
      const resp = await client.put<unknown>(
        `/plugins/${encodeURIComponent(pluginId)}/configuration`,
        data,
      )
      return decodePluginDTO(resp)
    },

    disconnectAuth: (pluginId: string): Promise<void> =>
      client.delete(`/plugins/${encodeURIComponent(pluginId)}/auth`),
  }
}

export const pluginsService = createPluginsService()
