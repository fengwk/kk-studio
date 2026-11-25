import { randomUUID } from 'node:crypto'

import { assert } from '../lib/http.mjs'
import { createWorkspaceSession } from '../lib/harness.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

export const workspaceCases = []
const registerCase = createCaseAdder(workspaceCases)

function key() {
  return randomUUID()
}

async function request(ctx, method, path, body, headers = {}, allowError = false) {
  return ctx.request(path, {
    method,
    ...(body === undefined ? {} : { body }),
    ...(Object.keys(headers).length === 0 ? {} : { headers }),
    ...(allowError ? { allowError: true } : {}),
  })
}

async function json(ctx, method, path, body, headers = {}) {
  const response = await request(ctx, method, path, body, headers)
  assert(response.status >= 200 && response.status < 300,
    `${method} ${path}: expected success, got ${response.status}: ${response.text}`)
  return response.json
}

async function createCanvas(ctx, spaceId, title) {
  const tree = await json(ctx, 'GET', `/api/chat-spaces/${spaceId}/tree`)
  const folderId = tree.folders?.[0]?.id
  assert(folderId, `space ${spaceId} has no root folder: ${JSON.stringify(tree)}`)
  const idempotencyKey = key()
  const created = await json(ctx, 'POST', `/api/chat-spaces/${spaceId}/canvases`, {
    folderId,
    title,
    expectedTreeVersion: tree.treeVersion,
  }, { 'Idempotency-Key': idempotencyKey })
  assert(created.kind === 'CANVAS' && UUID.test(created.id), JSON.stringify(created))
  return created.id
}

async function createSession(ctx, spaceId, folderId, text) {
  return createWorkspaceSession(ctx, spaceId, {
    idempotencyKey: key(),
    folderId,
    message: [{ type: 'TEXT', text }],
  })
}

function openSpaceSocket(ctx) {
  const socket = ctx.openWebSocket('/ws/events')
  const frames = []
  socket.addEventListener('message', (event) => {
    try {
      frames.push(JSON.parse(String(event.data)))
    } catch {
      // Ignore non-JSON WebSocket frames.
    }
  })
  return { socket, frames }
}

async function waitForFrame(frames, predicate, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const index = frames.findIndex(predicate)
    if (index >= 0) return frames.splice(index, 1)[0]
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
  throw new Error(`timed out waiting for WebSocket frame; received ${JSON.stringify(frames)}`)
}

async function waitForSocketOpen(socket) {
  if (socket.readyState === WebSocket.OPEN) return
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('WebSocket open timeout')), 5000)
    socket.addEventListener('open', () => { clearTimeout(timeout); resolve() }, { once: true })
    socket.addEventListener('error', () => { clearTimeout(timeout); reject(new Error('WebSocket connection failed')) }, { once: true })
  })
}

