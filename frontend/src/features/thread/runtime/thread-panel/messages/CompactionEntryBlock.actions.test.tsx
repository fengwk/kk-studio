import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { EntryMessageBlock } from '@/features/thread/runtime/thread-panel/messages/EntryMessageBlock'
import {
  CompactionActionsContext,
  type CompactionActions,
} from '@/features/thread/runtime/thread-panel/compaction-actions-context'
import type {
  CompactionCardFact,
  EntryEventDialogueMessage,
} from '@/features/thread/runtime/thread-timeline-types'

function card(fact: CompactionCardFact, text = ''): EntryEventDialogueMessage {
  return {
    id: 'entry:turn-start-1',
    role: 'entry',
    kind: 'compaction',
    title: 'ignored',
    text,
    subjectEntryId: 'turn-start-1',
    createdAt: 1767225600000,
    status: 'done',
    compaction: fact,
  }
}

const READY: CompactionActions = {
  compact: vi.fn(),
  compactPending: false,
  availability: { status: 'ready', availability: { available: true, disabledReason: null } },
}

function renderCard(message: EntryEventDialogueMessage, actions: CompactionActions | null) {
  return render(
    <MemoryRouter>
      <CompactionActionsContext.Provider value={actions}>
        <EntryMessageBlock message={message} />
      </CompactionActionsContext.Provider>
    </MemoryRouter>,
  )
}

describe('CompactionEntryBlock lifecycle actions', () => {
  it('opens the execution view from any card that carries a branch thread', () => {
    const { container } = renderCard(card({
      state: 'compacted',
      childThreadId: 'child-1',
      cutEntryId: 'cut-1',
      errorCode: null,
    }, 'summary'), null)

    const link = screen.getByRole('link', { name: '查看执行' })
    expect(link).toHaveAttribute('href', '/threads/child-1?afterEntryId=cut-1')
    expect(container.querySelector('[data-compaction-state="compacted"]')).not.toBeNull()
  })

  it('shows the failure reason and retries through the shared manual compaction action', () => {
    const compact = vi.fn()
    renderCard(card({
      state: 'failed',
      childThreadId: 'child-1',
      cutEntryId: 'cut-1',
      errorCode: 'COMPACTION_OUTPUT_TRUNCATED',
    }), { ...READY, compact })

    expect(screen.getByText('上下文压缩失败')).toBeInTheDocument()
    expect(screen.getByText('摘要因输出上限被截断。')).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: '重新压缩' })
    expect(retry).toBeEnabled()
    fireEvent.click(retry)
    expect(compact).toHaveBeenCalledTimes(1)
  })

  it('offers retry for stopped compactions and reports the unavailable reason', () => {
    renderCard(card({
      state: 'stopped',
      childThreadId: null,
      cutEntryId: null,
      errorCode: null,
    }), {
      compact: vi.fn(),
      compactPending: false,
      availability: {
        status: 'ready',
        availability: { available: false, disabledReason: 'THREAD_BUSY' },
      },
    })

    expect(screen.getByText('上下文压缩已停止')).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: '重新压缩' })
    expect(retry).toBeDisabled()
    expect(retry).toHaveAttribute('title', 'Thread 正在执行，请等待当前回合结束')
  })

  it('does not offer the write action in read-only contexts', () => {
    renderCard(card({
      state: 'failed',
      childThreadId: 'child-1',
      cutEntryId: 'cut-1',
      errorCode: 'COMPACTION_FAILED',
    }), null)

    expect(screen.queryByRole('button', { name: '重新压缩' })).toBeNull()
    expect(screen.getByRole('link', { name: '查看执行' })).toBeInTheDocument()
  })

  it('renders the running card without a summary body or retry', () => {
    const { container } = render(
      <MemoryRouter>
        <CompactionActionsContext.Provider value={READY}>
          <EntryMessageBlock message={card({
            state: 'running',
            childThreadId: 'child-1',
            cutEntryId: 'cut-1',
            errorCode: null,
          })} />
        </CompactionActionsContext.Provider>
      </MemoryRouter>,
    )

    expect(screen.getByText('正在压缩上下文…')).toBeInTheDocument()
    expect(screen.getByText('正在整理以上内容。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新压缩' })).toBeNull()
    expect(container.querySelector('.thread-disclosure-toggle')).toBeNull()
  })
})
