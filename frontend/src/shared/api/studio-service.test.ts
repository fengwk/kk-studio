import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/api/client'
import { createCanvas, listCanvases } from '@/shared/api/studio-service'

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('studio-service', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists canvases without a workspace id', async () => {
    vi.mocked(apiClient.get).mockResolvedValueOnce([])
    await listCanvases()
    expect(apiClient.get).toHaveBeenCalledWith('/canvases')
  })

  it('creates canvases with a title-only body', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ id: 'c1' })
    await createCanvas('Board')
    expect(apiClient.post).toHaveBeenCalledWith('/canvases', { title: 'Board' })
  })
})
