import type { ComponentProps } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'

const branchEntries: HarnessSessionEntryDTO[] = [
  { entryId: 'root', sessionId: 's1', parentEntryId: null, entryType: 'ROOT', payloadJson: '{}', createTime: null },
  { entryId: 'user', sessionId: 's1', parentEntryId: 'root', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"USER","contents":[{"type":"text","text":"user prompt"}]}}', createTime: null },
  { entryId: 'assistant', sessionId: 's1', parentEntryId: 'user', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"type":"text","text":"assistant reply"}]}}', createTime: null },
  { entryId: 'tool', sessionId: 's1', parentEntryId: 'assistant', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"TOOL","contents":[{"type":"text","text":"tool result"}]}}', createTime: null },
  { entryId: 'tool2', sessionId: 's1', parentEntryId: 'assistant', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"TOOL","contents":[{"type":"text","text":"another tool result"}]}}', createTime: null },
  { entryId: 'follow-up', sessionId: 's1', parentEntryId: 'tool', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"type":"text","text":"follow up"}]}}', createTime: null },
  { entryId: 'follow-up-2', sessionId: 's1', parentEntryId: 'tool2', entryType: 'MESSAGE', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"type":"text","text":"follow up two"}]}}', createTime: null },
]

describe('HistoryBranchPanel', () => {
  it('selects the current head by default and emits an ENTRY_DRAFT selection only on confirmation', async () => {
    const user = userEvent.setup()
    const onSelectEntry = vi.fn()
    renderPanel({ onSelectEntry, currentHeadEntryId: 'follow-up' })

    const headButton = screen.getByRole('button', {
      name: '助手 · follow up · 当前路径 · 当前线程位置',
    })
    expect(headButton).toHaveAttribute('aria-pressed', 'true')
    expect(within(headButton).getByText('当前线程位置')).toBeInTheDocument()
    expect(screen.getByText('3 / 4 · ↑↓ 选择 · Enter 确认 · Esc 返回')).toBeInTheDocument()
    expect(onSelectEntry).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))
    expect(onSelectEntry).toHaveBeenCalledWith(expect.objectContaining({ entryId: 'follow-up' }))
  })

  it('supports multi-token AND search without changing the selected target', async () => {
    const user = userEvent.setup()
    const onSelectEntry = vi.fn()
    renderPanel({ onSelectEntry, currentHeadEntryId: 'follow-up' })

    await user.type(screen.getByLabelText('搜索记录'), 'follow two')
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(1)
    expect(within(rows[0]!).getByText('follow up two')).toBeInTheDocument()
    expect(onSelectEntry).not.toHaveBeenCalled()
  })

  it('keeps the active path marker on every visible node from root to the head', () => {
    renderPanel({ currentHeadEntryId: 'follow-up-2' })
    const activeButtons = screen.getAllByRole('button', { name: /当前路径/ })
    expect(activeButtons.map((button) => button.getAttribute('aria-label'))).toEqual([
      '用户 · user prompt · 当前路径',
      '助手 · assistant reply · 当前路径',
      '助手 · follow up two · 当前路径 · 当前线程位置',
    ])
  })

  it('shows connector glyphs only when the history branches', () => {
    renderPanel({ currentHeadEntryId: 'follow-up-2' })
    const userPrompt = screen.getByRole('button', { name: /user prompt/ })
    const firstBranch = screen.getByRole('button', { name: '助手 · follow up' })
    const lastBranch = screen.getByRole('button', { name: /follow up two/ })
    expect(userPrompt.querySelector('.history-branch-entry-glyphs')).toHaveTextContent('')
    expect(firstBranch.querySelector('.history-branch-entry-glyphs')).toHaveTextContent('├─')
    expect(lastBranch.querySelector('.history-branch-entry-glyphs')).toHaveTextContent('└─')
  })

  it('renders long previews as a single ellipsized row', () => {
    const longText = 'very long message '.repeat(50).trim()
    const longEntry: HarnessSessionEntryDTO = {
      entryId: 'long-entry',
      sessionId: 's1',
      parentEntryId: 'user',
      entryType: 'MESSAGE',
      payloadJson: JSON.stringify({
        message: { role: 'ASSISTANT', contents: [{ type: 'text', text: longText }] },
      }),
      createTime: null,
    }
    renderPanel({ entries: [...branchEntries, longEntry], currentHeadEntryId: 'follow-up-2' })
    const preview = screen.getByRole('button', { name: /very long message/ })
      .querySelector('.history-branch-entry-preview')
    expect(preview?.textContent).toHaveLength(220)
    expect(preview?.textContent).toMatch(/…$/)
  })

  it('shows a compact empty state when no record matches the search', async () => {
    const user = userEvent.setup()
    renderPanel()

    await user.type(screen.getByLabelText('搜索记录'), 'missing')
    expect(screen.getByText('没有匹配 “missing” 的记录')).toBeInTheDocument()
  })

  it('focuses search and supports Arrow navigation, Enter selection, and Escape close', async () => {
    const user = userEvent.setup()
    const onSelectEntry = vi.fn()
    const onClose = vi.fn()
    renderPanel({ currentHeadEntryId: 'assistant', onSelectEntry, onClose })
    expect(screen.getByLabelText('搜索记录')).toHaveFocus()

    await user.keyboard('{ArrowDown}{Enter}')
    expect(onSelectEntry).toHaveBeenCalledWith(expect.objectContaining({ entryId: 'follow-up' }))
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('disables selection while loading and renders query failures', () => {
    const { rerender } = renderPanel({ loading: true })
    expect(screen.getByText('正在加载历史分支…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '从这里继续当前 Thread' })).toBeDisabled()

    rerender(
      <HistoryBranchPanel
        entries={[]}
        currentHeadEntryId={null}
        loading={false}
        queryError={new Error('failed')}
        onClose={vi.fn()}
        onSelectEntry={vi.fn()}
      />,
    )
    expect(screen.getByText('历史分支加载失败')).toBeInTheDocument()
  })
})

function renderPanel(overrides: Partial<ComponentProps<typeof HistoryBranchPanel>> = {}) {
  return render(
    <HistoryBranchPanel
      entries={branchEntries}
      currentHeadEntryId="assistant"
      loading={false}
      queryError={null}
      onClose={vi.fn()}
      onSelectEntry={vi.fn()}
      {...overrides}
    />,
  )
}
