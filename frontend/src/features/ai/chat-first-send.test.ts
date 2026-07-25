import { describe, expect, it, vi } from 'vitest'
import { performBlankPaneFirstSend } from '@/features/ai/chat-first-send'

describe('performBlankPaneFirstSend', () => {
  it('creates agentless session, attaches, then enqueues SET_AGENT before USER_MESSAGE', async () => {
    const calls: string[] = []
    const createSession = vi.fn(async () => {
      calls.push('createSession')
      return {
        sessionId: 's1',
        title: null,
        mainThreadId: 't1',
        rootSessionId: 's1',
        parentSessionId: null,
        parentInvocationId: null,
        depth: 0,
        createTime: null,
        updateTime: null,
      }
    })
    const attachChatSession = vi.fn(async () => {
      calls.push('attach')
      return {
        sessionId: 's1',
        title: null,
        mainThreadId: 't1',
        rootSessionId: 's1',
        parentSessionId: null,
        parentInvocationId: null,
        depth: 0,
        createTime: null,
        updateTime: null,
      }
    })
    const setThreadAgent = vi.fn(async (_threadId: string, data: { clientMessageId: string }) => {
      calls.push(`setAgent:${data.clientMessageId}`)
      return {
        inputId: 'i1',
        threadId: 't1',
        sequence: 1,
        inputType: 'SET_AGENT' as const,
        payloadJson: '{}',
        clientMessageId: data.clientMessageId,
        status: 'QUEUED' as const,
            resolvedAt: null,
            createTime: null,
      }
    })
    const submitThreadMessage = vi.fn(async (_threadId: string, data: { clientMessageId: string; content: string }) => {
      calls.push(`user:${data.clientMessageId}:${data.content}`)
      return {
        inputId: 'i2',
        threadId: 't1',
        sequence: 2,
        inputType: 'USER_MESSAGE' as const,
        payloadJson: '{}',
        clientMessageId: data.clientMessageId,
        status: 'QUEUED' as const,
            resolvedAt: null,
            createTime: null,
      }
    })

    const result = await performBlankPaneFirstSend({
      chatId: 'c1',
      agentDefinitionId: 'a1',
      content: 'hello',
      createSession,
      attachChatSession,
      setThreadAgent,
      submitThreadMessage,
      createIds: () => ({ setAgentId: 'cid-agent', userMessageId: 'cid-user' }),
    })

    expect(createSession).toHaveBeenCalledWith({})
    expect(attachChatSession).toHaveBeenCalledWith('c1', { sessionId: 's1' })
    expect(setThreadAgent).toHaveBeenCalledWith('t1', {
      agentDefinitionId: 'a1',
      clientMessageId: 'cid-agent',
    })
    expect(submitThreadMessage).toHaveBeenCalledWith('t1', {
      content: 'hello',
      clientMessageId: 'cid-user',
    })
    expect(calls).toEqual(['createSession', 'attach', 'setAgent:cid-agent', 'user:cid-user:hello'])
    expect(result).toMatchObject({ sessionId: 's1', threadId: 't1' })
  })
})
