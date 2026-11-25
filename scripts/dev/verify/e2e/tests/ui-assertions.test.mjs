import assert from 'node:assert/strict'
import test from 'node:test'

import { assertReadOnlyZeroFooter, waitForSettledAssistantText } from '../ui/assertions.mjs'

test('空 Footer 使用未知费用占位，并保留零用量与只读断言（双语自适应）', async () => {
  // 零 token 不意味着零费用，且 Footer 不能出现可操作按钮，且不硬编码单一界面语言。
  const requestedLabels = []
  const scope = (text, buttons = 0) => ({
    getByLabel: (matcher) => {
      requestedLabels.push(matcher)
      return {
        waitFor: async () => {},
        innerText: async () => text,
        getByRole: () => ({ count: async () => buttons }),
      }
    },
  })
  const zhLine = '未选择环境 ∣ ↑0 · ↓0 · R0 · W0 · — · cache 0.00% · — tok/s'
  const enLine = 'No environment ∣ ↑0 · ↓0 · R0 · W0 · — · cache 0.00% · — tok/s'
  await assertReadOnlyZeroFooter(scope(zhLine), 'Footer zh')
  await assertReadOnlyZeroFooter(scope(enLine), 'Footer en')
  assert.ok(requestedLabels.every((matcher) => matcher instanceof RegExp && matcher.test('Thread status') && matcher.test('Thread 状态')))
  await assert.rejects(() => assertReadOnlyZeroFooter(scope(enLine.replace('W0', 'W1')), 'Footer'))
  await assert.rejects(() => assertReadOnlyZeroFooter(scope(enLine, 1), 'Footer'), /read-only/)
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
