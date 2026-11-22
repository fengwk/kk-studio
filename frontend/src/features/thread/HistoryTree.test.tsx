import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { HistoryTree } from '@/features/thread/HistoryTree'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

function entry(entryId: string, parentEntryId: string | null, text: string): HarnessSessionEntryDTO {
  return {
    entryId,
    parentEntryId,
    sessionId: 'session-1',
    entryType: parentEntryId == null ? 'ROOT' : 'TURN_END',
    payload: { message: { contents: [{ type: 'text', text }] } },
    createdAt: 0,
    usageCost: null,
  }
}

function setup(headEntryId = 'head') {
  const entries = [entry('root', null, 'needle first'), entry('end', 'root', 'needle second'), entry('head', 'end', 'latest')]
  const onFork = vi.fn()
  const user = userEvent.setup()
  render(<HistoryTree entries={entries} headEntryId={headEntryId} loading={false} queryError={null} onClose={vi.fn()} onFork={onFork} />)
  return { entries, onFork, user, search: screen.getByRole('searchbox') }
}

describe('HistoryTree search keyboard actions', () => {
  it('branches from the first displayed hit instead of the unrelated head', async () => {
    const { entries, onFork, user, search } = setup()
    await user.type(search, 'needle')
    await user.keyboard('{Enter}')
    expect(onFork).toHaveBeenCalledExactlyOnceWith(entries[0])
  })

  it('keeps the selected row when it still matches the new query', async () => {
    const { entries, onFork, user, search } = setup('end')
    await user.type(search, 'needle')
    await user.keyboard('{Enter}')
    expect(onFork).toHaveBeenCalledExactlyOnceWith(entries[1])
  })

  it('does not branch on Enter when the query has no hits', async () => {
    const { onFork, user, search } = setup()
    await user.type(search, 'missing')
    await user.keyboard('{Enter}')
    expect(onFork).not.toHaveBeenCalled()
  })
})
