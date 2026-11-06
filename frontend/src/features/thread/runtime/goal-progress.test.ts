import { describe, expect, it } from 'vitest'
import { parseGoalProgress } from '@/features/thread/runtime/goal-progress'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

function makeEntry(overrides: Partial<HarnessSessionEntryDTO> = {}): HarnessSessionEntryDTO {
  return {
    entryId: 'entry-1',
    sessionId: 'session-1',
    parentEntryId: null,
    entryType: 'CUSTOM',
    payload: JSON.stringify({
      contributorId: 'builtin',
      customType: 'goal.progress',
      data: {
        goalId: 'goal-123',
        status: 'complete',
        reason: 'Successfully completed the task',
        reportedAt: '2026-09-24T12:00:00Z',
      },
    }),
    createdAt: 1790251200,
    usageCost: null,
    ...overrides,
  }
}

describe('parseGoalProgress', () => {
  it('returns empty progress for empty entries or non-CUSTOM entries', () => {
    expect(parseGoalProgress([], 'goal-123')).toEqual({ active: null, stale: null })

    const userEntry = makeEntry({
      entryType: 'MESSAGE',
      payload: JSON.stringify({ text: 'hello' }),
    })
    expect(parseGoalProgress([userEntry], 'goal-123')).toEqual({ active: null, stale: null })
  })

  it('parses active goal.progress with matching goalId from data object', () => {
    const entry = makeEntry({
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
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
      createdAt: 1790251200,
    })
  })

  it('parses active goal.progress with matching goalId from stringified dataJson', () => {
    const entry = makeEntry({
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
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
      createdAt: 1790251200,
    })
  })

  it('marks report as stale if goalId does not match currentGoalId', () => {
    const entry = makeEntry({
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
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
      createdAt: 1790251200,
    })
  })

  it('marks report as stale if currentGoalId is null (goal cleared)', () => {
    const entry = makeEntry({
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
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
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
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
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
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
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
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
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
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

  it('ignores entries with invalid contributorId, customType, or status', () => {
    const invalidContributor = makeEntry({
      payload: JSON.stringify({
        contributorId: 'other-plugin',
        customType: 'goal.progress',
        data: { goalId: 'goal-123', status: 'complete', reason: 'ok' },
      }),
    })
    const invalidType = makeEntry({
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'other.progress',
        data: { goalId: 'goal-123', status: 'complete', reason: 'ok' },
      }),
    })
    const invalidStatus = makeEntry({
      payload: JSON.stringify({
        contributorId: 'builtin',
        customType: 'goal.progress',
        data: { goalId: 'goal-123', status: 'in_progress', reason: 'ok' },
      }),
    })

    expect(parseGoalProgress([invalidContributor], 'goal-123')).toEqual({ active: null, stale: null })
    expect(parseGoalProgress([invalidType], 'goal-123')).toEqual({ active: null, stale: null })
    expect(parseGoalProgress([invalidStatus], 'goal-123')).toEqual({ active: null, stale: null })
  })
})
