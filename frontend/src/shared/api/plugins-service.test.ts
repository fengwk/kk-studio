import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createPluginsService } from '@/shared/api/plugins-service'

function createClient(): HttpClient {
  return {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  }
}

const validPluginPayload = {
  pluginId: 'minimax-mavis',
  name: 'MiniMax Mavis',
  version: '1.0.0',
  authKind: {
    type: 'DEEP_LINK',
    regionCandidates: ['CN'],
  },
  status: 'CONNECTED',
  region: 'CN',
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '1',
    valuesJson: '{"enabled":true}',
    valuesSchema: '{"type":"object","properties":{"enabled":{"type":"boolean"}}}',
    defaultsJson: '{"enabled":true}',
    secrets: {},
  },
  desiredEpoch: '1',
  appliedEpoch: '1',
  desiredRevision: '1',
  appliedRevision: '1',
  harnessToolNames: ['search'],
  canvasFunctionNames: [],
  applicationError: null,
}

describe('pluginsService', () => {
  it('maps and decodes plugin endpoints correctly', async () => {
    const client = createClient()
    vi.mocked(client.get).mockResolvedValueOnce([validPluginPayload])
    vi.mocked(client.get).mockResolvedValueOnce(validPluginPayload)
    vi.mocked(client.post).mockResolvedValueOnce({
      authAttemptId: 'attempt-1',
      loginUrl: 'https://example.com/login',
    })
    vi.mocked(client.post).mockResolvedValueOnce(validPluginPayload)
    vi.mocked(client.put).mockResolvedValueOnce(validPluginPayload)
    vi.mocked(client.delete).mockResolvedValueOnce(undefined)

    const service = createPluginsService(client)

    const list = await service.listPlugins()
    expect(client.get).toHaveBeenNthCalledWith(1, '/plugins')
    expect(list).toHaveLength(1)
    expect(list[0].pluginId).toBe('minimax-mavis')

    const plugin = await service.getPlugin('minimax-mavis')
    expect(client.get).toHaveBeenNthCalledWith(2, '/plugins/minimax-mavis')
    expect(plugin.name).toBe('MiniMax Mavis')

    const prepareData = { region: 'CN' }
    const prepareResp = await service.prepareAuth('minimax-mavis', prepareData)
    expect(client.post).toHaveBeenNthCalledWith(
      1,
      '/plugins/minimax-mavis/auth/prepare',
      prepareData,
    )
    expect(prepareResp.authAttemptId).toBe('attempt-1')

    const completeData = {
      authAttemptId: 'attempt-1',
      callbackUrl: 'minimax-cn://auth-callback?code=abc',
    }
    const completeResp = await service.completeAuth('minimax-mavis', completeData)
    expect(client.post).toHaveBeenNthCalledWith(
      2,
      '/plugins/minimax-mavis/auth/complete',
      completeData,
    )
    expect(completeResp.pluginId).toBe('minimax-mavis')

    const updateData = {
      expectedEpoch: '1',
      expectedRevision: '1',
      valuesJson: '{"enabled":false}',
      secretPatch: {
        apiKey: { operation: 'SET' as const, value: 'new-key' },
      },
    }
    const updateResp = await service.updateConfiguration('minimax-mavis', updateData)
    expect(client.put).toHaveBeenNthCalledWith(
      1,
      '/plugins/minimax-mavis/configuration',
      updateData,
    )
    expect(updateResp.pluginId).toBe('minimax-mavis')

    await service.disconnectAuth('minimax-mavis')
    expect(client.delete).toHaveBeenCalledWith('/plugins/minimax-mavis/auth')
  })
})