registerCase({
  id: 'workspace.schedule_immediate_run_paging',
  level: 'L1',
  title: '定时任务创建、立即运行与执行记录分页',
  docs: '使用默认 L1 offline/default-assistant 配置创建禁用的 ONCE 定时任务，连续立即运行六次；验证首次提交、未收敛时跳过，并按服务端最新优先顺序核对 5+1 条执行记录分页',
  async run(ctx) {
    const spaceId = await ctx.createSpace(`e2e-schedule-${key().slice(0, 8)}`)
    const tree = await json(ctx, 'GET', `/api/chat-spaces/${spaceId}/tree`)
    const folderId = tree.folders?.[0]?.id
    assert(folderId, `space ${spaceId} has no root folder: ${JSON.stringify(tree)}`)

    const schedule = await json(ctx, 'POST', `/api/chat-spaces/${spaceId}/schedules`, {
      title: `e2e schedule ${key().slice(0, 8)}`,
      message: 'Reply with exactly: scheduled integration output',
      enabled: false,
      trigger: { type: 'ONCE', localDate: '2099-01-01', time: '12:00' },
      timeZone: 'UTC',
      target: { type: 'NEW_SESSION', folderId, agentName: 'default-assistant' },
    }, { 'Idempotency-Key': key() })
    assert(UUID.test(schedule.id), JSON.stringify(schedule))
    assert(schedule.enabled === false, JSON.stringify(schedule))
    assert(schedule.trigger?.type === 'ONCE'
      && schedule.trigger.localDate === '2099-01-01'
      && schedule.trigger.time === '12:00', JSON.stringify(schedule.trigger))
    assert(schedule.target?.type === 'NEW_SESSION'
      && schedule.target.folderId === folderId
      && schedule.target.agentName === 'default-assistant', JSON.stringify(schedule.target))
    const listed = await json(ctx, 'GET', `/api/chat-spaces/${spaceId}/schedules`)
    assert(Array.isArray(listed) && listed.some((item) => item.id === schedule.id), JSON.stringify(listed))

    const expectedRuns = []
    for (let index = 0; index < 6; index++) {
      const run = await json(ctx, 'POST', `/api/chat-spaces/${spaceId}/schedules/${schedule.id}/runs`, undefined,
        { 'Idempotency-Key': key() })
      assert(UUID.test(run.id), JSON.stringify(run))
      if (index === 0) {
        assert(run.result === 'SUBMITTED', JSON.stringify(run))
      } else if (run.result === 'SKIPPED') {
        assert(run.reason === 'EXECUTION_NOT_SETTLED', JSON.stringify(run))
      } else {
        assert(run.result === 'SUBMITTED', JSON.stringify(run))
      }
      if (run.result === 'SUBMITTED') {
        assert(UUID.test(run.thread?.id) && UUID.test(run.thread?.sessionId), JSON.stringify(run.thread))
      } else {
        assert(run.thread == null, JSON.stringify(run.thread))
      }
      expectedRuns.push(run)
      // Keep the server's timestamp order unambiguous between manual runs.
      await new Promise((resolve) => setTimeout(resolve, 20))
    }

    const expectedIds = expectedRuns
      .toSorted((a, b) => b.scheduledAt - a.scheduledAt || b.id.localeCompare(a.id))
      .map((run) => run.id)
    const firstPage = await json(ctx, 'GET', `/api/chat-spaces/${spaceId}/schedules/${schedule.id}/runs`)
    assert(firstPage.items.length === 5, JSON.stringify(firstPage))
    assert(firstPage.olderCursor, JSON.stringify(firstPage))
    assert(firstPage.items.map((run) => run.id).join(',') === expectedIds.slice(0, 5).join(','),
      `newest-page IDs/order differ from manual run responses: ${JSON.stringify(firstPage.items.map((run) => run.id))}`)

    const olderPage = await json(ctx, 'GET',
      `/api/chat-spaces/${spaceId}/schedules/${schedule.id}/runs?before=${encodeURIComponent(firstPage.olderCursor)}`)
    assert(olderPage.items.length === 1, JSON.stringify(olderPage))
    assert(olderPage.olderCursor == null, JSON.stringify(olderPage))
    assert(olderPage.items[0].id === expectedIds[5],
      `older-page ID differs from manual run responses: ${JSON.stringify(olderPage.items[0])}`)
    const repeatedFirstPage = await json(ctx, 'GET', `/api/chat-spaces/${spaceId}/schedules/${schedule.id}/runs`)
    assert(JSON.stringify(repeatedFirstPage) === JSON.stringify(firstPage), 'schedule page changed during read-only pagination')
  },
})

