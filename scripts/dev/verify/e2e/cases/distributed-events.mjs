import { randomUUID } from 'node:crypto'
import { assert, envelopeData, expectHttpError, HttpError, sleep } from '../lib/http.mjs'
import { assertDistributedContext, waitForNodeHealth } from '../lib/distributed.mjs'
import { EventProbe, withCleanup } from '../lib/event-probe.mjs'
import { registerCase } from '../lib/registry.mjs'

const projects = { kind: 'projects' }
const environments = { kind: 'environments' }
const projectEvent = (id) => (f) => f.type === 'event' && f.resource.kind === 'projects' && f.data.projectId === id
const topicEvent = (kind) => (f) => f.type === 'event' && f.resource.kind === kind
const data = async (ctx, node, method, path, body) => envelopeData((await ctx.callNode(node, method, path, body)).json)

// Only validated protocol frames are persisted, never HTTP Environment/token responses.
function capture(ctx, probes) {
  ctx.writeArtifact('ws-frames.json', JSON.stringify(probes.map((probe, connection) => ({
    connection, url: probe.socket.url, closed: probe.closed, frames: probe.frames,
  })), null, 2))
}

async function deleteVersioned(ctx, collection, id) {
  if (!id) return
  let current
  try { current = await data(ctx, 'b', 'GET', `${collection}/${id}`) } catch (error) {
    if (error instanceof HttpError && error.status === 404) return
    throw error
  }
  await ctx.callNode('b', 'DELETE', `${collection}/${id}?expectedVersion=${current.version}`)
}

async function probes(ctx, resources) {
  assertDistributedContext(ctx)
  const pair = []
  try {
    for (const node of ['a', 'b']) {
      const probe = new EventProbe(ctx.baseUrls[node])
      pair.push(probe)
      for (const [resource, cursor] of resources) await probe.subscribe(resource, cursor)
    }
    return pair
  } catch (error) {
    await withCleanup(() => { throw error }, pair.map((probe) => () => probe.close()))
  }
}

/** Capture every matching frame, including arrivals before HTTP returns; read immediately on receipt. */
async function commit(pair, predicate, mutate, verify, { silentKinds = [] } = {}) {
  const starts = pair.map((probe) => probe.frames.length)
  const reads = []
  const hooks = pair.map((probe, index) => {
    const hook = (frame) => {
      if (predicate(frame)) {
        // Resolve failure into a value now to avoid unhandled rejection before mutation finishes.
        reads.push(Promise.resolve(verify(index === 0 ? 'a' : 'b', frame)).then(
          () => null, (error) => error,
        ))
      }
    }
    probe.hooks.add(hook)
    return hook
  })
  try {
    const result = await mutate()
    for (let i = 0; i < pair.length; i++) {
      await pair[i].until(() => pair[i].count(predicate, starts[i]) >= 1)
    }
    await Promise.all(pair.map((probe) => probe.stable()))
    for (let i = 0; i < pair.length; i++) {
      assert(pair[i].count(predicate, starts[i]) === 1, 'each commit must deliver exactly once per node (no self echo)')
      for (const kind of silentKinds) assert(pair[i].count(topicEvent(kind), starts[i]) === 0, `unexpected ${kind} event`)
    }
    for (const error of await Promise.all(reads)) if (error) throw error
    return result
  } finally {
    pair.forEach((probe, index) => probe.hooks.delete(hooks[index]))
  }
}

function register(id, title, docs, run) {
  registerCase({ id: `distributed.${id}`, level: 'L5', title, docs, requires: ['distributed'], run })
}

