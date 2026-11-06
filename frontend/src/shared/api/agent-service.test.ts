import { describe, expect, it, vi } from 'vitest'
import { createAgentService, type CatalogRequester } from '@/shared/api/agent-service'
import { createMockHttpClient } from '@/test-support/mock-http-client'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionUpdateDTO,
  ModelCreateDTO,
  ModelUpdateDTO,
  ProviderCreateDTO,
  ProviderUpdateDTO,
} from '@/shared/api/generated/contracts'

function createClient() {
  return createMockHttpClient()
}

function createCatalogRequest() {
  return vi.fn(async () => ({})) as unknown as CatalogRequester
}

describe('agentService', () => {
  it('maps global resource lists to catalog routes', async () => {
    const client = createClient()
    const catalogRequest = createCatalogRequest()
    const service = createAgentService(client, catalogRequest)

    await service.listProviders()
    await service.listModels()
    await service.listAgents(4, 40)
    await service.listTools()

    expect(catalogRequest).toHaveBeenNthCalledWith(1, 'GET /api/platform/providers', {})
    expect(catalogRequest).toHaveBeenNthCalledWith(
      2,
      'GET /api/platform/models',
      {},
      { jsonParser: parseJsonPreservingPathEqualsNumbers },
    )
    expect(client.get).toHaveBeenCalledWith('/agents', { params: { pageNumber: 4, pageSize: 40 } })
    expect(catalogRequest).toHaveBeenNthCalledWith(3, 'GET /api/platform/tools', {})
  })

  it('keeps the standard page defaults for the Agent list', async () => {
    const client = createClient()
    const service = createAgentService(client, createCatalogRequest())

    await service.listAgents()

    expect(client.get).toHaveBeenNthCalledWith(1, '/agents', {
      params: { pageNumber: 1, pageSize: 50 },
    })
  })

  /**
   * 测试意图：验证 Provider、Model 和 Agent 的增删改端点，
   * 特别验证 Model 的 PUT/DELETE 端点使用尾段通配身份。
   */
  it('maps provider, model and agent mutations to name-based CRUD endpoints', async () => {
    const client = createClient()
    const catalogRequest = createCatalogRequest()
    const service = createAgentService(client, catalogRequest)
    const providerBody: ProviderCreateDTO = {
      name: 'minimax',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.chat/v1',
      credential: 'key',
      modelCallTimeoutMillis: 1800000,
      modelCallIdleTimeoutMillis: 120000,
    }
    const modelBody: ModelCreateDTO = {
      providerName: 'minimax',
      type: 'MODEL',
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
        variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
      },
    }
    const agentBody: AgentDefinitionCreateDTO = {
      name: 'default-assistant',
      description: null,
      systemPrompt: null,
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: 'default' } },
      config: { tools: [], skills: [], subagents: [] },
    }
    const updateProviderBody: ProviderUpdateDTO = {
      credential: { operation: 'KEEP' },
      description: 'updated',
      modelCallTimeoutMillis: 1800000,
      modelCallIdleTimeoutMillis: 120000,
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
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
          overrides: [],
          multipliers: [],
        },
        defaultVariant: 'fast',
        variants: [{ id: 'fast', promptCacheRetention: 'NONE' }],
      },
    }
    const updateAgentBody: AgentDefinitionUpdateDTO = {
      description: 'updated',
      expectedVersion: '9',
      systemPrompt: null,
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: 'default' } },
      config: { tools: [], skills: [], subagents: [] },
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
    expect(client.post).toHaveBeenNthCalledWith(1, '/agents', agentBody)
    expect(client.put).toHaveBeenNthCalledWith(1, '/agents/default-assistant', updateAgentBody)
    expect(client.delete).toHaveBeenNthCalledWith(1, '/agents/default-assistant', {
      params: { expectedVersion: '11' },
    })
  })

  it('opts model catalog into the lossless JSON parser', async () => {
    const catalogRequest = createCatalogRequest()
    const service = createAgentService(createClient(), catalogRequest)

    await service.listModelDefaults('speed', 'cursor-1', 10)
    await service.refreshModelDefaults()

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

  /**
   * 测试意图：验证 Skill 全局目录与 Skill Package 的增删改查端点。
   */
  it('maps skill package endpoints correctly', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listSkillPackages()
    expect(client.get).toHaveBeenNthCalledWith(1, '/skill-packages')

    await service.getSkillPackage('core')
    expect(client.get).toHaveBeenNthCalledWith(2, '/skill-packages/core')

    await service.listSkillPackageEnvironments('core')
    expect(client.get).toHaveBeenNthCalledWith(3, '/skill-packages/core/environments')

    await service.listSkillPackageFiles('core')
    expect(client.get).toHaveBeenNthCalledWith(4, '/skill-packages/core/files')

    const createPkg = {
      packageName: 'core',
      description: 'Core skills',
      repositoryUrl: 'https://example.com/repo.git',
      branch: 'main',
    }
    await service.createSkillPackage(createPkg)
    expect(client.post).toHaveBeenNthCalledWith(1, '/skill-packages/git', createPkg, { timeout: 0 })

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
    expect(client.post).toHaveBeenNthCalledWith(2, '/skill-packages/git/core/check', checkPkg, { timeout: 0 })

    const publishPkg = {
      expectedVersion: '3',
      targetCommit: '0123456789012345678901234567890123456789',
    }
    await service.publishSkillPackage('core', publishPkg)
    expect(client.post).toHaveBeenNthCalledWith(3, '/skill-packages/git/core/publish', publishPkg, { timeout: 0 })

    await service.getSkillPackageArchiveUrl('core')
    expect(client.post).toHaveBeenNthCalledWith(4, '/skill-packages/core/archive-url')

    const previewUploadPkg = {
      uploadId: 'upload-123',
      packageName: 'uploaded-core',
      packageVersion: '1.0.0',
    }
    await service.previewSkillPackageUpload(previewUploadPkg)
    expect(client.post).toHaveBeenNthCalledWith(5, '/skill-packages/uploads/preview', previewUploadPkg, { timeout: 0 })

    const publishUploadPkg = {
      stagingUploadId: 'stage-123',
      packageName: 'uploaded-core',
      packageVersion: '1.0.0',
    }
    await service.publishSkillPackageUpload(publishUploadPkg)
    expect(client.post).toHaveBeenNthCalledWith(6, '/skill-packages/uploads/publish', publishUploadPkg, { timeout: 0 })

    await service.cancelSkillPackageUpload('stage-123')
    expect(client.post).toHaveBeenNthCalledWith(7, '/skill-packages/uploads/stage-123/cancel')

    await service.resyncEnvironmentSkills('env-1')
    expect(client.post).toHaveBeenNthCalledWith(8, '/environments/env-1/skills/resync')

    await service.deleteSkillPackage('core', '4')
    expect(client.delete).toHaveBeenNthCalledWith(1, '/skill-packages/core', {
      params: { expectedVersion: '4' },
    })
  })
})
