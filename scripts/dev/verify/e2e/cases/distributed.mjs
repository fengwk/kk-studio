import { randomUUID } from 'node:crypto'
import { assert, assertDecimalVersion, assertExactFields, captureHttpError, expectHttpError, sleep } from '../lib/http.mjs'
import {
  assertDistributedContext,
  ENV_A_ID,
  ENV_B_ID,
  ENV_C_ID,
  queryDistributedEnvironmentOwners,
} from '../lib/distributed.mjs'
import { createDurationTimer } from '../lib/time.mjs'
import {
  branchSettingsOf,
  chatOwner,
  createChat,
  createNewSession,
  userMessageCommand,
} from '../lib/harness.mjs'
import { baseModelConfig, daemonConfigFixture, environmentCreateBody } from '../lib/fixtures.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

const ENV_C_NAME = 'distributed-c'

/**
 * DB 网络断开期间的 DB 权威读必须失败：具体 4xx/5xx 由应用错误映射决定，
 * 关键是绝不回退本机 websocket 返回 200。
 */
async function expectDatabaseFailClosed(fn) {
  const error = await captureHttpError(fn)
  assert(
    error.status >= 400,
    `DB loss must fail closed instead of serving the local websocket, got ${error.status}`,
  )
  return error
}

async function waitForEnvironmentReady(callNode, node, envId, maxAttempts = 60) {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const res = await callNode(node, 'GET', `/api/platform/environments/${encodeURIComponent(envId)}`)
      const environment = res.json
      if (environment?.ready === true && environment?.status === 'READY') {
        return environment
      }
    } catch {
      // transient startup / reconnecting
    }
    await sleep(500)
  }
  throw new Error(`environment '${envId}' on node '${node}' did not reach READY within timeout`)
}

/**
 * 确定性地将 distributed-c 路由放到指定目标节点（缺省为 'a'）。
 * 若当前持有者为对端节点，通过优雅停止（drain）对端节点立即主动释放路由租约，
 * 引导 daemon-ingress 将 daemon-c 重连至目标节点，随后重启对端节点；
 * 全程利用产品自身优雅排空，避免异节点准入等待约 60s 路由宽限期。
 */
export async function ensureDistributedCRouteOwner(ctx, targetNode = 'a') {
  const otherNode = targetNode === 'a' ? 'b' : 'a'
  try {
    ctx.runDistributedCommand('start-app-a')
  } catch {}
  try {
    ctx.runDistributedCommand('start-app-b')
  } catch {}
  await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID, 60)
  await waitForEnvironmentReady(ctx.callNode, 'b', ENV_B_ID, 60)

  for (let attempt = 0; attempt < 30; attempt++) {
    const { envCOwner, statusC } = queryDistributedEnvironmentOwners()
    if (envCOwner === targetNode && statusC === 'READY') {
      return targetNode
    }
    if (envCOwner === otherNode && statusC === 'READY') {
      ctx.runDistributedCommand(`stop-app-${otherNode}`)
      await waitForEnvironmentReady(ctx.callNode, targetNode, ENV_C_ID, 60)
      ctx.runDistributedCommand(`start-app-${otherNode}`)
      await waitForEnvironmentReady(
        ctx.callNode,
        otherNode,
        otherNode === 'a' ? ENV_A_ID : ENV_B_ID,
        60,
      )
      return targetNode
    }
    await sleep(500)
  }
  return targetNode
}

