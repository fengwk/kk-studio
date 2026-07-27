import { describe, expect, it } from 'vitest'
import {
  canRebindThread,
  groupThreadsBySessionId,
  isRunningThread,
  isSessionRunning,
  sortChatSessionsWithRunningFirst,
  toSessionSelectionItem,
  toThreadSelectionItem,
} from '@/features/ai/chat-session-picker'
import type { HarnessSessionDTO, HarnessThreadDTO } from '@/shared/api/contracts'

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
    expect(isRunningThread(thread(null, 't', 'UNBOUND', false))).toBe(false)
    expect(isSessionRunning([thread('s', 't1', 'IDLE'), thread('s', 't2', 'WAITING')])).toBe(true)
    expect(isSessionRunning([])).toBe(false)
  })

  it('allows rebind only for quiescent UNBOUND/IDLE Threads', () => {
    expect(canRebindThread(thread(null, 't', 'UNBOUND'))).toBe(true)
    expect(canRebindThread(thread('s', 't', 'IDLE'))).toBe(true)
    // ACTIVE statuses are rejected so an in-flight execution can never be moved underneath.
    expect(canRebindThread(thread('s', 't', 'RUNNING'))).toBe(false)
    expect(canRebindThread(thread('s', 't', 'WAITING'))).toBe(false)
    expect(canRebindThread(thread('s', 't', 'RUNNABLE'))).toBe(false)
    // A held processor lease also blocks rebind even when the projected status reads IDLE.
    expect(canRebindThread(thread('s', 't', 'IDLE', true))).toBe(false)
    expect(canRebindThread(undefined)).toBe(false)
  })

  it('groups globally listed Threads by derived Session and drops UNBOUND ones', () => {
    const grouped = groupThreadsBySessionId([
      thread('s1', 't1', 'IDLE'),
      thread('s1', 't2', 'RUNNING'),
      thread('s2', 't3', 'IDLE'),
      thread(null, 't-unbound', 'UNBOUND'),
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
    const item = toSessionSelectionItem(session('s1', '2026-07-20T12:00:00Z'))
    expect(item).toEqual({ id: 's1', title: 's1', subtitle: 'Session s1', badge: undefined })
    expect(item.subtitle).not.toContain('Main')
  })

  it('projects Thread rows with UNBOUND kept selectable and labelled', () => {
    expect(toThreadSelectionItem(thread('s1', 't1', 'RUNNING'))).toEqual({
      id: 't1',
      title: 't1',
      subtitle: 's1',
      badge: 'RUNNING',
    })
    // UNBOUND is a legal state: the row stays listed so /session or /tree can bind it.
    expect(toThreadSelectionItem(thread(null, 't-unbound', 'UNBOUND'))).toEqual({
      id: 't-unbound',
      title: 't-unbound',
      subtitle: '未绑定 Session',
      badge: 'UNBOUND',
    })
  })
})
