import { beforeEach, describe, expect, it, vi } from 'vitest'
import { agentService } from '@/shared/api/agent-service'
import { request } from '@/shared/api/client'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
import type {
  AgentCreateDTO,
  AgentUpdateDTO,
  ModelCreateDTO,
  ModelUpdateDTO,
  ProviderCreateDTO,
  ProviderUpdateDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const catalogRequest = vi.mocked(request) as unknown as ReturnType<typeof vi.fn>

describe('agentService', () => {
  beforeEach(() => {
    catalogRequest.mockReset()
    catalogRequest.mockResolvedValue(undefined)
  })

  it('maps global resource lists to catalog routes', async () => {
    await agentService.listProviders()
    await agentService.listModels()
    await agentService.listAgents()
    await agentService.listTools()

    expect(catalogRequest).toHaveBeenNthCalledWith(1, 'GET /api/platform/providers', {})
    expect(catalogRequest).toHaveBeenNthCalledWith(
      2,
      'GET /api/platform/models',
      {},
      { jsonParser: parseJsonPreservingPathEqualsNumbers },
    )
    expect(catalogRequest).toHaveBeenNthCalledWith(3, 'GET /api/platform/agents', {})
    expect(catalogRequest).toHaveBeenNthCalledWith(4, 'GET /api/platform/tools', {})
  })

  /**
   * 测试意图：验证 Provider、Model 和 Agent 的增删改端点，
   * 特别验证 Model 的 PUT/DELETE 端点使用尾段通配身份。
   */
  it('maps provider, model and agent mutations to name-based CRUD endpoints', async () => {
    const providerBody: ProviderCreateDTO = {
      name: 'minimax',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.chat/v1',
      credential: { operation: 'SET', value: 'key' },
      modelCallTimeoutMillis: '1800000',
      modelCallIdleTimeoutMillis: '120000',
    }
    const modelBody: ModelCreateDTO = {
      providerName: 'minimax',
      type: 'MODEL',
      name: 'MiniMax-M2.7',
      modelId: 'wire-minimax',
      description: null,
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, messageLevelEffort: false, inputModalities: ['TEXT'] },
        pricing: {
          base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
          overrides: [],
          multipliers: [],
        },
        defaultVariant: 'default',
        variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
      },
    }
    const agentBody: AgentCreateDTO = {
      name: 'default-assistant',
      description: null,
      systemPrompt: null,
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: 'default' } },
      config: { tools: [], skills: [], subagents: [] },
    }
    const updateProviderBody: ProviderUpdateDTO = {
      credential: { operation: 'KEEP' },
      description: 'updated',
      modelCallTimeoutMillis: '1800000',
      modelCallIdleTimeoutMillis: '120000',
      expectedVersion: '7',
    }
    const updateModelBody: ModelUpdateDTO = {
      type: 'MODEL',
      name: 'MiniMax-M2.7-renamed',
      providerName: 'minimax',
      modelId: 'wire-minimax',
      description: null,
      expectedVersion: '8',
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, messageLevelEffort: false, inputModalities: ['TEXT'] },
        pricing: {
          base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
          overrides: [],
          multipliers: [],
        },
        defaultVariant: 'fast',
        variants: [{ id: 'fast', promptCacheRetention: 'NONE' }],
      },
    }
    const updateAgentBody: AgentUpdateDTO = {
      description: 'updated',
      expectedVersion: '9',
      systemPrompt: null,
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: 'default' } },
      config: { tools: [], skills: [], subagents: [] },
    }
    await agentService.createProvider(providerBody)
    await agentService.updateProvider('minimax', updateProviderBody)
    await agentService.deleteProvider('minimax', '8')
    await agentService.createModel(modelBody)
    await agentService.updateModel('vendor/MiniMax M2.7', updateModelBody)
    await agentService.deleteModel('vendor/MiniMax M2.7', '10')
    await agentService.createAgent(agentBody)
    await agentService.updateAgent('default-assistant', updateAgentBody)
    await agentService.deleteAgent('default-assistant', '11')

    expect(catalogRequest).toHaveBeenNthCalledWith(1, 'POST /api/platform/providers', { body: providerBody })
    expect(catalogRequest).toHaveBeenNthCalledWith(2, 'PUT /api/platform/providers/{name}', {
      path: { name: 'minimax' },
      body: updateProviderBody,
    })
    expect(catalogRequest).toHaveBeenNthCalledWith(3, 'DELETE /api/platform/providers/{name}', {
      path: { name: 'minimax' },
      query: { expectedVersion: '8' },
    })
    expect(catalogRequest).toHaveBeenNthCalledWith(
      4,
      'POST /api/platform/models',
      { body: modelBody },
      { jsonParser: parseJsonPreservingPathEqualsNumbers },
    )
    expect(catalogRequest).toHaveBeenNthCalledWith(
      5,
      'PUT /api/platform/models/{*name}',
      { path: { name: 'vendor/MiniMax M2.7' }, body: updateModelBody },
      { jsonParser: parseJsonPreservingPathEqualsNumbers },
    )
    expect(catalogRequest).toHaveBeenNthCalledWith(6, 'DELETE /api/platform/models/{*name}', {
      path: { name: 'vendor/MiniMax M2.7' },
      query: { expectedVersion: '10' },
    })
    expect(catalogRequest).toHaveBeenNthCalledWith(7, 'POST /api/platform/agents', { body: agentBody })
    expect(catalogRequest).toHaveBeenNthCalledWith(8, 'PUT /api/platform/agents/{name}', {
      path: { name: 'default-assistant' },
      body: updateAgentBody,
    })
    expect(catalogRequest).toHaveBeenNthCalledWith(9, 'DELETE /api/platform/agents/{name}', {
      path: { name: 'default-assistant' },
      query: { expectedVersion: '11' },
    })
  })

  it('opts model catalog into the lossless JSON parser', async () => {

    await agentService.listModelCatalog('speed', 'cursor-1', 10)
    await agentService.refreshModelCatalog()

    expect(catalogRequest).toHaveBeenNthCalledWith(
      1,
      'GET /api/platform/model-catalog',
      { query: { query: 'speed', cursor: 'cursor-1', limit: 10 } },
      { jsonParser: parseJsonPreservingPathEqualsNumbers },
    )
    expect(catalogRequest).toHaveBeenNthCalledWith(
      2,
      'POST /api/platform/model-catalog/refresh',
      {},
      { jsonParser: parseJsonPreservingPathEqualsNumbers },
    )
  })

})