registerCase({
  id: 'distributed.shared_state',
  level: 'L5',
  title: '双节点共享状态：跨节点 Environment 创建/编辑/token 轮换 CAS/删除与 404',
  requires: ['distributed'],
  docs: '在 node A 创建临时 Environment，在 node B 读取并轮换 registrationToken（CAS 推进 version、name 恒定），回 node A 用陈旧 version 轮换得 409、用新 version 轮换取到不同 token；node B 以最新 version PUT 完整 note+daemonConfig，node A 读取到新 version/note/config；向同一 PUT path（不使用 query expectedVersion）携带非法 name 得 400 validation 且不写入；再跨节点删除并在两节点验证 404；finally 尽力清理，禁止将一次性 registrationToken 写入 artifact/log',
  async run(ctx) {
    assertDistributedContext(ctx)

    const envName = `dist-shared-${randomUUID().slice(0, 8)}`
    let createdId = null
    let latestVersion = null

    try {
      // 1. 在 node A 创建临时 Environment
      const createRes = await ctx.callNode(
        'a',
        'POST',
        '/api/platform/environments',
        environmentCreateBody(envName),
      )
      const created = createRes.json
      assert(created?.id, 'expected created environment id')
      assert(created.name === envName, `expected name ${envName}, got ${created.name}`)
      assert(
        typeof created.registrationToken === 'string' && created.registrationToken.length > 0,
        'expected non-empty registrationToken in create response',
      )
      assertDecimalVersion(created.version, 'created.version')
      createdId = created.id
      latestVersion = created.version

      // 2. 在 node B 读取并验证一致性（注意 registrationToken 不暴露）
      const getBRes = await ctx.callNode('b', 'GET', `/api/platform/environments/${encodeURIComponent(createdId)}`)
      const fromB = getBRes.json
      assert(fromB.id === createdId, 'node B get: id mismatch')
      assert(fromB.name === envName, 'node B get: name mismatch')
      assert(fromB.version === latestVersion, 'node B get: version mismatch')
      assert(!fromB.registrationToken, 'GET must never expose registrationToken')

      // 3. 在 node B 轮换 registrationToken：唯一的可变状态，CAS 推进 version，name 作为身份恒定不变
      const rotateOnBRes = await ctx.callNode(
        'b',
        'POST',
        `/api/platform/environments/${encodeURIComponent(createdId)}/rotate-token`,
        { expectedVersion: latestVersion },
      )
      const rotatedOnB = rotateOnBRes.json
      assert(rotatedOnB.name === envName, 'node B rotate: name must stay immutable')
      assert(rotatedOnB.version !== latestVersion, 'node B rotate: version must advance')
      assertDecimalVersion(rotatedOnB.version, 'rotatedOnB.version')
      assert(
        typeof rotatedOnB.registrationToken === 'string' && rotatedOnB.registrationToken.length > 0,
        'node B rotate: expected freshly generated registrationToken',
      )
      const tokenFromB = rotatedOnB.registrationToken
      latestVersion = rotatedOnB.version

      // 4. 回 node A 验证跨节点 CAS：陈旧 version 必须 409，新 version 必须取到与 node B 不同的新 token
      await expectHttpError(
        () =>
          ctx.callNode(
            'a',
            'POST',
            `/api/platform/environments/${encodeURIComponent(createdId)}/rotate-token`,
            { expectedVersion: created.version },
          ),
        { status: 409, code: 'version_conflict' },
      )
      const rotateOnARes = await ctx.callNode(
        'a',
        'POST',
        `/api/platform/environments/${encodeURIComponent(createdId)}/rotate-token`,
        { expectedVersion: latestVersion },
      )
      const rotatedOnA = rotateOnARes.json
      assert(rotatedOnA.name === envName, 'node A rotate: name must stay immutable')
      assert(
        rotatedOnA.registrationToken !== tokenFromB,
        'node A rotate: token must differ from the one issued by node B',
      )
      assert(rotatedOnA.version !== latestVersion, 'node A rotate: version must advance again')
      assertDecimalVersion(rotatedOnA.version, 'rotatedOnA.version')
      latestVersion = rotatedOnA.version

      // 4b. node B 用最新 version PUT 完整 note+daemonConfig，node A 必须看到共享的新配置
      const updatedNote = 'distributed edited note'
      const updatedConfig = daemonConfigFixture({ proxyUrl: 'http://127.0.0.1:7891' })
      const updateOnBRes = await ctx.callNode(
        'b',
        'PUT',
        `/api/platform/environments/${encodeURIComponent(createdId)}`,
        { expectedVersion: latestVersion, note: updatedNote, daemonConfig: updatedConfig },
      )
      const updatedOnB = updateOnBRes.json
      assert(updatedOnB.name === envName, 'node B update: name must stay immutable')
      assert(updatedOnB.note === updatedNote, 'node B update: note mismatch')
      assert(updatedOnB.version !== latestVersion, 'node B update: version must advance')
      assertDecimalVersion(updatedOnB.version, 'updatedOnB.version')
      latestVersion = updatedOnB.version

      const getAfterUpdateRes = await ctx.callNode(
        'a',
        'GET',
        `/api/platform/environments/${encodeURIComponent(createdId)}`,
      )
      const afterUpdateOnA = getAfterUpdateRes.json
      assert(afterUpdateOnA.name === envName, 'node A after update: name must stay immutable')
      assert(afterUpdateOnA.version === latestVersion, 'node A after update: version mismatch')
      assert(afterUpdateOnA.note === updatedNote, 'node A after update: note mismatch')
      assertExactFields(
        afterUpdateOnA.daemonConfig,
        ['studioUrl', 'bashExecutable', 'lsp', 'proxyUrl'],
        'node A after update daemonConfig',
      )
      assert(
        afterUpdateOnA.daemonConfig.studioUrl === updatedConfig.studioUrl,
        'node A after update: studioUrl mismatch',
      )
      assert(
        afterUpdateOnA.daemonConfig.bashExecutable === updatedConfig.bashExecutable,
        'node A after update: bashExecutable mismatch',
      )
      assert(
        afterUpdateOnA.daemonConfig.proxyUrl === updatedConfig.proxyUrl,
        'node A after update: proxyUrl mismatch',
      )
      assert(
        JSON.stringify(afterUpdateOnA.daemonConfig.lsp) === JSON.stringify(updatedConfig.lsp),
        'node A after update: lsp mismatch',
      )

      // 4c. PUT 不接受 name：同一 PUT path 携带真实更新 body + 非法 name 必须 400 validation 且不写入
      await expectHttpError(
        () =>
          ctx.callNode(
            'a',
            'PUT',
            `/api/platform/environments/${encodeURIComponent(createdId)}`,
            {
              expectedVersion: latestVersion,
              note: updatedNote,
              daemonConfig: updatedConfig,
              name: `${envName}-renamed`,
            },
          ),
        { status: 400, code: 'validation' },
      )
      const getARes = await ctx.callNode('a', 'GET', `/api/platform/environments/${encodeURIComponent(createdId)}`)
      const fromA = getARes.json
      assert(fromA.name === envName, 'node A verify: name must stay immutable')
      assert(fromA.version === latestVersion, 'node A verify: version mismatch')
      assert(fromA.note === updatedNote, 'node A verify: note must stay unchanged after rejected rename')
      assert(
        fromA.daemonConfig?.proxyUrl === updatedConfig.proxyUrl,
        'node A verify: config must stay unchanged after rejected rename',
      )

      // 5. 跨节点删除（在 node B 发起）并在两节点验证 404
      await ctx.callNode(
        'b',
        'DELETE',
        `/api/platform/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(latestVersion)}`,
      )
      await expectHttpError(
        () => ctx.callNode('a', 'GET', `/api/platform/environments/${encodeURIComponent(createdId)}`),
        { status: 404, code: 'not_found' },
      )
      await expectHttpError(
        () => ctx.callNode('b', 'GET', `/api/platform/environments/${encodeURIComponent(createdId)}`),
        { status: 404, code: 'not_found' },
      )

      // 6. 记录安全 artifact（禁止写入一次性 registrationToken）
      ctx.writeArtifact(
        'shared-state-crud.json',
        JSON.stringify(
          {
            id: createdId,
            immutableName: envName,
            rotated: true,
            finalVersion: latestVersion,
            deleted: true,
          },
          null,
          2,
        ),
      )
      createdId = null
    } finally {
      // 尽力清理临时 Environment
      if (createdId) {
        try {
          const probe = await ctx.callNode('a', 'GET', `/api/platform/environments/${encodeURIComponent(createdId)}`)
          const environment = probe.json
          if (environment?.version) {
            await ctx.callNode(
              'a',
              'DELETE',
              `/api/platform/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(environment.version)}`,
            )
          }
        } catch {
          // best-effort cleanup
        }
      }
    }
  },
})

