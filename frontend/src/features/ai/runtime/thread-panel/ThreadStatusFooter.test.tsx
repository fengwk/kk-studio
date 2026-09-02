import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadStatusFooter } from '@/features/ai/runtime/thread-panel/ThreadStatusFooter'
import { middleEllipsis } from '@/features/ai/runtime/thread-panel/thread-status-format'

describe('ThreadStatusFooter', () => {
  it('renders zero usage facts when no closed-turn usage exists', () => {
    render(<ThreadStatusFooter />)
    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('none env')
    expect(footer).toHaveTextContent('↑0 · ↓0 · $0.000')
    expect(footer).toHaveTextContent('cache 0%')
    expect(footer).not.toHaveTextContent('ctx')
    expect(footer.querySelector('button')).toBeNull()
  })

  it('renders Environment/Workspace and Git as readonly facts', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-local-id', environmentName: 'local', workspacePath: 'proj/a' }}
        environmentReady
        gitBranch="feature/footer"
      />,
    )

    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('env:local · @/proj/a')
    expect(footer).toHaveTextContent('git:feature/footer')
    expect(footer.querySelector('button')).toBeNull()
  })

  // 验证在缺失 environmentName 时，规范回退为显示 environmentId 作为身份标识。
  it('falls back to environmentId when environmentName is omitted', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-uuid-42', workspacePath: 'proj/sub' }}
        environmentReady
      />,
    )

    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('env:env-uuid-42 · @/proj/sub')
  })

  it('marks an unavailable binding without replacing it or exposing an absolute path', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-dev-id', environmentName: 'dev', workspacePath: '.' }}
        environmentReady={false}
      />,
    )

    const footer = screen.getByLabelText('会话状态')
    expect(footer).toHaveTextContent('env:dev · @/ (unavailable)')
    expect(footer.textContent).not.toContain('/home/')
  })

  it('uses middle ellipsis in text while keeping the complete safe path in title', () => {
    const path = 'projects/very-long-directory-name/packages/runtime/thread-panel'
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-dev-id', environmentName: 'dev', workspacePath: path }}
        environmentReady
      />,
    )

    const segment = screen.getByLabelText('会话状态').querySelector('.thread-status-environment .thread-status-seg')
    expect(segment).toHaveTextContent('…')
    expect(segment).toHaveAttribute('title', `env:dev · @/${path}`)
    expect(middleEllipsis('abcdefghij', 7)).toBe('abc…hij')
  })

  it('renders usage, context estimate, and cache hit rate in stable readonly order', () => {
    render(
      <ThreadStatusFooter
        environment={{ environmentId: 'env-local-id', environmentName: 'local', workspacePath: '.' }}
        environmentReady
        gitBranch="main"
        branchUsage={{
          input: 30,
          output: 9,
          cacheRead: 14,
          cacheWrite: 17,
          reasoning: 0,
          providerTotal: 70,
          cost: 0.5,
        }}
        contextWindow={128_000}
      />,
    )

    const segments = [...screen.getByLabelText('会话状态').querySelectorAll('.thread-status-seg')]
    expect(segments.map((segment) => segment.textContent)).toEqual([
      'env:local · @/',
      'git:main',
      '↑30 · ↓9 · R14 · W17 · $0.500',
      'ctx 61/128k',
      'cache 23%',
    ])
    expect(screen.getByLabelText('会话状态').querySelector('button')).toBeNull()
  })

  it('renders zero usage, context, and cache when closed-turn totals are empty', () => {
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
        }}
        contextWindow={128_000}
      />,
    )
    const segments = [...screen.getByLabelText('会话状态').querySelectorAll('.thread-status-seg')]
    expect(segments.map((segment) => segment.textContent)).toEqual([
      'none env',
      '↑0 · ↓0 · $0.000',
      'ctx 0/128k',
      'cache 0%',
    ])
  })
})
