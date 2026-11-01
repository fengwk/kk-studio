import { randomUUID } from 'node:crypto'
import { assert, assertDecimalVersion, envelopeData, expectHttpError, sleep } from '../lib/http.mjs'
import { assertDistributedContext } from '../lib/distributed.mjs'
import { registerCase } from '../lib/registry.mjs'
import { createDurationTimer } from '../lib/time.mjs'
import {
  branchSettingsOf,
  chatOwner,
  createChat,
  createNewSession,
  userMessageCommand,
} from '../lib/harness.mjs'
import { baseModelConfig } from '../lib/fixtures.mjs'

const ENV_A_ID = '33333333-3333-3333-3333-333333333333'
const ENV_B_ID = '44444444-4444-4444-4444-444444444444'
const ENV_C_ID = '55555555-5555-5555-5555-555555555555'
const ENV_C_NAME = 'distributed-c'

async function waitForEnvironmentReady(callNode, node, envId, maxAttempts = 30) {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const res = await callNode(node, 'GET', `/api/environments/${encodeURIComponent(envId)}`)
      const card = envelopeData(res.json)
      if (card?.ready === true && card?.status === 'READY') {
        return card
      }
    } catch {
      // transient startup / reconnecting
    }
    await sleep(500)
  }
  throw new Error(`environment '${envId}' on node '${node}' did not reach READY within timeout`)
}