registerCase({
  id: 'distributed.lease_routing',
  level: 'L5',
  title: '双节点租约路由：DB 权威投影跨节点一致',
  requires: ['distributed'],
  docs: '固定环境 distributed-a=33333333-3333-3333-3333-333333333333 连 app-a，distributed-b=44444444-4444-4444-4444-444444444444 连 app-b；两个 App 都投影两者 READY，且同一 Environment 在两个节点上的 id/name/version/status/ready/userName/homeDirectory/capabilities 逐项一致（路由事实来自 DB，不依赖本机 websocket），coding capability 为 fs.read@3',
  async run(ctx) {
    assertDistributedContext(ctx)

    // 1. 两个 App 都必须投影两者 READY
    const environmentAOnA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID)
    const environmentBOnA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_B_ID)
    const environmentAOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_A_ID)
    const environmentBOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_B_ID)

    assert(
      environmentAOnA.ready && environmentBOnA.ready && environmentAOnB.ready && environmentBOnB.ready,
      'all four projections must be ready',
    )

    // 2. 跨节点投影必须逐字段一致：DB 是唯一路由权威，node B 读取 env A 时不得回退本机连接。
    for (const [label, fromA, fromB] of [
      ['env A', environmentAOnA, environmentAOnB],
      ['env B', environmentBOnA, environmentBOnB],
    ]) {
      assert(fromA.id === fromB.id, `${label}: id must match across nodes`)
      assert(fromA.name === fromB.name, `${label}: name must match across nodes`)
      assert(fromA.version === fromB.version, `${label}: version must match across nodes`)
      assert(
        fromA.status === fromB.status && fromA.ready === fromB.ready,
        `${label}: status/ready must match across nodes`,
      )
      // 宿主事实来自连接行保留的最近一次 READY payload：DB 是唯一路由权威，本机 socket 不参与。
      assert(
        typeof fromA.homeDirectory === 'string' && fromA.homeDirectory.length > 0,
        `${label}: homeDirectory must be projected cross-node`,
      )
      assert(
        fromA.homeDirectory === fromB.homeDirectory && fromA.userName === fromB.userName,
        `${label}: host facts must come from DB routing, not the caller's local socket`,
      )
      // live 投影的原子能力来自权威能力目录。
      const capabilityKey = (environment) =>
        (environment.capabilities || [])
          .map((capability) => capability.id)
          .sort()
          .join(',')
      assert(
        capabilityKey(fromA) === capabilityKey(fromB),
        `${label}: capability projection must match across nodes: ${capabilityKey(fromA)} != ${capabilityKey(fromB)}`,
      )
      assert(
        (fromA.capabilities || []).some(
          (capability) => capability.id === 'fs.read',
        ),
        `${label}: coding capabilities must advertise fs.read, got ${capabilityKey(fromA)}`,
      )
    }

    ctx.writeArtifact(
      'lease-routing.json',
      JSON.stringify(
        {
          envA: { onA: environmentAOnA, onB: environmentAOnB },
          envB: { onA: environmentBOnA, onB: environmentBOnB },
        },
        null,
        2,
      ),
    )
  },
})

