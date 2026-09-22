import { describe, expect, it, vi } from 'vitest'
import { createOwnerService } from './owner-service'
import type { HttpClient } from './client'

describe('ownerService', () => {
  it('listProjectSessions should request /projects/:projectId/sessions', async () => {
    // 测试意图：验证 listProjectSessions 向正确的 REST 端点发起 GET 请求并返回数据
    const mockSessions = [
      {
        sessionId: 's-100',
        name: 'Coordinator Session',
        createdAt: '2026-09-14T00:00:00Z',
        threadCount: 1,
      },
    ]
    const client: HttpClient = {
      get: vi.fn().mockResolvedValue(mockSessions),
      post: vi.fn(),
      put: vi.fn(),
      patch: vi.fn(),
      delete: vi.fn(),
    }

    const service = createOwnerService(client)
    const result = await service.listProjectSessions('proj-123')

    expect(client.get).toHaveBeenCalledWith('/projects/proj-123/sessions')
    expect(result).toEqual(mockSessions)
  })

  it('listProjectSessions should encode projectId properly', async () => {
    // 测试意图：验证路径中的特殊字符被正确 URL 编码
    const client: HttpClient = {
      get: vi.fn().mockResolvedValue([]),
      post: vi.fn(),
      put: vi.fn(),
      patch: vi.fn(),
      delete: vi.fn(),
    }

    const service = createOwnerService(client)
    await service.listProjectSessions('proj/with/slash')

    expect(client.get).toHaveBeenCalledWith('/projects/proj%2Fwith%2Fslash/sessions')
  })
})
