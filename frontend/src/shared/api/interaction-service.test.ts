import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { InteractionService } from '@/shared/api/interaction-service'

describe('InteractionService', () => {
  it('calls GET /interactions with root, cursor and limit params', async () => {
    const mockClient: HttpClient = {
      get: vi.fn().mockResolvedValue({ items: [], nextCursor: null, total: 0, freshnessAt: null }),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
    }
    const service = new InteractionService(mockClient)

    const result = await service.listInteractions('root-1', 'cursor-1', 20)

    expect(mockClient.get).toHaveBeenCalledWith('/interactions', {
      params: { rootThreadId: 'root-1', cursor: 'cursor-1', limit: 20 },
    })
    expect(result).toEqual({ items: [], nextCursor: null, total: 0, freshnessAt: null })
  })

  it('omits every unset param when listing without a root filter', async () => {
    const mockClient: HttpClient = {
      get: vi.fn().mockResolvedValue({ items: [], nextCursor: null, total: 0, freshnessAt: null }),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
    }
    const service = new InteractionService(mockClient)

    await service.listInteractions(null, null, null)

    expect(mockClient.get).toHaveBeenCalledWith('/interactions', { params: {} })
  })

  it('submits tool input without actor to POST /interactions/:id/input', async () => {
    const mockClient: HttpClient = {
      get: vi.fn(),
      post: vi.fn().mockResolvedValue({
        threadId: 'th-1',
        interactionId: 'int-1',
        submissionId: 'sub-1',
        actor: 'local-user',
        acceptedAt: '2026-09-27T00:00:00Z',
        materialized: false,
      }),
      put: vi.fn(),
      delete: vi.fn(),
    }
    const service = new InteractionService(mockClient)

    const result = await service.submitToolInput('int-1', {
      threadId: 'th-1',
      submissionId: 'sub-1',
      declined: false,
      answers: [['1080P'], ['成片']],
    })

    expect(mockClient.post).toHaveBeenCalledWith('/interactions/int-1/input', {
      threadId: 'th-1',
      submissionId: 'sub-1',
      declined: false,
      answers: [['1080P'], ['成片']],
    })
    expect(result.materialized).toBe(false)
  })
})
