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
  desiredRevision: '1',
  appliedRevision: '1',
  connectionCheckSupported: true,
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

    // 显式连接检查只向该 Plugin 的固定端点提交一次期望坐标，不重试也不拼接任何其它路径。
    const checkRequest = { expectedRevision: '1' }
    const checkResult = {
      pluginId: 'minimax-mavis',
      appliedRevision: '1',
      checkedAt: 1772366400,
      reachability: 'REACHABLE',
      authentication: 'ACCEPTED',
      browserSession: 'UNKNOWN',
    }
    vi.mocked(client.post).mockResolvedValueOnce(checkResult)
    await expect(service.checkConnection('minimax-mavis', checkRequest)).resolves.toEqual(checkResult)
    expect(client.post).toHaveBeenNthCalledWith(
      3,
      '/plugins/minimax-mavis/connection-check',
      checkRequest,
    )
    expect(client.post).toHaveBeenCalledTimes(3)

    // 需要转义的安装身份必须整体编码，确保只命中该 Plugin 自己的端点。
    vi.mocked(client.post).mockResolvedValueOnce({ ...checkResult, pluginId: 'a/b?c' })
    await service.checkConnection('a/b?c', checkRequest)
    expect(client.post).toHaveBeenNthCalledWith(4, '/plugins/a%2Fb%3Fc/connection-check', checkRequest)
    expect(client.post).toHaveBeenCalledTimes(4)
  })

  it('rejects a connection check payload that leaves the typed contract', async () => {
    const client = createClient()
    const service = createPluginsService(client)
    vi.mocked(client.post).mockResolvedValue({
      pluginId: 'minimax-mavis',
      appliedRevision: '1',
      checkedAt: 1772366400,
      reachability: 'CONNECTED',
      authentication: 'ACCEPTED',
      browserSession: 'UNKNOWN',
    })

    await expect(
      service.checkConnection('minimax-mavis', { expectedRevision: '1' }),
    ).rejects.toThrowError(/reachability must be one of/)
    expect(client.post).toHaveBeenCalledTimes(1)
  })
})
