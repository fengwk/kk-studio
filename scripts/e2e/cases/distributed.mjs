import { randomUUID } from 'node:crypto'
import { assert, assertDecimalVersion, envelopeData, expectHttpError, sleep } from '../lib/http.mjs'
import { assertDistributedContext } from '../lib/distributed.mjs'
import { registerCase } from '../lib/registry.mjs'

const ENV_A_ID = '33333333-3333-3333-3333-333333333333'
const ENV_B_ID = '44444444-4444-4444-4444-444444444444'
const MARKER_A = 'distributed-a-only'
const MARKER_B = 'distributed-b-only'

async function waitForEnvironmentReady(callNode, node, envId, maxAttempts = 30) {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const res = await callNode(node, 'GET', `/api/ai/environments/${encodeURIComponent(envId)}`)
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
      const createRes = await ctx.callNode('a', 'POST', '/api/ai/environments', { name: envName })
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
      const getBRes = await ctx.callNode('b', 'GET', `/api/ai/environments/${encodeURIComponent(createdId)}`)
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
        `/api/ai/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(latestVersion)}`,
        { name: updatedName },
      )
      const updated = envelopeData(updateRes.json)
      assert(updated.name === updatedName, 'node B update: name mismatch')
      assert(updated.version !== latestVersion, 'node B update: version must advance')
      assertDecimalVersion(updated.version, 'updated.version')
      latestVersion = updated.version

      // 4. 回 node A 验证更新内容与 CAS version
      const getARes = await ctx.callNode('a', 'GET', `/api/ai/environments/${encodeURIComponent(createdId)}`)
      const fromA = envelopeData(getARes.json)
      assert(fromA.name === updatedName, 'node A verify: name mismatch')
      assert(fromA.version === latestVersion, 'node A verify: version mismatch')

      // 5. 跨节点删除（在 node B 发起）并在两节点验证 404
      await ctx.callNode(
        'b',
        'DELETE',
        `/api/ai/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(latestVersion)}`,
      )
      await expectHttpError(
        () => ctx.callNode('a', 'GET', `/api/ai/environments/${encodeURIComponent(createdId)}`),
        { status: 404 },
      )
      await expectHttpError(
        () => ctx.callNode('b', 'GET', `/api/ai/environments/${encodeURIComponent(createdId)}`),
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
          const probe = await ctx.callNode('a', 'GET', `/api/ai/environments/${encodeURIComponent(createdId)}`)
          const card = envelopeData(probe.json)
          if (card?.version) {
            await ctx.callNode(
              'a',
              'DELETE',
              `/api/ai/environments/${encodeURIComponent(createdId)}?expectedVersion=${encodeURIComponent(card.version)}`,
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
  id: 'distributed.lease_mailbox_routing',
  level: 'L5',
  title: '双节点租约与 mailbox 目录路由：双向跨节点读取 marker',
  requires: ['distributed'],
  docs: '固定环境 distributed-a=33333333-3333-3333-3333-333333333333 连 app-a，distributed-b=44444444-4444-4444-4444-444444444444 连 app-b；两个 App 均投影两者 READY；从 node B 查询 env A 目录命中 distributed-a-only、从 node A 查询 env B 目录命中 distributed-b-only，强制走 PostgreSQL directory mailbox 且 marker 绝不混淆',
  async run(ctx) {
    assertDistributedContext(ctx)

    // 1. 两个 App 都必须投影两者 READY
    const cardAOnA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID)
    const cardBOnA = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_B_ID)
    const cardAOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_A_ID)
    const cardBOnB = await waitForEnvironmentReady(ctx.callNode, 'b', ENV_B_ID)

    assert(cardAOnA.ready && cardBOnA.ready && cardAOnB.ready && cardBOnB.ready, 'all four projections must be ready')

    // 2. 从 node B 查询 env A 根目录：必须强制走 mailbox，命中 MARKER_A 且不能含 MARKER_B
    const resAFromB = await ctx.callNode('b', 'GET', `/api/ai/environments/${ENV_A_ID}/directories`)
    const dirA = envelopeData(resAFromB.json)
    assert(dirA?.path === '.', `expected root path '.', got ${dirA?.path}`)
    assert(Array.isArray(dirA?.entries), 'expected entries array')
    const entryNamesA = dirA.entries.map((entry) => entry.name)
    assert(
      entryNamesA.includes(MARKER_A),
      `node B -> env A must contain marker '${MARKER_A}', entries: ${JSON.stringify(entryNamesA)}`,
    )
    assert(
      !entryNamesA.includes(MARKER_B),
      `node B -> env A must NOT contain marker '${MARKER_B}'`,
    )

    // 3. 从 node A 查询 env B 根目录：必须强制走 mailbox，命中 MARKER_B 且不能含 MARKER_A
    const resBFromA = await ctx.callNode('a', 'GET', `/api/ai/environments/${ENV_B_ID}/directories`)
    const dirB = envelopeData(resBFromA.json)
    assert(dirB?.path === '.', `expected root path '.', got ${dirB?.path}`)
    assert(Array.isArray(dirB?.entries), 'expected entries array')
    const entryNamesB = dirB.entries.map((entry) => entry.name)
    assert(
      entryNamesB.includes(MARKER_B),
      `node A -> env B must contain marker '${MARKER_B}', entries: ${JSON.stringify(entryNamesB)}`,
    )
    assert(
      !entryNamesB.includes(MARKER_A),
      `node A -> env B must NOT contain marker '${MARKER_A}'`,
    )

    ctx.writeArtifact(
      'mailbox-routing.json',
      JSON.stringify({ dirAFromB: dirA, dirBFromA: dirB }, null, 2),
    )
  },
})

