import { assert, envelopeData, expectHttpError, cid } from '../lib/http.mjs'
import {
  agentConfigMatrix,
  baseModelConfig,
  modelConfigMatrix,
  providerCreateBody,
} from '../lib/fixtures.mjs'
import { registerCase } from '../lib/registry.mjs'

/**
 * 为每个矩阵行注册独立 case，报告里逐条 PASS/FAIL。
 * 共享一个临时 provider，在第一个 case 创建，最后一个 case 删除。
 */
let sharedProviderId = null
let sharedModelIdForAgent = null

registerCase({
  id: 'matrix.model.setup_provider',
  level: 'L1',
  title: '配置矩阵前置：创建临时 Provider',
  docs: '供 config.model.* 复用，避免每行都建 provider',
  async run(ctx) {
    const { json } = await ctx.call('POST', '/api/providers', providerCreateBody(`mx-${cid().slice(0, 6)}`))
    sharedProviderId = String(envelopeData(json).id)
    ctx.vars.matrixProviderId = sharedProviderId
  },
})

for (const row of modelConfigMatrix()) {
  registerCase({
    id: `config.model.${row.id}`,
    level: 'L1',
    title: `Model config 矩阵：${row.title}`,
    docs: `期望 ${row.ok ? '成功创建' : `失败 ${row.expectStatus || 400}`}；${row.messageIncludes || ''}`,
    async run(ctx) {
      assert(sharedProviderId, 'matrix provider missing; ensure matrix.model.setup_provider runs first')
      const body = row.rawBody
        ? { providerId: sharedProviderId, name: `mx-${row.id}-${cid().slice(0, 4)}`, description: row.title }
        : {
            providerId: sharedProviderId,
            name: `mx-${row.id}-${cid().slice(0, 4)}`,
            description: row.title,
            config: row.build(),
          }
      if (row.ok) {
        const { status, json } = await ctx.call('POST', '/api/models', body)
        assert([200, 201].includes(status), `status ${status}`)
        const model = envelopeData(json)
        assert(model?.id, JSON.stringify(json))
        ctx.writeArtifact(`model-${row.id}.json`, JSON.stringify(model, null, 2))
        // cleanup model immediately to keep list small
        await ctx.call('DELETE', `/api/models/${model.id}`)
      } else {
        const err = await expectHttpError(() => ctx.call('POST', '/api/models', body), {
          status: row.expectStatus || 400,
          messageIncludes: row.messageIncludes,
        })
        ctx.writeArtifact(`model-${row.id}-error.txt`, String(err.body))
      }
    },
  })
}

registerCase({
  id: 'matrix.model.teardown_provider',
  level: 'L1',
  title: '配置矩阵收尾：删除临时 Provider',
  docs: '删除 matrix.model.setup_provider 创建的 provider',
  async run(ctx) {
    if (sharedProviderId) {
      await ctx.call('DELETE', `/api/providers/${sharedProviderId}`)
      sharedProviderId = null
    }
  },
})

registerCase({
  id: 'matrix.agent.setup_model',
  level: 'L1',
  title: 'Agent 配置矩阵前置：创建临时 Model',
  docs: '基于 seed provider 或新建 provider 建一个合法 model',
  async run(ctx) {
    const { json: pCreate } = await ctx.call(
      'POST',
      '/api/providers',
      providerCreateBody(`ag-${cid().slice(0, 6)}`),
    )
    const providerId = String(envelopeData(pCreate).id)
    ctx.vars.agentMatrixProviderId = providerId
    const { json: mCreate } = await ctx.call('POST', '/api/models', {
      providerId,
      name: `agent-matrix-model-${cid().slice(0, 4)}`,
      description: 'for agent matrix',
      config: baseModelConfig(),
    })
    sharedModelIdForAgent = String(envelopeData(mCreate).id)
    ctx.vars.agentMatrixModelId = sharedModelIdForAgent
  },
})

for (const row of agentConfigMatrix()) {
  registerCase({
    id: `config.agent.${row.id}`,
    level: 'L1',
    title: `Agent config 矩阵：${row.title}`,
    docs: `期望 ${row.ok ? '成功创建 agent' : `失败 ${row.expectStatus || 400}`}`,
    async run(ctx) {
      assert(sharedModelIdForAgent, 'agent matrix model missing')
      const body = {
        name: `ag-${row.id}-${cid().slice(0, 4)}`,
        description: row.title,
        systemPrompt: 'e2e',
        modelId: sharedModelIdForAgent,
        variant: 'default',
        config: row.build(),
      }
      if (row.ok) {
        const { status, json } = await ctx.call('POST', '/api/agents', body)
        assert([200, 201].includes(status), `status ${status} ${JSON.stringify(json)}`)
        const agent = envelopeData(json)
        assert(agent?.id, JSON.stringify(json))
        if (typeof row.assertCreated === 'function') row.assertCreated(agent)
        ctx.writeArtifact(`agent-${row.id}.json`, JSON.stringify(agent, null, 2))
        await ctx.call('DELETE', `/api/agents/${agent.id}`)
      } else {
        const err = await expectHttpError(() => ctx.call('POST', '/api/agents', body), {
          status: row.expectStatus || 400,
          messageIncludes: row.messageIncludes,
        })
        ctx.writeArtifact(`agent-${row.id}-error.txt`, String(err.body))
      }
    },
  })
}

registerCase({
  id: 'matrix.agent.teardown_model',
  level: 'L1',
  title: 'Agent 配置矩阵收尾：删除临时 Model/Provider',
  docs: '清理 matrix.agent.setup_model 资源；并清扫残留 e2e/ag- agent',
  async run(ctx) {
    // best-effort cleanup of leftover matrix agents that block model delete
    try {
      const { json } = await ctx.call('GET', '/api/agents?pageNumber=1&pageSize=100')
      const agents = (json?.data?.results || [])
      for (const agent of agents) {
        const name = String(agent.name || '')
        if (name.startsWith('ag-') || name.startsWith('e2e-agent-') || name.startsWith('t-[')) {
          try {
            await ctx.call('DELETE', `/api/agents/${agent.id}`)
          } catch {
            // ignore
          }
        }
      }
    } catch {
      // ignore list failures
    }
    if (sharedModelIdForAgent) {
      await ctx.call('DELETE', `/api/models/${sharedModelIdForAgent}`)
      sharedModelIdForAgent = null
    }
    if (ctx.vars.agentMatrixProviderId) {
      await ctx.call('DELETE', `/api/providers/${ctx.vars.agentMatrixProviderId}`)
      ctx.vars.agentMatrixProviderId = null
    }
  },
})
