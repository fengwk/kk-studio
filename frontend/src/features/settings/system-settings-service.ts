import { request } from '@/shared/api/client'
import type {
  SystemSettingsDTO,
  SystemSettingsSchemaDTO,
  SystemSettingsUpdateDTO,
} from '@/shared/api/generated/contracts'

/**
 * 全局 system settings 聚合的读写面：
 * - GET /api/platform/settings：返回六个 section + version/createdAt/updatedAt；
 * - PUT /api/platform/settings：以 expectedVersion CAS 完整替换聚合，返回更新后的 GET 形状。
 *
 * 前端不做默认值兜底：GET 返回的权威聚合是唯一事实源，draft 只从它派生。
 */
export function createSystemSettingsService() {
  return {
    get: (): Promise<SystemSettingsDTO> => request('GET /api/platform/settings', {}),
    getSchema: (): Promise<SystemSettingsSchemaDTO> =>
      request('GET /api/platform/settings/schema', {}),
    update: (update: SystemSettingsUpdateDTO): Promise<SystemSettingsDTO> =>
      request('PUT /api/platform/settings', { body: update }),
  }
}

export const systemSettingsService = createSystemSettingsService()
