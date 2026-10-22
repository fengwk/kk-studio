import { describe, expect, it } from 'vitest'
import {
  projectDurableEntry,
  type EntryProjectionContext,
} from '@/features/chat/runtime/thread-timeline/entry-projection'
import type { DialogueMessage } from '@/features/chat/runtime/thread-timeline-types'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

function projectionContext(): EntryProjectionContext {
  return { pendingTurnSummary: null, lastSettings: null }
}

function abortedEntry(
  text: string,
  thinking: string,
  entryId = 'aborted-1',
): HarnessSessionEntryDTO {
  const message = {
    role: 'ASSISTANT',
    contents: [] as Array<Record<string, unknown>>,
  }
  if (text) {
    message.contents.push({ type: 'text', text })
  }
  if (thinking) {
    message.contents.push({ type: 'thinking', text: thinking })
  }
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: '2',
    entryType: 'ASSISTANT_ABORTED',
    payloadJson: JSON.stringify({ message }),
    createTime: 1782864000,
    usageCost: null,
  }
}

describe('ASSISTANT_ABORTED entry projection', () => {
  it('renders aborted assistant text with aborted=true and clears realtime overlay flags', () => {
    const messages: DialogueMessage[] = []
    projectDurableEntry(abortedEntry('partial answer', 'thinking'), messages, new Map(), projectionContext())
    expect(messages).toHaveLength(1)
    const msg = messages[0]
    expect(msg).toMatchObject({
      id: 'aborted-1',
      role: 'assistant',
      text: 'partial answer',
      status: 'done',
      aborted: true,
    })
    if (msg.role !== 'assistant') {
      throw new Error('Expected assistant message')
    }
    expect(msg.thinking).toBe('thinking')
  })

  it('renders aborted assistant thinking-only content', () => {
    const messages: DialogueMessage[] = []
    projectDurableEntry(abortedEntry('', 'reasoning'), messages, new Map(), projectionContext())
    expect(messages).toHaveLength(1)
    const msg = messages[0]
    expect(msg).toMatchObject({ text: '', aborted: true })
    if (msg.role !== 'assistant') {
      throw new Error('Expected assistant message')
    }
    expect(msg.thinking).toBe('reasoning')
  })
})