registerCase({
  id: 'distributed.shared_state',
  level: 'L5',
  title: '双节点共享状态：跨节点 Environment 创建/token 轮换 CAS/删除与 404',
  requires: ['distributed'],
  docs: '在 node A 创建临时 Environment Card，在 node B 读取并轮换 registrationToken（CAS 推进 version、name 恒定），回 node A 用陈旧 version 轮换得 409、用新 version 轮换取到不同 token；再跨节点删除并在两节点验证 404；finally 尽力清理，禁止将一次性 registrationToken 写入 artifact/log',
  async run(ctx) {
    assertDistributedContext(ctx)

    const envName = `dist-shared-${randomUUID().slice(0, 8)}`
    let createdId = null
    let latestVersion = null

    try {
      // 1. 在 node A 创建临时 Environment Card
      const createRes = await ctx.callNode('a', 'POST', '/api/environments', { name: envName })
      const created = envelopeData(createRes.json)
      assert(created?.id, 'expected created environment id')
      assert(created.name === envName, `expected name ${envName}, got ${created.name}`)
      assert(
        typeof created.registrationToken === 'string' && created.registrationToken.length > 0,
        'expected non-empty registrationToken in create response',
      )
      assertDecimalVersion(created.version, 'created.version')
      createdId = created.id
      latestVersion = created.version

      // 2. 在 node B 读取并验证一致性（注意 registrationToken 不再暴露）
      const getBRes = await ctx.callNode('b', 'GET', `/api/environments/${encodeURIComponent(createdId)}`)
      const fromB = envelopeData(getBRes.json)
      assert(fromB.id === createdId, 'node B get: id mismatch')
      assert(fromB.name === envName, 'node B get: name mismatch')
      assert(fromB.version === latestVersion, 'node B get: version mismatch')
      assert(!fromB.registrationToken, 'GET must never expose registrationToken')

      // 3. 在 node B 轮换 registrationToken：唯一的可变状态，CAS 推进 version，name 作为身份恒定不变
      const rotateOnBRes = await ctx.callNode(
        'b',
        'POST',
        `/api/environments/${encodeURIComponent(createdId)}/registration-token`,
        { expectedVersion: latestVersion },
      )
      const rotatedOnB = envelopeData(rotateOnBRes.json)
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
            `/api/environments/${encodeURIComponent(createdId)}/registration-token`,
            { expectedVersion: created.version },
          ),
        { status: 409 },
      )
      const rotateOnARes = await ctx.callNode(
        'a',
        'POST',
        `/api/environments/${encodeURIComponent(createdId)}/registration-token`,
        { expectedVersion: latestVersion },
      )
      const rotatedOnA = envelopeData(rotateOnARes.json)
      assert(rotatedOnA.name === envName, 'node A rotate: name must stay immutable')
      assert(
        rotatedOnA.registrationToken !== tokenFromB,
        'node A rotate: token must differ from the one issued by node B',
      )
      assert(rotatedOnA.version !== latestVersion, 'node A rotate: version must advance again')
      assertDecimalVersion(rotatedOnA.version, 'rotatedOnA.version')
      latestVersion = rotatedOnA.version

      // 4c. 改名端点已彻底移除：Environment name 是不可变身份
      await expectHttpError(
        () =>
          ctx.callNode(
            'a',
            'PUT',
            `/api/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(latestVersion)}`,
            { name: `${envName}-renamed` },
          ),
        { status: 405 },
      )
      const getARes = await ctx.callNode('a', 'GET', `/api/environments/${encodeURIComponent(createdId)}`)
      const fromA = envelopeData(getARes.json)
      assert(fromA.name === envName, 'node A verify: name must stay immutable')
      assert(fromA.version === latestVersion, 'node A verify: version mismatch')

      // 5. 跨节点删除（在 node B 发起）并在两节点验证 404
      await ctx.callNode(
        'b',
        'DELETE',
        `/api/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(latestVersion)}`,
      )
      await expectHttpError(
        () => ctx.callNode('a', 'GET', `/api/environments/${encodeURIComponent(createdId)}`),
        { status: 404 },
      )
      await expectHttpError(
        () => ctx.callNode('b', 'GET', `/api/environments/${encodeURIComponent(createdId)}`),
        { status: 404 },
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
      // 尽力清理临时环境卡片
      if (createdId) {
        try {
          const probe = await ctx.callNode('a', 'GET', `/api/environments/${encodeURIComponent(createdId)}`)
          const card = envelopeData(probe.json)
          if (card?.version) {
            await ctx.callNode(
              'a',
              'DELETE',
              `/api/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(card.version)}`,
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
    const cardAOnA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID)
    const cardBOnA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_B_ID)
    const cardAOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_A_ID)
    const cardBOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_B_ID)

    assert(
      cardAOnA.ready && cardBOnA.ready && cardAOnB.ready && cardBOnB.ready,
      'all four projections must be ready',
    )

    // 2. 跨节点投影必须逐字段一致：DB 是唯一路由权威，node B 读取 env A 时不得回退本机连接。
    for (const [label, fromA, fromB] of [
      ['env A', cardAOnA, cardAOnB],
      ['env B', cardBOnA, cardBOnB],
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
      const capabilityKey = (card) =>
        (card.capabilities || [])
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
          envA: { onA: cardAOnA, onB: cardAOnB },
          envB: { onA: cardBOnA, onB: cardBOnB },
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
    const preCardA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID)
    const preCardAOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_A_ID)
    assert(
      preCardA.homeDirectory === preCardAOnB.homeDirectory,
      `pre-check: cross-node host fact mismatch: ${JSON.stringify({
        onA: preCardA.homeDirectory,
        onB: preCardAOnB.homeDirectory,
      })}`,
    )

    // 2. 故障注入：断开 node A DB 网络，验证 node A 不会用本机 websocket 绕过 DB 返回成功
    let dbLossError = null
    let dbLossDurationMs = null
    try {
      ctx.runDistributedCommand('disconnect-db-a')

      const elapsed = createDurationTimer()
      dbLossError = await expectHttpError(() =>
        ctx.callNode(
          'a',
          'GET',
          `/api/environments/${encodeURIComponent(ENV_A_ID)}`,
          undefined,
          10_000,
        ),
      )
      dbLossDurationMs = elapsed()
      // 只约束「必须失败」：具体 4xx/5xx 由应用错误映射决定，关键是绝不返回 200。
      assert(
        dbLossError.status >= 400,
        `DB loss must fail closed instead of serving the local websocket, got ${dbLossError.status}`,
      )
    } finally {
      // 3. finally 无条件恢复 node A DB 网络连接
      ctx.runDistributedCommand('reconnect-db-a')
    }

    // 4. 有界轮询 node A 与 node B 都恢复 DB 权威 READY 投影
    const recoveredOnA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID, 60)
    const recoveredOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_A_ID, 60)
    assert(recoveredOnA?.status === 'READY', 'node A failed to recover READY status')
    assert(
      recoveredOnA.homeDirectory === preCardA.homeDirectory,
      `node A homeDirectory must recover to the same DB-authoritative value: ${
        recoveredOnA.homeDirectory
      } != ${preCardA.homeDirectory}`,
    )
    assert(
      recoveredOnB.homeDirectory === preCardA.homeDirectory,
      `node B must observe the recovered route: ${recoveredOnB.homeDirectory} != ${preCardA.homeDirectory}`,
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
  const { json } = await callNode(node, 'GET', `/api/threads/${encodeURIComponent(threadId)}`)
  return envelopeData(json)
}

async function setupDistributedHarness(ctx, suffix) {
  const providerRes = await ctx.callNode('a', 'POST', '/api/providers', {
    name: `e2e-dist-prov-${suffix}`,
    description: 'Distributed test provider',
    providerType: 'openai',
    baseUrl: 'http://stub.local:8080/v1',
    credential: `e2e-dist-${suffix}`,
    modelCallTimeoutMillis: 60_000,
    modelCallIdleTimeoutMillis: 30_000,
  })
  const provider = envelopeData(providerRes.json)
  assert(provider?.name, 'provider creation failed')

  const modelRes = await ctx.callNode('a', 'POST', '/api/models', {
    kind: 'MODEL',
    providerName: provider.name,
    name: `e2e-dist-model-${suffix}`,
    modelId: 'gpt-4o',
    description: 'Distributed test model',
    config: baseModelConfig({
      limit: { context: 4096, output: 512 },
      abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
      variants: [{ id: 'default' }],
    }),
  })
  const model = envelopeData(modelRes.json)
  assert(model?.name, 'model creation failed')

  const agentRes = await ctx.callNode('a', 'POST', '/api/agents', {
    name: `e2e-dist-agent-${suffix}`,
    description: 'Distributed test agent',
    systemPrompt: 'You are an agent with bash tools.',
    model: model.name,
    variant: 'default',
    config: {
      tools: ['bash'],
      skills: [],
      subagents: [],
      inheritParentEnvironment: true,
    },
  })
  const agent = envelopeData(agentRes.json)
  assert(agent?.name, 'agent creation failed')

  return { provider, model, agent }
}

async function cleanupDistributedHarness(ctx, { agent, model, provider }) {
  if (agent?.name) {
    try {
      await ctx.callNode(
        'a',
        'DELETE',
        `/api/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
      )
    } catch {
      try {
        await ctx.callNode(
          'b',
          'DELETE',
          `/api/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
        )
      } catch {
        // best effort
      }
    }
  }
  if (model?.name && model?.providerName) {
    try {
      await ctx.callNode(
        'a',
        'DELETE',
        `/api/models/${encodeURIComponent(model.providerName)}/${encodeURIComponent(model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
      )
    } catch {
      try {
        await ctx.callNode(
          'b',
          'DELETE',
          `/api/models/${encodeURIComponent(model.providerName)}/${encodeURIComponent(model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
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
        `/api/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
      )
    } catch {
      try {
        await ctx.callNode(
          'b',
          'DELETE',
          `/api/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
        )
      } catch {
        // best effort
      }
    }
  }
}

async function restoreDistributedStack(ctx) {
  try {
    ctx.runDistributedCommand('start-app-a')
  } catch {
    // ignore
  }
  try {
    ctx.runDistributedCommand('restart-daemon-c')
  } catch {
    // ignore
  }
  await waitForEnvironmentReady(ctx.callNode, 'a', ENV_C_ID, 60)
  await waitForEnvironmentReady(ctx.callNode, 'b', ENV_C_ID, 60)
}

registerCase({
  id: 'distributed.graceful_drain_handover',
  level: 'L5',
  title: '双节点滚动发布优雅排空与工具挂接接管',
  requires: ['distributed'],
  docs: '在 distributed-c 上运行 sleep 20 && echo handover-done；工具进入 RUNNING 后优雅停止 app-a（SIGTERM）；验证 app-b 重新挂接并在 attempt=1 下交付 SUCCEEDED，全程无 UNKNOWN，unconfirmedExecutions 最终为空',
  async run(ctx) {
    assertDistributedContext(ctx)
    await waitForEnvironmentReady(ctx.callNode, 'a', ENV_C_ID)
    await waitForEnvironmentReady(ctx.callNode, 'b', ENV_C_ID)

    const suffix = randomUUID().slice(0, 8)
    let harness = null
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
        const snap = await getThreadSnapshotFromNode(ctx.callNode, 'a', threadId)
        const tool = snap.toolInvocations?.[0]
        if (tool && tool.status === 'RUNNING') {
          toolId = tool.id
          break
        }
        await sleep(500)
      }
      assert(toolId, 'tool invocation did not enter RUNNING')

      // 优雅停止 app-a：触发排空、release claim 并让出路由租约
      ctx.runDistributedCommand('stop-app-a')

      let finalSnapshot = null
      let sawUnknown = false
      for (let i = 0; i < 90; i++) {
        try {
          const snap = await getThreadSnapshotFromNode(ctx.callNode, 'b', threadId)
          const tool = snap.toolInvocations?.[0]
          if (tool?.status === 'UNKNOWN') sawUnknown = true
          if (tool && tool.status === 'SUCCEEDED' && snap.executionStatus === 'IDLE') {
            finalSnapshot = snap
            break
          }
        } catch {
          // app-b 可能正在处理重连与状态同步
        }
        await sleep(1000)
      }

      assert(finalSnapshot, 'thread did not reach quiescence on node B')
      assert(!sawUnknown, 'must not observe UNKNOWN during graceful drain handover')

      const finalTool = finalSnapshot.toolInvocations?.[0]
      assert(finalTool?.status === 'SUCCEEDED', `expected tool status SUCCEEDED, got ${finalTool?.status}`)
      assert(
        finalTool?.resultJson?.includes('handover-done'),
        `expected result to contain handover-done: ${finalTool?.resultJson}`,
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
            executionStatus: finalSnapshot.executionStatus,
          },
          null,
          2,
        ),
      )
    } finally {
      await restoreDistributedStack(ctx)
      if (harness) await cleanupDistributedHarness(ctx, harness)
    }
  },
})

