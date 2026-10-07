import assert from 'node:assert/strict'
import test from 'node:test'

import { sleep } from '../lib/http.mjs'
import { ControlledCompletionsMock } from '../cases/thread-queued-batch.mjs'

const MARKERS = {
  initialMarker: 'QUEUE-INITIAL-UNIT',
  firstMarker: 'QUEUE-FIRST-UNIT',
  secondMarker: 'QUEUE-SECOND-UNIT',
  initialReply: 'E2E_QUEUE_INITIAL_REPLY UNIT',
  batchedReply: 'E2E_QUEUE_BATCHED_REPLY UNIT',
}

function messages(...texts) {
  return { messages: texts.map((text) => ({ role: 'user', content: text })) }
}

async function post(mock, body) {
  return fetch(`${mock.baseUrl('/v1')}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/** 只把 request#1 保持 open 的 mock 起好，并确认首个请求处于 held 且 fetch 未完成。 */
async function holdFirst(mock) {
  await mock.start()
  const held = post(mock, messages(MARKERS.initialMarker))
  const settlement = await Promise.race([
    held.then(() => 'settled'),
    sleep(150).then(() => 'pending'),
  ])
  assert.equal(settlement, 'pending', 'first request must stay open until releaseFirst')
  assert.equal(mock.requests.length, 1)
  assert.equal(mock.requests[0].state, 'held')
  // mock 只记录 {index, body, state}，绝不保留 headers/token 等敏感材料。
  assert.deepEqual(
    Object.keys(mock.requests[0]).sort(),
    ['body', 'index', 'state'],
    JSON.stringify(mock.requests[0]),
  )
  // 必须用对象包住 held：async 函数直接 return 一个 pending Promise 会被 await 采用而阻塞调用方。
  return { held }
}

test('queue mock holds the first request and releases it with initialReply', async () => {
  // 测试意图：真实本地 HTTP mock 证明首个请求保持 open，releaseFirst 后返回 initialReply。
  const mock = new ControlledCompletionsMock(MARKERS)
  try {
    const { held } = await holdFirst(mock)
    mock.releaseFirst()
    const response = await held
    assert.equal(response.status, 200)
    const text = await response.text()
    assert.ok(text.includes(MARKERS.initialReply), text)
    assert.equal(mock.requests[0].state, 'completed')
  } finally {
    await mock.close()
  }
})

test('queue mock accepts the whole snapshot only when both markers appear in order', async () => {
  // 测试意图：request#2 必须同时携带 first 与 second 且顺序正确，才返回 batchedReply。
  const mock = new ControlledCompletionsMock(MARKERS)
  try {
    const { held } = await holdFirst(mock)
    mock.releaseFirst()
    await (await held).text()
    const response = await post(mock, messages(MARKERS.firstMarker, MARKERS.secondMarker))
    assert.equal(response.status, 200)
    const text = await response.text()
    assert.ok(text.includes(MARKERS.batchedReply), text)
    assert.equal(mock.requests[1].state, 'completed')
  } finally {
    await mock.close()
  }
})

test('queue mock rejects first-only, reversed and unexpected extra requests', async () => {
  // 测试意图：错误收割语义（首条后截断、顺序颠倒、多余的第 3 个请求）都必须确定性 400。
  for (const scenario of ['first-only', 'reversed', 'extra']) {
    const mock = new ControlledCompletionsMock(MARKERS)
    try {
      const { held } = await holdFirst(mock)
      mock.releaseFirst()
      await (await held).text()
      if (scenario === 'first-only') {
        const response = await post(mock, messages(MARKERS.firstMarker))
        assert.equal(response.status, 400, 'first-only harvest must be rejected')
        await response.text()
        continue
      }
      if (scenario === 'reversed') {
        const response = await post(mock, messages(MARKERS.secondMarker, MARKERS.firstMarker))
        assert.equal(response.status, 400, 'reversed history order must be rejected')
        await response.text()
        continue
      }
      const accepted = await post(mock, messages(MARKERS.firstMarker, MARKERS.secondMarker))
      assert.equal(accepted.status, 200)
      await accepted.text()
      const extra = await post(mock, messages(MARKERS.firstMarker, MARKERS.secondMarker))
      assert.equal(extra.status, 400, 'a third model request must be rejected')
      await extra.text()
    } finally {
      await mock.close()
    }
  }
})