registerCase({
  id: 'distributed.db_loss_fail_closed',
  level: 'L5',
  title: 'DB loss fail-closed 与有界 recovery',
  requires: ['distributed'],
  docs: '先验证 node B -> env A mailbox 目录成功；通过受限白名单调 disconnect-db-a 断开 node A DB 网络；断网后 node A 调 env A 目录必须返回 HTTP 409 且 envelope 含 ENVIRONMENT_UNAVAILABLE（证明本地 websocket 无法绕过 DB）；finally 无条件 reconnect-db-a，随后有界轮询 node A DB API 与 node B -> env A 目录恢复并再次读取 marker',
  async run(ctx) {
    assertDistributedContext(ctx)
    assert(
      typeof ctx.runDistributedCommand === 'function',
      'ctx.runDistributedCommand is required for fault injection',
    )

    // 1. 先证明 node B -> env A mailbox 成功
    const preRes = await ctx.callNode('b', 'GET', `/api/ai/environments/${ENV_A_ID}/directories`)
    const preDir = envelopeData(preRes.json)
    assert(
      preDir?.entries?.some((entry) => entry.name === MARKER_A),
      `pre-check: node B -> env A must read marker '${MARKER_A}'`,
    )

    // 2. 故障注入：断开 node A DB 网络，验证 node A 本地目录 API 严格 fail-closed
    try {
      ctx.runDistributedCommand('disconnect-db-a')

      const err = await expectHttpError(
        () =>
          ctx.callNode(
            'a',
            'GET',
            `/api/ai/environments/${ENV_A_ID}/directories`,
            undefined,
            10_000,
          ),
        { status: 409, messageIncludes: 'ENVIRONMENT_UNAVAILABLE' },
      )
      assert(
        String(err.body).includes('ENVIRONMENT_UNAVAILABLE'),
        `expected response body to include ENVIRONMENT_UNAVAILABLE, got: ${err.body}`,
      )
    } finally {
      // 3. finally 无条件恢复 node A DB 网络连接
      ctx.runDistributedCommand('reconnect-db-a')
    }

    // 4. 有界轮询 node A DB API 恢复 READY
    const recoveredCard = await waitForEnvironmentReady(ctx.callNode, 'a', ENV_A_ID, 30)
    assert(recoveredCard?.status === 'READY', 'node A failed to recover READY status')

    // 5. 有界轮询 node B -> env A directory 恢复并再次读取 A marker（证明 DB-authoritative route 恢复）
    let recoveredDir = null
    for (let attempt = 1; attempt <= 30; attempt++) {
      try {
        const res = await ctx.callNode('b', 'GET', `/api/ai/environments/${ENV_A_ID}/directories`)
        const dir = envelopeData(res.json)
        if (dir?.entries?.some((entry) => entry.name === MARKER_A)) {
          recoveredDir = dir
          break
        }
      } catch {
        // waiting for mailbox recovery
      }
      await sleep(500)
    }
    assert(
      recoveredDir,
      `node B -> env A directory failed to recover and read marker '${MARKER_A}' within timeout`,
    )

    ctx.writeArtifact(
      'recovery-summary.json',
      JSON.stringify(
        {
          recoveredCardStatus: recoveredCard.status,
          markerFound: true,
        },
        null,
        2,
      ),
    )
  },
})
