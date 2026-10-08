import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { SubagentTreePanel } from './SubagentTreePanel'
import { ThreadNavigationContext } from './thread-navigation-context'
import { projectThreadTree, type ActiveThreadTreeNode } from './thread-panel/active-thread-tree'
import type { useActiveThreadTree } from './useActiveThreadTree'

const node = (threadId: string, parentThreadId: string | null, overrides: Partial<ActiveThreadTreeNode> = {}): ActiveThreadTreeNode => ({
  threadId, parentThreadId, agentName: 'Explorer', name: 'main',
  status: 'IDLE', processing: false, outcome: null, updateTime: 1774958400,
  turnCount: 7, toolCallCount: 12,
  model: { providerName: 'provider', modelName: 'model', variant: 'default' },
  ...overrides,
})

function tree(nodes = [
  node('root', null), node('parent', 'root'),
  node('child', 'parent', { name: 'Review', outcome: 'FAILED' }),
]): ReturnType<typeof useActiveThreadTree> {
  return {
    ...projectThreadTree(nodes), rootThreadId: 'root', activeCount: 0,
    isLoading: false, isError: false, refreshFailed: false, refetch: vi.fn(),
  }
}

describe('subagent execution selector', () => {
  it('renders all real history nodes as flat cards, excluding the execution root', () => {
    const { container } = render(<MemoryRouter><SubagentTreePanel tree={tree()} currentThreadId="child" /></MemoryRouter>)
    expect(screen.getAllByRole('link')).toHaveLength(2)
    expect(container.querySelector('[data-thread-id="root"]')).toBeNull()
    expect(container.querySelector('.thread-tree-connectors')).toBeNull()
    expect(container.querySelector('[data-thread-id="child"]')).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('link', { name: /Review/ })).toHaveAttribute('href', '/threads/child')
    expect(screen.getByRole('link', { name: /Review/ })).toHaveTextContent('失败')
    expect(screen.getByRole('link', { name: /Review/ })).toHaveTextContent('turns: 7 · tools: 12')
    expect(screen.getByRole('link', { name: /Review/ })).toHaveTextContent('provider/model')
  })

  it('moves selection by keyboard, clamps boundaries and opens the real selected link', async () => {
    const open = vi.fn()
    const close = vi.fn()
    const user = userEvent.setup()
    render(<MemoryRouter><ThreadNavigationContext.Provider value={open}>
      <SubagentTreePanel tree={tree()} onClose={close} />
    </ThreadNavigationContext.Provider></MemoryRouter>)
    const panel = screen.getByRole('region')
    expect(panel).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('link', { name: /Review/ })).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('link', { name: /Review/ })).toHaveFocus()
    await user.keyboard('{ArrowUp}{ArrowUp}')
    expect(screen.getAllByRole('link')[0]).toHaveFocus()
    panel.focus()
    await user.keyboard('{Enter}')
    expect(open).toHaveBeenCalledWith('parent')
    expect(close).toHaveBeenCalledOnce()
  })

  it('keeps selection by threadId when live rows reorder and falls back when removed', () => {
    const open = vi.fn()
    const initial = tree()
    const view = (value: ReturnType<typeof useActiveThreadTree>) => <MemoryRouter>
      <ThreadNavigationContext.Provider value={open}>
        <SubagentTreePanel tree={value} currentThreadId="child" />
      </ThreadNavigationContext.Provider>
    </MemoryRouter>
    const host = render(view(initial))
    const reordered = { ...initial, historyRows: [...initial.historyRows].reverse() }
    host.rerender(view(reordered))
    fireEvent.keyDown(screen.getByRole('region'), { key: 'Enter' })
    expect(open).toHaveBeenLastCalledWith('child')
    host.rerender(view(tree([node('root', null), node('new', 'root', { processing: true })])))
    fireEvent.keyDown(screen.getByRole('region'), { key: 'Enter' })
    expect(open).toHaveBeenLastCalledWith('new')
  })

  it('ignores IME/repeated Escape/modified Enter, stops handled keys and restores focus on close', () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const close = vi.fn()
    const bubble = vi.fn()
    const host = render(<MemoryRouter><div onKeyDown={bubble}>
      <SubagentTreePanel tree={tree()} onClose={close} />
    </div></MemoryRouter>)
    const panel = screen.getByRole('region')
    for (const key of ['Escape', 'ArrowDown', 'Enter']) {
      fireEvent.keyDown(panel, { key, isComposing: true })
      fireEvent.keyDown(panel, { key, keyCode: 229 })
    }
    fireEvent.keyDown(panel, { key: 'Escape', repeat: true })
    fireEvent.keyDown(panel, { key: 'Enter', ctrlKey: true })
    expect(close).not.toHaveBeenCalled()
    bubble.mockClear()
    fireEvent.keyDown(panel, { key: 'Escape' })
    expect(close).toHaveBeenCalledOnce()
    expect(bubble).not.toHaveBeenCalled()
    host.unmount()
    expect(trigger).toHaveFocus()
    trigger.remove()
  })

  it('preserves standard modified links without closing or pane interception', () => {
    const close = vi.fn()
    const open = vi.fn()
    render(<MemoryRouter><ThreadNavigationContext.Provider value={open}>
      <SubagentTreePanel tree={tree()} onClose={close} />
    </ThreadNavigationContext.Provider></MemoryRouter>)
    fireEvent.click(screen.getAllByRole('link')[0], { ctrlKey: true })
    expect(open).not.toHaveBeenCalled()
    expect(close).not.toHaveBeenCalled()
    fireEvent.click(screen.getAllByRole('link')[0])
    expect(open).toHaveBeenCalledWith('parent')
    expect(close).toHaveBeenCalledOnce()
  })

  it('retains refresh errors and retry, loading and empty states without inventing executions', () => {
    const initial = tree([])
    const host = render(<MemoryRouter><SubagentTreePanel tree={initial} /></MemoryRouter>)
    expect(screen.getByText('暂无 subagent 执行')).toBeInTheDocument()
    fireEvent.keyDown(screen.getByRole('region'), { key: 'ArrowDown' })
    fireEvent.keyDown(screen.getByRole('region'), { key: 'Enter' })
    host.rerender(<MemoryRouter><SubagentTreePanel tree={{ ...initial, isLoading: true }} /></MemoryRouter>)
    expect(screen.getByRole('status')).toHaveTextContent('正在加载会话')
    host.rerender(<MemoryRouter><SubagentTreePanel tree={{ ...initial, isError: true }} /></MemoryRouter>)
    expect(screen.getByRole('alert')).toHaveTextContent('Agent 关系加载失败')
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(initial.refetch).toHaveBeenCalledOnce()
    host.rerender(<MemoryRouter><SubagentTreePanel tree={{ ...initial, isError: true, refreshFailed: true }} /></MemoryRouter>)
    expect(screen.getByRole('alert')).toHaveTextContent('Agent 关系刷新失败')
    fireEvent.click(screen.getByRole('button', { name: '关闭' }))
  })
})