registerCase({
  id: 'distributed.db_loss_fail_closed',
  level: 'L5',
  title: 'DB loss fail-closed 与有界 recovery',
  requires: ['distributed'],
  docs: '先验证 node A 投影 env A READY；通过受限白名单调 disconnect-db-a 断开 node A DB 网络；断网后 node A 的 DB 权威读路径必须失败（HTTP 4xx/5xx，而不是回退本机 websocket 返回 200）；finally 无条件 reconnect-db-a，随后有界轮询 node A 与 node B 都恢复 READY 且宿主事实（userName/homeDirectory）与断网前一致（证明 DB-authoritative route 恢复）',
  async run(ctx) {
    assertDistributedContext(ctx)
    assert(
      typeof ctx.runDistributedCommand === 'function',
      'ctx.runDistributedCommand is required for fault injection',
    )

    // 1. 先证明 node A 与 node B 都能读取 env A 的 DB 权威投影
    const preEnvironmentA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID)
    const preEnvironmentAOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_A_ID)
    assert(
      preEnvironmentA.homeDirectory === preEnvironmentAOnB.homeDirectory,
      `pre-check: cross-node host fact mismatch: ${JSON.stringify({
        onA: preEnvironmentA.homeDirectory,
        onB: preEnvironmentAOnB.homeDirectory,
      })}`,
    )

    // 2. 故障注入：断开 node A DB 网络，验证 node A 不会用本机 websocket 绕过 DB 返回成功
    let dbLossError = null
    let dbLossDurationMs = null
    try {
      ctx.runDistributedCommand('disconnect-db-a')

      const elapsed = createDurationTimer()
      dbLossError = await expectDatabaseFailClosed(() =>
        ctx.callNode(
          'a',
          'GET',
          `/api/platform/environments/${encodeURIComponent(ENV_A_ID)}`,
          undefined,
          10_000,
        ),
      )
      dbLossDurationMs = elapsed()
    } finally {
      // 3. finally 无条件恢复 node A DB 网络连接
      ctx.runDistributedCommand('reconnect-db-a')
    }

    // 4. 有界轮询 node A 与 node B 都恢复 DB 权威 READY 投影
    const recoveredOnA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID, 60)
    const recoveredOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_A_ID, 60)
    assert(recoveredOnA?.status === 'READY', 'node A failed to recover READY status')
    assert(
      recoveredOnA.homeDirectory === preEnvironmentA.homeDirectory,
      `node A homeDirectory must recover to the same DB-authoritative value: ${
        recoveredOnA.homeDirectory
      } != ${preEnvironmentA.homeDirectory}`,
    )
    assert(
      recoveredOnB.homeDirectory === preEnvironmentA.homeDirectory,
      `node B must observe the recovered route: ${recoveredOnB.homeDirectory} != ${preEnvironmentA.homeDirectory}`,
    )

    ctx.writeArtifact(
      'recovery-summary.json',
      JSON.stringify(
        {
          dbLossStatus: dbLossError?.status ?? null,
          dbLossDurationMs,
          recoveredOnA: { status: recoveredOnA.status, homeDirectory: recoveredOnA.homeDirectory },
          recoveredOnB: { status: recoveredOnB.status, homeDirectory: recoveredOnB.homeDirectory },
        },
        null,
        2,
      ),
    )
  },
})

async function getThreadSnapshotFromNode(callNode, node, threadId) {
  const { json } = await callNode(node, 'GET', `/api/harness/threads/${encodeURIComponent(threadId)}`)
  return json
}

