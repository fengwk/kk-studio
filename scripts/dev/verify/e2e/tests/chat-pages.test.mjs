import assert from 'node:assert/strict'
import test from 'node:test'

import { listChats, listChatSessions } from '../lib/harness.mjs'

const summary = (id) => ({
  sessionId: id,
  name: 'Session',
  createdAt: 1,
  lastActivityAt: 2,
  firstMessagePreview: null,
  threadCount: 1,
})

test('Chat 与 Session 摘要按 cursor 读取全部页面并保持顺序', async () => {
  // 通过两页及需要 URL 编码的 cursor，验证调用方不会漏掉后页。
  for (const [list, path, first, second] of [
    [listChats, '/api/chat/chats', { id: 'first' }, { id: 'second' }],
    [
      (ctx) => listChatSessions(ctx, 'chat/id'),
      '/api/chat/chats/chat%2Fid/sessions',
      summary('00000000-0000-4000-8000-000000000001'),
      summary('00000000-0000-4000-8000-000000000002'),
    ],
  ]) {
    const requests = []
    const ctx = {
      async call(method, url) {
        requests.push([method, url])
        return {
          status: 200,
          json: requests.length === 1
            ? { items: [first], nextCursor: 'next+/=' }
            : { items: [second], nextCursor: null },
        }
      },
    }
    assert.deepEqual(await list(ctx), [first, second])
    assert.deepEqual(requests, [
      ['GET', path],
      ['GET', `${path}?cursor=next%2B%2F%3D`],
    ])
  }
})

test('Chat 分页拒绝错误响应和无效游标', async () => {
  // 合同缺字段时不能误判为最后一页，更不能把无效状态当成成功。
  for (const response of [
    { status: 500, json: { items: [], nextCursor: null } },
    { status: 200, json: [] },
    { status: 200, json: { items: [] } },
    { status: 200, json: { items: [], nextCursor: '' } },
  ]) {
    await assert.rejects(() => listChats({ call: async () => response }))
  }
})

test('Session 每页均校验摘要', async () => {
  // 后续页面也必须经过与首页一致的 DTO 校验。
  let calls = 0
  await assert.rejects(() => listChatSessions({
    async call() {
      calls += 1
      return { status: 200, json: calls === 1
        ? { items: [summary('00000000-0000-4000-8000-000000000001')], nextCursor: 'next' }
        : { items: [summary('bad-id')], nextCursor: null } }
    },
  }, 'chat'), /session\.sessionId/)
})
