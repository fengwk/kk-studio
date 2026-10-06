import { act, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadStatusFooter } from '@/features/ai/runtime/thread-panel/ThreadStatusFooter'
import { setLocale } from '@/shared/i18n'

/**
 * hover 只读明细按段分行：环境 / 上下文 / 用量（token、cache、费用）共 5 行，且没有冗余标题行。
 * 逐字文案由 catalog 拥有（新 key 见交付报告），此处只验证 Footer 的接线与结构；
 * 文案组合本身在 thread-status-format.test.ts 用注入式 t 做确定性验证。
 */
function hoverLines(element: HTMLElement): string[] {
  return (element.getAttribute('title') ?? '').split('\n')
}

describe('ThreadStatusFooter', () => {
  // 缺失最新调用上下文时，累计输入不能伪装成单次上下文占用。
  it('does not substitute aggregate usage for an unknown context estimate', () => {
    render(
      <ThreadStatusFooter
        branchUsage={{
          input: 300_000, output: 9, cacheRead: 10, cacheWrite: 0,
          reasoning: 0, providerTotal: 300_019, cost: { currency: 'USD', amount: '0.5' },
        }}
        contextWindow={128_000}
      />,
    )
    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('ctx —/128k')
    const title = hoverLines(lineOf(footer))
    expect(title).toHaveLength(5)
    expect(title[0]).toBe('未选择环境')
    // 未知即如实说明无数据：分支累计输入 300000 绝不能冒充上下文占用。
    // 上下文段如实标注无数据，累计用量只在独立的用量段里以完整数字出现。
    expect(title[1]).toContain('暂无数据')
    expect(title[1]).not.toContain('300000')
    expect(title[2]).toContain('300000')
  })

  // 全部事实必须落在唯一一行，避免 Footer 因数据增多重新折行。
  it('renders all facts on a single line without wrapping', () => {
    render(<ThreadStatusFooter />)
    const footer = screen.getByLabelText('会话状态')
    const lines = [...footer.querySelectorAll('.thread-status-line')]
    expect(lines).toHaveLength(1)
    // 无可用定价显示 "—"，绝不伪装成 $0
    expect(lines[0].textContent).toBe('未选择环境 ∣ ↑0 · ↓0 · — · cache — · — tok/s')
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
          reasoning: 0, providerTotal: 70, cost: { currency: 'USD', amount: '0.5' },
          decodeTokens: 9, decodeDurationMillis: 500, contextInputTokens: 61,
        }}
      />,
    )
    const footer = screen.getByLabelText('会话状态')
    const spans = [...footer.querySelectorAll('.thread-status-line span')]
    expect(spans.map((span) => span.textContent)).toEqual([
      'local',
      'ctx 61/128k',
      '↑30 · ↓9 · R14 · W17 · $0.5 · cache 23% · 18 tok/s',
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
    expect(footer).toHaveTextContent('local')
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
    expect(footer).toHaveTextContent('env-uuid-42')
  })

  it('marks an unavailable binding without replacing it', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-dev-id', environmentName: 'dev' }}
        environmentReady={false}
      />,
    )

    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('dev（不可用）')
    expect(lineOf(footer).getAttribute('title')).toContain('环境：dev（不可用）')
  })

  // 单行按 | 连接环境/上下文/用量；hover 用完整数字与全称字段，且没有冗余的累计标题。
  it('joins facts with pipes and exposes a full-number hover readout', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-local-id', environmentName: 'local' }}
        environmentReady
        branchUsage={{
          input: 30,
          output: 9,
          cacheRead: 14,
          cacheWrite: 17,
          reasoning: 4,
          providerTotal: 70,
          cost: { currency: 'USD', amount: '0.5' },
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
      'local ∣ ctx 61/128k ∣ ↑30 · ↓9 · R14 · W17 · $0.5 · cache 23% · 18 tok/s',
    )
    const title = hoverLines(lines[0])
    // 环境 + 上下文 + 用量两行（token/cache）+ 费用行：既无冗余的累计标题行，也没有被压缩成一行。
    expect(title).toHaveLength(5)
    expect(title[0]).toBe('环境：local')
    // 不照抄可见缩写，也不解释内部口径（分母、是否含首次请求）。
    expect(lines[0].getAttribute('title')).not.toContain('含首次请求')
    expect(lines[0].getAttribute('title')).not.toContain('非待发请求精确值')
    expect(lines[0].getAttribute('title')).not.toContain('累计')
    expect(footer.querySelector('button')).toBeNull()
  })

  // 零用量/无定价/无测速样本时，hover 仍给出完整数字并把缺失项标注为暂无数据。
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
          cost: null,
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
      '未选择环境 ∣ ctx 0/128k ∣ ↑0 · ↓0 · — · cache — · — tok/s',
    )
    // 零用量/无定价/无测速样本时 hover 仍有完整 5 行结构（缺失项由 catalog 文案标注暂无数据）。
    expect(hoverLines(lines[0])).toHaveLength(5)
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
      'dev (unavailable) ∣ ctx —/128k ∣ ↑0 · ↓0 · — · cache — · — tok/s',
    )
    const unavailableTitle = hoverLines(line)
    expect(unavailableTitle).toHaveLength(5)
    expect(unavailableTitle[0]).toBe('Environment: dev (unavailable)')

    rerender(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-dev-id', environmentName: 'dev' }}
        environmentReady
        contextWindow={128_000}
        branchUsage={{
          input: 30, output: 9, cacheRead: 14, cacheWrite: 17,
          reasoning: 0, providerTotal: 70, cost: { currency: 'USD', amount: '0.5' },
          decodeTokens: 9, decodeDurationMillis: 500, contextInputTokens: 61,
        }}
      />,
    )
    const readyTitle = hoverLines(line)
    expect(readyTitle).toHaveLength(5)
    expect(readyTitle[0]).toBe('Environment: dev')
    expect(line.textContent).toBe('dev ∣ ctx 61/128k ∣ ↑30 · ↓9 · R14 · W17 · $0.5 · cache 23% · 18 tok/s')
  })
})

function lineOf(footer: HTMLElement): HTMLElement {
  return footer.querySelector('.thread-status-line') as HTMLElement
}