async function setupDistributedHarness(ctx, suffix) {
  const providerRes = await ctx.callNode('a', 'POST', '/api/platform/providers', {
    name: `e2e-dist-prov-${suffix}`,
    description: 'Distributed test provider',
    providerType: 'openai',
    baseUrl: 'http://stub.local:8080/v1',
    credential: { operation: 'SET', value: `e2e-dist-${suffix}` },
    modelCallTimeoutMillis: '60000',
    modelCallIdleTimeoutMillis: '30000',
  })
  const provider = providerRes.json
  assert(provider?.name, 'provider creation failed')

  const modelRes = await ctx.callNode('a', 'POST', '/api/platform/models', {
    type: 'MODEL',
    providerName: provider.name,
    name: `e2e-dist-model-${suffix}`,
    modelId: 'gpt-4o',
    description: 'Distributed test model',
    config: baseModelConfig({
      limit: { context: 4096, output: 512 },
      abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
      variants: [{ id: 'default', promptCacheRetention: 'SHORT' }],
    }),
  })
  const model = modelRes.json
  assert(model?.name, 'model creation failed')

  const agentRes = await ctx.callNode('a', 'POST', '/api/platform/agents', {
    name: `e2e-dist-agent-${suffix}`,
    description: 'Distributed test agent',
    systemPrompt: 'You are an agent with bash tools.',
    execution: { driver: 'builtin', settings: { modelName: model.name, variant: 'default' } },
    config: {
      tools: ['bash'],
      skills: [],
      subagents: [],
    },
  })
  const agent = agentRes.json
  assert(agent?.name, 'agent creation failed')

  return { provider, model, agent }
}

async function cleanupDistributedHarness(ctx, { agent, model, provider }) {
  if (agent?.name) {
    try {
      await ctx.callNode(
        'a',
        'DELETE',
        `/api/platform/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
      )
    } catch {
      try {
        await ctx.callNode(
          'b',
          'DELETE',
          `/api/platform/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
        )
      } catch {
        // best effort
      }
    }
  }
  if (model?.name) {
    try {
      await ctx.callNode(
        'a',
        'DELETE',
        `/api/platform/models/${encodeURIComponent(model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
      )
    } catch {
      try {
        await ctx.callNode(
          'b',
          'DELETE',
          `/api/platform/models/${encodeURIComponent(model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
        )
      } catch {
        // best effort
      }
    }
  }
  if (provider?.name) {
    try {
      await ctx.callNode(
        'a',
        'DELETE',
        `/api/platform/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
      )
    } catch {
      try {
        await ctx.callNode(
          'b',
          'DELETE',
          `/api/platform/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
        )
      } catch {
        // best effort
      }
    }
  }
}

function extractToolExecution(snapshot) {
  if (snapshot?.toolInvocations?.length > 0) {
    const t = snapshot.toolInvocations[0]
    return {
      id: t.id,
      status: t.status,
      result: t.result,
      attempt: t.attempt,
    }
  }
  for (let i = (snapshot?.entries?.length || 0) - 1; i >= 0; i--) {
    const entry = snapshot.entries[i]
    if (entry.entryType !== 'MESSAGE') continue
    let payload = null
    try {
      payload = entry.payload
    } catch {
      continue
    }
    if (payload?.message?.role === 'TOOL') {
      const toolResult = payload.message.contents?.find((c) => c.type === 'tool_result')
      const metadata = payload.toolResultMetadata
      const text = toolResult?.contents?.map((c) => c.text || '').join('') || ''
      return {
        id: metadata?.invocationId || toolResult?.toolCallId,
        status: metadata?.status || (toolResult?.error ? 'FAILED' : 'SUCCEEDED'),
        result: text,
        attempt: metadata?.attempt ?? 1,
      }
    }
  }
  return null
}

async function restoreDistributedStack(ctx) {
  try {
    ctx.runDistributedCommand('start-app-a')
  } catch {
    // ignore if already started
  }
  try {
    ctx.runDistributedCommand('start-app-b')
  } catch {
    // ignore if already started
  }
  await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID, 120)
  await waitForEnvironmentReady(ctx.callNode, 'b', ENV_B_ID, 120)
  // 确定性地将 distributed-c 路由恢复到 node a
  await ensureDistributedCRouteOwner(ctx, 'a')
}

