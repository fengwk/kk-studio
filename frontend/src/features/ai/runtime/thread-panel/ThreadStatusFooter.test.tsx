import { act, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadStatusFooter } from '@/features/ai/runtime/thread-panel/ThreadStatusFooter'
import { setLocale } from '@/shared/i18n'

describe('ThreadStatusFooter', () => {
  // 缺失最新调用上下文时，累计输入不能伪装成单次上下文占用。
  it('does not substitute aggregate usage for an unknown context estimate', () => {
    render(
      <ThreadStatusFooter
        branchUsage={{
          input: 300_000, output: 9, cacheRead: 10, cacheWrite: 0,
          reasoning: 0, providerTotal: 300_019, cost: 0.5,
        }}
        contextWindow={128_000}
      />,
    )
    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('ctx —/128k')
    // 未知即如实说明无数据，绝不用分支累计输入填充该缺口。
    expect(lineOf(footer).getAttribute('title')).toContain(
      '上次请求上下文：暂无数据（上限 128000 tokens）',
    )
  })

  // 全部事实必须落在唯一一行，避免 Footer 因数据增多重新折行。
  it('renders all facts on a single line without wrapping', () => {
    render(<ThreadStatusFooter />)
    const footer = screen.getByLabelText('会话状态')
    const lines = [...footer.querySelectorAll('.thread-status-line')]
    expect(lines).toHaveLength(1)
    expect(lines[0].textContent).toBe('未选择环境 | ↑0 | ↓0 | $0.000 | cache — | — tok/s')
    expect(footer).not.toHaveTextContent('ctx')
    expect(footer.querySelector('button')).toBeNull()
  })

  // 每段事实保留独立 span，保证精确文本定位不被分隔符或相邻字段干扰。
  it('keeps every fact addressable inside its own span', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-local-id', environmentName: 'local' }}
        environmentReady
        contextWindow={128_000}
        branchUsage={{
          input: 30, output: 9, cacheRead: 14, cacheWrite: 17,
          reasoning: 0, providerTotal: 70, cost: 0.5,
          decodeTokens: 9, decodeDurationMillis: 500, contextInputTokens: 61,
        }}
      />,
    )
    const footer = screen.getByLabelText('会话状态')
    const spans = [...footer.querySelectorAll('.thread-status-line span')]
    expect(spans.map((span) => span.textContent)).toEqual([
      'env:local',
      'ctx 61/128k',
      '↑30 | ↓9 | R14 | W17 | $0.500 | cache 23% | 18 tok/s',
    ])
  })

  it('renders Environment identity as readonly facts', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-local-id', environmentName: 'local' }}
        environmentReady
      />,
    )

    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('env:local')
    expect(footer.querySelector('button')).toBeNull()
    expect(lineOf(footer).getAttribute('title')).toContain('环境：local')
  })

  // 验证在缺失 environmentName 时，规范回退为显示 environmentId 作为身份标识。
  it('falls back to environmentId when environmentName is omitted', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-uuid-42' }}
        environmentReady
      />,
    )

    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('env:env-uuid-42')
  })

  it('marks an unavailable binding without replacing it', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-dev-id', environmentName: 'dev' }}
        environmentReady={false}
      />,
    )

    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('env:dev（不可用）')
    expect(lineOf(footer).getAttribute('title')).toContain('环境：dev（不可用）')
  })

  // 验证单行按 | 连接环境/上下文/用量，hover 用简明读数完整保留全部累计事实。
  it('joins environment, context and usage with pipes and keeps a readable hover readout', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-local-id', environmentName: 'local' }}
        environmentReady
        branchUsage={{
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
        }}
        contextWindow={128_000}
      />,
    )

    const footer = screen.getByLabelText('会话状态')
    const lines = [...footer.querySelectorAll('.thread-status-line')]
    expect(lines).toHaveLength(1)
    expect(lines[0].textContent).toBe(
      'env:local | ctx 61/128k | ↑30 | ↓9 | R14 | W17 | $0.500 | cache 23% | 18 tok/s',
    )
    expect(lines[0]).toHaveAttribute(
      'title',
      [
        '环境：local',
        '上次请求上下文：约 61 / 128000 tokens',
        '累计用量',
        '未缓存输入：30 tokens；输出：9 tokens',
        '缓存读取：14 tokens；写入：17 tokens',
        '费用：$0.500；缓存命中：23%',
        '平均生成速度：18 tok/s',
      ].join('\n'),
    )
    // 不照抄可见缩写，也不解释内部口径（分母、是否含首次请求）。
    expect(lines[0].getAttribute('title')).not.toContain('含首次请求')
    expect(lines[0].getAttribute('title')).not.toContain('非待发请求精确值')
    expect(footer.querySelector('button')).toBeNull()
  })

  // 零用量/无测速样本时，hover 仍给出完整数字并把缺失项标注为暂无数据。
  it('renders zero usage, context and localized no-data placeholders on one line', () => {
    render(
      <ThreadStatusFooter
        branchUsage={{
          input: 0,
          output: 0,
          cacheRead: 0,
          cacheWrite: 0,
          reasoning: 0,
          providerTotal: 0,
          cost: 0,
          decodeTokens: null,
          decodeDurationMillis: null,
          contextInputTokens: 0,
        }}
        contextWindow={128_000}
      />,
    )
    const footer = screen.getByLabelText('会话状态')
    const lines = [...footer.querySelectorAll('.thread-status-line')]
    expect(lines).toHaveLength(1)
    expect(lines[0].textContent).toBe(
      '未选择环境 | ctx 0/128k | ↑0 | ↓0 | $0.000 | cache — | — tok/s',
    )
    expect(lines[0]).toHaveAttribute(
      'title',
      [
        '未选择环境',
        '上次请求上下文：约 0 / 128000 tokens',
        '累计用量',
        '未缓存输入：0 tokens；输出：0 tokens',
        '缓存读取：0 tokens；写入：0 tokens',
        '费用：$0.000；缓存命中：暂无数据',
        '平均生成速度：暂无数据',
      ].join('\n'),
    )
  })

  it('updates visible status and hover readouts when switching to English', () => {
    const { rerender } = render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-dev-id', environmentName: 'dev' }}
        environmentReady={false}
        contextWindow={128_000}
      />,
    )

    act(() => setLocale('en-US'))
    const line = lineOf(screen.getByLabelText('Thread status'))
    expect(line.textContent).toBe(
      'env:dev (unavailable) | ctx —/128k | ↑0 | ↓0 | $0.000 | cache — | — tok/s',
    )
    expect(line).toHaveAttribute('title', [
      'Environment: dev (unavailable)',
      'Last request context: no data (limit 128000 tokens)',
      'Cumulative usage',
      'Uncached input: 0 tokens; output: 0 tokens',
      'Cache read: 0 tokens; write: 0 tokens',
      'Cost: $0.000; cache hit: No data',
      'Average generation speed: No data',
    ].join('\n'))

    rerender(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-dev-id', environmentName: 'dev' }}
        environmentReady
        contextWindow={128_000}
        branchUsage={{
          input: 30, output: 9, cacheRead: 14, cacheWrite: 17,
          reasoning: 0, providerTotal: 70, cost: 0.5,
          decodeTokens: 9, decodeDurationMillis: 500, contextInputTokens: 61,
        }}
      />,
    )
    expect(line).toHaveAttribute('title', [
      'Environment: dev',
      'Last request context: ~61 / 128000 tokens',
      'Cumulative usage',
      'Uncached input: 30 tokens; output: 9 tokens',
      'Cache read: 14 tokens; write: 17 tokens',
      'Cost: $0.500; cache hit: 23%',
      'Average generation speed: 18 tok/s',
    ].join('\n'))
  })
})

function lineOf(footer: HTMLElement): HTMLElement {
  return footer.querySelector('.thread-status-line') as HTMLElement
}
