import { describe, expect, it } from 'vitest'
import {
  buildThreadStatusModel,
  formatThreadStatusLabel,
} from '@/features/ai/runtime/thread-panel/thread-status-format'

describe('thread status formatting', () => {
  // 状态模型格式调整不能改变工作状态的本地化映射及未知状态回退。
  it.each([
    'WAITING_CHILDREN', 'QUEUED', 'IDLE', 'CONTINUATION_DUE', 'APPLYING',
    'MODEL_READY', 'MODEL_DISPATCHING', 'MODEL_RUNNING', 'TOOL_WAITING_APPROVAL',
    'TOOL_READY', 'TOOL_RUNNING',
  ])('localizes %s through its runtime status key', (status) => {
    expect(formatThreadStatusLabel(status, (key) => `translated:${key}`))
      .toBe(`translated:ai.runtime.thread.status.${status}`)
  })

  it.each([null, undefined, ''])('omits an absent status: %s', (status) => {
    expect(formatThreadStatusLabel(status)).toBe('')
  })

  it('preserves an unknown status without inventing a localized state', () => {
    expect(formatThreadStatusLabel('UNRECOGNIZED')).toBe('UNRECOGNIZED')
  })

  // 缺失或无效窗口不能制造上下文占用事实；用量摘要仍应稳定显示。
  it.each([undefined, 0, -1, NaN, Infinity])('omits invalid context window %s', (contextWindow) => {
    const model = buildThreadStatusModel({ contextWindow })
    expect(model.segments.map((segment) => segment.key)).toEqual(['environment', 'usage'])
  })

  // 不同数量级的窗口沿用 token 缩写规范，不能随 Footer 布局重构改变精度。
  it.each([
    [999, '999'],
    [1000, '1.0k'],
    [10000, '10k'],
    [1000000, '1.0M'],
  ])('formats context window %s as %s', (contextWindow, text) => {
    const context = buildThreadStatusModel({ contextWindow }).segments
      .find((segment) => segment.key === 'context')
    expect(context?.text).toBe(`ctx —/${text}`)
  })
})