registerCase({
  id: 'distributed.graceful_drain_handover',
  level: 'L5',
  title: '双节点滚动发布优雅排空与工具挂接接管',
  requires: ['distributed'],
  docs: '在 distributed-c 上运行 sleep 20 && echo handover-done；工具进入 RUNNING 后优雅停止持有节点（SIGTERM）；验证对端节点重新挂接并在 attempt=1 下交付 SUCCEEDED，全程无 UNKNOWN，unconfirmedExecutions 最终为空',
  async run(ctx) {
    assertDistributedContext(ctx)
    await ensureDistributedCRouteOwner(ctx, 'a')
    const { envCOwner } = queryDistributedEnvironmentOwners()
    const ownerNode = envCOwner || 'a'
    const takeoverNode = ownerNode === 'a' ? 'b' : 'a'

    const suffix = randomUUID().slice(0, 8)
    let harness = null
    let runError = null
    try {
      harness = await setupDistributedHarness(ctx, suffix)
      const chat = await createChat(ctx, {
        title: `e2e-dist-chat-${suffix}`,
        agentName: harness.agent.name,
        yoloEnabled: true,
      })

      const threadId = randomUUID()
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: randomUUID(),
        threadId,
        rootSettings: branchSettingsOf(
          harness.agent,
          {
            modelName: harness.model.name,
            variant: 'default',
          },
          ENV_C_NAME,
        ),
        yoloEnabled: true,
        commands: [
          userMessageCommand(
            'Please run sleep 20 && echo handover-done on the environment.',
            randomUUID(),
          ),
        ],
      })

      let toolId = null
      for (let i = 0; i < 60; i++) {
        const snap = await getThreadSnapshotFromNode(ctx.callNode, ownerNode, threadId)
        const tool = snap.toolInvocations?.[0]
        if (tool && tool.status === 'RUNNING') {
          toolId = tool.id
          break
        }
        await sleep(500)
      }
      assert(toolId, 'tool invocation did not enter RUNNING')

      // 优雅停止当前持有节点：触发排空、release claim 并让出路由租约
      ctx.runDistributedCommand(`stop-app-${ownerNode}`)

      let finalSnapshot = null
      let finalTool = null
      let sawUnknown = false
      for (let i = 0; i < 90; i++) {
        try {
          const snap = await getThreadSnapshotFromNode(ctx.callNode, takeoverNode, threadId)
          const tool = extractToolExecution(snap)
          if (tool?.status === 'UNKNOWN' || snap.toolInvocations?.some((t) => t.status === 'UNKNOWN')) {
            sawUnknown = true
          }
          if (snap.thread.status === 'IDLE' && tool?.status === 'SUCCEEDED') {
            finalSnapshot = snap
            finalTool = tool
            break
          }
        } catch {
          // 对端节点可能正在处理重连与状态同步
        }
        await sleep(1000)
      }

      assert(finalSnapshot, `thread did not reach quiescence on takeover node ${takeoverNode}`)
      assert(!sawUnknown, 'must not observe UNKNOWN during graceful drain handover')

      assert(finalTool?.status === 'SUCCEEDED', `expected tool status SUCCEEDED, got ${finalTool?.status}`)
      assert(
        JSON.stringify(finalTool?.result).includes('handover-done'),
        `expected result to contain handover-done: ${JSON.stringify(finalTool?.result)}`,
      )
      assert(finalTool?.attempt === 1, `expected attempt 1, got ${finalTool?.attempt}`)
      assert(
        Array.isArray(finalSnapshot.unconfirmedExecutions) && finalSnapshot.unconfirmedExecutions.length === 0,
        `expected unconfirmedExecutions to be empty, got: ${JSON.stringify(finalSnapshot.unconfirmedExecutions)}`,
      )

      ctx.writeArtifact(
        'graceful-drain-handover.json',
        JSON.stringify(
          {
            toolInvocation: finalTool,
            unconfirmedExecutions: finalSnapshot.unconfirmedExecutions,
            threadStatus: finalSnapshot.thread.status,
          },
          null,
          2,
        ),
      )
    } catch (err) {
      runError = err
      console.error('[graceful_drain_handover] Inner error:', err)
      throw err
    } finally {
      try {
        await restoreDistributedStack(ctx)
      } catch (restoreErr) {
        if (runError) {
          console.error(
            '[graceful_drain_handover] Suppressing restore error because of inner error:',
            restoreErr,
          )
          throw runError
        }
        throw restoreErr
      } finally {
        if (harness) await cleanupDistributedHarness(ctx, harness)
      }
    }
  },
})

