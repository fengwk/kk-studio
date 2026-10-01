import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadStatusFooter } from '@/features/ai/runtime/thread-panel/ThreadStatusFooter'

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
    expect(screen.getByLabelText('会话状态')).toHaveTextContent('ctx —/128k')
  })

  it('renders zero usage facts when no closed-turn usage exists', () => {
    render(<ThreadStatusFooter />)
    const footer = screen.getByLabelText('会话状态')
    const lines = [...footer.querySelectorAll('.thread-status-line')]
    expect(lines).toHaveLength(2)
    expect(lines[0].textContent).toBe('none env')
    expect(lines[1].textContent).toBe('↑0 · ↓0 · $0.000 · cache — · — tok/s')
    expect(footer).not.toHaveTextContent('ctx')
    expect(footer.querySelector('button')).toBeNull()
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
    expect(footer).toHaveTextContent('env:dev (unavailable)')
  })

  // 验证闭合回合存在完整数据时，两行分别渲染环境/上下文与包含中点/cache/speed的统一用量摘要
  it('renders environment and context on first line, and unified usage summary on second line', () => {
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
    expect(lines).toHaveLength(2)
    expect(lines[0].textContent).toBe('env:local · ctx 61/128k')
    expect(lines[1].textContent).toBe('↑30 · ↓9 · R14 · W17 · $0.500 · cache 23% · 18 tok/s')
    expect(lines[0]).toHaveAttribute(
      'title',
      'env:local · 最新模型调用已知上下文输入估计：61 / 128000 tokens（非待发请求精确值）',
    )
    expect(lines[1]).toHaveAttribute(
      'title',
      '分支累计用量（含首次请求，cache 为累计输入缓存命中率）：↑30 · ↓9 · R14 · W17 · $0.500 · cache 23% · 18 tok/s',
    )
    expect(footer.querySelector('button')).toBeNull()
  })

  // 验证闭合回合为空时，零用量与无样本空态规范渲染（分母为0显示cache —，无样本显示— tok/s）
  it('renders zero usage, context, cache placeholder, and speed placeholder when closed-turn totals are empty', () => {
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
    const lines = [...screen.getByLabelText('会话状态').querySelectorAll('.thread-status-line')]
    expect(lines).toHaveLength(2)
    expect(lines[0].textContent).toBe('none env · ctx 0/128k')
    expect(lines[1].textContent).toBe('↑0 · ↓0 · $0.000 · cache — · — tok/s')
    expect(lines[0]).toHaveAttribute(
      'title',
      'none env · 最新模型调用已知上下文输入估计：0 / 128000 tokens（非待发请求精确值）',
    )
    expect(lines[1]).toHaveAttribute(
      'title',
      '分支累计用量（含首次请求，cache 为累计输入缓存命中率）：↑0 · ↓0 · $0.000 · cache — · — tok/s',
    )
  })
})
