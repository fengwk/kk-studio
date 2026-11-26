import { describe, expect, it } from 'vitest'
import {
  filterThreadCommands,
  manualCompactionDisabledReasonKey,
  THREAD_COMMANDS,
} from '@/features/thread/runtime/thread-panel/thread-commands'

describe('thread command registry', () => {
  it('contains only commands supported by the thread composer', () => {
    expect(THREAD_COMMANDS.map(({ id }) => id)).toEqual([
      'yolo', 'models', 'stop', 'upload', 'debug',
    ])
  })

  it('filters command names and keywords and strips a slash prefix', () => {
    expect(filterThreadCommands('/sto').map(({ id }) => id)).toEqual(['stop'])
    expect(filterThreadCommands('interrupt').map(({ id }) => id)).toEqual(['stop'])
    expect(filterThreadCommands('debug').map(({ id }) => id)).toEqual(['debug'])
    expect(filterThreadCommands('no such command')).toEqual([])
    expect(filterThreadCommands(' ')).toEqual(THREAD_COMMANDS)
  })

  it('maps compaction failure reasons to localized keys', () => {
    expect(manualCompactionDisabledReasonKey('THREAD_STOPPED'))
      .toBe('ai.runtime.command.disabled.compaction.threadStopped')
    expect(manualCompactionDisabledReasonKey('FUTURE_REASON')).toBe('ai.runtime.action.compactUnavailable')
  })
})