registerCase({
  id: 'distributed.crash_failover',
  level: 'L5',
  title: '双节点崩溃故障接管与租约超时恢复',
  requires: ['distributed'],
  docs: '在 distributed-c 上运行 sleep 20 && echo handover-done；工具进入 RUNNING 后强杀 app-a（SIGKILL）；验证租约超时后 app-b 接管并交付 SUCCEEDED，被杀节点的 TOOL 退出事实保持未确认留在 unconfirmedExecutions 中',
  async run(ctx) {
    assertDistributedContext(ctx)
    await waitForEnvironmentReady(ctx.callNode, 'a', ENV_C_ID)
    await waitForEnvironmentReady(ctx.callNode, 'b', ENV_C_ID)

    const suffix = randomUUID().slice(0, 8)
    let harness = null
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
        const snap = await getThreadSnapshotFromNode(ctx.callNode, 'a', threadId)
        const tool = snap.toolInvocations?.[0]
        if (tool && tool.status === 'RUNNING') {
          toolId = tool.id
          break
        }
        await sleep(500)
      }
      assert(toolId, 'tool invocation did not enter RUNNING')

      // 强杀 app-a（不排空，不释放租约与 Work）
      ctx.runDistributedCommand('kill-app-a')

      let finalSnapshot = null
      for (let i = 0; i < 90; i++) {
        try {
          const snap = await getThreadSnapshotFromNode(ctx.callNode, 'b', threadId)
          const tool = snap.toolInvocations?.[0]
          if (tool && tool.status === 'SUCCEEDED' && snap.executionStatus === 'IDLE') {
            finalSnapshot = snap
            break
          }
        } catch {
          // 等待租约过期与接管
        }
        await sleep(1000)
      }

      assert(finalSnapshot, 'thread did not reach quiescence on node B after crash failover')

      const finalTool = finalSnapshot.toolInvocations?.[0]
      assert(finalTool?.status === 'SUCCEEDED', `expected tool status SUCCEEDED, got ${finalTool?.status}`)
      assert(
        finalTool?.resultJson?.includes('handover-done'),
        `expected result to contain handover-done: ${finalTool?.resultJson}`,
      )

      const unconfirmed = finalSnapshot.unconfirmedExecutions || []
      assert(
        unconfirmed.some((e) => e.kind === 'TOOL' && e.attempt === 1),
        `expected unconfirmedExecutions to retain killed node TOOL fact: ${JSON.stringify(unconfirmed)}`,
      )

      ctx.writeArtifact(
        'crash-failover.json',
        JSON.stringify(
          {
            toolInvocation: finalTool,
            unconfirmedExecutions: finalSnapshot.unconfirmedExecutions,
            executionStatus: finalSnapshot.executionStatus,
          },
          null,
          2,
        ),
      )
    } finally {
      await restoreDistributedStack(ctx)
      if (harness) await cleanupDistributedHarness(ctx, harness)
    }
  },
})

