import { describe, expect, it } from 'vitest'
import { deriveThreadRetryPresentation } from '@/features/ai/thread-retry-presentation'
import type { HarnessThreadDTO, ThreadEventDTO } from '@/shared/api/contracts'

describe('deriveThreadRetryPresentation', () => {
  it('shows the durable retry countdown and attempt progress before the due time', () => {
    const now = Date.parse('2026-07-21T08:00:00.000Z')
    const presentation = deriveThreadRetryPresentation(
      { ...thread(), status: 'RETRYING', retryAttempt: 2, retryAt: '2026-07-21T08:00:05.000Z' },
      [retryEvent({ retryAttempt: 2, maxRetries: 3, retryAt: '2026-07-21T08:00:05.000Z' })],
      now,
    )

    expect(presentation).toEqual({
      workingLabel: '5 秒后进行第 2/3 次自动重试…',
      stoppedNotice: null,
      queueLabel: 'queued',
    })
  })

  it('treats offset-free UTC Thread DTO due times as UTC when the event is unavailable', () => {
    const now = Date.parse('2026-07-21T08:00:00.000Z')
    const presentation = deriveThreadRetryPresentation(
      { ...thread(), status: 'RETRYING', retryAttempt: 1, retryAt: '2026-07-21T08:00:05.000' },
      [],
      now,
    )

    expect(presentation.workingLabel).toBe('5 秒后进行第 1 次自动重试…')
  })

  it('shows an active retry label for a due or processing retry and accepts array timestamps', () => {
    const presentation = deriveThreadRetryPresentation(
      {
        ...thread(),
        status: 'RETRYING',
        retryAttempt: 2,
        retryAt: [2026, 7, 21, 8, 0, 5, 0],
        processing: true,
      },
      [retryEvent({ retryAttempt: 2, maxRetries: 0 })],
      Date.parse('2026-07-21T08:00:00.000Z'),
    )

    expect(presentation.workingLabel).toBe('正在进行第 2 次自动重试…')
  })

  it('leaves ordinary Thread statuses without retry-specific decoration', () => {
    expect(deriveThreadRetryPresentation(thread(), [])).toEqual({
      workingLabel: null,
      stoppedNotice: null,
      queueLabel: 'queued',
    })
  })

  it('stops Working and labels queued inputs as waiting for restart after retry exhaustion', () => {
    const presentation = deriveThreadRetryPresentation(
      { ...thread(), status: 'FAILED', retryAttempt: 3 },
      [],
    )

    expect(presentation).toEqual({
      workingLabel: null,
      stoppedNotice: '本次请求已停止；发送新消息可重新开始。',
      queueLabel: '等待重启',
    })
  })
})

function thread(): HarnessThreadDTO {
  return {
    threadId: 'thread-1',
    sessionId: 'session-1',
    sessionTitle: 'Chat',
    headEntryId: 'entry-1',
    status: 'IDLE',
    inputSequence: 1,
    retryAttempt: 0,
    retryAt: null,
    activeAgentDefinitionId: null,
    activeAgentName: null,
    modelId: null,
    variant: null,
    yoloEnabled: false,
    processing: false,
    createTime: null,
    updateTime: null,
  }
}

function retryEvent(payload: Record<string, unknown>): ThreadEventDTO {
  return {
    eventId: 'event-1',
    threadId: 'thread-1',
    subjectEntryId: null,
    eventType: 'thread_retry_scheduled',
    payloadJson: JSON.stringify(payload),
    createTime: '2026-07-21T08:00:00.000Z',
  }
}
