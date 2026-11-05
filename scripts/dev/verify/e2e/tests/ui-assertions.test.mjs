import assert from 'node:assert/strict'
import test from 'node:test'

import { assertReadOnlyZeroFooter, waitForSettledAssistantText } from '../ui/assertions.mjs'

test('空 Footer 使用未知费用占位，并保留零用量与只读断言', async () => {
  // 零 token 不意味着零费用，且 Footer 不能出现可操作按钮。
  const scope = (text, buttons = 0) => ({
    getByLabel: () => ({
      waitFor: async () => {},
      innerText: async () => text,
      getByRole: () => ({ count: async () => buttons }),
    }),
  })
  const line = '未选择环境 ∣ ↑0 · ↓0 · R0 · W0 · — · cache 0.00% · — tok/s'
  await assertReadOnlyZeroFooter(scope(line), 'Footer')
  await assert.rejects(() => assertReadOnlyZeroFooter(scope(line.replace('W0', 'W1')), 'Footer'))
  await assert.rejects(() => assertReadOnlyZeroFooter(scope(line, 1), 'Footer'), /read-only/)
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
