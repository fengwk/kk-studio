import {
  assert,
  assertDecimalVersion,
  assertExactFields,
  cid,
  envelopeData,
  expectHttpError,
} from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

const TRANSIENT_SERVER_FIELDS = [
  'id',
  'name',
  'type',
  'environmentId',
  'enabled',
  'timeoutMillis',
  'discoveryStatus',
  'toolCount',
  'version',
]

const PERSISTED_SERVER_FIELDS = [
  ...TRANSIENT_SERVER_FIELDS,
  'createTime',
  'updateTime',
]

const PENDING_OPERATION_FIELDS = [
  'id',
  'environmentId',
  'resourceType',
  'resourceId',
  'operationType',
  'status',
  'resourceVersion',
  'parameterSummary',
  'deadlineAt',
  'createdAt',
  'updatedAt',
]

const CANCELLED_OPERATION_FIELDS = [...PENDING_OPERATION_FIELDS, 'finishedAt']

registerCase({
  id: 'mcp.json_local_operation_lifecycle',
  level: 'L1',
  title: 'MCP JSON-only Local 配置、安全投影与异步发现生命周期',
  docs: '创建临时 Environment 与 Local MCP Server；验证标准 DTO 不含配置、显式 config 端点 no-store、CAS 完整替换、旧 refresh 路径消失；discover 统一返回 202 PENDING 通用操作，随后查询、取消并安全删除。',
  async run(ctx) {
    let environmentId = null
    let serverId = null

    try {
      const environmentResponse = await ctx.call('POST', '/api/harness/environments', {
        name: `e2e-mcp-env-${cid().slice(0, 8)}`,
      })
      assert(
        environmentResponse.status === 201,
        `expected 201 for environment create, got ${environmentResponse.status}`,
      )
      const environment = envelopeData(environmentResponse.json)
      environmentId = environment.id
      assert(environmentId, 'created environment must have id')

      const serverName = `e2e_mcp_${cid().replaceAll('-', '').slice(0, 8)}`
      const localConfig = {
        type: 'local',
        environmentId,
        command: ['node', 'fake-local-mcp.mjs'],
        cwd: '/tmp/kk-studio-e2e-mcp',
        env: { KK_STUDIO_MCP_E2E: 'enabled' },
        enabled: true,
        timeoutMillis: 30_000,
      }
      const createResponse = await ctx.call('POST', '/api/ai/mcp-servers', {
        name: serverName,
        configJson: JSON.stringify(localConfig),
      })
      assert(
        createResponse.status === 201,
        `expected 201 for MCP server create, got ${createResponse.status}`,
      )
      const created = envelopeData(createResponse.json)
      assertExactFields(created, TRANSIENT_SERVER_FIELDS, 'created MCP server safe projection')
      serverId = created.id
      assert(serverId, 'created MCP server must have id')
      assert(created.name === serverName, `expected name ${serverName}, got ${created.name}`)
      assert(created.type === 'local', `expected local type, got ${created.type}`)
      assert(created.environmentId === environmentId, 'local MCP environment must match')
      assert(created.enabled === true, 'created MCP server must be enabled')
      assert(created.timeoutMillis === '30000', 'created MCP timeout must be 30000')
      assert(created.discoveryStatus === 'UNVERIFIED', 'new MCP server must be UNVERIFIED')
      assert(
        created.discoveredVersion === undefined,
        'new MCP server must omit a null discovered version',
      )
      assert(created.toolCount === 0, 'new MCP server must have no tools')
      assertDecimalVersion(created.version, 'created MCP server version')
      const safeProjectionJson = JSON.stringify(created)
      assert(!safeProjectionJson.includes(localConfig.command[1]), 'safe DTO must omit command')
      assert(!safeProjectionJson.includes(localConfig.cwd), 'safe DTO must omit cwd')
      assert(!safeProjectionJson.includes('configJson'), 'safe DTO must omit configJson')

      const detailResponse = await ctx.call(
        'GET',
        `/api/ai/mcp-servers/${encodeURIComponent(serverId)}`,
      )
      assert(detailResponse.status === 200, `expected 200 for MCP detail, got ${detailResponse.status}`)
      assertExactFields(
        envelopeData(detailResponse.json),
        PERSISTED_SERVER_FIELDS,
        'MCP server detail safe projection',
      )

      const configResponse = await ctx.call(
        'GET',
        `/api/ai/mcp-servers/${encodeURIComponent(serverId)}/config`,
      )
      assert(configResponse.status === 200, `expected 200 for MCP config, got ${configResponse.status}`)
      assert(
        configResponse.headers.get('cache-control') === 'no-store',
        `expected Cache-Control no-store, got ${configResponse.headers.get('cache-control')}`,
      )
      const configData = envelopeData(configResponse.json)
      assertExactFields(configData, ['id', 'name', 'version', 'configJson'], 'explicit MCP config')
      assert(configData.id === serverId, 'explicit config server id must match')
      assert(configData.name === serverName, 'explicit config server name must match')
      assert(configData.version === created.version, 'explicit config version must match')
      const echoedConfig = JSON.parse(configData.configJson)
      assertExactFields(
        echoedConfig,
        ['type', 'environmentId', 'enabled', 'timeoutMillis', 'command', 'cwd', 'env'],
        'canonical local MCP config',
      )
      assert(echoedConfig.cwd === localConfig.cwd, 'explicit config must preserve cwd')
      assert(
        JSON.stringify(echoedConfig.command) === JSON.stringify(localConfig.command),
        'explicit config must preserve argv',
      )

      await expectHttpError(
        () =>
          ctx.call(
            'POST',
            `/api/ai/mcp-servers/${encodeURIComponent(serverId)}/refresh`,
            { expectedVersion: created.version },
          ),
        { status: 404 },
      )

      const updatedConfig = { ...localConfig, timeoutMillis: 45_000 }
      const updateResponse = await ctx.call(
        'PUT',
        `/api/ai/mcp-servers/${encodeURIComponent(serverId)}`,
        {
          expectedVersion: created.version,
          configJson: JSON.stringify(updatedConfig),
        },
      )
      assert(updateResponse.status === 200, `expected 200 for MCP update, got ${updateResponse.status}`)
      const updated = envelopeData(updateResponse.json)
      assertExactFields(updated, PERSISTED_SERVER_FIELDS, 'updated MCP server safe projection')
      assert(
        BigInt(updated.version) === BigInt(created.version) + 1n,
        'MCP update must increment version exactly once',
      )
      assert(updated.timeoutMillis === '45000', 'MCP update must replace timeout')
      assert(updated.discoveryStatus === 'UNVERIFIED', 'updated MCP server must be UNVERIFIED')
      assert(
        updated.discoveredVersion === undefined,
        'updated MCP discovered version must be cleared',
      )

      const discoveryResponse = await ctx.call(
        'POST',
        `/api/ai/mcp-servers/${encodeURIComponent(serverId)}/discover`,
        { expectedVersion: updated.version },
      )
      assert(
        discoveryResponse.status === 202,
        `expected 202 for local MCP discovery, got ${discoveryResponse.status}`,
      )
      const discovery = envelopeData(discoveryResponse.json)
      assertExactFields(discovery, ['server', 'operation'], 'MCP discovery response')
      assertExactFields(discovery.server, PERSISTED_SERVER_FIELDS, 'MCP discovery safe server')
      assert(discovery.server.id === serverId, 'discovery server id must match')

      const operation = discovery.operation
      assertExactFields(operation, PENDING_OPERATION_FIELDS, 'MCP discovery operation')
      assert(operation.environmentId === environmentId, 'operation environment must match')
      assert(operation.resourceType === 'MCP_SERVER', 'operation resourceType must be MCP_SERVER')
      assert(operation.resourceId === serverId, 'operation resourceId must match server')
      assert(
        operation.operationType === 'MCP_SERVER_DISCOVER',
        'operation type must be MCP_SERVER_DISCOVER',
      )
      assert(operation.status === 'PENDING', `expected PENDING operation, got ${operation.status}`)
      assert(operation.resourceVersion === updated.version, 'operation version must be frozen')
      assertDecimalVersion(operation.resourceVersion, 'MCP operation resourceVersion')
      const operationJson = JSON.stringify(operation)
      assert(!operationJson.includes(localConfig.command[1]), 'operation DTO must omit command')
      assert(!operationJson.includes(localConfig.cwd), 'operation DTO must omit cwd')

      const operationResponse = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(environmentId)}/operations/${encodeURIComponent(operation.id)}`,
      )
      assert(
        operationResponse.status === 200,
        `expected 200 for MCP operation detail, got ${operationResponse.status}`,
      )
      const operationDetail = envelopeData(operationResponse.json)
      assertExactFields(operationDetail, PENDING_OPERATION_FIELDS, 'MCP operation detail')
      assert(operationDetail.status === 'PENDING', 'offline local MCP operation must remain PENDING')

      const cancelResponse = await ctx.call(
        'POST',
        `/api/harness/environments/${encodeURIComponent(environmentId)}/operations/${encodeURIComponent(operation.id)}/cancel`,
      )
      assert(
        cancelResponse.status === 200,
        `expected 200 for MCP operation cancel, got ${cancelResponse.status}`,
      )
      const cancelled = envelopeData(cancelResponse.json)
      assertExactFields(cancelled, CANCELLED_OPERATION_FIELDS, 'cancelled MCP operation')
      assert(cancelled.status === 'CANCELLED', `expected CANCELLED, got ${cancelled.status}`)

      const deleteResponse = await ctx.call(
        'DELETE',
        `/api/ai/mcp-servers/${encodeURIComponent(serverId)}?expectedVersion=${encodeURIComponent(updated.version)}`,
      )
      assert(
        deleteResponse.status === 204,
        `expected 204 for MCP server delete, got ${deleteResponse.status}`,
      )
      serverId = null
      await expectHttpError(
        () => ctx.call('GET', `/api/ai/mcp-servers/${encodeURIComponent(created.id)}`),
        { status: 404 },
      )
    } finally {
      if (serverId) {
        try {
          const currentResponse = await ctx.call(
            'GET',
            `/api/ai/mcp-servers/${encodeURIComponent(serverId)}`,
          )
          const current = envelopeData(currentResponse.json)
          await ctx.call(
            'DELETE',
            `/api/ai/mcp-servers/${encodeURIComponent(serverId)}?expectedVersion=${encodeURIComponent(current.version)}`,
          )
        } catch {}
      }
      if (environmentId) {
        try {
          const currentResponse = await ctx.call(
            'GET',
            `/api/harness/environments/${encodeURIComponent(environmentId)}`,
          )
          const current = envelopeData(currentResponse.json)
          await ctx.call(
            'DELETE',
            `/api/harness/environments/${encodeURIComponent(environmentId)}?expectedVersion=${encodeURIComponent(current.version)}`,
          )
        } catch {}
      }
    }
  },
})