registerCase({
  id: 'distributed.daemon_restart_unknown',
  level: 'L5',
  title: '双节点远端 Daemon 重启崩溃恢复收敛为 UNKNOWN',
  requires: ['distributed'],
  docs: '在 distributed-c 上运行 sleep 20 && echo handover-done；工具进入 RUNNING 后重启 daemon-c（journal 丢失）；验证远端调用因 RECOVER_NOT_FOUND 收敛为 UNKNOWN，且 Thread 正常达到 quiescence',
  async run(ctx) {
    assertDistributedContext(ctx)
    await waitForEnvironmentReady(ctx.callNode, 'a', ENV_C_ID)
    await waitForEnvironmentReady(ctx.callNode, 'b', ENV_C_ID)

    const suffix = randomUUID().slice(0, 8)
    let harness = null
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
        const snap = await getThreadSnapshotFromNode(ctx.callNode, 'a', threadId)
        const tool = snap.toolInvocations?.[0]
        if (tool && tool.status === 'RUNNING') {
          toolId = tool.id
          break
        }
        await sleep(500)
      }
      assert(toolId, 'tool invocation did not enter RUNNING')

      // 重启 daemon-c，journal 丢失
      ctx.runDistributedCommand('restart-daemon-c')

      let finalSnapshot = null
      for (let i = 0; i < 60; i++) {
        try {
          const snap = await getThreadSnapshotFromNode(ctx.callNode, 'a', threadId)
          const tool = snap.toolInvocations?.[0]
          if (tool && tool.status === 'UNKNOWN' && snap.executionStatus === 'IDLE') {
            finalSnapshot = snap
            break
          }
        } catch {
          // waiting for recovery failure
        }
        await sleep(1000)
      }

      assert(finalSnapshot, 'thread did not reach quiescence with UNKNOWN tool status after daemon restart')
      const finalTool = finalSnapshot.toolInvocations?.[0]
      assert(finalTool?.status === 'UNKNOWN', `expected tool status UNKNOWN, got ${finalTool?.status}`)
      assert(finalSnapshot.executionStatus === 'IDLE', `expected thread to be IDLE, got ${finalSnapshot.executionStatus}`)

      ctx.writeArtifact(
        'daemon-restart-unknown.json',
        JSON.stringify(
          {
            toolInvocation: finalTool,
            unconfirmedExecutions: finalSnapshot.unconfirmedExecutions,
            executionStatus: finalSnapshot.executionStatus,
          },
          null,
          2,
        ),
      )
    } finally {
      await restoreDistributedStack(ctx)
      if (harness) await cleanupDistributedHarness(ctx, harness)
    }
  },
})
