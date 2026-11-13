import { describe, expect, it } from 'vitest'
import {
  compactionRetryDisabledReasonKey,
  type CompactionActions,
} from '@/features/thread/runtime/thread-panel/compaction-actions-context'
import type { CommandCompactionState } from '@/features/thread/runtime/thread-panel/thread-commands'

function actions(availability: CommandCompactionState | null, compactPending = false): CompactionActions {
  return { compact: () => {}, compactPending, availability }
}

describe('compaction retry availability', () => {
  it('allows the retry only when the shared availability check reports ready and available', () => {
    expect(compactionRetryDisabledReasonKey(
      actions({ status: 'ready', availability: { available: true, disabledReason: null } }),
    )).toBeNull()
  })

  it('uses the same checking/failed reasons as the command palette', () => {
    expect(compactionRetryDisabledReasonKey(actions(null)))
      .toBe('ai.runtime.command.disabled.compaction.checking')
    expect(compactionRetryDisabledReasonKey(actions({ status: 'checking' })))
      .toBe('ai.runtime.command.disabled.compaction.checking')
    expect(compactionRetryDisabledReasonKey(actions({ status: 'failed' })))
      .toBe('ai.runtime.command.disabled.compaction.checkFailed')
  })

  it('maps every backend disabled reason to its shared message', () => {
    const reason = (disabledReason: string | null) => compactionRetryDisabledReasonKey(
      actions({ status: 'ready', availability: { available: false, disabledReason } }),
    )
    expect(reason('THREAD_BUSY')).toBe('ai.runtime.command.disabled.compaction.threadBusy')
    expect(reason('THREAD_STOPPED')).toBe('ai.runtime.command.disabled.compaction.threadStopped')
    expect(reason('NOTHING_TO_COMPACT'))
      .toBe('ai.runtime.command.disabled.compaction.nothingToCompact')
    expect(reason('SOMETHING_NEW')).toBe('ai.runtime.action.compactUnavailable')
    expect(reason(null)).toBe('ai.runtime.action.compactUnavailable')
  })

  it('disables retry while a compaction request is already pending', () => {
    expect(compactionRetryDisabledReasonKey(
      actions({ status: 'ready', availability: { available: true, disabledReason: null } }, true),
    )).toBe('ai.runtime.entry.compactionRetryPending')
  })
})
