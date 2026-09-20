import { describe, expect, it, vi } from 'vitest'
import { createAgentService } from '@/shared/api/agent-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('agentService', () => {
  it('maps global resource lists to catalog paths', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders(2, 20)
    await service.listModels(3, 30)
    await service.listAgents(4, 40)
    await service.listTools()

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/catalog/providers', { params: { pageNumber: 2, pageSize: 20 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/ai/catalog/models', { params: { pageNumber: 3, pageSize: 30 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/ai/catalog/agents', { params: { pageNumber: 4, pageSize: 40 } })
    expect(client.get).toHaveBeenNthCalledWith(4, '/ai/catalog/tools')
  })

  it('uses the standard page defaults for global resource lists', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders()
    await service.listModels()
    await service.listAgents()

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/catalog/providers', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/ai/catalog/models', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/ai/catalog/agents', { params: { pageNumber: 1, pageSize: 50 } })
  })

  /**
   * 测试意图：验证 Provider、Model 和 Agent 的增删改端点，
   * 特别验证 Model 的 PUT/DELETE 端点规范化为 /ai/catalog/models/{providerName}/{modelName}。
   */
  it('maps provider, model and agent mutations to name-based CRUD endpoints', async () => {
    const client = createClient()
    const service = createAgentService(client)
    const providerBody = {
      name: 'minimax',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.chat/v1',
      credential: 'key',
      modelCallTimeoutMillis: 1800000,
      modelCallIdleTimeoutMillis: 120000,
    }
    const modelBody = {
      providerName: 'minimax',
      name: 'MiniMax-M2.7',
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          currency: 'USD',
          pricingTier: 'default',
          serviceTier: 'default',
          serviceTierMultiplier: 1,
          version: 'v1',
          inputPerMillionTokens: 0,
          outputPerMillionTokens: 0,
          cacheReadPerMillionTokens: 0,
          cacheWritePerMillionTokens: 0,
          cacheWriteLongPerMillionTokens: 0,
          reasoningPerMillionTokens: 0,
        },
        defaultVariant: 'default',
        variants: [{ id: 'default' }],
      },
    }
    const agentBody = {
      name: 'default-assistant',
      description: null,
      systemPrompt: null,
      model: 'minimax/MiniMax-M2.7',
      variant: 'default',
      config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
    }
    const updateProviderBody = { description: 'updated', expectedVersion: '7' }
    const updateModelBody = {
      name: 'MiniMax-M2.7-renamed',
      modelId: 'wire-minimax',
      expectedVersion: '8',
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          currency: 'USD',
          pricingTier: 'default',
          serviceTier: 'default',
          serviceTierMultiplier: 1,
          version: 'v1',
          inputPerMillionTokens: 0,
          outputPerMillionTokens: 0,
          cacheReadPerMillionTokens: 0,
          cacheWritePerMillionTokens: 0,
          cacheWriteLongPerMillionTokens: 0,
          reasoningPerMillionTokens: 0,
        },
        defaultVariant: 'fast',
        variants: [{ id: 'fast' }],
      },
    }
    const updateAgentBody = { description: 'updated', expectedVersion: '9' }

    await service.createProvider(providerBody)
    await service.updateProvider('minimax', updateProviderBody)
    await service.deleteProvider('minimax', '8')
    await service.createModel(modelBody)
    await service.updateModel('minimax', 'vendor/MiniMax M2.7', updateModelBody)
    await service.deleteModel('minimax', 'vendor/MiniMax M2.7', '10')
    await service.createAgent(agentBody)
    await service.updateAgent('default-assistant', updateAgentBody)
    await service.deleteAgent('default-assistant', '11')

    expect(client.post).toHaveBeenNthCalledWith(1, '/ai/catalog/providers', providerBody)
    expect(client.put).toHaveBeenNthCalledWith(1, '/ai/catalog/providers/minimax', updateProviderBody)
    expect(client.delete).toHaveBeenNthCalledWith(1, '/ai/catalog/providers/minimax', { params: { expectedVersion: '8' } })
    expect(client.post).toHaveBeenNthCalledWith(2, '/ai/catalog/models', modelBody)
    expect(client.put).toHaveBeenNthCalledWith(2, '/ai/catalog/models/minimax/vendor/MiniMax%20M2.7', updateModelBody)
    expect(client.delete).toHaveBeenNthCalledWith(2, '/ai/catalog/models/minimax/vendor/MiniMax%20M2.7', { params: { expectedVersion: '10' } })
    expect(client.post).toHaveBeenNthCalledWith(3, '/ai/catalog/agents', agentBody)
    expect(client.put).toHaveBeenNthCalledWith(3, '/ai/catalog/agents/default-assistant', updateAgentBody)
    expect(client.delete).toHaveBeenNthCalledWith(3, '/ai/catalog/agents/default-assistant', { params: { expectedVersion: '11' } })
  })

  /**
   * 测试意图：验证 Skill 全局目录与 Skill Package 的增删改查端点。
   */
  it('maps skill package endpoints correctly', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listSkillPackages()
    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/catalog/skill-packages')

    const createPkg = {
      packageName: 'core',
      description: 'Core skills',
      repositoryUrl: 'https://example.com/repo.git',
      branch: 'main',
    }
    await service.createSkillPackage(createPkg)
    expect(client.post).toHaveBeenNthCalledWith(1, '/ai/catalog/skill-packages', createPkg)

    const editPkg = {
      expectedVersion: '1',
      description: 'Updated core skills',
      branch: 'main',
    }
    await service.editSkillPackage('core', editPkg)
    expect(client.put).toHaveBeenNthCalledWith(1, '/ai/catalog/skill-packages/core', editPkg)

    const checkPkg = {
      expectedVersion: '2',
    }
    await service.checkSkillPackage('core', checkPkg)
    expect(client.post).toHaveBeenNthCalledWith(2, '/ai/catalog/skill-packages/core/check', checkPkg)

    const publishPkg = {
      expectedVersion: '3',
      targetCommit: '0123456789012345678901234567890123456789',
    }
    await service.publishSkillPackage('core', publishPkg)
    expect(client.post).toHaveBeenNthCalledWith(3, '/ai/catalog/skill-packages/core/update', publishPkg)

    await service.deleteSkillPackage('core', '4')
    expect(client.delete).toHaveBeenNthCalledWith(1, '/ai/catalog/skill-packages/core', {
      params: { expectedVersion: '4' },
    })
  })
})