registerCase({
  id: 'workspace.two_user_resource_isolation',
  level: 'L1',
  title: '双用户工作区资源、交互与实时订阅隔离',
  docs: '两个独立普通用户各自可访问自己的会话和画布；另一用户对会话、画布和交互根收到精确 RESOURCE_NOT_FOUND，对 chat-space 订阅收到对应错误帧',
  async run(ctx) {
    const first = await ctx.createUser()
    const second = await ctx.createUser()
    const firstSpace = await first.createSpace(`e2e-owner-a-${key().slice(0, 8)}`)
    const secondSpace = await second.createSpace(`e2e-owner-b-${key().slice(0, 8)}`)
    const firstTree = await json(first, 'GET', `/api/chat-spaces/${firstSpace}/tree`)
    const secondTree = await json(second, 'GET', `/api/chat-spaces/${secondSpace}/tree`)
    const firstSession = await createSession(first, firstSpace, firstTree.folders[0].id, `owner-a-${key()}`)
    const secondSession = await createSession(second, secondSpace, secondTree.folders[0].id, `owner-b-${key()}`)
    const firstCanvas = await createCanvas(first, firstSpace, 'owner-a canvas')
    const secondCanvas = await createCanvas(second, secondSpace, 'owner-b canvas')

    for (const [owner, spaceId, session, canvasId] of [
      [first, firstSpace, firstSession, firstCanvas],
      [second, secondSpace, secondSession, secondCanvas],
    ]) {
      const ownThread = await json(owner, 'GET', `/api/harness/threads/${session.threadId}`)
      assert(ownThread.thread?.threadId === session.threadId, JSON.stringify(ownThread))
      const ownCanvas = await json(owner, 'GET', `/api/canvas/canvases/${canvasId}`)
      assert(ownCanvas.document?.id === canvasId, JSON.stringify(ownCanvas))
      const ownInteractions = await json(owner, 'GET', `/api/harness/interactions?rootThreadId=${session.threadId}`)
      assert(Array.isArray(ownInteractions.items), JSON.stringify(ownInteractions))
      const ownTree = await json(owner, 'GET', `/api/chat-spaces/${spaceId}/tree`)
      assert(ownTree, 'owner could not read own space tree')
    }

    for (const [other, session, canvasId, spaceId] of [
      [second, firstSession, firstCanvas, firstSpace],
      [first, secondSession, secondCanvas, secondSpace],
    ]) {
      for (const [path, label] of [
        [`/api/harness/threads/${session.threadId}`, 'session thread'],
        [`/api/canvas/canvases/${canvasId}`, 'canvas'],
        [`/api/harness/interactions?rootThreadId=${session.threadId}`, 'interaction root'],
        [`/api/chat-spaces/${spaceId}/tree`, 'chat-space tree'],
      ]) {
        const denied = await request(other, 'GET', path, undefined, {}, true)
        const reason = label === 'chat-space tree' ? 'WORKSPACE_NOT_FOUND' : 'RESOURCE_NOT_FOUND'
        assert(denied.status === 404 && denied.json?.reason === reason,
          `cross-user ${label} must return 404 ${reason}, got ${denied.status}: ${denied.text}`)
      }
    }

    const { socket: ownSocket, frames: ownFrames } = openSpaceSocket(first)
    try {
      await waitForSocketOpen(ownSocket)
      ownSocket.send(JSON.stringify({ type: 'subscribe', resource: { kind: 'chat-space', id: firstSpace } }))
      const ack = await waitForFrame(ownFrames, (frame) => frame?.type === 'subscribed'
        && frame?.resource?.kind === 'chat-space' && frame?.resource?.id === firstSpace)
      assert(Object.keys(ack).sort().join(',') === 'cursor,resource,type'
        && Object.keys(ack.resource).sort().join(',') === 'id,kind'
        && ack.cursor === '0', JSON.stringify(ack))
    } finally {
      ownSocket.close(1000, 'L1 isolation check complete')
    }

    const { socket: deniedSocket, frames: deniedFrames } = openSpaceSocket(second)
    try {
      await waitForSocketOpen(deniedSocket)
      deniedSocket.send(JSON.stringify({ type: 'subscribe', resource: { kind: 'chat-space', id: firstSpace } }))
      const denial = await waitForFrame(deniedFrames, (frame) => frame?.type === 'error')
      assert(Object.keys(denial).sort().join(',') === 'code,message,resource,type'
        && Object.keys(denial.resource ?? {}).sort().join(',') === 'id,kind'
        && denial.type === 'error'
        && denial.code === 'RESOURCE_NOT_FOUND'
        && denial.message === 'Resource not found'
        && denial.resource.kind === 'chat-space'
        && denial.resource.id === firstSpace,
      `expected exact RESOURCE_NOT_FOUND subscription rejection, got ${JSON.stringify(denial)}`)
    } finally {
      if (deniedSocket.readyState === WebSocket.OPEN) deniedSocket.close(1000, 'L1 isolation check complete')
    }
  },
})
