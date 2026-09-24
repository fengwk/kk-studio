import { describe, expect, it } from 'vitest'
import { parseGoalProgress } from '@/features/ai/runtime/goal-progress'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'

function makeEntry(overrides: Partial<HarnessSessionEntryDTO> = {}): HarnessSessionEntryDTO {
  return {
    entryId: 'entry-1',
    sessionId: 'session-1',
    threadId: 'thread-1',
    parentEntryId: null,
    entryType: 'CUSTOM',
    author: { type: 'AGENT', name: 'assistant' },
    status: 'COMMITTED',
    payloadJson: JSON.stringify({
      contributorId: 'builtin',
      customType: 'goal.progress',
      schemaVersion: 1,
      data: {
        goalId: 'goal-123',
        status: 'complete',
        reason: 'Successfully completed the task',
        reportedAt: '2026-09-24T12:00:00Z',
      },
    }),
    createTime: '2026-09-24T12:00:00Z',
    updateTime: '2026-09-24T12:00:00Z',
    ...overrides,
  }
}

describe('parseGoalProgress', () => {
  it('returns empty progress for empty entries or non-CUSTOM entries', () => {
    expect(parseGoalProgress([], 'goal-123')).toEqual({ active: null, stale: null })

    const userEntry = makeEntry({
      entryType: 'USER_MESSAGE',
      payloadJson: JSON.stringify({ text: 'hello' }),
    })
    expect(parseGoalProgress([userEntry], 'goal-123')).toEqual({ active: null, stale: null })
  })

  it('parses active goal.progress with matching goalId from data object', () => {
    const entry = makeEntry({
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: {
          goalId: 'goal-123',
          status: 'complete',
          reason: 'All requirements verified',
          reportedAt: '2026-09-24T10:00:00Z',
        },
      }),
    })

    const result = parseGoalProgress([entry], 'goal-123')
    expect(result.stale).toBeNull()
    expect(result.active).toEqual({
      entryId: 'entry-1',
      goalId: 'goal-123',
      status: 'complete',
      reason: 'All requirements verified',
      reportedAt: '2026-09-24T10:00:00Z',
      createTime: '2026-09-24T12:00:00Z',
    })
  })

  it('parses active goal.progress with matching goalId from stringified dataJson', () => {
    const entry = makeEntry({
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        dataJson: JSON.stringify({
          goalId: 'goal-123',
          status: 'blocked',
          reason: 'Missing API key',
          reportedAt: '2026-09-24T11:00:00Z',
        }),
      }),
    })

    const result = parseGoalProgress([entry], 'goal-123')
    expect(result.stale).toBeNull()
    expect(result.active).toEqual({
      entryId: 'entry-1',
      goalId: 'goal-123',
      status: 'blocked',
      reason: 'Missing API key',
      reportedAt: '2026-09-24T11:00:00Z',
      createTime: '2026-09-24T12:00:00Z',
    })
  })

  it('marks report as stale if goalId does not match currentGoalId', () => {
    const entry = makeEntry({
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: {
          goalId: 'old-goal-456',
          status: 'complete',
          reason: 'Old task completed',
          reportedAt: '2026-09-24T09:00:00Z',
        },
      }),
    })

    const result = parseGoalProgress([entry], 'new-goal-789')
    expect(result.active).toBeNull()
    expect(result.stale).toEqual({
      entryId: 'entry-1',
      goalId: 'old-goal-456',
      status: 'complete',
      reason: 'Old task completed',
      reportedAt: '2026-09-24T09:00:00Z',
      createTime: '2026-09-24T12:00:00Z',
    })
  })

  it('marks report as stale if currentGoalId is null (goal cleared)', () => {
    const entry = makeEntry({
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: {
          goalId: 'old-goal-456',
          status: 'complete',
          reason: 'Old task completed',
          reportedAt: '2026-09-24T09:00:00Z',
        },
      }),
    })

    const result = parseGoalProgress([entry], null)
    expect(result.active).toBeNull()
    expect(result.stale?.goalId).toBe('old-goal-456')
  })

  it('picks the latest goal.progress from multiple entries', () => {
    const olderEntry = makeEntry({
      entryId: 'entry-1',
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: {
          goalId: 'goal-123',
          status: 'blocked',
          reason: 'Step 1 blocked',
          reportedAt: '2026-09-24T09:00:00Z',
        },
      }),
    })
    const newerEntry = makeEntry({
      entryId: 'entry-2',
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: {
          goalId: 'goal-123',
          status: 'complete',
          reason: 'Step 1 resolved and finished',
          reportedAt: '2026-09-24T09:30:00Z',
        },
      }),
    })

    const result = parseGoalProgress([olderEntry, newerEntry], 'goal-123')
    expect(result.active?.entryId).toBe('entry-2')
    expect(result.active?.status).toBe('complete')
  })

  it('finds latest matching active report even when a newer stale report exists without letting stale override current', () => {
    const activeEntry = makeEntry({
      entryId: 'entry-active',
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: {
          goalId: 'current-goal',
          status: 'complete',
          reason: 'Done current goal',
          reportedAt: '2026-09-24T10:00:00Z',
        },
      }),
    })
    const newerMismatchedEntry = makeEntry({
      entryId: 'entry-mismatched',
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: {
          goalId: 'other-or-stale-goal',
          status: 'blocked',
          reason: 'Irrelevant stale report',
          reportedAt: '2026-09-24T10:30:00Z',
        },
      }),
    })

    const result = parseGoalProgress([activeEntry, newerMismatchedEntry], 'current-goal')
    expect(result.active).not.toBeNull()
    expect(result.active?.goalId).toBe('current-goal')
    expect(result.active?.status).toBe('complete')
    expect(result.stale).toBeNull()
  })

  it('ignores entries with invalid schemaVersion, contributorId, customType, or status', () => {
    const invalidContributor = makeEntry({
      payloadJson: JSON.stringify({
        contributorId: 'other-plugin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: { goalId: 'goal-123', status: 'complete', reason: 'ok' },
      }),
    })
    const invalidSchema = makeEntry({
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 2,
        data: { goalId: 'goal-123', status: 'complete', reason: 'ok' },
      }),
    })
    const invalidType = makeEntry({
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'other.progress',
        schemaVersion: 1,
        data: { goalId: 'goal-123', status: 'complete', reason: 'ok' },
      }),
    })
    const invalidStatus = makeEntry({
      payloadJson: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        schemaVersion: 1,
        data: { goalId: 'goal-123', status: 'in_progress', reason: 'ok' },
      }),
    })

    expect(parseGoalProgress([invalidContributor], 'goal-123')).toEqual({ active: null, stale: null })
    expect(parseGoalProgress([invalidSchema], 'goal-123')).toEqual({ active: null, stale: null })
    expect(parseGoalProgress([invalidType], 'goal-123')).toEqual({ active: null, stale: null })
    expect(parseGoalProgress([invalidStatus], 'goal-123')).toEqual({ active: null, stale: null })
  })
})
