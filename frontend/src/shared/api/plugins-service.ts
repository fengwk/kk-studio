import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  PluginAuthCompleteRequestDTO,
  PluginAuthPrepareDTO,
  PluginAuthPrepareRequestDTO,
  PluginConfigurationUpdateRequestDTO,
  PluginConnectionCheckDTO,
  PluginConnectionCheckRequestDTO,
  PluginDTO,
} from '@/shared/api/contracts/ai-plugin'
import {
  decodePluginAuthPrepareDTO,
  decodePluginConnectionCheckDTO,
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

    /**
     * 显式只读连接检查：只向该 Plugin 的固定端点提交一次期望已应用坐标。
     * 调用方只以用户显式操作触发，本方法不重试、不拼接任意路径或凭据。
     */
    checkConnection: async (
      pluginId: string,
      data: PluginConnectionCheckRequestDTO,
    ): Promise<PluginConnectionCheckDTO> => {
      const resp = await client.post<unknown>(
        `/plugins/${encodeURIComponent(pluginId)}/connection-check`,
        data,
      )
      return decodePluginConnectionCheckDTO(resp)
    },
  }
}

export const pluginsService = createPluginsService()
