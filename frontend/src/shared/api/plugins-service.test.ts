import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createPluginsService } from '@/shared/api/plugins-service'

function createClient(): HttpClient {
  return {
    get: vi.fn().mockResolvedValue([]),
    post: vi.fn().mockResolvedValue({}),
    put: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue(undefined),
  }
}

describe('pluginsService', () => {
  it('maps plugin endpoints correctly', async () => {
    const client = createClient()
    const service = createPluginsService(client)

    await service.listPlugins()
    expect(client.get).toHaveBeenNthCalledWith(1, '/plugins')

    await service.getPlugin('minimax-mavis')
    expect(client.get).toHaveBeenNthCalledWith(2, '/plugins/minimax-mavis')

    const prepareData = { region: 'CN' }
    await service.prepareAuth('minimax-mavis', prepareData)
    expect(client.post).toHaveBeenNthCalledWith(1, '/plugins/minimax-mavis/auth/prepare', prepareData)

    const completeData = { callbackUrl: 'minimax-cn://auth-callback?code=abc' }
    await service.completeAuth('minimax-mavis', completeData)
    expect(client.post).toHaveBeenNthCalledWith(2, '/plugins/minimax-mavis/auth/complete', completeData)

    await service.disconnectAuth('minimax-mavis')
    expect(client.delete).toHaveBeenCalledWith('/plugins/minimax-mavis/auth')
  })
})
