import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAgentService } from '@/shared/api/agent-service'
import { createMockHttpClient } from '@/test-support/mock-http-client'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
import type {
  AgentCreateDTO,
  AgentUpdateDTO,
  AgentModelCreateDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderUpdateDTO,
} from '@/shared/api/generated/contracts'

afterEach(() => {
  vi.unstubAllGlobals()
})

function createClient() {
  return createMockHttpClient()
}

describe('agentService', () => {
  it('maps provider, model and tool lists to catalog paths', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders(2, 20)
    await service.listModels(3, 30)
    await service.listTools()

    expect(client.get).toHaveBeenNthCalledWith(1, '/providers', { params: { pageNumber: 2, pageSize: 20 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/models', {
      params: { pageNumber: 3, pageSize: 30 },
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
    expect(client.get).toHaveBeenNthCalledWith(3, '/tools')
  })

  it('uses the standard page defaults for global resource lists', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders()
    await service.listModels()

    expect(client.get).toHaveBeenNthCalledWith(1, '/providers', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/models', {
      params: { pageNumber: 1, pageSize: 50 },
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
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
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'DELETE') {
        return new Response(null, { status: 204 })
      }
      return new Response('{}', {
        status: init?.method === 'POST' ? 201 : 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

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
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/platform/agents', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify(agentBody),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/platform/agents/default-assistant', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify(updateAgentBody),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/platform/agents/default-assistant?expectedVersion=11',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('reads the unpaged platform agent list', async () => {
    const fetchMock = vi.fn(async () => new Response('[]', {
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)
    const service = createAgentService()

    expect(await service.listAgents()).toEqual([])
    expect(fetchMock).toHaveBeenCalledWith('/api/platform/agents', expect.objectContaining({ method: 'GET' }))
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
