import assert from 'node:assert/strict'
import test from 'node:test'

import { assertReadOnlyZeroFooter, waitForSettledAssistantText } from '../ui/assertions.mjs'

function footerScope(text, buttons = 0) {
  return {
    getByLabel(label) {
      assert.equal(label, '会话状态')
      return {
        async waitFor(options) {
          assert.deepEqual(options, { state: 'visible', timeout: 10_000 })
        },
        async innerText() { return text },
        getByRole(role) {
          assert.equal(role, 'button')
          return { async count() { return buttons } }
        },
      }
    },
  }
}

test('只读零用量 Footer 固定展示缓存读写和无币种默认零值', async () => {
  await assertReadOnlyZeroFooter(footerScope(
    '未选择环境 ∣ ctx 0/205k ∣ ↑0 · ↓0 · R0 · W0 · 0 · cache 0% · 0 tok/s',
  ), 'Footer')
})

test('只读零用量 Footer 拒绝缺项、旧占位和交互按钮', async () => {
  for (const text of [
    '未选择环境 ∣ ↑0 · ↓0 · — · cache — · — tok/s',
    '未选择环境 ∣ ↑0 · ↓0 · R0 · 0 · cache 0% · 0 tok/s',
    '未选择环境 ∣ ↑0 · ↓0 · R0 · W0 · $0 · cache 0% · 0 tok/s',
  ]) {
    await assert.rejects(() => assertReadOnlyZeroFooter(footerScope(text), 'Footer'), /missing/)
  }
  await assert.rejects(() => assertReadOnlyZeroFooter(footerScope(
    '未选择环境 ∣ ↑0 · ↓0 · R0 · W0 · 0 · cache 0% · 0 tok/s', 1,
  ), 'Footer'), /must stay read-only/)
})

test('真实 UI 回复断言只读取最终正文并等待 Working 消失', async () => {
  // 测试意图：防止 thinking 复述提示词中的期望文本时，真实模型 UI case 在终态前误报成功。
  const waits = []
  const textLocator = {
    last() {
      return this
    },
    async waitFor(options) {
      waits.push(['text', options])
    },
    async innerText() {
      return '  OK  '
    },
  }
  const workingLocator = {
    async waitFor(options) {
      waits.push(['working', options])
    },
  }
  const scope = {
    locator(selector) {
      if (selector === '.thread-turn-assistant .thread-assistant-text') {
        return textLocator
      }
      if (selector === '.thread-working') {
        return workingLocator
      }
      throw new Error(`unexpected selector: ${selector}`)
    },
  }

  const text = await waitForSettledAssistantText(scope, 1234)

  assert.equal(text, 'OK')
  assert.deepEqual(waits, [
    ['text', { state: 'visible', timeout: 1234 }],
    ['working', { state: 'detached', timeout: 1234 }],
  ])
})
