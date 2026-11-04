import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  PluginAuthCompleteRequestDTO,
  PluginAuthPrepareDTO,
  PluginAuthPrepareRequestDTO,
  PluginConfigurationUpdateRequestDTO,
  PluginConnectionCheckDTO,
  PluginConnectionCheckRequestDTO,
  PluginDTO,
} from '@/shared/api/generated/contracts'

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
    ): Promise<PluginDTO> =>
      client.post(`/plugins/${encodeURIComponent(pluginId)}/auth/complete`, data),

    updateConfiguration: (
      pluginId: string,
      data: PluginConfigurationUpdateRequestDTO,
    ): Promise<PluginDTO> =>
      client.put(`/plugins/${encodeURIComponent(pluginId)}/configuration`, data),

    disconnectAuth: (pluginId: string): Promise<void> =>
      client.delete(`/plugins/${encodeURIComponent(pluginId)}/auth`),

    /**
     * 显式只读连接检查：只向该 Plugin 的固定端点提交一次期望已应用坐标。
     * 调用方只以用户显式操作触发，本方法不重试、不拼接任意路径或凭据。
     */
    checkConnection: (
      pluginId: string,
      data: PluginConnectionCheckRequestDTO,
    ): Promise<PluginConnectionCheckDTO> =>
      client.post(`/plugins/${encodeURIComponent(pluginId)}/connection-check`, data),
  }
}

export const pluginsService = createPluginsService()
