import { vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'

/** 在 HTTP 边界注入响应，同时保留泛型调用签名和请求追踪。 */
export function createMockHttpClient() {
  const unconfigured = async (): Promise<never> => {
    throw new Error('HTTP mock is not configured')
  }
  const client: HttpClient = {
    get: unconfigured,
    post: unconfigured,
    put: unconfigured,
    delete: unconfigured,
  }
  vi.spyOn(client, 'get').mockResolvedValue({})
  vi.spyOn(client, 'post').mockResolvedValue({})
  vi.spyOn(client, 'put').mockResolvedValue({})
  vi.spyOn(client, 'delete').mockResolvedValue({})
  return vi.mocked(client)
}