registerCase({
  id: 'distributed.crash_failover',
  level: 'L5',
  title: '双节点崩溃故障接管与租约超时恢复',
  requires: ['distributed'],
  docs: '在 distributed-c 上运行 sleep 20 && echo handover-done；工具进入 RUNNING 后强杀持有节点（SIGKILL）；验证租约超时后对端节点接管并交付 SUCCEEDED，被杀节点的 TOOL 退出事实保持未确认留在 unconfirmedExecutions 中',
  async run(ctx) {
    assertDistributedContext(ctx)
    await ensureDistributedCRouteOwner(ctx, 'a')
    const { envCOwner } = queryDistributedEnvironmentOwners()
    const ownerNode = envCOwner || 'a'
    const takeoverNode = ownerNode === 'a' ? 'b' : 'a'

    const suffix = randomUUID().slice(0, 8)
    let harness = null
    let runError = null
    try {
      harness = await setupDistributedHarness(ctx, suffix)
      const chat = await createChat(ctx, {
        title: `e2e-crash-chat-${suffix}`,
        agentName: harness.agent.name,
        yoloEnabled: true,
      })

      const threadId = randomUUID()
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: randomUUID(),
        threadId,
        rootSettings: branchSettingsOf(
          harness.agent,
          {
            modelName: harness.model.name,
            variant: 'default',
          },
          ENV_C_NAME,
        ),
        yoloEnabled: true,
        commands: [
          userMessageCommand(
            'Please run sleep 20 && echo handover-done on the environment.',
            randomUUID(),
          ),
        ],
      })

      let toolId = null
      for (let i = 0; i < 60; i++) {
        const snap = await getThreadSnapshotFromNode(ctx.callNode, ownerNode, threadId)
        const tool = snap.toolInvocations?.[0]
        if (tool && tool.status === 'RUNNING') {
          toolId = tool.id
          break
        }
        await sleep(500)
      }
      assert(toolId, 'tool invocation did not enter RUNNING')

      // 强杀当前持有节点（不排空，不释放租约与 Work）
      // 崩溃接管用例受 Environment 路由断线宽限期约束（被杀节点的路由未主动释放，
      // 必须等待约 60s 的 lease/grace 超时结束后，接管节点才能在 DB 层面成功抢占 acquire 租约并接管）；
      // 加上工具自身 sleep 20s 及调度接管收尾，等待上限设置为 150s 以留足安全裕度，
      // 绝不为了加速而修改产品租约与宽限期配置。
      ctx.runDistributedCommand(`kill-app-${ownerNode}`)

      let finalSnapshot = null
      let finalTool = null
      for (let i = 0; i < 150; i++) {
        try {
          const snap = await getThreadSnapshotFromNode(ctx.callNode, takeoverNode, threadId)
          const tool = extractToolExecution(snap)
          // 接管节点在租约过期后重新挂接，并在同一 attempt=1 下交付 SUCCEEDED 结果
          if (tool && tool.status === 'SUCCEEDED' && JSON.stringify(tool.result).includes('handover-done')) {
            finalSnapshot = snap
            finalTool = tool
            break
          }
        } catch {
          // 等待租约过期与接管
        }
        await sleep(1000)
      }

      assert(finalSnapshot, `node ${takeoverNode} did not deliver tool result after crash failover`)

      assert(finalTool?.status === 'SUCCEEDED', `expected tool status SUCCEEDED, got ${finalTool?.status}`)
      assert(
        JSON.stringify(finalTool?.result).includes('handover-done'),
        `expected result to contain handover-done: ${JSON.stringify(finalTool?.result)}`,
      )

      // 【退出事实模型预期（Exit Fact Model Invariant）】：
      // 崩溃节点被 SIGKILL 终止，无真实退出凭据，故被杀节点的 TOOL 代次严格保持 UNCONFIRMED；
      // 根据 ThreadActivityEvaluator 不变式，未确认代次存在时 Thread 运行时状态不虚构收敛，在显式 abandon 前严格保持 RUNNING。
      const unconfirmed = finalSnapshot.unconfirmedExecutions || []
      assert(
        unconfirmed.some((e) => e.kind === 'TOOL' && e.attempt === 1),
        `expected unconfirmedExecutions to retain killed node TOOL fact: ${JSON.stringify(unconfirmed)}`,
      )
      assert(
        finalSnapshot.thread.status === 'RUNNING',
        `expected thread to remain RUNNING while unconfirmed facts exist, got ${finalSnapshot.thread.status}`,
      )

      ctx.writeArtifact(
        'crash-failover.json',
        JSON.stringify(
          {
            toolInvocation: finalTool,
            unconfirmedExecutions: finalSnapshot.unconfirmedExecutions,
            threadStatus: finalSnapshot.thread.status,
          },
          null,
          2,
        ),
      )
    } catch (err) {
      runError = err
      console.error('[crash_failover] Inner error:', err)
      throw err
    } finally {
      try {
        await restoreDistributedStack(ctx)
      } catch (restoreErr) {
        if (runError) {
          console.error(
            '[crash_failover] Suppressing restore error because of inner error:',
            restoreErr,
          )
          throw runError
        }
        throw restoreErr
      } finally {
        if (harness) await cleanupDistributedHarness(ctx, harness)
      }
    }
  },
})

