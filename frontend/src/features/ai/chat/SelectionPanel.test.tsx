import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  AgentSelectionPanel,
  SelectionPanel,
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

  it('lists agents', async () => {
    render(
      <AgentSelectionPanel
        agents={[{ name: 'assistant', description: 'desc' }]}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByRole('option', { name: /assistant/ })).toBeInTheDocument()
  })

  it('matches multi-token search across subtitle, badge, and searchText', async () => {
    const user = userEvent.setup()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[
          { id: 's1', title: 'Alpha', subtitle: 'main', searchText: 'hidden-key' },
          { id: 's2', title: 'Beta', subtitle: 'feature', badge: 'env-prod' },
          { id: 's3', title: 'Gamma', subtitle: null },
        ]}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    // 多 token 是 AND 语义，且搜索在 title 之外还覆盖 subtitle/badge/searchText。
    await user.type(screen.getByRole('searchbox'), 'feature env')
    expect(screen.getByRole('option', { name: /Beta/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Alpha/ })).not.toBeInTheDocument()

    await user.clear(screen.getByRole('searchbox'))
    await user.type(screen.getByRole('searchbox'), 'HIDDEN-KEY')
    // searchText 命中且大小写不敏感。
    expect(screen.getByRole('option', { name: /Alpha/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Beta/ })).not.toBeInTheDocument()
  })

  it('shows loading state without options and keeps the search disabled during a pending selection', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[
          { id: 's1', title: 'Alpha' },
          { id: 's2', title: 'Beta' },
        ]}
        loading
        selectionPending
        onSelect={onSelect}
        onClose={vi.fn()}
      />,
    )
    const panel = screen.getByRole('region', { name: '选择 Session' })
    expect(panel).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText('加载中…')).toBeInTheDocument()
    // loading 时不渲染任何 option，搜索与提交都被 pending 锁住。
    expect(screen.queryByRole('option')).not.toBeInTheDocument()
    expect(screen.getByRole('searchbox')).toBeDisabled()
    await user.keyboard('{Enter}')
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('filters while loading and shows the no-match empty state after load completes', async () => {
    const user = userEvent.setup()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[]}
        loading
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    // loading 期间仍可输入查询；查询只影响加载完成后的过滤结果。
    await user.type(screen.getByRole('searchbox'), 'missing')
    expect(screen.getByText('加载中…')).toBeInTheDocument()
    expect(screen.queryByText(/没有匹配/)).not.toBeInTheDocument()
  })

  it('cycles the sort control with Tab, refocuses search, and shows the hint in the footer', async () => {
    const user = userEvent.setup()
    const onCycleControl = vi.fn()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[
          { id: 's1', title: 'Alpha' },
          { id: 's2', title: 'Beta' },
        ]}
        onCycleControl={onCycleControl}
        cycleControlHint="Tab 切换排序"
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    await user.tab()
    expect(onCycleControl).toHaveBeenCalledTimes(1)
    // Tab 循环排序后焦点回到搜索框，而非继续在面板内顺序移动。
    await waitFor(() => expect(screen.getByRole('searchbox')).toHaveFocus())
    expect(screen.getByText('2 / 2 · ↑↓ 选择 · Enter 确认 · Esc 返回 · Tab 切换排序')).toBeInTheDocument()
    // 未提供 cycleControlHint 时不渲染排序提示。
    const { unmount } = render(
      <SelectionPanel
        title="选择 Session"
        items={[{ id: 's1', title: 'Alpha' }]}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByText('1 / 1 · ↑↓ 选择 · Enter 确认 · Esc 返回')).toBeInTheDocument()
    unmount()
  })

  it('navigates with PageUp/PageDown and Home/End within the filtered list', async () => {
    const user = userEvent.setup()
    render(
      <SelectionPanel
        title="选择 Session"
        items={Array.from({ length: 10 }, (_, index) => ({
          id: `s${index + 1}`,
          title: `Item ${index + 1}`,
        }))}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    const initial = screen.getByRole('option', { name: /^Item 1$/ })
    expect(initial).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{PageDown}')
    // Page 步长 8：从 s1 前进到 s9。
    const item9 = screen.getByRole('option', { name: /Item 9/ })
    expect(item9).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{PageUp}')
    const item1 = screen.getByRole('option', { name: /^Item 1$/ })
    expect(item1).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{End}')
    const item10 = screen.getByRole('option', { name: /Item 10/ })
    expect(item10).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{Home}')
    expect(item1).toHaveAttribute('aria-selected', 'true')
    // PageDown 在尾部环绕到列表头部。
    await user.keyboard('{PageDown}')
    expect(item9).toHaveAttribute('aria-selected', 'true')
  })

  it('marks the current selection with aria-current and renders badge/subtitle', () => {
    render(
      <SelectionPanel
        title="选择 Session"
        items={[{ id: 's1', title: 'Alpha', badge: 'env-prod' }]}
        selectedId="s1"
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    const option = screen.getByRole('option', { name: /Alpha/ })
    expect(option).toHaveAttribute('aria-current', 'true')
    expect(within(option).getByText('env-prod')).toBeInTheDocument()
    expect(within(option).getByText('已选')).toBeInTheDocument()
  })

  it('activates a row rename button with Enter without selecting the row', async () => {
    // 行内重命名按钮拥有 Enter；事件不能冒泡成 SelectionPanel 的 active-row 提交。
    const user = userEvent.setup()
    const onRename = vi.fn()
    const onSelect = vi.fn()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[{ id: 's1', title: 'Alpha' }]}
        renameLabel="重命名"
        onRename={onRename}
        onSelect={onSelect}
        onClose={vi.fn()}
      />,
    )
    const rename = screen.getByRole('button', { name: '重命名' })
    rename.focus()

    await user.keyboard('{Enter}')

    expect(onRename).toHaveBeenCalledWith('s1')
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('falls back to the previously selected item when it reappears after filtering', async () => {
    const user = userEvent.setup()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[
          { id: 's1', title: 'Alpha', subtitle: 'core' },
          { id: 's2', title: 'Beta', subtitle: 'feature' },
        ]}
        selectedId="s2"
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    // 初始 active 回退到 selectedId=s2。
    expect(screen.getByRole('option', { name: /Beta/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    // 过滤掉 s2 后 active 回退到过滤结果的第一项；清空查询后 s2 重新可见，
    // active 稳定保持为过滤结果的第一项（s1），不会跳回 selectedId。
    await user.type(screen.getByRole('searchbox'), 'core')
    expect(screen.getByRole('option', { name: /Alpha/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.clear(screen.getByRole('searchbox'))
    expect(screen.getByRole('option', { name: /Alpha/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('leaves the active item untouched when keyboard navigation has no items', async () => {
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
    // 空列表下的所有方向键都不产生异常或选择。
    await user.keyboard('{ArrowDown}{PageDown}{Home}{End}{Enter}')
    expect(screen.getByText('空列表')).toBeInTheDocument()
  })

  it('allows composition input (IME) to pass through without acting on the draft keys', () => {
    const onClose = vi.fn()
    render(
      <SelectionPanel
        title="选择 Session"
        items={[{ id: 's1', title: 'Alpha' }]}
        onSelect={vi.fn()}
        onClose={onClose}
      />,
    )
    // 组合输入（keyCode 229）被判定为不完整的字符输入：不得触发选择/关闭。
    fireEvent.keyDown(screen.getByRole('region', { name: '选择 Session' }), {
      key: 'Escape',
      keyCode: 229,
    })
    expect(onClose).not.toHaveBeenCalled()
  })
})
