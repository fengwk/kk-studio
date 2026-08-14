import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ModelAttemptFailureMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ModelAttemptFailureMessageBlock'
import { retryCountdownSeconds } from '@/features/ai/runtime/thread-panel/messages/retry-countdown'
import type { ModelAttemptFailureDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

function message(
  overrides: Partial<ModelAttemptFailureDialogueMessage> = {},
): ModelAttemptFailureDialogueMessage {
  return {
    id: 'failure-1',
    role: 'model_attempt_failure',
    subjectEntryId: null,
    createdAt: '2026-07-28T10:00:00Z',
    status: 'done',
    attempt: 1,
    sequence: '2',
    text: 'partial output',
    thinking: 'partial thinking',
    errorCode: 'TRANSIENT',
    errorMessage: 'provider is busy',
    failedAt: '2026-07-28T10:00:00Z',
    retryAt: '2026-07-28T10:00:05Z',
    nextAttempt: 2,
    modelInvocationId: 'model-1',
    ...overrides,
  }
}

describe('ModelAttemptFailureMessageBlock', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-28T10:00:02Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders partial content normally and error in a separate block with countdown', () => {
    const { container } = render(<ModelAttemptFailureMessageBlock message={message()} />)

    expect(screen.getByText('第 1 次尝试失败')).toBeInTheDocument()
    expect(screen.getByText('已安排第 2 次重试，还剩 3 秒')).toBeInTheDocument()
    expect(screen.getByText('partial output')).toBeInTheDocument()
    expect(screen.getByText('partial thinking')).toBeInTheDocument()
    expect(screen.getByText('TRANSIENT: provider is busy')).toBeInTheDocument()
    expect(container.querySelector('.thread-block-model-attempt-failure-error')).toBeInTheDocument()
    expect(container.querySelector('.thread-error-raw')).toBeInTheDocument()
  })

  it('changes to the stable retrying copy at retryAt and clears its timer on unmount', () => {
    const { unmount } = render(<ModelAttemptFailureMessageBlock message={message()} />)

    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(screen.getByText('正在进行第 2 次重试')).toBeInTheDocument()

    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not schedule a timer for terminal failures', () => {
    render(
      <ModelAttemptFailureMessageBlock
        message={message({ retryAt: null, nextAttempt: null })}
      />,
    )

    expect(screen.getByText('第 1 次尝试失败')).toBeInTheDocument()
    expect(screen.queryByText(/重试/u)).not.toBeInTheDocument()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('renders a durable retry as historical state without a countdown timer', () => {
    render(
      <ModelAttemptFailureMessageBlock
        message={message({
          subjectEntryId: 'entry-1',
          modelInvocationId: undefined,
        })}
      />,
    )

    expect(screen.getByText('已安排第 2 次重试')).toBeInTheDocument()
    expect(screen.queryByText(/还剩/u)).not.toBeInTheDocument()
    expect(screen.queryByText(/正在进行/u)).not.toBeInTheDocument()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('calculates remaining seconds without a polling interval', () => {
    expect(
      retryCountdownSeconds('2026-07-28T10:00:05Z', Date.parse('2026-07-28T10:00:02.001Z')),
    ).toBe(3)
    expect(
      retryCountdownSeconds('2026-07-28T10:00:05Z', Date.parse('2026-07-28T10:00:05Z')),
    ).toBe(0)
  })
})
