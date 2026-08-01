import { describe, expect, it, vi } from 'vitest'
import { performBlankPaneFirstSend } from '@/features/ai/chat/chat-first-send'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: null,
    sessionTitle: null,
    headEntryId: null,
    executionEpoch: 0,
    revision: '0',
    status: 'UNBOUND',
    inputSequence: 0,
    activeAgentDefinitionId: null,
    activeAgentName: null,
    activeEnvironmentName: null,
    modelId: null,
    variant: null,
    yoloEnabled: false,
    processing: false,
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

describe('performBlankPaneFirstSend', () => {
  it('creates an atomically bound Thread, then enqueues USER_MESSAGE without bootstrap', async () => {
    const calls: string[] = []
    const createChatThread = vi.fn(async (chatId: string) => {
      calls.push(`createChatThread:${chatId}`)
      return thread({
        threadId: 't1',
        sessionId: 's1',
        headEntryId: 'e-config',
        executionEpoch: 1,
        status: 'IDLE',
        activeAgentDefinitionId: 'a1',
        activeAgentName: 'assistant',
      })
    })
    const submitThreadMessage = vi.fn(
      async (
        threadId: string,
        data: { clientMessageId: string; content: string; expectedExecutionEpoch: number | string },
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
      createChatThread,
      submitThreadMessage,
      createIds: () => ({ userMessageId: 'cid-user' }),
    })

    // Atomic Chat-scoped creation already binds the Thread; no bootstrap request is made.
    expect(calls).toEqual(['createChatThread:chat-1', 'user:t1:cid-user:hello:1'])
    expect(submitThreadMessage).toHaveBeenCalledWith('t1', {
      content: 'hello',
      clientMessageId: 'cid-user',
      expectedExecutionEpoch: 1,
    })
    expect(result.sessionId).toBe('s1')
    expect(result.thread).toMatchObject({
      threadId: 't1',
      sessionId: 's1',
      activeAgentDefinitionId: 'a1',
      executionEpoch: 1,
      status: 'IDLE',
    })
  })

  it('rejects a create response without a Session and never enqueues the message', async () => {
    const createChatThread = vi.fn(async () => thread({ threadId: 't9', sessionId: null }))
    const submitThreadMessage = vi.fn()

    await expect(
      performBlankPaneFirstSend({
        chatId: 'chat-1',
        content: 'hello',
        createChatThread,
        submitThreadMessage: submitThreadMessage as never,
      }),
    ).rejects.toThrow('创建 Chat Thread 未返回 Session')
    expect(submitThreadMessage).not.toHaveBeenCalled()
  })

  it('does not enqueue a message when the atomic Thread creation fails', async () => {
    const createChatThread = vi.fn(async () => {
      throw new Error('unknown agent definition: 404')
    })
    const submitThreadMessage = vi.fn()

    await expect(
      performBlankPaneFirstSend({
        chatId: 'chat-1',
        content: 'hello',
        createChatThread,
        submitThreadMessage: submitThreadMessage as never,
      }),
    ).rejects.toThrow('unknown agent definition: 404')
    expect(submitThreadMessage).not.toHaveBeenCalled()
  })
})
