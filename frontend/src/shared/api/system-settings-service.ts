import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  SystemSettingsDTO,
  SystemSettingsUpdateDTO,
} from '@/shared/api/contracts/system-settings'

/**
 * 全局 system settings 聚合的读写面：
 * - GET /settings：返回六个 section + version/createTime/updateTime；
 * - PUT /settings：以 expectedVersion CAS 完整替换聚合，返回更新后的 GET 形状。
 *
 * 前端不做默认值兜底：GET 返回的权威聚合是唯一事实源，draft 只从它派生。
 */
export function createSystemSettingsService(client: HttpClient = apiClient) {
  return {
    get: (): Promise<SystemSettingsDTO> => client.get<SystemSettingsDTO>('/settings'),
    update: (update: SystemSettingsUpdateDTO): Promise<SystemSettingsDTO> =>
      client.put<SystemSettingsDTO>('/settings', update),
  }
}

export const systemSettingsService = createSystemSettingsService()
