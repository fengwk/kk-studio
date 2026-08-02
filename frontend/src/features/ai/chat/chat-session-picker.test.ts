import { describe, expect, it } from 'vitest'
import {
  canRebindThread,
  groupThreadsBySessionId,
  isRunningThread,
  isSessionRunning,
  sortChatSessionsWithRunningFirst,
  toSessionSelectionItemWithRunning,
  toThreadSelectionItem,
} from '@/features/ai/chat/chat-session-picker'
import type { HarnessSessionDTO, HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

function session(id: string, updateTime: string, createTime = updateTime): HarnessSessionDTO {
  return {
    sessionId: id,
    title: id,
    createTime,
    updateTime,
  }
}

function thread(
  sessionId: string | null,
  threadId: string,
  status: HarnessThreadDTO['status'],
  processing = false,
): HarnessThreadDTO {
  return {
    threadId,
    sessionId,
    sessionTitle: sessionId,
    headEntryId: sessionId ? 'h1' : null,
    executionEpoch: 3,
    status,
    inputSequence: 0,
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

  it('allows rebind only for quiescent IDLE Threads', () => {
    expect(canRebindThread(thread('s', 't', 'IDLE'))).toBe(true)
    // ACTIVE statuses are rejected so an in-flight execution can never be moved underneath.
    expect(canRebindThread(thread('s', 't', 'RUNNING'))).toBe(false)
    expect(canRebindThread(thread('s', 't', 'WAITING'))).toBe(false)
    expect(canRebindThread(thread('s', 't', 'RUNNABLE'))).toBe(false)
    // A held processor lease also blocks rebind even when the projected status reads IDLE.
    expect(canRebindThread(thread('s', 't', 'IDLE', true))).toBe(false)
    expect(canRebindThread(undefined)).toBe(false)
  })

  it('groups globally listed Threads by derived Session', () => {
    const grouped = groupThreadsBySessionId([
      thread('s1', 't1', 'IDLE'),
      thread('s1', 't2', 'RUNNING'),
      thread('s2', 't3', 'IDLE'),
    ])
    expect([...grouped.keys()].sort()).toEqual(['s1', 's2'])
    expect(grouped.get('s1')?.map((item) => item.threadId)).toEqual(['t1', 't2'])
    expect(grouped.get('s2')?.map((item) => item.threadId)).toEqual(['t3'])
  })

  it('sorts a Session with a RUNNING Thread above a newer idle Session', () => {
    const sessions = [
      session('s-idle', '2026-07-20T12:00:00Z'),
      session('s-running', '2026-07-19T12:00:00Z'),
      session('s-older-idle', '2026-07-18T12:00:00Z'),
    ]
    // Running-first is derived from the global Thread list, not from a per-Session request.
    const threadsBySessionId = groupThreadsBySessionId([
      thread('s-idle', 't-idle', 'IDLE'),
      thread('s-running', 't-running-a', 'IDLE'),
      thread('s-running', 't-running-b', 'RUNNING'),
      thread('s-older-idle', 't-older', 'IDLE'),
    ])

    const sorted = sortChatSessionsWithRunningFirst(sessions, threadsBySessionId, 'recent')
    expect(sorted.map((item) => item.sessionId)).toEqual(['s-running', 's-idle', 's-older-idle'])

    // Missing thread list for a Session is treated as not running.
    const partial = sortChatSessionsWithRunningFirst(sessions, new Map([['s-idle', []]]), 'recent')
    expect(partial.map((item) => item.sessionId)).toEqual(['s-idle', 's-running', 's-older-idle'])
  })

  it('projects Session rows without a main Thread reference', () => {
    const item = toSessionSelectionItemWithRunning(session('s1', '2026-07-20T12:00:00Z'), false)
    expect(item).toEqual({ id: 's1', title: 's1', subtitle: 'Session s1', badge: undefined })
    expect(item.subtitle).not.toContain('Main')
  })

  it('projects Thread rows with their session context', () => {
    expect(toThreadSelectionItem(thread('s1', 't1', 'RUNNING'))).toEqual({
      id: 't1',
      title: 't1',
      subtitle: 's1',
      badge: 'RUNNING',
    })
  })

  it('uses the selected sort timestamp and omits a placeholder time when absent', () => {
    const item = toThreadSelectionItem(
      {
        ...thread('s1', 't-created', 'IDLE'),
        createTime: '2026-07-21T08:30:00Z',
        updateTime: null,
      },
      'created',
    )
    expect(item.subtitle).toContain('2026-07-21 08:30')
    expect(toThreadSelectionItem(thread(null, 't-no-time', 'IDLE')).subtitle).toBe('t-no-time')
  })
})