register('projects_notifications', '双向 Projects 提交通知与 commit 可见性',
  'A/B 各一真实 WS projects 订阅，subscribed 屏障后双向创建/CAS 更新/删除；UUID 过滤、400ms 稳定窗逐提交恰好一次（含本节点无 self echo），每次收到事件立即 GET 对端权威事实；陈旧 CAS 409 且无通知。',
  async (ctx) => {
    const pair = await probes(ctx, [[projects, '0']])
    const ids = new Set()
    await withCleanup(async () => {
      for (const writer of ['a', 'b']) {
        const other = writer === 'a' ? 'b' : 'a'
        const title = `notify-${randomUUID()}`
        // Creation IDs are server-generated: capture by UUID, identify the fixture via its unique title.
        let id
        const createStarts = pair.map((probe) => probe.frames.length)
        const observations = []
        const hooks = pair.map((probe, index) => {
          const hook = (frame) => {
            if (frame.type === 'event' && frame.resource.kind === 'projects') {
              observations.push({ id: frame.data.projectId, read: data(ctx, index === 0 ? 'b' : 'a', 'GET', `/api/projects/${frame.data.projectId}`)
                .then((value) => ({ value }), (error) => ({ error })) })
            }
          }
          probe.hooks.add(hook)
          return hook
        })
        let created
        try {
          created = await data(ctx, writer, 'POST', '/api/projects', { title })
          id = created.id
          ids.add(id)
          for (let i = 0; i < pair.length; i++) {
            await pair[i].until(() => pair[i].count(projectEvent(id), createStarts[i]) >= 1)
          }
          await Promise.all(pair.map((probe) => probe.stable()))
          for (let i = 0; i < pair.length; i++) assert(pair[i].count(projectEvent(id), createStarts[i]) === 1, 'duplicate create notification')
          const fixtureReads = observations.filter((item) => item.id === id)
          assert(fixtureReads.length === 2, 'both create events must initiate immediate reads')
          for (const item of fixtureReads) {
            const observed = await item.read
            if (observed.error) throw observed.error
            assert(observed.value.title === title && observed.value.version === created.version, 'create not commit-visible')
          }
        } finally {
          pair.forEach((probe, i) => probe.hooks.delete(hooks[i]))
        }
        const updated = await commit(pair, projectEvent(id),
          () => data(ctx, other, 'PUT', `/api/projects/${id}`, { expectedVersion: created.version, title: `${title}-updated` }),
          async (receiver) => {
            const value = await data(ctx, receiver === 'a' ? 'b' : 'a', 'GET', `/api/projects/${id}`)
            assert(value.title === `${title}-updated` && BigInt(value.version) === BigInt(created.version) + 1n, 'update not commit-visible')
          })
        const marks = pair.map((probe) => probe.frames.length)
        await expectHttpError(() => ctx.callNode(writer, 'PUT', `/api/projects/${id}`, { expectedVersion: created.version, title: 'stale' }), { status: 409 })
        await Promise.all(pair.map((probe) => probe.stable()))
        pair.forEach((probe, i) => assert(probe.count(projectEvent(id), marks[i]) === 0, 'CAS failure emitted event'))
        await commit(pair, projectEvent(id),
          () => ctx.callNode(writer, 'DELETE', `/api/projects/${id}?expectedVersion=${updated.version}`),
          (receiver) => expectHttpError(() => ctx.callNode(receiver === 'a' ? 'b' : 'a', 'GET', `/api/projects/${id}`), { status: 404 }))
        ids.delete(id)
      }
      ctx.writeArtifact('projects-notifications.json', JSON.stringify({ directions: ['a->b', 'b->a'], commits: 6, perNodePerCommit: 1, stableWindowMs: 400 }))
    }, [...pair.map((probe) => () => probe.close()), () => capture(ctx, pair),
      () => withCleanup(async () => {}, [...ids].map((id) => () => deleteVersioned(ctx, '/api/projects', id)))])
  })

