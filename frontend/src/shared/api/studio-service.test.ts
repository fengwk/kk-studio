import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/api/client'
import {
  createCanvas,
  DEFAULT_WORKSPACE_ID,
  getCanvasSnapshot,
  listCanvases,
  listFunctions,
} from '@/shared/api/studio-service'

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

  it('lists functions and canvases with default workspace id', async () => {
    vi.mocked(apiClient.get).mockResolvedValueOnce([]).mockResolvedValueOnce([])
    await listFunctions()
    await listCanvases()
    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/functions', { params: { workspaceId: DEFAULT_WORKSPACE_ID } })
    expect(apiClient.get).toHaveBeenNthCalledWith(2, '/canvases', { params: { workspaceId: DEFAULT_WORKSPACE_ID } })
  })

  it('creates and loads canvases with encoded ids', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ id: 'c1' })
    vi.mocked(apiClient.get).mockResolvedValue({ id: 'c /1' })
    await createCanvas('Board', 'ws-2')
    await getCanvasSnapshot('c /1')
    expect(apiClient.post).toHaveBeenCalledWith('/canvases', { workspaceId: 'ws-2', title: 'Board' })
    expect(apiClient.get).toHaveBeenCalledWith('/canvases/c%20%2F1')
  })
})
