import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ChatRunStatus } from '@/features/ai/ChatRunStatus'
import type { HarnessRunDTO } from '@/shared/api/contracts'

describe('ChatRunStatus', () => {
  it('renders the latest run and formats ISO and array timestamps', () => {
    render(<ChatRunStatus runs={[run('QUEUED', [2026, 6, 20, 2, 1]), run('RUNNING', '2026-06-20T02:02:00')]} activeRun />)

    expect(screen.getByText('RUNNING')).toBeInTheDocument()
    expect(screen.getByText('2026-06-20 02:02')).toBeInTheDocument()
    expect(screen.getByText('RUNNING').parentElement).toHaveClass('active')
  })

  it('renders no-run and malformed timestamp fallbacks', () => {
    const { rerender } = render(<ChatRunStatus runs={[]} activeRun={false} />)
    expect(screen.getByText('no run')).toBeInTheDocument()
    expect(screen.getByText('-')).toBeInTheDocument()

    rerender(<ChatRunStatus runs={[run('FAILED', [Number.NaN])]} activeRun={false} />)
    expect(screen.getByText('FAILED')).toBeInTheDocument()
    expect(screen.getByText('-')).toBeInTheDocument()
  })
})

function run(status: string, updateTime: HarnessRunDTO['updateTime']): HarnessRunDTO {
  return {
    runId: '1',
    sessionId: '2',
    triggerEntryId: '3',
    status,
    turnIndex: 0,
    attempt: 1,
    eventSequence: 0,
    nextAttemptAt: null,
    cancelRequestedAt: null,
    startedAt: null,
    finishedAt: null,
    createTime: null,
    updateTime,
  }
}
