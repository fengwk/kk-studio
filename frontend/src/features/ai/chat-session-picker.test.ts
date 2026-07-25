import { describe, expect, it } from 'vitest'
import {
  isRunningThread,
  isSessionRunning,
  sortChatSessionsWithRunningFirst,
} from '@/features/ai/chat-session-picker'
import type { HarnessSessionDTO, HarnessThreadDTO } from '@/shared/api/contracts'

function session(id: string, mainThreadId: string, updateTime: string, createTime = updateTime): HarnessSessionDTO {
  return {
    sessionId: id,
    title: id,
    mainThreadId,
    rootSessionId: id,
    parentSessionId: null,
    parentInvocationId: null,
    depth: 0,
    createTime,
    updateTime,
  }
}

function thread(
  sessionId: string,
  threadId: string,
  status: HarnessThreadDTO['status'],
  processing = false,
): HarnessThreadDTO {
  return {
    threadId,
    sessionId,
    sessionTitle: sessionId,
    headEntryId: null,
    status,
    inputSequence: 0,
    activeAgentDefinitionId: null,
    activeAgentName: null,
    modelId: null,
    variant: null,
    yoloEnabled: false,
    processing,
    createTime: null,
    updateTime: null,
  }
}

describe('chat-session-picker', () => {
  it('detects running threads including processing-only and RUNNABLE', () => {
    expect(isRunningThread(thread('s', 't', 'IDLE', true))).toBe(true)
    expect(isRunningThread(thread('s', 't', 'IDLE', false))).toBe(false)
    expect(isRunningThread(thread('s', 't', 'RUNNABLE', false))).toBe(true)
    expect(isSessionRunning([thread('s', 't1', 'IDLE'), thread('s', 't2', 'WAITING')])).toBe(true)
    expect(isSessionRunning([])).toBe(false)
  })

  it('sorts a non-current Session with RUNNING Thread above a newer idle Session', () => {
    // s-idle is newer; s-running is older but has a RUNNING Thread that is not the "current" bound session.
    const sessions = [
      session('s-idle', 't-idle-main', '2026-07-20T12:00:00Z'),
      session('s-running', 't-running-main', '2026-07-19T12:00:00Z'),
      session('s-older-idle', 't-older', '2026-07-18T12:00:00Z'),
    ]
    const threadsBySessionId = new Map<string, HarnessThreadDTO[]>([
      ['s-idle', [thread('s-idle', 't-idle-main', 'IDLE')]],
      // Running thread is a secondary thread, not main — still counts for the whole Session.
      [
        's-running',
        [
          thread('s-running', 't-running-main', 'IDLE'),
          thread('s-running', 't-running-secondary', 'RUNNING'),
        ],
      ],
      ['s-older-idle', [thread('s-older-idle', 't-older', 'IDLE')]],
    ])

    const sorted = sortChatSessionsWithRunningFirst(sessions, threadsBySessionId, 'recent')
    expect(sorted.map((item) => item.sessionId)).toEqual(['s-running', 's-idle', 's-older-idle'])

    // Missing thread list for a Session is treated as not running.
    const partial = sortChatSessionsWithRunningFirst(sessions, new Map([['s-idle', []]]), 'recent')
    expect(partial.map((item) => item.sessionId)).toEqual(['s-idle', 's-running', 's-older-idle'])
  })
})
