import { describe, expect, it } from 'vitest'
import {
  projectDurableEntry,
  type EntryProjectionContext,
} from '@/features/thread/runtime/thread-timeline/entry-projection'
import type { DialogueMessage } from '@/features/thread/runtime/thread-timeline-types'
import { getLocale, LOCALE_STORAGE_KEY, setLocale } from '@/shared/i18n'
import { createBuiltinExecution } from '@/shared/api/contracts/ai-runtime'
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

const settings = {
  agentName: 'coder',
  execution: createBuiltinExecution('gone', 'default'),
  environmentName: null,
}

function turnStart(executedSelection: unknown): HarnessSessionEntryDTO {
  return {
    entryId: 'turn-1',
    sessionId: 'session-1',
    parentEntryId: 'root-1',
    entryType: 'TURN_START',
    payloadJson: JSON.stringify({ reason: 'INPUT', settings, executedSelection }),
    createTime: 1792368000,
    usageCost: null,
  }
}

function project(entry: HarnessSessionEntryDTO): DialogueMessage[] {
  const messages: DialogueMessage[] = []
  projectDurableEntry(entry, messages, new Map(), {
    pendingTurnSummary: null,
    lastSettings: settings,
  })
  return messages
}

describe('TURN_START executedSelection fallback projection', () => {
  // 模型失效改用 Agent 默认模型时，时间线说明本轮实际使用的模型，且不当作设置变更。
  it('announces the default model that served the turn', () => {
    const previousLocale = getLocale()
    const storedLocale = localStorage.getItem(LOCALE_STORAGE_KEY)
    try {
      setLocale('en-US')
      const messages = project(
        turnStart(createBuiltinExecution('gpt-5', 'default')),
      )
      expect(messages).toHaveLength(1)
      expect(messages[0]).toMatchObject({ id: 'entry:turn-1:model-fallback', kind: 'settings_change' })
      expect(JSON.stringify(messages[0])).toContain('this turn used the agent default model')
    } finally {
      setLocale(previousLocale)
      if (storedLocale === null) {
        localStorage.removeItem(LOCALE_STORAGE_KEY)
      } else {
        localStorage.setItem(LOCALE_STORAGE_KEY, storedLocale)
      }
    }
  })

  it('stays silent when the turn ran on the thread model', () => {
    expect(project(turnStart(null))).toEqual([])
    expect(project(turnStart({ driver: 'builtin', settings: {} }))).toEqual([])
  })
})
