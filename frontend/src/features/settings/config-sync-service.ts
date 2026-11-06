import { request } from '@/shared/api/client'
import type {
  ConfigSyncExportDTO,
  ConfigSyncImportCheckDTO,
  ConfigSyncImportResultDTO,
  ConfigSyncInventoryDTO,
  ConfigSyncRef,
} from '@/shared/api/generated/contracts'

/**
 * 配置同步读写面：
 * - `GET /api/platform/config-sync`：可导出的库存（种类、条目与依赖），不含配置值与凭据；
 * - `POST /api/platform/config-sync/export`：按引用导出 YAML，依赖由后端补齐；
 * - `POST /api/platform/config-sync/check-import`：只做预检查，返回将新增/覆盖/跳过的计划，不写库；
 * - `POST /api/platform/config-sync/import`：导入 YAML，按 allowPartial 决定是否写入可用条目。
 *
 * 同步只读写服务端已保存的配置，不触碰客户端 Settings draft。
 */
export function createConfigSyncService() {
  return {
    getInventory: (): Promise<ConfigSyncInventoryDTO> =>
      request('GET /api/platform/config-sync', {}),

    exportConfig: (items: ConfigSyncRef[]): Promise<ConfigSyncExportDTO> =>
      request('POST /api/platform/config-sync/export', { body: { items } }),

    checkImport: (yaml: string): Promise<ConfigSyncImportCheckDTO> =>
      request('POST /api/platform/config-sync/check-import', { body: { yaml } }),

    importConfig: (yaml: string, allowPartial = false): Promise<ConfigSyncImportResultDTO> =>
      request('POST /api/platform/config-sync/import', { body: { yaml, allowPartial } }),
  }
}

export const configSyncService = createConfigSyncService()
