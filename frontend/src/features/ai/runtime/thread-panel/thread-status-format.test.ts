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

  it('uses the approval label for both waiting-approval status names', () => {
    // WAITING_APPROVAL 与 TOOL_WAITING_APPROVAL 对用户都是等待决策，不能显示成正在运行。
    expect(formatThreadStatusLabel('WAITING_APPROVAL')).toBe('等待审批')
    expect(formatThreadStatusLabel('TOOL_WAITING_APPROVAL')).toBe('等待审批')
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

  // 已知与未知上下文必须区分表述：不能把缺失值说成精确值，也不能伪造成有数据。
  it('describes a known context estimate as approximate without leaking internal caveats', () => {
    const context = buildThreadStatusModel({
      contextWindow: 128_000,
      branchUsage: { ...EMPTY_USAGE, contextInputTokens: 61 },
    }).segments.find((segment) => segment.key === 'context')

    expect(context?.title).toBe('上次请求上下文：约 61 / 128000 tokens')
  })

  it('marks an unknown context estimate as no data with the window upper bound', () => {
    const context = buildThreadStatusModel({ contextWindow: 128_000 }).segments
      .find((segment) => segment.key === 'context')

    expect(context?.title).toBe('上次请求上下文：暂无数据（上限 128000 tokens）')
  })

  // 累计用量 hover 复用与可见摘要同源的 cache/速率，未知即标注暂无数据。
  it('builds a concise cumulative readout from full numbers and shared calculations', () => {
    const usage = buildThreadStatusModel({
      contextWindow: 128_000,
      branchUsage: {
        input: 30,
        output: 9,
        cacheRead: 14,
        cacheWrite: 17,
        reasoning: 0,
        providerTotal: 70,
        cost: 0.5,
        decodeTokens: 9,
        decodeDurationMillis: 500,
        contextInputTokens: 61,
      },
    }).segments.find((segment) => segment.key === 'usage')

    expect(usage?.text).toBe('↑30 | ↓9 | R14 | W17 | $0.500 | cache 23% | 18 tok/s')
    expect(usage?.title).toBe(
      [
        '累计用量',
        '未缓存输入：30 tokens；输出：9 tokens',
        '缓存读取：14 tokens；写入：17 tokens',
        '费用：$0.500；缓存命中：23%',
        '平均生成速度：18 tok/s',
      ].join('\n'),
    )
  })
})

const EMPTY_USAGE = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  reasoning: 0,
  providerTotal: 0,
  cost: 0,
}
