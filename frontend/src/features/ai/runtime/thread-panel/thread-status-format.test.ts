import { describe, expect, it } from 'vitest'
import {
  buildThreadStatusModel,
  formatThreadStatusLabel,
  type TranslateFn,
} from '@/features/ai/runtime/thread-panel/thread-status-format'
import type { UsageCost } from '@/features/ai/runtime/thread-timeline-types'

/**
 * 待 parent 落库的新 catalog 文案（本切片只引用 key）：用注入式 t 做确定性验证，
 * 保证 hover 组合正确，而不依赖 catalog 是否已合入。
 */
const MESSAGES: Record<string, string> = {
  'ai.runtime.status.noData': '暂无数据',
  'ai.runtime.status.contextText': 'ctx {{used}}/{{total}}',
  'ai.runtime.status.contextUsageTitleKnown':
    '上下文占用（最近一次调用估算）：约 {{used}} / {{total}} tokens',
  'ai.runtime.status.contextUsageTitleUnknown': '上下文占用：暂无数据（上限 {{total}} tokens）',
  'ai.runtime.status.usageTokensDetail':
    '无缓存输入：{{input}} tokens；输出：{{output}} tokens；推理：{{reasoning}} tokens',
  'ai.runtime.status.usageCacheDetail': '缓存读取：{{cacheRead}} tokens；缓存写入：{{cacheWrite}} tokens',
  'ai.runtime.status.usageCostDetail': '估算费用：{{cost}}；缓存命中率：{{cache}}；生成速度：{{speed}}',
}

const t: TranslateFn = (key, values) =>
  (MESSAGES[key] ?? `⟦missing:${key}⟧`).replace(
    /\{\{\s*([\w.-]+)\s*\}\}/gu,
    (_match, name: string) => String(values?.[name] ?? ''),
  )

const EMPTY_USAGE = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  reasoning: 0,
  providerTotal: 0,
  cost: null as UsageCost | null,
}

describe('thread status formatting', () => {
  // 状态模型格式调整不能改变工作状态的本地化映射及未知状态回退。
  it.each([
    'STOPPED', 'QUEUED', 'IDLE', 'CONTINUATION_DUE', 'APPLYING',
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
    expect(formatThreadStatusLabel('WAITING_APPROVAL')).toBe('等待审批')
    expect(formatThreadStatusLabel('TOOL_WAITING_APPROVAL')).toBe('等待审批')
  })

  // 缺失或无效窗口不能制造上下文占用事实；用量摘要仍应稳定显示。
  it.each([undefined, 0, -1, NaN, Infinity])('omits invalid context window %s', (contextWindow) => {
    const model = buildThreadStatusModel({ contextWindow }, t)
    expect(model.segments.map((segment) => segment.key)).toEqual(['environment', 'usage'])
  })

  // 不同数量级的窗口沿用 token 缩写规范，不能随 Footer 布局重构改变精度。
  it.each([
    [999, '999'],
    [1000, '1.0k'],
    [10000, '10k'],
    [1000000, '1.0M'],
  ])('formats context window %s as %s', (contextWindow, text) => {
    const context = buildThreadStatusModel({ contextWindow }, t).segments
      .find((segment) => segment.key === 'context')
    expect(context?.text).toBe(`ctx —/${text}`)
  })

  // 上下文占用：已知/未知必须区分，且只使用最近一次调用的估计，绝不用累计输入冒充
  it('labels the context occupancy estimate and its unknown state', () => {
    const known = buildThreadStatusModel({
      contextWindow: 128_000,
      branchUsage: { ...EMPTY_USAGE, contextInputTokens: 61 },
    }, t).segments.find((segment) => segment.key === 'context')
    expect(known?.title).toBe('上下文占用（最近一次调用估算）：约 61 / 128000 tokens')

    const unknown = buildThreadStatusModel({ contextWindow: 128_000 }, t).segments
      .find((segment) => segment.key === 'context')
    expect(unknown?.title).toBe('上下文占用：暂无数据（上限 128000 tokens）')
  })

  // hover：完整数字 + 全称字段（含推理），无冗余的累计标题；主行保持紧凑
  it('builds a full-number readout without a redundant cumulative title', () => {
    const usage = buildThreadStatusModel({
      contextWindow: 128_000,
      branchUsage: {
        input: 30,
        output: 9,
        cacheRead: 14,
        cacheWrite: 17,
        reasoning: 4,
        providerTotal: 70,
        cost: { currency: 'USD', amount: '0.500000000000' },
        decodeTokens: 9,
        decodeDurationMillis: 500,
        contextInputTokens: 61,
      },
    }, t).segments.find((segment) => segment.key === 'usage')

    expect(usage?.text).toBe('↑30 · ↓9 · R14 · W17 · $0.5 · cache 23% · 18 tok/s')
    expect(usage?.title).toBe(
      [
        '无缓存输入：30 tokens；输出：9 tokens；推理：4 tokens',
        '缓存读取：14 tokens；缓存写入：17 tokens',
        '估算费用：$0.5；缓存命中率：23%；生成速度：18 tok/s',
      ].join('\n'),
    )
    expect(usage?.title).not.toContain('累计')
  })

  // 缺失定价/测速样本时如实标注暂无数据，绝不伪造成 $0
  it('reports missing price and speed as no-data instead of a fake zero', () => {
    const usage = buildThreadStatusModel({
      branchUsage: { ...EMPTY_USAGE, contextInputTokens: 0 },
    }, t).segments.find((segment) => segment.key === 'usage')

    expect(usage?.text).toBe('↑0 · ↓0 · — · cache — · — tok/s')
    expect(usage?.title).toContain('估算费用：暂无数据；缓存命中率：暂无数据；生成速度：暂无数据')
  })
})
