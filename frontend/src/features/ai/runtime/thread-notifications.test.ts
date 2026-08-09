import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  collectPendingPermissions,
  useThreadNotifications,
} from '@/features/ai/runtime/thread-notifications'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

function toolMessage(
  overrides: Partial<ToolDialogueMessage> = {},
): ToolDialogueMessage {
  return {
    id: 'tool-call',
    role: 'tool',
    subjectEntryId: 'assistant-1',
    createdAt: null,
    phase: 'call',
    text: '',
    toolCallId: 'call-1',
    toolName: 'bash',
    rendererKey: 'bash',
    arguments: '{}',
    attachments: [],
    invocationId: '10',
    ...overrides,
  }
}

describe('collectPendingPermissions', () => {
  it('combines parent approvals and child task relay approvals with target-thread identity', () => {
    const taskStatus = JSON.stringify({
      kind: 'task.status',
      threadId: '2',
      subagentType: 'coder',
      state: 'waiting_approval',
      depth: 2,
      turns: 1,
      toolCalls: 1,
      lastActivity: 'running bash',
      approvals: [{ invocationId: '10', toolName: 'write', reason: 'changes a file' }],
      descendants: [
        {
          threadId: '3',
          subagentType: 'helper',
          state: 'waiting_approval',
          depth: 3,
          turns: 1,
          toolCalls: 1,
          lastActivity: 'running bash',
          approvals: [{ invocationId: '11', toolName: 'bash', reason: 'nested command' }],
        },
      ],
    })
    expect(
      collectPendingPermissions('1', [
        toolMessage({
          approval: {
            required: true,
            decision: null,
            decisionId: null,
            reason: 'runs a command',
          },
        }),
        toolMessage({
          id: 'task-call',
          toolName: 'task',
          rendererKey: 'task',
          invocationId: '20',
          partial: taskStatus,
        }),
      ]),
    ).toEqual([
      {
        key: '1:10',
        toolName: 'bash',
        reason: 'runs a command',
      },
      {
        key: '2:10',
        toolName: 'write',
        reason: 'changes a file',
      },
      {
        key: '3:11',
        toolName: 'bash',
        reason: 'nested command',
      },
    ])
  })

  it('ignores already decided and malformed approvals', () => {
    expect(
      collectPendingPermissions('1', [
        toolMessage({
          approval: {
            required: true,
            decision: 'DENIED',
            decisionId: 'decision',
            reason: null,
          },
        }),
      ]),
    ).toEqual([])
  })
})

describe('useThreadNotifications', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('notifies once when a new parent permission becomes pending', async () => {
    const notifications = installNotificationSpy()
    const messages: DialogueMessage[] = [
      toolMessage({
        approval: {
          required: true,
          decision: null,
          decisionId: null,
          reason: 'runs a command',
        },
      }),
    ]
    renderHook(() =>
      useThreadNotifications({
        threadId: '1',
        title: 'thread 1',
        messages,
        working: true,
        enabled: true,
      }))

    await waitFor(() => expect(notifications).toHaveLength(1))
    expect(notifications[0]).toMatchObject({
      title: '等待审批',
      body: 'thread 1 正在等待运行 bash：runs a command',
    })
  })

  it('notifies every permission that becomes pending in the same projection', async () => {
    const notifications = installNotificationSpy()
    const messages: DialogueMessage[] = [
      toolMessage({
        approval: {
          required: true,
          decision: null,
          decisionId: null,
          reason: 'runs a command',
        },
      }),
      toolMessage({
        id: 'tool-call-2',
        toolCallId: 'call-2',
        toolName: 'write',
        rendererKey: 'write',
        invocationId: '11',
        approval: {
          required: true,
          decision: null,
          decisionId: null,
          reason: 'changes a file',
        },
      }),
    ]
    const { rerender } = renderHook(
      ({ projected }: { projected: DialogueMessage[] }) =>
        useThreadNotifications({
          threadId: '1',
          title: 'thread 1',
          messages: projected,
          working: true,
          enabled: true,
        }),
      { initialProps: { projected: messages } },
    )

    await waitFor(() => expect(notifications).toHaveLength(2))
    expect(notifications.map(({ body }) => body)).toEqual([
      'thread 1 正在等待运行 bash：runs a command',
      'thread 1 正在等待运行 write：changes a file',
    ])

    rerender({ projected: [...messages] })
    expect(notifications).toHaveLength(2)
  })

  it('suppresses completed immediately after a denial but still reports final errors', async () => {
    const notifications = installNotificationSpy()
    const completed: DialogueMessage[] = [
      {
        id: 'assistant',
        role: 'assistant',
        subjectEntryId: 'assistant',
        createdAt: null,
        status: 'done',
        text: 'done',
      },
    ]
    const { result, rerender } = renderHook(
      ({ working, messages }: { working: boolean; messages: DialogueMessage[] }) =>
        useThreadNotifications({
          threadId: '1',
          title: 'thread 1',
          messages,
          working,
          enabled: true,
        }),
      { initialProps: { working: true, messages: completed } },
    )

    act(() => result.current.markPermissionRejected())
    rerender({ working: false, messages: completed })
    await waitFor(() => expect(notifications).toHaveLength(0))

    const failed: DialogueMessage[] = [{ ...completed[0]!, status: 'error' }]
    rerender({ working: true, messages: failed })
    rerender({ working: false, messages: failed })
    await waitFor(() => expect(notifications).toHaveLength(1))
    expect(notifications[0]?.title).toBe('Agent 执行失败')
  })
})

function installNotificationSpy(): Array<{ title: string; body: string | undefined }> {
  const notifications: Array<{ title: string; body: string | undefined }> = []
  class FakeNotification {
    static permission: NotificationPermission = 'granted'

    static requestPermission(): Promise<NotificationPermission> {
      return Promise.resolve('granted')
    }

    constructor(title: string, options?: NotificationOptions) {
      notifications.push({ title, body: options?.body })
    }
  }
  vi.stubGlobal('Notification', FakeNotification)
  return notifications
}
