import assert from 'node:assert/strict'
import test from 'node:test'

import { waitForSettledAssistantText } from '../ui/assertions.mjs'

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
