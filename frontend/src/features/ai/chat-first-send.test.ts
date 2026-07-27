import { describe, expect, it, vi } from 'vitest'
import { performBlankPaneFirstSend } from '@/features/ai/chat-first-send'
import type { HarnessThreadDTO } from '@/shared/api/contracts'

function thread(overrides: Partial<HarnessThreadDTO>): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: null,
    sessionTitle: null,
    headEntryId: null,
    executionEpoch: 0,
    status: 'UNBOUND',
    inputSequence: 0,
    activeAgentDefinitionId: null,
    activeAgentName: null,
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
  it('creates an UNBOUND Thread, bootstraps it, then enqueues USER_MESSAGE with the bootstrap epoch', async () => {
    const calls: string[] = []
    const createThread = vi.fn(async () => {
      calls.push('createThread')
      return thread({ threadId: 't1', executionEpoch: 0, status: 'UNBOUND' })
    })
    const bootstrapThread = vi.fn(async (threadId: string) => {
      calls.push(`bootstrap:${threadId}`)
      return {
        session: {
          sessionId: 's1',
          title: null,
          createTime: null,
          updateTime: null,
        },
        thread: thread({
          threadId: 't1',
          sessionId: 's1',
          headEntryId: 'e-config',
          executionEpoch: 1,
          status: 'IDLE',
          activeAgentDefinitionId: 'a1',
        }),
      }
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
      agentDefinitionId: 'a1',
      content: 'hello',
      createThread,
      bootstrapThread,
      submitThreadMessage,
      createIds: () => ({ userMessageId: 'cid-user' }),
    })

    // Strict order: create -> bootstrap -> message. No POST /sessions, no chat attach, no SET_AGENT.
    expect(calls).toEqual(['createThread', 'bootstrap:t1', 'user:t1:cid-user:hello:1'])
    // Bootstrap fences against the epoch of the freshly created UNBOUND Thread.
    expect(bootstrapThread).toHaveBeenCalledWith('t1', {
      title: undefined,
      agentDefinitionId: 'a1',
      yoloEnabled: false,
      expectedExecutionEpoch: 0,
    })
    // The message must use the *post-bootstrap* epoch, not the pre-bootstrap one.
    expect(submitThreadMessage).toHaveBeenCalledWith('t1', {
      content: 'hello',
      clientMessageId: 'cid-user',
      expectedExecutionEpoch: 1,
    })
    expect(result.sessionId).toBe('s1')
    expect(result.thread.threadId).toBe('t1')
    expect(result.thread.status).toBe('IDLE')
    expect(result.thread.executionEpoch).toBe(1)
  })

  it('forwards the requested title and yolo flag into bootstrap', async () => {
    const createThread = vi.fn(async () => thread({ threadId: 't7', executionEpoch: 5 }))
    const bootstrapThread = vi.fn(async () => ({
      session: {
        sessionId: 's7',
        title: 'Titled',
        createTime: null,
        updateTime: null,
      },
      thread: thread({ threadId: 't7', sessionId: 's7', executionEpoch: 6, status: 'IDLE' }),
    }))
    const submitThreadMessage = vi.fn(async () => ({
      inputId: 'i1',
      threadId: 't7',
      sequence: 1,
      inputType: 'USER_MESSAGE' as const,
      payloadJson: '{}',
      clientMessageId: 'cid',
      status: 'QUEUED' as const,
      resolvedAt: null,
      createTime: null,
    }))

    await performBlankPaneFirstSend({
      agentDefinitionId: 'a2',
      content: 'go',
      title: 'Titled',
      yoloEnabled: true,
      createThread,
      bootstrapThread,
      submitThreadMessage,
      createIds: () => ({ userMessageId: 'cid' }),
    })

    expect(bootstrapThread).toHaveBeenCalledWith('t7', {
      title: 'Titled',
      agentDefinitionId: 'a2',
      yoloEnabled: true,
      expectedExecutionEpoch: 5,
    })
  })

  it('surfaces a bootstrap failure without enqueuing the message', async () => {
    const createThread = vi.fn(async () => thread({ threadId: 't9' }))
    const bootstrapThread = vi.fn(async () => {
      throw new Error('unknown agent definition: 404')
    })
    const submitThreadMessage = vi.fn()

    await expect(
      performBlankPaneFirstSend({
        agentDefinitionId: 'missing',
        content: 'hello',
        createThread,
        bootstrapThread,
        submitThreadMessage: submitThreadMessage as never,
      }),
    ).rejects.toThrow('unknown agent definition: 404')
    expect(submitThreadMessage).not.toHaveBeenCalled()
  })
})
