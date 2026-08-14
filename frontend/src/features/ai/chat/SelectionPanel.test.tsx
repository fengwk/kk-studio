import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  AgentSelectionPanel,
  EnvironmentSelectionPanel,
  SelectionPanel,
  ThreadSelectionPanel,
} from '@/features/ai/chat/SelectionPanel'

describe('SelectionPanel', () => {
  it('focuses search, filters by typing, and confirms the active item with Enter', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[
          { id: 's1', title: 'Alpha', subtitle: 'main' },
          { id: 's2', title: 'Beta', subtitle: 'feature' },
        ]}
        onSelect={onSelect}
        onClose={vi.fn()}
      />,
    )
    const search = screen.getByRole('searchbox', { name: '搜索' })
    await waitFor(() => expect(search).toHaveFocus())

    await user.type(search, 'feature')
    const panel = screen.getByRole('region', { name: '选择 Session' })
    expect(within(panel).queryByRole('option', { name: /Alpha/ })).not.toBeInTheDocument()
    expect(within(panel).getByRole('option', { name: /Beta/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.keyboard('{Enter}')
    expect(onSelect).toHaveBeenCalledWith('s2')
  })

  it('navigates with arrows and exits with Escape without a backdrop', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[
          { id: 's1', title: 'Alpha' },
          { id: 's2', title: 'Beta' },
        ]}
        selectedId="s1"
        onSelect={vi.fn()}
        onClose={onClose}
      />,
    )
    expect(document.querySelector('.modal-backdrop')).toBeNull()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('option', { name: /Beta/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    fireEvent.mouseEnter(screen.getByRole('option', { name: /Alpha/ }))
    expect(screen.getByRole('option', { name: /Beta/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    fireEvent.mouseMove(screen.getByRole('option', { name: /Alpha/ }))
    expect(screen.getByRole('option', { name: /Alpha/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('yields keyboard ownership to an active confirmation modal', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <>
        <SelectionPanel
          title="选择 Session"
          items={[
            { id: 's1', title: 'Alpha' },
            { id: 's2', title: 'Beta' },
          ]}
          selectedId="s1"
          onSelect={vi.fn()}
          onClose={onClose}
        />
        <div role="alertdialog" aria-label="确认操作" />
      </>,
    )

    await user.keyboard('{ArrowDown}{Escape}')
    expect(screen.getByRole('option', { name: /Alpha/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(onClose).not.toHaveBeenCalled()
  })

  it('shows an empty state for a filtered list', async () => {
    const user = userEvent.setup()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[]}
        emptyText="空列表"
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByText('空列表')).toBeInTheDocument()
    await user.type(screen.getByRole('searchbox'), 'missing')
    expect(screen.getByText('没有匹配“missing”的选项')).toBeInTheDocument()
  })

  it('supports Thread sort buttons and the keyboard Tab cycle', async () => {
    const user = userEvent.setup()
    const onSortChange = vi.fn()
    render(
      <ThreadSelectionPanel
        items={[
          { id: 't1', title: 'Thread 1' },
          { id: 't2', title: 'Thread 2' },
        ]}
        sort="recent"
        onSortChange={onSortChange}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    await user.click(screen.getByRole('button', { name: '创建时间' }))
    expect(onSortChange).toHaveBeenCalledWith('created')
    onSortChange.mockClear()
    await user.click(screen.getByRole('searchbox'))
    await user.keyboard('{Tab}')
    expect(onSortChange).toHaveBeenCalledWith('created')
  })

  it('lists agents and only READY environments', async () => {
    const user = userEvent.setup()
    const { rerender } = render(
      <AgentSelectionPanel
        agents={[{ name: 'assistant', description: 'desc' }]}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByRole('option', { name: /assistant/ })).toBeInTheDocument()

    rerender(
      <EnvironmentSelectionPanel
        environments={[
          { name: 'local', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
          { name: 'booting', ready: false, status: 'CONNECTING', lastSeen: null, tools: [], skills: [] },
        ]}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByRole('option', { name: /^local/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /booting/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^local/ }))
  })
})
