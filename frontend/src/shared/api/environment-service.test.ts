import { describe, expect, it, vi } from 'vitest'
import { createEnvironmentService } from '@/shared/api/environment-service'

describe('environmentService', () => {
  /**
   * 测试意图：验证 Environment 列表查询端点已迁移至 /harness/environments。
   */
  it('lists environments from GET /harness/environments', async () => {
    const client = {
      get: vi.fn(async () => []),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.listEnvironments()
    expect(client.get).toHaveBeenCalledWith('/harness/environments')
  })

  /**
   * 测试意图：验证通过 UUID 获取单个 Environment 详情端点使用 /harness/environments/{id}。
   */
  it('gets a single environment by UUID via GET /harness/environments/{id}', async () => {
    const client = {
      get: vi.fn(async () => ({ id: 'env-1', name: 'local' })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.getEnvironment('env-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1')
  })

  /**
   * 测试意图：验证创建 Environment 使用 POST /harness/environments，且返回包含 registrationToken。
   */
  it('creates an environment card via POST /harness/environments', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({ id: 'env-1', name: 'dev', registrationToken: 'tok-123' })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.createEnvironment({ name: 'dev' })
    expect(client.post).toHaveBeenCalledWith('/harness/environments', { name: 'dev' })
    expect(result.registrationToken).toBe('tok-123')
  })

  /**
   * 测试意图：验证按需读取当前 registration token 使用 GET /harness/environments/{id}/token，
   * 且该调用是普通只读 GET（不携带 expectedVersion、不触发轮换）。
   */
  it('reads the current registration token via GET /harness/environments/{id}/token', async () => {
    const client = {
      get: vi.fn(async () => ({ id: 'env-1', registrationToken: 'tok-current', version: '3' })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.getRegistrationToken('env-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1/token')
    expect(client.post).not.toHaveBeenCalled()
    expect(result.registrationToken).toBe('tok-current')
    expect(result.version).toBe('3')
  })

  /**
   * 测试意图：验证轮换 registration token 端点使用 POST /harness/environments/{id}/registration-token，
   * 且 expectedVersion 放在 POST body { expectedVersion } 中。
   */
  it('rotates registration token via POST /harness/environments/{id}/registration-token with body {expectedVersion}', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({ id: 'env-1', registrationToken: 'tok-new', version: '2' })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.rotateToken('env-1', '1')
    expect(client.post).toHaveBeenCalledWith(
      '/harness/environments/env-1/registration-token',
      { expectedVersion: '1' },
    )
    expect(result.registrationToken).toBe('tok-new')
  })

  /**
   * 测试意图：验证删除 Environment 端点使用 DELETE /harness/environments/{id}?expectedVersion=。
   */
  it('deletes environment via DELETE /harness/environments/{id}?expectedVersion=', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => undefined),
    }
    const service = createEnvironmentService(client)
    await service.deleteEnvironment('env-1', '3')
    expect(client.delete).toHaveBeenCalledWith(
      '/harness/environments/env-1?expectedVersion=3',
    )
  })

  // --- Skill sources ---

  /**
   * 测试意图：验证查询 Skill 来源列表端点使用 GET /harness/environments/{environmentId}/skill-sources，
   * 且 environmentId 被正确 URI 编码。
   */
  it('lists skill sources via GET /harness/environments/{environmentId}/skill-sources', async () => {
    const mockSources = [{ sourceId: 'src-1', type: 'git' as const }]
    const client = {
      get: vi.fn(async () => mockSources),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.listSkillSources('env 1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env%201/skill-sources')
    expect(result).toBe(mockSources)
  })

  /**
   * 测试意图：验证查询单个 Skill 来源详情使用 GET /harness/environments/{environmentId}/skill-sources/{sourceId}，
   * 且路径参数均经过 URI 编码。
   */
  it('gets a skill source via GET /harness/environments/{environmentId}/skill-sources/{sourceId}', async () => {
    const mockSource = { sourceId: 'src-1', type: 'path' as const, path: '/opt/skills' }
    const client = {
      get: vi.fn(async () => mockSource),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.getSkillSource('env-1', 'src-1')
    expect(client.get).toHaveBeenCalledWith(
      '/harness/environments/env-1/skill-sources/src-1',
    )
    expect(result).toBe(mockSource)
  })

  /**
   * 测试意图：验证创建 Skill 来源端点使用 POST /harness/environments/{environmentId}/skill-sources，
   * 请求体完整透传并返回新建的来源对象。
   */
  it('creates a skill source via POST /harness/environments/{environmentId}/skill-sources', async () => {
    const createData = { type: 'git' as const, gitUrl: 'https://github.com/example/skills' }
    const mockCreated = { sourceId: 'src-new', ...createData }
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => mockCreated),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.createSkillSource('env-1', createData)
    expect(client.post).toHaveBeenCalledWith(
      '/harness/environments/env-1/skill-sources',
      createData,
    )
    expect(result).toBe(mockCreated)
  })

  /**
   * 测试意图：验证更新 Skill 来源端点使用 PUT /harness/environments/{environmentId}/skill-sources/{sourceId}，
   * 包含 CAS 期望版本且请求体完整透传。
   */
  it('updates a skill source via PUT /harness/environments/{environmentId}/skill-sources/{sourceId}', async () => {
    const updateData = {
      type: 'git' as const,
      gitRef: 'v2.0',
      expectedVersion: '3',
    }
    const mockUpdated = { sourceId: 'src-1', ...updateData, version: '4' }
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => mockUpdated),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.updateSkillSource('env-1', 'src-1', updateData)
    expect(client.put).toHaveBeenCalledWith(
      '/harness/environments/env-1/skill-sources/src-1',
      updateData,
    )
    expect(result).toBe(mockUpdated)
  })

  /**
   * 测试意图：验证删除 Skill 来源端点使用 DELETE /harness/environments/{environmentId}/skill-sources/{sourceId}?expectedVersion=，
   * 路径与查询参数均经过 URI 编码。
   */
  it('deletes a skill source via DELETE /harness/environments/{environmentId}/skill-sources/{sourceId}?expectedVersion=', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => undefined),
    }
    const service = createEnvironmentService(client)
    await service.deleteSkillSource('env-1', 'src 1', 'ver 2')
    expect(client.delete).toHaveBeenCalledWith(
      '/harness/environments/env-1/skill-sources/src%201?expectedVersion=ver%202',
    )
  })

  // --- Persistent inventory ---

  /**
   * 测试意图：验证获取持久 Inventory 头信息端点使用 GET /harness/environments/{environmentId}/inventory。
   */
  it('gets environment inventory via GET /harness/environments/{environmentId}/inventory', async () => {
    const mockInventory = { environmentId: 'env-1', sourceSetVersion: '2' }
    const client = {
      get: vi.fn(async () => mockInventory),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.getInventory('env-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1/inventory')
    expect(result).toBe(mockInventory)
  })

  /**
   * 测试意图：验证获取 Inventory Skills 列表端点使用 GET /harness/environments/{environmentId}/inventory/skills，
   * 默认 usableOnly 显式传递 false。
   */
  it('lists inventory skills with default usableOnly=false query parameter', async () => {
    const mockSkills = [{ sourceId: 'src-1', name: 'bash' }]
    const client = {
      get: vi.fn(async () => mockSkills),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.listInventorySkills('env-1')
    expect(client.get).toHaveBeenCalledWith(
      '/harness/environments/env-1/inventory/skills?usableOnly=false',
    )
    expect(result).toBe(mockSkills)
  })

  /**
   * 测试意图：验证当指定 usableOnly=true 时，URL 查询参数显式呈现 ?usableOnly=true。
   */
  it('lists inventory skills with explicit usableOnly=true query parameter', async () => {
    const client = {
      get: vi.fn(async () => []),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.listInventorySkills('env-1', true)
    expect(client.get).toHaveBeenCalledWith(
      '/harness/environments/env-1/inventory/skills?usableOnly=true',
    )
  })

  // --- Durable async management operations ---

  /**
   * 测试意图：验证请求来源 Refresh 操作端点使用 POST /harness/environments/{envId}/skill-sources/{srcId}/refresh，
   * 且 timeoutMillis 参数必填并完整传递到请求体。
   */
  it('requests skill source refresh operation via POST /harness/environments/{id}/skill-sources/{sourceId}/refresh', async () => {
    const opData = { timeoutMillis: 30000 }
    const mockOp = { id: 'op-1', operationType: 'SKILL_REFRESH' as const, status: 'PENDING' as const }
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => mockOp),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.requestSkillSourceRefresh('env-1', 'src-1', opData)
    expect(client.post).toHaveBeenCalledWith(
      '/harness/environments/env-1/skill-sources/src-1/refresh',
      opData,
    )
    expect(result).toBe(mockOp)
  })

  /**
   * 测试意图：验证请求来源 Install 操作端点使用 POST /harness/environments/{envId}/skill-sources/{srcId}/install，
   * 请求体完整传递并返回操作对象。
   */
  it('requests skill source install operation via POST /harness/environments/{id}/skill-sources/{sourceId}/install', async () => {
    const opData = { timeoutMillis: 60000 }
    const mockOp = { id: 'op-2', operationType: 'SKILL_INSTALL' as const, status: 'PENDING' as const }
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => mockOp),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.requestSkillSourceInstall('env-1', 'src-1', opData)
    expect(client.post).toHaveBeenCalledWith(
      '/harness/environments/env-1/skill-sources/src-1/install',
      opData,
    )
    expect(result).toBe(mockOp)
  })

  /**
   * 测试意图：验证请求来源 Update 管理操作端点使用 POST /harness/environments/{envId}/skill-sources/{srcId}/update，
   * 与配置更新 PUT 区分开，请求体完整传递并返回操作对象。
   */
  it('requests skill source update management operation via POST /harness/environments/{id}/skill-sources/{sourceId}/update', async () => {
    const opData = { timeoutMillis: 45000 }
    const mockOp = { id: 'op-3', operationType: 'SKILL_UPDATE' as const, status: 'PENDING' as const }
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => mockOp),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.requestSkillSourceUpdate('env-1', 'src-1', opData)
    expect(client.post).toHaveBeenCalledWith(
      '/harness/environments/env-1/skill-sources/src-1/update',
      opData,
    )
    expect(result).toBe(mockOp)
  })

  // --- Operations listing & cancel ---

  /**
   * 测试意图：验证列出操作端点使用 GET /harness/environments/{environmentId}/operations?limit=50（默认 limit 50 呈现在 URL）。
   */
  it('lists operations with default limit=50 represented in URL query', async () => {
    const mockOps = [{ id: 'op-1', status: 'RUNNING' as const }]
    const client = {
      get: vi.fn(async () => mockOps),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.listOperations('env-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1/operations?limit=50')
    expect(result).toBe(mockOps)
  })

  /**
   * 测试意图：验证当指定自定义 limit 时，URL 查询参数精确反映传入的整数值。
   */
  it('lists operations with explicit custom limit represented in URL query', async () => {
    const client = {
      get: vi.fn(async () => []),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.listOperations('env-1', 20)
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1/operations?limit=20')
  })

  /**
   * 测试意图：验证查询单个操作详情端点使用 GET /harness/environments/{environmentId}/operations/{operationId}。
   */
  it('gets a single operation by UUID via GET /harness/environments/{environmentId}/operations/{operationId}', async () => {
    const mockOp = { id: 'op-1', status: 'SUCCEEDED' as const }
    const client = {
      get: vi.fn(async () => mockOp),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.getOperation('env-1', 'op-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1/operations/op-1')
    expect(result).toBe(mockOp)
  })

  /**
   * 测试意图：验证取消操作端点使用 POST /harness/environments/{environmentId}/operations/{operationId}/cancel，
   * 不携带请求体并返回取消后的操作状态。
   */
  it('cancels an operation via POST /harness/environments/{environmentId}/operations/{operationId}/cancel', async () => {
    const mockCancelled = { id: 'op-1', status: 'CANCELLED' as const }
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => mockCancelled),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.cancelOperation('env-1', 'op-1')
    expect(client.post).toHaveBeenCalledWith(
      '/harness/environments/env-1/operations/op-1/cancel',
    )
    expect(result).toBe(mockCancelled)
  })
})
