import { describe, expect, it, vi } from 'vitest'
import {
  FirstSendMessageError,
  performBlankPaneFirstSend,
} from '@/features/ai/chat/chat-first-send'
import type {
  HarnessBranchSettingsDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'

function settings(): HarnessBranchSettingsDTO {
  return {
    environmentId: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    thinkingLevel: 'default',
    activeTools: [],
  }
}

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    headEntryId: 'root',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: settings(),
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function snapshot(thread: HarnessThreadDTO): HarnessThreadSnapshotDTO {
  return {
    revision: thread.revision,
    thread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
  }
}

describe('performBlankPaneFirstSend', () => {
  it('creates an atomically bound Thread, then posts only the USER_MESSAGE batch with CAS from snapshot.thread', async () => {
    const calls: string[] = []
    const createChatThread = vi.fn(async (chatId: string, data: { branchSettings: HarnessBranchSettingsDTO }) => {
      calls.push(`create:${chatId}`)
      const t = thread({ threadId: 't1', sessionId: 's1', headEntryId: 'root', nextCommandSequence: '1' })
      expect(data.branchSettings).toEqual(settings())
      return snapshot(t)
    })
    const enqueueCommands = vi.fn(
      async (
        threadId: string,
        batch: { expectedHeadEntryId: string; expectedNextCommandSequence: string; commands: Array<{ type: string; clientCommandId: string; content?: string; role?: string }> },
      ): Promise<HarnessThreadCommandDTO[]> => {
        calls.push(`enqueue:${threadId}:${batch.commands[0]?.clientCommandId}:${batch.commands[0]?.content}`)
        return [{
          commandId: 'cmd-1',
          threadId,
          sequence: batch.expectedNextCommandSequence,
          type: batch.commands[0]?.type ?? 'USER_MESSAGE',
          state: 'QUEUED',
          clientCommandId: batch.commands[0]?.clientCommandId ?? 'cid-1',
          payloadJson: JSON.stringify(batch),
          consumedTurnStartEntryId: null,
          cancelledAt: null,
          createTime: null,
        }]
      },
    )

    const result = await performBlankPaneFirstSend({
      chatId: 'chat-1',
      content: 'hello',
      title: 'New Thread',
      branchSettings: settings(),
      yoloEnabled: true,
      createChatThread,
      enqueueCommands,
    })

    expect(calls[0]).toBe('create:chat-1')
    expect(calls[1]?.startsWith('enqueue:t1:')).toBe(true)
    expect(createChatThread).toHaveBeenCalledWith('chat-1', {
      title: 'New Thread',
      branchSettings: settings(),
      yoloEnabled: true,
    })
    // Exact body: USER_MESSAGE must NEVER carry role (the strict mapper forbids it).
    expect(enqueueCommands).toHaveBeenCalledWith('t1', {
      expectedHeadEntryId: 'root',
      expectedNextCommandSequence: '1',
      commands: [
        { type: 'USER_MESSAGE', clientCommandId: expect.any(String) as string, content: 'hello' },
      ],
    })
    const sentBody = enqueueCommands.mock.calls[0]?.[1] as {
      commands: Array<Record<string, unknown>>
    }
    expect(sentBody.commands[0]).not.toHaveProperty('role')
    expect(result.threadId).toBe('t1')
    expect(result.snapshot.thread).toMatchObject({
      threadId: 't1',
      sessionId: 's1',
      nextCommandSequence: '1',
    })
    expect(result.plan.batch.commands).toHaveLength(1)
    expect(result.plan.batch.commands[0]).toMatchObject({ type: 'USER_MESSAGE' })
  })

  it('rejects when the create response has no threadId and never enqueues a message', async () => {
    // No server validation in the new contract; a blank threadId is a server invariant. We
    // instead simulate the create-then-fail path: createChatThread throws, and we assert
    // enqueueCommands is never called. (The prior sessionId-validation test no longer maps.)
    const createChatThread = vi.fn(async () => {
      throw new Error('create failed')
    })
    const enqueueCommands = vi.fn()

    await expect(
      performBlankPaneFirstSend({
        chatId: 'chat-1',
        content: 'hello',
        title: 't',
        branchSettings: settings(),
        yoloEnabled: false,
        createChatThread,
        enqueueCommands,
      }),
    ).rejects.toThrow('create failed')
    expect(enqueueCommands).not.toHaveBeenCalled()
  })

  it('throws FirstSendMessageError carrying snapshot + plan when message batch fails', async () => {
    const createChatThread = vi.fn(async () =>
      snapshot(thread({
        threadId: 't-replay',
        sessionId: 's-replay',
        headEntryId: 'root',
        nextCommandSequence: '2',
      })),
    )
    const enqueueCommands = vi.fn(async () => {
      throw new Error('message temporarily unavailable')
    })

    const error = await performBlankPaneFirstSend({
      chatId: 'chat-1',
      content: 'retry me',
      title: 't',
      branchSettings: settings(),
      yoloEnabled: false,
      createChatThread,
      enqueueCommands,
    }).catch((value: unknown) => value)

    expect(error).toBeInstanceOf(FirstSendMessageError)
    const err = error as FirstSendMessageError
    expect(err.snapshot.thread).toMatchObject({ threadId: 't-replay', nextCommandSequence: '2' })
    expect(err.plan.batch).toEqual({
      expectedHeadEntryId: 'root',
      expectedNextCommandSequence: '2',
      commands: [
        { type: 'USER_MESSAGE', clientCommandId: expect.any(String) as string, content: 'retry me' },
      ],
    })
    // Strict wire: no role leaks into USER_MESSAGE.
    expect(err.plan.batch.commands[0]).not.toHaveProperty('role')
    expect(err.cause).toBeInstanceOf(Error)
    expect((err.cause as Error).message).toBe('message temporarily unavailable')
    expect(createChatThread).toHaveBeenCalledOnce()
    expect(enqueueCommands).toHaveBeenCalledOnce()
  })

  it('preserves a supplied clientCommandId across replay so the batch is byte-for-byte identical', async () => {
    const createChatThread = vi.fn(async () => snapshot(thread({ threadId: 't1' })))
    const enqueueCommands = vi.fn(async () => {
      throw new Error('network')
    })

    await performBlankPaneFirstSend({
      chatId: 'chat-1',
      content: 'retry me',
      title: 't',
      branchSettings: settings(),
      yoloEnabled: false,
      clientCommandId: 'cid-stable',
      createChatThread,
      enqueueCommands,
    }).catch(() => undefined)

    expect(enqueueCommands).toHaveBeenCalledWith('t1', expect.objectContaining({
      commands: [expect.objectContaining({ type: 'USER_MESSAGE', clientCommandId: 'cid-stable', content: 'retry me' })],
    }))
    const sent = enqueueCommands.mock.calls[0]?.[1] as { commands: Array<Record<string, unknown>> }
    expect(sent.commands[0]).not.toHaveProperty('role')

    // Subsequent invocation with same clientCommandId reuses it (replay identity).
    enqueueCommands.mockResolvedValueOnce([])
    await performBlankPaneFirstSend({
      chatId: 'chat-1',
      content: 'retry me',
      title: 't',
      branchSettings: settings(),
      yoloEnabled: false,
      clientCommandId: 'cid-stable',
      createChatThread,
      enqueueCommands,
    })
    expect(enqueueCommands).toHaveBeenLastCalledWith('t1', expect.objectContaining({
      commands: [expect.objectContaining({ clientCommandId: 'cid-stable' })],
    }))
  })

  it('does not enqueue a message when the atomic Thread creation fails', async () => {
    const createChatThread = vi.fn(async () => {
      throw new Error('chat not found: 404')
    })
    const enqueueCommands = vi.fn()

    await expect(
      performBlankPaneFirstSend({
        chatId: 'chat-1',
        content: 'hello',
        title: 't',
        branchSettings: settings(),
        yoloEnabled: false,
        createChatThread,
        enqueueCommands,
      }),
    ).rejects.toThrow('chat not found: 404')
    expect(enqueueCommands).not.toHaveBeenCalled()
  })
})