register('canvas_environment_notifications', 'Canvas revision 与 Environment 跨 topic 通知及回滚静默',
  '两节点订阅两个 Canvas UUID 与 environments；Canvas command revision/Environment token CAS 的真实跨节点通知及立即权威读取，400ms 窗口验证另一 topic 不串；Canvas 原子批前置条件失败回滚与 Environment 陈旧 CAS 409 不发事件。Environment v1 data={}，只能按全局 topic 计数，不声称按实体过滤。',
  async (ctx) => {
    assertDistributedContext(ctx)
    const canvases = []
    let envId
    let pair = []
    await withCleanup(async () => {
      for (let i = 0; i < 2; i++) canvases.push(await data(ctx, 'a', 'POST', '/api/canvases', { title: `notify-${randomUUID()}` }))
      const env = await data(ctx, 'a', 'POST', '/api/harness/environments', { name: `notify-${randomUUID()}` })
      envId = env.id
      pair = await probes(ctx, [...canvases.map((canvas) => [{ kind: 'canvas', id: canvas.id }, '0']), [environments, '0']])
      const canvas = canvases[0]
      const nodeId = randomUUID()
      const predicate = (f) => topicEvent('canvas')(f) && f.resource.id === canvas.id
      await commit(pair, predicate,
        () => data(ctx, 'b', 'POST', `/api/canvases/${canvas.id}/commands`, { idempotencyKey: randomUUID(), commands: [{
          type: 'CREATE_NODE', nodeId, name: 'note', transform: { x: 1, y: 2, width: 100, height: 80 },
          resources: [{ kind: 'TEXT', name: 'body', textContent: 'free' }],
        }] }),
        async (receiver, frame) => {
          assert(frame.cursor === '1', 'incorrect revision event')
          const snapshot = await data(ctx, receiver === 'a' ? 'b' : 'a', 'GET', `/api/canvases/${canvas.id}`)
          assert(snapshot.document.revision === '1' && snapshot.nodes.some((n) => n.id === nodeId), 'canvas not commit-visible')
        }, { silentKinds: ['environments'] })
      for (const probe of pair) assert(probe.count((f) => topicEvent('canvas')(f) && f.resource.id === canvases[1].id) === 0, 'unrelated canvas event')
      await commit(pair, topicEvent('environments'),
        () => data(ctx, 'a', 'POST', `/api/harness/environments/${envId}/registration-token`, { expectedVersion: env.version }),
        async (receiver) => {
          const card = await data(ctx, receiver === 'a' ? 'b' : 'a', 'GET', `/api/harness/environments/${envId}`)
          assert(BigInt(card.version) === BigInt(env.version) + 1n, 'environment not commit-visible')
        }, { silentKinds: ['canvas'] })
      const marks = pair.map((probe) => probe.frames.length)
      await expectHttpError(() => ctx.callNode('b', 'POST', `/api/canvases/${canvas.id}/commands`, {
        idempotencyKey: randomUUID(), commands: [
          { type: 'RENAME_NODE', nodeId, expectedName: 'note', name: 'rolled-back' },
          { type: 'RENAME_NODE', nodeId, expectedName: 'stale', name: 'invalid' },
        ],
      }), { status: 409 })
      await expectHttpError(() => ctx.callNode('b', 'POST', `/api/harness/environments/${envId}/registration-token`, { expectedVersion: env.version }), { status: 409 })
      await Promise.all(pair.map((probe) => probe.stable()))
      pair.forEach((probe, i) => assert(probe.count((f) => f.type === 'event', marks[i]) === 0, 'failed transaction emitted event'))
      const snapshot = await data(ctx, 'a', 'GET', `/api/canvases/${canvas.id}`)
      assert(snapshot.document.revision === '1' && snapshot.nodes.find((n) => n.id === nodeId)?.name === 'note', 'batch did not roll back')
      const card = await data(ctx, 'a', 'GET', `/api/harness/environments/${envId}`)
      assert(BigInt(card.version) === BigInt(env.version) + 1n, 'failed environment CAS changed durable state')
      ctx.writeArtifact('topic-notifications.json', JSON.stringify({ canvasId: canvas.id, unrelatedCanvasId: canvases[1].id, envId, revision: '1', rollback: true, environmentEntityFilter: false }))
    }, [() => withCleanup(async () => {}, pair.map((probe) => () => probe.close())),
      () => capture(ctx, pair),
      () => deleteVersioned(ctx, '/api/harness/environments', envId),
      () => withCleanup(async () => {}, canvases.map((canvas) => () => ctx.callNode('b', 'DELETE', `/api/canvases/${canvas.id}`)))])
  })

register('notification_subscription_lifecycle', '通知 unsubscribe/close/reconnect 新基线',
  'A unsubscribe 后用同连接另一 topic 的 subscribed 作处理屏障，新提交仅 B 收到；关闭 A 后建立新 WS subscribed 基线，再提交两边各一次，旧连接无事件；不声称旧事件回放。',
  async (ctx) => {
    let pair = await probes(ctx, [[projects, '0']])
    const all = [...pair]
    let id
    await withCleanup(async () => {
      const created = await data(ctx, 'b', 'POST', '/api/projects', { title: `notify-${randomUUID()}` })
      id = created.id
      for (const probe of pair) await probe.until(() => probe.count(projectEvent(id)) >= 1)
      await Promise.all(pair.map((probe) => probe.stable()))
      for (const probe of pair) assert(probe.count(projectEvent(id)) === 1, 'duplicate fixture creation event')
      const old = pair[0]
      const mark = old.frames.length
      old.unsubscribe(projects)
      // No unsubscribe ack exists: the next command's ack establishes ordered processing.
      await old.subscribe(environments)
      const updated = await commit([pair[1]], projectEvent(id),
        () => data(ctx, 'b', 'PUT', `/api/projects/${id}`, { expectedVersion: created.version, title: 'unsubscribed' }),
        async () => { assert((await data(ctx, 'a', 'GET', `/api/projects/${id}`)).title === 'unsubscribed', 'update not visible') })
      await old.stable()
      assert(old.count(projectEvent(id), mark) === 0, 'unsubscribed socket received event')
      await old.close()
      const closedCount = old.frames.length
      const fresh = new EventProbe(ctx.baseUrls.a)
      all.push(fresh)
      await fresh.subscribe(projects)
      pair = [fresh, pair[1]]
      assert(fresh.count(projectEvent(id)) === 0, 'new subscription must not pretend to replay old events')
      await commit(pair, projectEvent(id),
        () => data(ctx, 'b', 'PUT', `/api/projects/${id}`, { expectedVersion: updated.version, title: 'reconnected' }),
        async (receiver) => { assert((await data(ctx, receiver === 'a' ? 'b' : 'a', 'GET', `/api/projects/${id}`)).title === 'reconnected', 'reconnect commit not visible') })
      assert(old.frames.length === closedCount, 'closed socket received event')
      ctx.writeArtifact('subscription-lifecycle.json', JSON.stringify({ id, unsubscribedSilent: true, reconnectedBaseline: '0', replayClaimed: false }))
    }, [() => withCleanup(async () => {}, all.map((probe) => () => probe.close())),
      () => capture(ctx, all), () => deleteVersioned(ctx, '/api/projects', id)])
  })

