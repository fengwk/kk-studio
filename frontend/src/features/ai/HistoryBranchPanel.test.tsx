import type { ComponentProps } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { HistoryBranchPanel } from '@/features/ai/HistoryBranchPanel'

const branchEntries = [
  { entryId: 'root', sessionId: 's', parentEntryId: null, entryType: 'agent_snapshot', payloadJson: '{}', createTime: null },
  { entryId: 'user', sessionId: 's', parentEntryId: 'root', entryType: 'message', payloadJson: '{"message":{"role":"USER","contents":[{"text":"user prompt"}]}}', createTime: null },
  { entryId: 'assistant', sessionId: 's', parentEntryId: 'user', entryType: 'message', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"text":"assistant reply"}]}}', createTime: null },
  { entryId: 'tool', sessionId: 's', parentEntryId: 'assistant', entryType: 'message', payloadJson: '{"message":{"role":"TOOL","contents":[{"text":"tool result"}]}}', createTime: null },
  { entryId: 'tool2', sessionId: 's', parentEntryId: 'assistant', entryType: 'message', payloadJson: '{"message":{"role":"TOOL","contents":[{"text":"another tool result"}]}}', createTime: null },
  { entryId: 'follow-up', sessionId: 's', parentEntryId: 'tool', entryType: 'message', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"text":"follow up"}]}}', createTime: null },
  { entryId: 'follow-up-2', sessionId: 's', parentEntryId: 'tool2', entryType: 'message', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"text":"follow up two"}]}}', createTime: null },
]

describe('HistoryBranchPanel', () => {
  it('selects the current head by default and creates only after explicit confirmation', async () => {
    const user = userEvent.setup()
    const onCreate = vi.fn()
    renderPanel({ onCreate, currentHeadEntryId: 'follow-up' })

    expect(screen.getByRole('dialog', { name: '历史分支' })).toBeInTheDocument()
    expect(screen.getByText(/当前分支 head/)).toBeInTheDocument()
    const headButton = screen.getByRole('button', { name: '助手 · follow up · 当前路径 · 当前线程位置' })
    expect(within(headButton).getByText('当前线程位置')).toBeInTheDocument()
    expect(headButton).toHaveAttribute('aria-pressed', 'true')
    expect(onCreate).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '从这里开启新 Thread' }))
    expect(onCreate).toHaveBeenCalledTimes(1)
    const createdEntry = onCreate.mock.calls[0][0]
    expect(createdEntry.entryId).toBe('follow-up')
  })

  it('re-attaches the selection to the nearest visible ancestor when the current selection becomes hidden', async () => {
    const user = userEvent.setup()
    const onCreate = vi.fn()
    renderPanel({ onCreate, currentHeadEntryId: 'follow-up' })

    await user.selectOptions(screen.getByLabelText('显示范围'), 'all')
    await user.click(screen.getByRole('button', { name: /工具 · tool result/ }))
    expect(screen.getByRole('button', { name: '从这里开启新 Thread' })).toBeEnabled()

    // The default filter hides tools. The selected tool's closest visible raw ancestor is assistant.
    await user.selectOptions(screen.getByLabelText('显示范围'), 'default')
    expect(screen.queryByText('tool result')).not.toBeInTheDocument()
    const confirm = screen.getByRole('button', { name: '从这里开启新 Thread' })
    expect(confirm).toBeEnabled()
    await user.click(confirm)
    expect(onCreate).toHaveBeenCalledTimes(1)
    expect(onCreate.mock.calls[0][0].entryId).toBe('assistant')
  })

  it('supports multi-token AND search across the tree without changing branch targets', async () => {
    const user = userEvent.setup()
    renderPanel({ currentHeadEntryId: 'follow-up' })

    await user.type(screen.getByLabelText('搜索历史消息'), 'follow two')
    expect(screen.queryByText('user prompt')).not.toBeInTheDocument()
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(1)
    expect(within(rows[0]!).getByText('follow up two')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '从这里开启新 Thread' })).toBeEnabled()
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
    // user has a single visible child (assistant), so it starts flush with no visual gutter.
    expect(userPrompt.querySelector('.history-branch-entry-glyphs')).toBeNull()
    const firstBranch = screen.getByRole('button', { name: '助手 · follow up' })
    const lastBranch = screen.getByRole('button', { name: '助手 · follow up two · 当前路径 · 当前线程位置' })
    expect(firstBranch.querySelector('.history-branch-entry-glyphs')).toHaveTextContent('├─')
    expect(lastBranch.querySelector('.history-branch-entry-glyphs')).toHaveTextContent('└─')
  })

  it('renders a tree projection that fits within a single line and trims long previews with ellipsis', () => {
    const longText = 'very long message '.repeat(50).trim()
    const longEntry = {
      entryId: 'long-entry',
      sessionId: 's',
      parentEntryId: 'user',
      entryType: 'message',
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

  it('disables creation while pending and shows loading, empty, error, and creation-failure states', () => {
    const { rerender } = renderPanel({ loading: true })
    expect(screen.getByText('正在加载历史分支…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '从这里开启新 Thread' })).toBeDisabled()

    rerender(<HistoryBranchPanel entries={[]} currentHeadEntryId={null} loading={false} queryError={null} pending={false} creationError={null} onClose={vi.fn()} onCreate={vi.fn()} />)
    expect(screen.getByText('没有可显示的历史条目')).toBeInTheDocument()

    rerender(<HistoryBranchPanel entries={[]} currentHeadEntryId={null} loading={false} queryError={new Error('failed')} pending={false} creationError={new Error('failed')} onClose={vi.fn()} onCreate={vi.fn()} />)
    expect(screen.getByText('历史分支加载失败')).toBeInTheDocument()
    expect(screen.getByText('创建 Thread 失败')).toBeInTheDocument()
  })

  it('disables entry selection, confirmation, and closing while creation is pending', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderPanel({ pending: true, onClose })
    expect(screen.getByRole('button', { name: /user prompt/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '从这里开启新 Thread' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '关闭' })).toBeDisabled()
    expect(screen.getByLabelText('显示范围')).toBeDisabled()
    expect(screen.getByLabelText('搜索历史消息')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '关闭' }))
    fireEvent.mouseDown(screen.getByRole('presentation'))
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
      creationError={null}
      onClose={vi.fn()}
      onCreate={vi.fn()}
      {...overrides}
    />,
  )
}