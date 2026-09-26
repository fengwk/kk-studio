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
    expect(footer).toHaveTextContent('none env')
    expect(footer).toHaveTextContent('↑0 · ↓0 · $0.000')
    expect(footer).toHaveTextContent('cache —')
    expect(footer).toHaveTextContent('— tok/s')
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

  // 验证环境、上下文、累计用量、缓存率、速率按全左对齐稳定顺序渲染
  it('renders usage, context estimate, cache hit rate, and tok/s in stable readonly order', () => {
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

    const segments = [...screen.getByLabelText('会话状态').querySelectorAll('.thread-status-seg')]
    expect(segments.map((segment) => segment.textContent)).toEqual([
      'env:local',
      'ctx 61/128k',
      '↑30 · ↓9 · R14 · W17 · $0.500',
      'cache 23%',
      '18 tok/s',
    ])
    expect(screen.getByLabelText('会话状态').querySelector('button')).toBeNull()
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
    const segments = [...screen.getByLabelText('会话状态').querySelectorAll('.thread-status-seg')]
    expect(segments.map((segment) => segment.textContent)).toEqual([
      'none env',
      'ctx 0/128k',
      '↑0 · ↓0 · $0.000',
      'cache —',
      '— tok/s',
    ])
  })
})
