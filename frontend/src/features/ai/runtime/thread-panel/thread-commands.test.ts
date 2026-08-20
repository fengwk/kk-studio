import { describe, expect, it } from 'vitest'
import {
  commandIdsForTarget,
  filterThreadCommands,
  threadCommandsForTarget,
  THREAD_COMMANDS,
} from '@/features/ai/runtime/thread-panel/thread-commands'

describe('AgentPane command registry', () => {
  it('has exactly the frozen command ids', () => {
    expect(THREAD_COMMANDS.map((command) => command.id)).toEqual([
      'thread',
      'agent',
      'environment',
      'yolo',
      'models',
      'tree',
      'stop',
      'new',
      'upload',
      'debug',
      'shortcuts',
      'compact',
    ])
  })

  it('keeps compact disabled with the server reason and enables it when available', () => {
    const target = { kind: 'BOUND_THREAD' as const, threadId: 't1' }
    const disabled = threadCommandsForTarget(target, {
      available: false,
      disabledReason: 'already compacting',
    }).find((command) => command.id === 'compact')
    expect(disabled).toMatchObject({
      disabled: true,
      disabledReason: 'already compacting',
    })
    expect(
      threadCommandsForTarget(target, { available: true, disabledReason: null })
        .find((command) => command.id === 'compact')?.disabled,
    ).toBe(false)
  })

  it('filters command names, keywords, descriptions, and strips a slash prefix', () => {
    expect(filterThreadCommands('/comp', THREAD_COMMANDS).map((command) => command.id))
      .toEqual(['compact'])
    expect(filterThreadCommands('context', THREAD_COMMANDS).map((command) => command.id))
      .toEqual(['compact'])
    expect(filterThreadCommands('debug', THREAD_COMMANDS).map((command) => command.id))
      .toEqual(['debug'])
    expect(filterThreadCommands('no such command', THREAD_COMMANDS)).toEqual([])
    expect(filterThreadCommands(' ', THREAD_COMMANDS)).toEqual(THREAD_COMMANDS)
  })

  it('exposes the target command matrix without mutating the shared registry', () => {
    const target = { kind: 'ENTRY_DRAFT' as const, sessionId: 's1', startEntryId: 'e1' }
    expect(commandIdsForTarget(target)).toEqual([
      'thread',
      'agent',
      'environment',
      'yolo',
      'models',
      'tree',
      'new',
      'upload',
      'shortcuts',
    ])
    expect(THREAD_COMMANDS.every((command) => command.disabled === undefined)).toBe(true)
  })
})
