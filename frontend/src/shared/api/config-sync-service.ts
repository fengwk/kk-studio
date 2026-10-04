import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  ConfigSyncExportResponseDTO,
  ConfigSyncImportResponseDTO,
  ConfigSyncInventoryDTO,
  ConfigSyncRef,
} from '@/shared/api/contracts/config-sync'

/**
 * 配置同步读写面：
 * - `GET /settings/sync`：可导出的库存（种类、条目与依赖），不含配置值与凭据；
 * - `POST /settings/sync/export`：按引用导出 YAML，依赖由后端补齐；
 * - `POST /settings/sync/import`：导入 YAML，返回已导入与跳过条目。
 *
 * 同步只读写服务端已保存的配置，不触碰客户端 Settings draft。
 */
export function createConfigSyncService(client: HttpClient = apiClient) {
  return {
    getInventory: (): Promise<ConfigSyncInventoryDTO> =>
      client.get<ConfigSyncInventoryDTO>('/settings/sync'),

    exportConfig: (items: ConfigSyncRef[]): Promise<ConfigSyncExportResponseDTO> =>
      client.post<ConfigSyncExportResponseDTO>('/settings/sync/export', { items }),

    importConfig: (yaml: string): Promise<ConfigSyncImportResponseDTO> =>
      client.post<ConfigSyncImportResponseDTO>('/settings/sync/import', { yaml }),
  }
}

export const configSyncService = createConfigSyncService()