registerCase({
  id: 'distributed.daemon_restart_unknown',
  level: 'L5',
  title: '双节点远端 Daemon 重启崩溃恢复收敛为 UNKNOWN',
  requires: ['distributed'],
  docs: '在 distributed-c 上运行 sleep 20 && echo handover-done；工具进入 RUNNING 后重启 daemon-c（journal 丢失）；验证远端调用因 RECOVER_NOT_FOUND 收敛为 UNKNOWN，且 Thread 保持 UNCONFIRMED 与 RUNNING',
  async run(ctx) {
    assertDistributedContext(ctx)
    await ensureDistributedCRouteOwner(ctx, 'a')
    const { envCOwner } = queryDistributedEnvironmentOwners()
    const ownerNode = envCOwner || 'a'

    const suffix = randomUUID().slice(0, 8)
    let harness = null
    let runError = null
    try {
      harness = await setupDistributedHarness(ctx, suffix)
      const chat = await createChat(ctx, {
        title: `e2e-restart-chat-${suffix}`,
        agentName: harness.agent.name,
        yoloEnabled: true,
      })

      const threadId = randomUUID()
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: randomUUID(),
        threadId,
        rootSettings: branchSettingsOf(
          harness.agent,
          {
            modelName: harness.model.name,
            variant: 'default',
          },
          ENV_C_NAME,
        ),
        yoloEnabled: true,
        commands: [
          userMessageCommand(
            'Please run sleep 20 && echo handover-done on the environment.',
            randomUUID(),
          ),
        ],
      })

      let toolId = null
      for (let i = 0; i < 60; i++) {
        const snap = await getThreadSnapshotFromNode(ctx.callNode, ownerNode, threadId)
        const tool = snap.toolInvocations?.[0]
        if (tool && tool.status === 'RUNNING') {
          toolId = tool.id
          break
        }
        await sleep(500)
      }
      assert(toolId, 'tool invocation did not enter RUNNING')

      // 重启 daemon-c，journal 丢失
      // 等待上限与准入时间依据：
      // 1. 同节点重连（daemon-ingress 首选 app-a，即原 owner 节点）：
      //    原节点命中 environment_connection.owner_node_id = excluded.owner_node_id，
      //    无需等待 60s 路由断开宽限期，在 1 秒内即可完成准入（日志核实：18:28:01 被 app-a 准入）。
      // 2. 跨节点重连（若发生网络漂移由 app-b 接管）：
      //    非原 owner 节点必须等待 60s 宽限期（lease_until 到期）才能抢占路由并准入。
      // 因此轮询上限设置为 90s，足以覆盖同节点快速恢复（~1-5s）及跨节点宽限期超时（~60-70s）。
      ctx.runDistributedCommand('restart-daemon-c')

      let finalSnapshot = null
      let finalTool = null
      for (let i = 0; i < 90; i++) {
        try {
          const snap = await getThreadSnapshotFromNode(ctx.callNode, ownerNode, threadId)
          const tool = extractToolExecution(snap)
          if (tool?.status === 'UNKNOWN' && (snap.toolInvocations?.length || 0) === 0) {
            finalSnapshot = snap
            finalTool = tool
            break
          }
        } catch {
          // waiting for recovery failure
        }
        await sleep(1000)
      }

      assert(finalSnapshot, 'thread did not converge with UNKNOWN tool status after daemon restart')
      assert(finalTool?.status === 'UNKNOWN', `expected tool status UNKNOWN, got ${finalTool?.status}`)

      // 【退出事实模型预期（Exit Fact Model Invariant）】：
      // Daemon 重启导致内存 journal 丢失，RECOVER 遇 NOT_FOUND，根据 X5 设计 §5 规则，远端代次严格保持 UNCONFIRMED（证据不足）。
      // 依据 ThreadActivityEvaluator 不变式，未确认代次存在时 Thread 运行时状态不虚构收敛，在显式 abandon 前严格保持 RUNNING。
      const unconfirmed = finalSnapshot.unconfirmedExecutions || []
      assert(
        unconfirmed.some((e) => e.kind === 'REMOTE'),
        `expected unconfirmedExecutions to retain restarted daemon REMOTE fact: ${JSON.stringify(unconfirmed)}`,
      )
      assert(
        finalSnapshot.thread.status === 'RUNNING',
        `expected thread to remain RUNNING while unconfirmed facts exist, got ${finalSnapshot.thread.status}`,
      )

      ctx.writeArtifact(
        'daemon-restart-unknown.json',
        JSON.stringify(
          {
            toolInvocation: finalTool,
            unconfirmedExecutions: finalSnapshot.unconfirmedExecutions,
            threadStatus: finalSnapshot.thread.status,
          },
          null,
          2,
        ),
      )
    } catch (err) {
      runError = err
      console.error('[daemon_restart_unknown] Inner error:', err)
      throw err
    } finally {
      try {
        await restoreDistributedStack(ctx)
      } catch (restoreErr) {
        if (runError) {
          console.error(
            '[daemon_restart_unknown] Suppressing restore error because of inner error:',
            restoreErr,
          )
          throw runError
        }
        throw restoreErr
      } finally {
        if (harness) await cleanupDistributedHarness(ctx, harness)
      }
    }
  },
})
