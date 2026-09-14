import { randomUUID } from 'node:crypto'
import { assert, assertDecimalVersion, envelopeData, expectHttpError, sleep } from '../lib/http.mjs'
import { assertDistributedContext } from '../lib/distributed.mjs'
import { registerCase } from '../lib/registry.mjs'

const ENV_A_ID = '33333333-3333-3333-3333-333333333333'
const ENV_B_ID = '44444444-4444-4444-4444-444444444444'

async function waitForEnvironmentReady(callNode, node, envId, maxAttempts = 30) {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const res = await callNode(node, 'GET', `/api/harness/environments/${encodeURIComponent(envId)}`)
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
  title: '双节点共享状态：跨节点 Environment CRUD 与 404',
  requires: ['distributed'],
  docs: '在 node A 创建临时 Environment Card，在 node B 读取并更新，回 node A 验证更新内容与 CAS version，再跨节点删除并在两节点验证 404；finally 尽力清理，禁止将一次性 registrationToken 写入 artifact/log',
  async run(ctx) {
    assertDistributedContext(ctx)

    const envName = `dist-shared-${randomUUID().slice(0, 8)}`
    let createdId = null
    let latestVersion = null

    try {
      // 1. 在 node A 创建临时 Environment Card
      const createRes = await ctx.callNode('a', 'POST', '/api/harness/environments', { name: envName })
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
      const getBRes = await ctx.callNode('b', 'GET', `/api/harness/environments/${encodeURIComponent(createdId)}`)
      const fromB = envelopeData(getBRes.json)
      assert(fromB.id === createdId, 'node B get: id mismatch')
      assert(fromB.name === envName, 'node B get: name mismatch')
      assert(fromB.version === latestVersion, 'node B get: version mismatch')
      assert(!fromB.registrationToken, 'GET must never expose registrationToken')

      // 3. 在 node B 更新名称
      const updatedName = `${envName}-renamed`
      const updateRes = await ctx.callNode(
        'b',
        'PUT',
        `/api/harness/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(latestVersion)}`,
        { name: updatedName },
      )
      const updated = envelopeData(updateRes.json)
      assert(updated.name === updatedName, 'node B update: name mismatch')
      assert(updated.version !== latestVersion, 'node B update: version must advance')
      assertDecimalVersion(updated.version, 'updated.version')
      latestVersion = updated.version

      // 4. 回 node A 验证更新内容与 CAS version
      const getARes = await ctx.callNode('a', 'GET', `/api/harness/environments/${encodeURIComponent(createdId)}`)
      const fromA = envelopeData(getARes.json)
      assert(fromA.name === updatedName, 'node A verify: name mismatch')
      assert(fromA.version === latestVersion, 'node A verify: version mismatch')

      // 5. 跨节点删除（在 node B 发起）并在两节点验证 404
      await ctx.callNode(
        'b',
        'DELETE',
        `/api/harness/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(latestVersion)}`,
      )
      await expectHttpError(
        () => ctx.callNode('a', 'GET', `/api/harness/environments/${encodeURIComponent(createdId)}`),
        { status: 404 },
      )
      await expectHttpError(
        () => ctx.callNode('b', 'GET', `/api/harness/environments/${encodeURIComponent(createdId)}`),
        { status: 404 },
      )

      // 6. 记录安全 artifact（禁止写入一次性 registrationToken）
      ctx.writeArtifact(
        'shared-state-crud.json',
        JSON.stringify(
          {
            id: createdId,
            originalName: envName,
            updatedName,
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
          const probe = await ctx.callNode('a', 'GET', `/api/harness/environments/${encodeURIComponent(createdId)}`)
          const card = envelopeData(probe.json)
          if (card?.version) {
            await ctx.callNode(
              'a',
              'DELETE',
              `/api/harness/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(card.version)}`,
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
  docs: '固定环境 distributed-a=33333333-3333-3333-3333-333333333333 连 app-a，distributed-b=44444444-4444-4444-4444-444444444444 连 app-b；两个 App 都投影两者 READY，且同一 Environment 在两个节点上的 id/name/version/status/ready/rootPath/capabilities 逐项一致（路由事实来自 DB，不依赖本机 websocket），coding capability 为 fs.read@1',
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
      assert(
        typeof fromA.rootPath === 'string' && fromA.rootPath.length > 0,
        `${label}: rootPath must be projected cross-node`,
      )
      assert(
        fromA.rootPath === fromB.rootPath,
        `${label}: rootPath must come from DB routing, not the caller's local socket`,
      )
      // live 投影的原子能力来自权威 catalog，因此必然包含 version=1 的 coding capability。
      const capabilityKey = (card) =>
        (card.capabilities || [])
          .map((capability) => `${capability.id}@${capability.version}`)
          .sort()
          .join(',')
      assert(
        capabilityKey(fromA) === capabilityKey(fromB),
        `${label}: capability projection must match across nodes: ${capabilityKey(fromA)} != ${capabilityKey(fromB)}`,
      )
      assert(
        (fromA.capabilities || []).some(
          (capability) => capability.id === 'fs.read' && capability.version === '1',
        ),
        `${label}: coding capabilities must advertise fs.read@1, got ${capabilityKey(fromA)}`,
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
  docs: '先验证 node A 投影 env A READY；通过受限白名单调 disconnect-db-a 断开 node A DB 网络；断网后 node A 的 DB 权威读路径必须失败（HTTP 4xx/5xx，而不是回退本机 websocket 返回 200）；finally 无条件 reconnect-db-a，随后有界轮询 node A 与 node B 都恢复 READY 且 rootPath 与断网前一致（证明 DB-authoritative route 恢复）',
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
      preCardA.rootPath === preCardAOnB.rootPath,
      `pre-check: cross-node rootPath mismatch: ${JSON.stringify({
        onA: preCardA.rootPath,
        onB: preCardAOnB.rootPath,
      })}`,
    )

    // 2. 故障注入：断开 node A DB 网络，验证 node A 不会用本机 websocket 绕过 DB 返回成功
    let dbLossError = null
    try {
      ctx.runDistributedCommand('disconnect-db-a')

      dbLossError = await expectHttpError(() =>
        ctx.callNode(
          'a',
          'GET',
          `/api/harness/environments/${encodeURIComponent(ENV_A_ID)}`,
          undefined,
          10_000,
        ),
      )
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
      recoveredOnA.rootPath === preCardA.rootPath,
      `node A rootPath must recover to the same DB-authoritative value: ${
        recoveredOnA.rootPath
      } != ${preCardA.rootPath}`,
    )
    assert(
      recoveredOnB.rootPath === preCardA.rootPath,
      `node B must observe the recovered route: ${recoveredOnB.rootPath} != ${preCardA.rootPath}`,
    )

    ctx.writeArtifact(
      'recovery-summary.json',
      JSON.stringify(
        {
          dbLossStatus: dbLossError?.status ?? null,
          recoveredOnA: { status: recoveredOnA.status, rootPath: recoveredOnA.rootPath },
          recoveredOnB: { status: recoveredOnB.status, rootPath: recoveredOnB.rootPath },
        },
        null,
        2,
      ),
    )
  },
})