register('notification_db_recovery', 'DB 网络故障通知 fail-closed 与权威 resync',
  '白名单断开 A DB 网络；B 持久提交，A 必须在 15s 内 resync 或关闭，恢复后重新 LISTEN 的 resync/新连接 subscribed 与权威 GET 重建基线，再验证新提交双节点恰好一次；finally 恢复网络和删除 fixture，不声称断网事件回放。',
  async (ctx) => {
    let pair = await probes(ctx, [[projects, '0']])
    const all = [...pair]
    let id
    let restore = false
    await withCleanup(async () => {
      const created = await data(ctx, 'b', 'POST', '/api/projects', { title: `notify-${randomUUID()}` })
      id = created.id
      for (const probe of pair) await probe.until(() => probe.count(projectEvent(id)) >= 1)
      await Promise.all(pair.map((probe) => probe.stable()))
      for (const probe of pair) assert(probe.count(projectEvent(id)) === 1, 'duplicate fixture creation event')
      const mark = pair[0].frames.length
      restore = true
      ctx.runDistributedCommand('disconnect-db-a')
      const updated = await commit([pair[1]], projectEvent(id),
        () => data(ctx, 'b', 'PUT', `/api/projects/${id}`, { expectedVersion: created.version, title: 'during-outage' }),
        async () => { assert((await data(ctx, 'b', 'GET', `/api/projects/${id}`)).title === 'during-outage', 'B commit not visible') })
      await pair[0].until(() => pair[0].closed || pair[0].frames.slice(mark).some((f) => f.type === 'resync'), 15_000, true)
      assert(pair[0].count(projectEvent(id), mark) === 0, 'isolated A delivered stale notification')
      // resync 会触发 1012 关闭；立即观测 closed 会与关闭握手竞态。明确结束旧连接后再建新基线。
      const old = pair[0]
      await old.close()
      const closedCount = old.frames.length
      ctx.runDistributedCommand('reconnect-db-a')
      restore = false
      await waitForNodeHealth(ctx, 'a')
      const fresh = new EventProbe(ctx.baseUrls.a)
      all.push(fresh)
      await fresh.subscribe(projects)
      pair = [fresh, pair[1]]
      assert(old.frames.length === closedCount, 'closed socket received recovery frames')
      // Resync is invalidation, not replay. Only the authoritative GET establishes current state.
      const deadline = Date.now() + 30_000
      let current
      while (Date.now() < deadline) {
        try {
          current = envelopeData((await ctx.callNode('a', 'GET', `/api/projects/${id}`, undefined, 2_000)).json)
          break
        } catch { await sleep(100) }
      }
      assert(current?.version === updated.version && current.title === 'during-outage', 'authority did not recover committed state')
      await commit(pair, projectEvent(id),
        () => data(ctx, 'b', 'PUT', `/api/projects/${id}`, { expectedVersion: updated.version, title: 'after-recovery' }),
        async (receiver) => { assert((await data(ctx, receiver === 'a' ? 'b' : 'a', 'GET', `/api/projects/${id}`)).title === 'after-recovery', 'new commit not visible') })
      ctx.writeArtifact('notification-recovery.json', JSON.stringify({ id, failClosed: true, authoritativeResync: true, newCommitPerNode: 1, replayClaimed: false }))
    }, [() => { if (restore) ctx.runDistributedCommand('reconnect-db-a') },
      () => withCleanup(async () => {}, all.map((probe) => () => probe.close())),
      () => capture(ctx, all),
      () => deleteVersioned(ctx, '/api/projects', id)])
  })
