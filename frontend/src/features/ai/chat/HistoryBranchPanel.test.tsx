import type { ComponentProps } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'

const branchEntries: HarnessSessionEntryDTO[] = [
  { entryId: 'root', parentEntryId: null, entryType: 'ROOT', payloadJson: '{}', createTime: null },
  { entryId: 'user', parentEntryId: 'root', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"USER","contents":[{"type":"text","text":"user prompt"}]}}', createTime: null },
  { entryId: 'assistant', parentEntryId: 'user', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"type":"text","text":"assistant reply"}]}}', createTime: null },
  { entryId: 'tool', parentEntryId: 'assistant', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"TOOL","contents":[{"type":"text","text":"tool result"}]}}', createTime: null },
  { entryId: 'tool2', parentEntryId: 'assistant', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"TOOL","contents":[{"type":"text","text":"another tool result"}]}}', createTime: null },
  { entryId: 'follow-up', parentEntryId: 'tool', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"type":"text","text":"follow up"}]}}', createTime: null },
  { entryId: 'follow-up-2', parentEntryId: 'tool2', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"type":"text","text":"follow up two"}]}}', createTime: null },
]

describe('HistoryBranchPanel', () => {
  it('selects the current head by default and rebinds only after explicit confirmation', async () => {
    const user = userEvent.setup()
    const onRebind = vi.fn()
    renderPanel({ onRebind, currentHeadEntryId: 'follow-up' })

    expect(screen.getByRole('region', { name: '历史分支' })).toBeInTheDocument()
    expect(document.querySelector('.modal-backdrop')).toBeNull()
    expect(screen.queryByText('选择历史位置后开启新的 Thread，当前 Thread 不会改变。')).not.toBeInTheDocument()
    // 确认会重定位*当前* Thread；不再保留创建新 Thread 的文案。
    expect(screen.queryByRole('button', { name: /开启新 Thread/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/当前分支 head/)).not.toBeInTheDocument()
    expect(within(screen.getByLabelText('显示记录')).getAllByRole('option').map((option) => option.textContent)).toEqual([
      '对话',
      '全部记录',
    ])
    expect(screen.queryByRole('button', { name: /系统/ })).not.toBeInTheDocument()
    const headButton = screen.getByRole('button', { name: '助手 · follow up · 当前路径 · 当前线程位置' })
    expect(within(headButton).getByText('当前线程位置')).toBeInTheDocument()
    expect(headButton).toHaveAttribute('aria-pressed', 'true')
    expect(
      Array.from(headButton.children).slice(0, 4).map((element) => element.className),
    ).toEqual([
      'history-branch-entry-cursor',
      'history-branch-entry-glyphs',
      'history-branch-entry-path',
      'history-branch-entry-kind',
    ])
    expect(headButton.querySelector('.history-branch-entry-cursor')).toHaveTextContent('›')
    expect(headButton.querySelector('.history-branch-entry-path')).toHaveTextContent('•')
    expect(screen.getByText('3 / 4 · ↑↓ 选择 · Enter 确认 · Esc 返回')).toBeInTheDocument()
    expect(onRebind).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))
    expect(onRebind).toHaveBeenCalledTimes(1)
    const rebindEntry = onRebind.mock.calls[0][0]
    expect(rebindEntry.entryId).toBe('follow-up')
  })

  it('re-attaches the selection to the nearest visible ancestor when the current selection becomes hidden', async () => {
    const user = userEvent.setup()
    const onRebind = vi.fn()
    renderPanel({ onRebind, currentHeadEntryId: 'follow-up' })

    await user.selectOptions(screen.getByLabelText('显示记录'), 'all')
    await user.click(screen.getByRole('button', { name: /工具 · tool result/ }))
    expect(screen.getByRole('button', { name: '从这里继续当前 Thread' })).toBeEnabled()

    // 对话视图会隐藏 tool。选中 tool 最近的可见原始祖先是 assistant。
    await user.selectOptions(screen.getByLabelText('显示记录'), 'conversation')
    expect(screen.queryByText('tool result')).not.toBeInTheDocument()
    const confirm = screen.getByRole('button', { name: '从这里继续当前 Thread' })
    expect(confirm).toBeEnabled()
    await user.click(confirm)
    expect(onRebind).toHaveBeenCalledTimes(1)
    expect(onRebind.mock.calls[0][0].entryId).toBe('assistant')
  })

  it('supports multi-token AND search across the tree without changing branch targets', async () => {
    const user = userEvent.setup()
    renderPanel({ currentHeadEntryId: 'follow-up' })

    await user.type(screen.getByLabelText('搜索记录'), 'follow two')
    expect(screen.queryByText('user prompt')).not.toBeInTheDocument()
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(1)
    expect(within(rows[0]!).getByText('follow up two')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '从这里继续当前 Thread' })).toBeEnabled()
  })

  it('keeps the active path marker on every visible node from root to the head', () => {
    renderPanel({ currentHeadEntryId: 'follow-up-2' })
    const activeButtons = screen.getAllByRole('button', { name: /当前路径/ })
    expect(activeButtons.map((button) => button.getAttribute('aria-label'))).toEqual([
      '用户 · user prompt · 当前路径',
      '助手 · assistant reply · 当前路径',
      '助手 · follow up two · 当前路径 · 当前线程位置',
    ])
    expect(screen.getByRole('button', { name: '助手 · follow up' })).not.toHaveAttribute('aria-current')
  })

  it('shows connector glyphs only when branches exist', () => {
    renderPanel({ currentHeadEntryId: 'follow-up-2' })
    const userPrompt = screen.getByRole('button', { name: /user prompt/ })
    // user 只有一个可见子节点（assistant），因此起始位置贴齐，不显示视觉缩进。
    expect(userPrompt.querySelector('.history-branch-entry-glyphs')).toBeNull()
    const firstBranch = screen.getByRole('button', { name: '助手 · follow up' })
    const lastBranch = screen.getByRole('button', { name: '助手 · follow up two · 当前路径 · 当前线程位置' })
    expect(firstBranch.querySelector('.history-branch-entry-glyphs')).toHaveTextContent('├─')
    expect(lastBranch.querySelector('.history-branch-entry-glyphs')).toHaveTextContent('└─')
  })

  it('renders a tree projection that fits within a single line and trims long previews with ellipsis', () => {
    const longText = 'very long message '.repeat(50).trim()
    const longEntry: HarnessSessionEntryDTO = {
      entryId: 'long-entry',
      parentEntryId: 'user',
      entryType: 'MESSAGE',
      payloadJson: JSON.stringify({ message: { role: 'ASSISTANT', contents: [{ type: 'text', text: longText }] } }),
      createTime: null,
    }
    renderPanel({ entries: [...branchEntries, longEntry], currentHeadEntryId: 'follow-up-2' })
    const longButton = screen.getByRole('button', { name: /very long message/ })
    const preview = longButton.querySelector('.history-branch-entry-preview')
    expect(preview).toHaveTextContent('very long message')
    expect(preview?.textContent).toHaveLength(220)
    expect(preview?.textContent).toMatch(/…$/)
    expect(longButton.getAttribute('aria-label')).not.toContain(longText)
  })

  it('uses the compact empty state when no record matches the search', async () => {
    const user = userEvent.setup()
    renderPanel()

    await user.type(screen.getByLabelText('搜索记录'), 'missing')
    expect(screen.getByText('没有匹配 “missing” 的记录')).toBeInTheDocument()
  })

  it('focuses search and supports Arrow navigation, Enter confirm, and Escape exit', async () => {
    const user = userEvent.setup()
    const onRebind = vi.fn()
    const onClose = vi.fn()
    renderPanel({ currentHeadEntryId: 'assistant', onRebind, onClose })
    expect(screen.getByLabelText('搜索记录')).toHaveFocus()

    await user.keyboard('{ArrowDown}{Enter}')
    expect(onRebind).toHaveBeenCalledWith(
      expect.objectContaining({ entryId: 'follow-up' }),
    )
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('disables rebind while pending and shows loading, empty, query-error and rebind-failure states', () => {
    const { rerender } = renderPanel({ loading: true })
    expect(screen.getByText('正在加载历史分支…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '从这里继续当前 Thread' })).toBeDisabled()

    rerender(<HistoryBranchPanel entries={[]} currentHeadEntryId={null} loading={false} queryError={null} pending={false} rebindError={null} onClose={vi.fn()} onRebind={vi.fn()} />)
    expect(screen.getByText('没有可显示的记录')).toBeInTheDocument()

    // rebind 失败文本由调用方生成（包含 409），并原样渲染。
    rerender(<HistoryBranchPanel entries={[]} currentHeadEntryId={null} loading={false} queryError={new Error('failed')} pending={false} rebindError="无法重定位 Thread：状态已变化（thread is not idle），请刷新后重试" onClose={vi.fn()} onRebind={vi.fn()} />)
    expect(screen.getByText('历史分支加载失败')).toBeInTheDocument()
    expect(screen.getByText('无法重定位 Thread：状态已变化（thread is not idle），请刷新后重试')).toBeInTheDocument()
  })

  it('disables entry selection, confirmation, and closing while a rebind is pending', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderPanel({ pending: true, onClose })
    expect(screen.getByRole('button', { name: /user prompt/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '从这里继续当前 Thread' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '关闭' })).toBeDisabled()
    expect(screen.getByLabelText('显示记录')).toBeDisabled()
    expect(screen.getByLabelText('搜索记录')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '关闭' }))
    fireEvent.keyDown(screen.getByRole('region', { name: '历史分支' }), {
      key: 'Escape',
    })
    expect(onClose).not.toHaveBeenCalled()
  })
})

function renderPanel(overrides: Partial<ComponentProps<typeof HistoryBranchPanel>> = {}) {
  return render(
    <HistoryBranchPanel
      entries={branchEntries}
      currentHeadEntryId="assistant"
      loading={false}
      queryError={null}
      pending={false}
      rebindError={null}
      onClose={vi.fn()}
      onRebind={vi.fn()}
      {...overrides}
    />,
  )
}