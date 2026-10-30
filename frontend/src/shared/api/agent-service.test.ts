import { describe, expect, it } from 'vitest'
import { createAgentService } from '@/shared/api/agent-service'
import { createMockHttpClient } from '@/test-support/mock-http-client'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderUpdateDTO,
} from '@/shared/api/generated/contracts'

function createClient() {
  return createMockHttpClient()
}

describe('agentService', () => {
  it('maps global resource lists to catalog paths', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders(2, 20)
    await service.listModels(3, 30)
    await service.listAgents(4, 40)
    await service.listTools()

    expect(client.get).toHaveBeenNthCalledWith(1, '/providers', { params: { pageNumber: 2, pageSize: 20 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/models', {
      params: { pageNumber: 3, pageSize: 30 },
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
    expect(client.get).toHaveBeenNthCalledWith(3, '/agents', { params: { pageNumber: 4, pageSize: 40 } })
    expect(client.get).toHaveBeenNthCalledWith(4, '/tools')
  })

  it('uses the standard page defaults for global resource lists', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders()
    await service.listModels()
    await service.listAgents()

    expect(client.get).toHaveBeenNthCalledWith(1, '/providers', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/models', {
      params: { pageNumber: 1, pageSize: 50 },
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
    expect(client.get).toHaveBeenNthCalledWith(3, '/agents', { params: { pageNumber: 1, pageSize: 50 } })
  })

  /**
   * 测试意图：验证 Provider、Model 和 Agent 的增删改端点，
   * 特别验证 Model 的 PUT/DELETE 端点规范化为 /models/{modelName}。
   */
  it('maps provider, model and agent mutations to name-based CRUD endpoints', async () => {
    const client = createClient()
    const service = createAgentService(client)
    const providerBody: AgentProviderCreateDTO = {
      name: 'minimax',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.chat/v1',
      credential: 'key',
      modelCallTimeoutMillis: 1800000,
      modelCallIdleTimeoutMillis: 120000,
    }
    const modelBody: AgentModelCreateDTO = {
      providerName: 'minimax',
      kind: 'MODEL',
      name: 'MiniMax-M2.7',
      modelId: 'wire-minimax',
      description: null,
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
        defaultVariant: 'default',
        variants: [{ id: 'default' }],
      },
    }
    const agentBody: AgentDefinitionCreateDTO = {
      name: 'default-assistant',
      description: null,
      systemPrompt: null,
      model: 'MiniMax-M2.7',
      variant: 'default',
      config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
    }
    const updateProviderBody: AgentProviderUpdateDTO = {
      providerType: 'openai',
      description: 'updated',
      expectedVersion: '7',
    }
    const updateModelBody: AgentModelUpdateDTO = {
      kind: 'MODEL',
      name: 'MiniMax-M2.7-renamed',
      modelId: 'wire-minimax',
      description: null,
      expectedVersion: '8',
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
        defaultVariant: 'fast',
        variants: [{ id: 'fast' }],
      },
    }
    const updateAgentBody: AgentDefinitionUpdateDTO = {
      description: 'updated',
      expectedVersion: '9',
      systemPrompt: null,
      model: 'MiniMax-M2.7',
      variant: 'default',
      config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
    }

    await service.createProvider(providerBody)
    await service.updateProvider('minimax', updateProviderBody)
    await service.deleteProvider('minimax', '8')
    await service.createModel(modelBody)
    await service.updateModel('vendor/MiniMax M2.7', updateModelBody)
    await service.deleteModel('vendor/MiniMax M2.7', '10')
    await service.createAgent(agentBody)
    await service.updateAgent('default-assistant', updateAgentBody)
    await service.deleteAgent('default-assistant', '11')

    expect(client.post).toHaveBeenNthCalledWith(1, '/providers', providerBody)
    expect(client.put).toHaveBeenNthCalledWith(1, '/providers/minimax', updateProviderBody)
    expect(client.delete).toHaveBeenNthCalledWith(1, '/providers/minimax', { params: { expectedVersion: '8' } })
    expect(client.post).toHaveBeenNthCalledWith(2, '/models', modelBody, {
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
    expect(client.put).toHaveBeenNthCalledWith(2, '/models/vendor/MiniMax%20M2.7', updateModelBody, {
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
    expect(client.delete).toHaveBeenNthCalledWith(2, '/models/vendor/MiniMax%20M2.7', { params: { expectedVersion: '10' } })
    expect(client.post).toHaveBeenNthCalledWith(3, '/agents', agentBody)
    expect(client.put).toHaveBeenNthCalledWith(3, '/agents/default-assistant', updateAgentBody)
    expect(client.delete).toHaveBeenNthCalledWith(3, '/agents/default-assistant', { params: { expectedVersion: '11' } })
  })

  it('opts model defaults into the lossless JSON parser', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listModelDefaults('speed', 'cursor-1', 10)
    await service.refreshModelDefaults()

    expect(client.get).toHaveBeenCalledWith('/model-defaults', {
      params: { query: 'speed', cursor: 'cursor-1', pageSize: 10 },
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
    expect(client.post).toHaveBeenCalledWith('/model-defaults/refresh', {}, {
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
  })

  /**
   * 测试意图：验证 Skill 全局目录与 Skill Package 的增删改查端点。
   */
  it('maps skill package endpoints correctly', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listSkillPackages()
    expect(client.get).toHaveBeenNthCalledWith(1, '/skill-packages')

    const createPkg = {
      packageName: 'core',
      description: 'Core skills',
      repositoryUrl: 'https://example.com/repo.git',
      branch: 'main',
    }
    await service.createSkillPackage(createPkg)
    expect(client.post).toHaveBeenNthCalledWith(1, '/skill-packages', createPkg, { timeout: 0 })

    const editPkg = {
      expectedVersion: '1',
      description: 'Updated core skills',
      branch: 'main',
    }
    await service.editSkillPackage('core', editPkg)
    expect(client.put).toHaveBeenNthCalledWith(1, '/skill-packages/core', editPkg)

    const checkPkg = {
      expectedVersion: '2',
    }
    await service.checkSkillPackage('core', checkPkg)
    expect(client.post).toHaveBeenNthCalledWith(2, '/skill-packages/core/check', checkPkg, { timeout: 0 })

    const publishPkg = {
      expectedVersion: '3',
      targetCommit: '0123456789012345678901234567890123456789',
    }
    await service.publishSkillPackage('core', publishPkg)
    expect(client.post).toHaveBeenNthCalledWith(3, '/skill-packages/core/update', publishPkg, { timeout: 0 })

    await service.deleteSkillPackage('core', '4')
    expect(client.delete).toHaveBeenNthCalledWith(1, '/skill-packages/core', {
      params: { expectedVersion: '4' },
    })
  })
})
