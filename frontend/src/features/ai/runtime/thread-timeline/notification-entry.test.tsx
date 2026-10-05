import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline'
import { EntryMessageBlock } from '@/features/ai/runtime/thread-panel/messages/EntryMessageBlock'
import type { EntryEventDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
} from '@/shared/api/contracts/ai-runtime'

/** NOTIFICATION Entry：message 是标准 USER AgentMessage，只作为 runtime 上下文事实。 */
function notificationEntry(
  kind: 'SUBAGENT_RESULT' | 'TASK_BUDGET',
  text: string,
  entryId = `entry-${kind}`,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType: 'NOTIFICATION',
    payloadJson: JSON.stringify({
      notificationId: `notification-${kind}`,
      kind,
      sourceThreadId: 'child-thread-1',
      message: { role: 'USER', contents: [{ type: 'text', text }] },
    }),
    createTime: '2026-07-28T10:00:00Z',
  }
}

function userEntry(text: string, entryId: string): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({
      message: { role: 'USER', contents: [{ type: 'text', text }] },
    }),
    createTime: '2026-07-28T10:00:00Z',
  }
}

function queuedCommand(
  type: 'USER_MESSAGE' | 'NOTIFICATION',
  text: string,
  sequence: string,
): HarnessThreadCommandDTO {
  return {
    threadId: 'thread-1',
    sequence,
    type,
    state: 'QUEUED',
    idempotencyKey: `queued-${type}-${sequence}`,
    payloadJson: type === 'USER_MESSAGE'
      ? JSON.stringify({ message: { role: 'USER', contents: [{ type: 'text', text }] } })
      : JSON.stringify({
          notificationId: `notification-${sequence}`,
          kind: 'SUBAGENT_RESULT',
          sourceThreadId: 'child-thread-1',
          message: { role: 'USER', contents: [{ type: 'text', text }] },
        }),
    cancelledAt: null,
    createTime: '2026-07-28T10:00:01Z',
  }
}

function notificationMessages(messages: ReturnType<typeof buildThreadTimeline>['messages']) {
  return messages.filter(
    (message): message is EntryEventDialogueMessage =>
      message.role === 'entry' && message.kind === 'notification',
  )
}

describe('NOTIFICATION timeline projection', () => {
  it('projects a durable NOTIFICATION entry as a system notification message with its title and text', () => {
    // 测试意图：NOTIFICATION 是系统结果事实，必须投影为 notification 条目而不是未知 Entry。
    const timeline = buildThreadTimeline(
      [userEntry('hello', 'entry-user-1'), notificationEntry('SUBAGENT_RESULT', 'child finished')],
      [],
      [],
    )

    const notifications = notificationMessages(timeline.messages)
    expect(notifications).toHaveLength(1)
    expect(notifications[0]).toMatchObject({ kind: 'notification', title: '子 Thread 结果', text: 'child finished' })
    expect(notifications[0]?.rawPayloadJson).toContain('SUBAGENT_RESULT')
    // 普通对话消息仍然保留，且没有被误判为 notification。
    expect(timeline.messages.some((message) => message.role === 'user')).toBe(true)
  })

  it('distinguishes the SUBAGENT_RESULT and TASK_BUDGET notification kinds', () => {
    const timeline = buildThreadTimeline(
      [
        notificationEntry('SUBAGENT_RESULT', 'child finished', 'entry-a'),
        notificationEntry('TASK_BUDGET', 'budget nearly exhausted', 'entry-b'),
      ],
      [],
      [],
    )

    const titles = notificationMessages(timeline.messages).map((message) => message.title)
    expect(titles).toEqual(['子 Thread 结果', '任务预算提醒'])
  })

  it('renders the notification with the dedicated system card style instead of a dialogue block', () => {
    // 测试意图：NOTIFICATION 使用独立系统样式，绝不渲染成 user/assistant 对话块。
    const timeline = buildThreadTimeline([notificationEntry('SUBAGENT_RESULT', 'child finished')], [], [])
    const message = notificationMessages(timeline.messages)[0]
    expect(message).toBeDefined()

    const { container } = render(<EntryMessageBlock message={message!} />)

    const block = container.querySelector('[data-entry-kind="notification"]')
    expect(block).not.toBeNull()
    expect(block).toHaveClass('thread-block-entry')
    expect(block).toHaveClass('kind-notification')
    expect(container.querySelector('.thread-block-user')).toBeNull()
    expect(container.querySelector('.thread-block-assistant')).toBeNull()
    expect(screen.getByText('child finished')).toBeInTheDocument()
  })

  it('keeps a queued NOTIFICATION out of the editable queue while a queued USER_MESSAGE stays editable', () => {
    // 测试意图：队列只承载可编辑的人类输入；系统通知不得进入队列、草稿与上下键历史。
    const timeline = buildThreadTimeline(
      [],
      [
        queuedCommand('NOTIFICATION', 'system fact', '1'),
        queuedCommand('USER_MESSAGE', 'human input', '2'),
      ],
      [],
    )

    expect(timeline.queuedMessages.map((message) => message.text)).toEqual(['human input'])
    expect(timeline.queuedMessages.some((message) => message.text === 'system fact')).toBe(false)
    // 系统通知不会让 mailbox 看起来含有待处理的用户输入之外的装饰项。
    expect(timeline.hasPendingInputs).toBe(true)
  })

  it('reports no pending human input when only a queued NOTIFICATION exists', () => {
    const timeline = buildThreadTimeline([], [queuedCommand('NOTIFICATION', 'system fact', '1')], [])

    expect(timeline.queuedMessages).toEqual([])
    expect(timeline.hasPendingInputs).toBe(false)
  })
})
