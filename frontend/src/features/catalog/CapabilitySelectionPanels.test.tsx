import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  CapabilitySelectionPanel,
  type CapabilityDirectory,
} from '@/features/catalog/CapabilitySelectionPanels'
import type { CapabilityOption } from '@/features/catalog/agent-capability-candidates'

/** 跨分组候选 + 一个已配置但目录中已缺失的旧值。 */
const options: CapabilityOption[] = [
  { value: 'read', name: 'read', description: 'Read local files', group: 'BUILTIN' },
  { value: 'mcp_search', name: 'mcp_search', description: 'Search the web', group: 'MCP' },
  { value: 'lost:old', name: 'lost / old', description: null, offline: true, missing: true },
]

const ready: CapabilityDirectory = { status: 'ready', retry: () => undefined }

interface PanelProps {
  options?: CapabilityOption[]
  selected?: string[]
  directory?: CapabilityDirectory
  error?: string
}

function renderPanel(initial: PanelProps = {}) {
  const onToggle = vi.fn()
  const tree = (props: PanelProps) => (
    <CapabilitySelectionPanel
      label="Tools"
      searchLabel="搜索工具"
      options={props.options ?? options}
      selected={props.selected ?? []}
      emptyText="目录还没有工具"
      directory={props.directory ?? ready}
      error={props.error}
      onToggle={onToggle}
    />
  )
  const view = render(tree(initial))
  return { onToggle, rerender: (props: PanelProps) => view.rerender(tree(props)) }
}

describe('CapabilitySelectionPanel', () => {
  it('shows the selected count and searchable choices without an extra action', () => {
    renderPanel({ selected: ['read'] })
    expect(screen.getByRole('group', { name: /^Tools.*1/ })).toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: '搜索工具' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'BUILTIN' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'MCP' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /read/ })).toBeChecked()
  })

  // 搜索只缩小可见候选；分组标题和已选值都留在用户可观察状态中。
  it('narrows candidates by search while keeping group identity and selection', async () => {
    const user = userEvent.setup()
    const { onToggle } = renderPanel({ selected: ['read'] })

    await user.type(screen.getByRole('searchbox', { name: '搜索工具' }), 'web')
    expect(screen.getByRole('checkbox', { name: /mcp_search/ })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /read/ })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'MCP' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'BUILTIN' })).not.toBeInTheDocument()
    expect(onToggle).not.toHaveBeenCalled()

    fireEvent.change(screen.getByRole('searchbox', { name: '搜索工具' }), {
      target: { value: '' },
    })
    expect(screen.getByRole('checkbox', { name: /read/ })).toBeChecked()
  })

  // 「已选」筛选与搜索互不覆盖，切换筛选不改变勾选值。
  it('keeps search text and selection while switching the selected-only filter', async () => {
    const user = userEvent.setup()
    const { onToggle } = renderPanel({ selected: ['read'] })

    const search = screen.getByRole('searchbox', { name: '搜索工具' })
    await user.type(search, 'read')
    await user.click(screen.getByRole('button', { name: '已选' }))
    expect(screen.getByRole('button', { name: '已选' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '全部' })).toHaveAttribute('aria-pressed', 'false')
    expect(search).toHaveValue('read')
    expect(screen.getByRole('checkbox', { name: /read/ })).toBeChecked()

    // 清空搜索后筛选仍然生效：未选中的候选不回来。
    fireEvent.change(search, { target: { value: '' } })
    expect(screen.queryByRole('checkbox', { name: /mcp_search/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /lost \/ old/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '全部' }))
    expect(screen.getByRole('checkbox', { name: /mcp_search/ })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /read/ })).toBeChecked()
    expect(onToggle).not.toHaveBeenCalled()
  })

  // 目录还没读到时，「已配置但不在目录里」不能算不可用，读完后才标记并可取消。
  it('keeps a selected orphan removable without marking it unavailable until the directory is ready', async () => {
    const user = userEvent.setup()
    const { onToggle, rerender } = renderPanel({
      selected: ['lost:old'],
      directory: { status: 'loading', retry: () => undefined },
    })

    const orphan = screen.getByRole('checkbox', { name: /lost \/ old/ })
    expect(orphan).toBeChecked()
    expect(screen.queryByText('不可用')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('正在加载资源')

    rerender({ selected: ['lost:old'], directory: ready })
    expect(screen.getByText('不可用')).toBeInTheDocument()
    await user.click(orphan)
    expect(onToggle).toHaveBeenCalledWith('lost:old')
  })

  // 读取失败要给出可重试的明确入口，失败期间同样不能把旧值判成不可用。
  it('surfaces a load failure with a retry that the panel forwards', async () => {
    const user = userEvent.setup()
    const retry = vi.fn()
    renderPanel({ selected: ['lost:old'], directory: { status: 'error', retry } })

    expect(screen.getByRole('alert')).toHaveTextContent('资源加载失败')
    expect(screen.queryByText('不可用')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(retry).toHaveBeenCalledTimes(1)
  })

  // 「目录为空」与「搜索无匹配」是两种不同结论，提示文案不能混用。
  it('distinguishes an empty catalog from a search without matches', async () => {
    const user = userEvent.setup()
    const { rerender } = renderPanel({ options: [] })

    expect(screen.getByText('目录还没有工具')).toBeInTheDocument()
    expect(screen.queryByText('没有匹配项')).not.toBeInTheDocument()

    rerender({ options })
    await user.type(screen.getByRole('searchbox', { name: '搜索工具' }), 'zzz')
    expect(screen.getByText('没有匹配项')).toBeInTheDocument()
    expect(screen.queryByText('目录还没有工具')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  // 校验错误与面板同处一个分组，并带错误语义。
  it('renders the field error inside the panel', () => {
    renderPanel({ error: '请至少选择一个 Tools' })

    expect(screen.getByRole('group', { name: /^Tools/ })).toHaveClass('is-error')
    expect(screen.getByText('请至少选择一个 Tools')).toBeInTheDocument()
  })
})
