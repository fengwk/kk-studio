import { describe, expect, it, vi } from 'vitest'
import { createEnvironmentService } from '@/shared/api/environment-service'

describe('environmentService', () => {
  it('lists live environments from the read-only registry', async () => {
    const client = {
      get: vi.fn(async () => []),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.listEnvironments()
    expect(client.get).toHaveBeenCalledWith('/environments')
  })
})
