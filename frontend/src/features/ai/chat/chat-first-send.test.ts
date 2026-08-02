import { describe, expect, it, vi } from 'vitest'
import {
  FirstSendMessageError,
  performBlankPaneFirstSend,
} from '@/features/ai/chat/chat-first-send'
import type {
  HarnessThreadDTO,
  HarnessThreadMessageCreateDTO,
} from '@/shared/api/contracts/ai-runtime'

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    sessionTitle: null,
    headEntryId: 'root',
    executionEpoch: 0,
    revision: '0',
    status: 'IDLE',
    inputSequence: 0,
    processing: false,
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

describe('performBlankPaneFirstSend', () => {
  it('creates an atomically bound Thread, then enqueues USER_MESSAGE directly', async () => {
    const calls: string[] = []
    const createChatThread = vi.fn(async (chatId: string) => {
      calls.push(`createChatThread:${chatId}`)
      return thread({
        threadId: 't1',
        sessionId: 's1',
        headEntryId: 'root',
        executionEpoch: 0,
        status: 'IDLE',
      })
    })
    const submitThreadMessage = vi.fn(
      async (
        threadId: string,
        data: HarnessThreadMessageCreateDTO,
      ) => {
        calls.push(`user:${threadId}:${data.clientMessageId}:${data.content}:${data.expectedExecutionEpoch}`)
        return {
          inputId: 'i2',
          threadId,
          sequence: 1,
          inputType: 'USER_MESSAGE' as const,
          payloadJson: '{}',
          clientMessageId: data.clientMessageId,
          status: 'QUEUED' as const,
          resolvedAt: null,
          createTime: null,
        }
      },
    )

    const result = await performBlankPaneFirstSend({
      chatId: 'chat-1',
      content: 'hello',
      agentName: 'assistant',
      environmentName: 'local',
      yoloEnabled: true,
      createChatThread,
      submitThreadMessage,
      createIds: () => ({ userMessageId: 'cid-user' }),
    })

    // Atomic Chat-scoped creation already binds the Thread.
    expect(calls).toEqual(['createChatThread:chat-1', 'user:t1:cid-user:hello:0'])
    expect(submitThreadMessage).toHaveBeenCalledWith('t1', {
      content: 'hello',
      agentName: 'assistant',
      environmentName: 'local',
      yoloEnabled: true,
      clientMessageId: 'cid-user',
      expectedExecutionEpoch: 0,
    })
    expect(result.sessionId).toBe('s1')
    expect(result.thread).toMatchObject({
      threadId: 't1',
      sessionId: 's1',
      executionEpoch: 0,
      status: 'IDLE',
    })
  })

  it('rejects a create response without a Session and never enqueues the message', async () => {
    const createChatThread = vi.fn(async () =>
      thread({ threadId: 't9', sessionId: null as never }),
    )
    const submitThreadMessage = vi.fn()

    await expect(
      performBlankPaneFirstSend({
        chatId: 'chat-1',
        content: 'hello',
        agentName: 'assistant',
        environmentName: null,
        yoloEnabled: false,
        createChatThread,
        submitThreadMessage: submitThreadMessage as never,
      }),
    ).rejects.toThrow('创建 Chat Thread 未返回 Session')
    expect(submitThreadMessage).not.toHaveBeenCalled()
  })

  it('returns the created Thread and replay identity when the first message fails', async () => {
    const createChatThread = vi.fn(async () =>
      thread({
        threadId: 't-replay',
        sessionId: 's-replay',
        headEntryId: 'root',
        executionEpoch: 2,
        status: 'IDLE',
      }),
    )
    const submitThreadMessage = vi.fn(async () => {
      throw new Error('message temporarily unavailable')
    })

    const error = await performBlankPaneFirstSend({
      chatId: 'chat-1',
      content: 'retry me',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: false,
      createChatThread,
      submitThreadMessage,
      createIds: () => ({ userMessageId: 'cid-replay' }),
    }).catch((value: unknown) => value)

    expect(error).toBeInstanceOf(FirstSendMessageError)
    expect(error).toMatchObject({
      message: 'message temporarily unavailable',
      replay: {
        threadId: 't-replay',
        content: 'retry me',
        clientMessageId: 'cid-replay',
      },
      thread: {
        threadId: 't-replay',
        sessionId: 's-replay',
      },
    })
    expect(createChatThread).toHaveBeenCalledOnce()
    expect(submitThreadMessage).toHaveBeenCalledWith('t-replay', {
      content: 'retry me',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: false,
      clientMessageId: 'cid-replay',
      expectedExecutionEpoch: 2,
    })
  })

  it('does not enqueue a message when the atomic Thread creation fails', async () => {
    const createChatThread = vi.fn(async () => {
      throw new Error('chat not found: 404')
    })
    const submitThreadMessage = vi.fn()

    await expect(
      performBlankPaneFirstSend({
        chatId: 'chat-1',
        content: 'hello',
        agentName: 'assistant',
        environmentName: null,
        yoloEnabled: false,
        createChatThread,
        submitThreadMessage: submitThreadMessage as never,
      }),
    ).rejects.toThrow('chat not found: 404')
    expect(submitThreadMessage).not.toHaveBeenCalled()
  })
})
