import { describe, expect, it } from 'vitest'
import { create } from '@bufbuild/protobuf'

import { InMemoryInvocationJournal, isTerminalState } from '../src/journal.js'
import {
  CancelledSchema,
  CompletedSchema,
  FailedSchema,
  MessageType,
} from '../src/generated/control_pb.js'

const terminal = (messageType: MessageType.COMPLETED | MessageType.FAILED | MessageType.CANCELLED) => {
  if (messageType === MessageType.COMPLETED) {
    return { messageType, payload: { case: 'completed' as const, value: create(CompletedSchema, {}) } }
  }
  if (messageType === MessageType.FAILED) {
    return { messageType, payload: { case: 'failed' as const, value: create(FailedSchema, { message: 'err' }) } }
  }
  return { messageType, payload: { case: 'cancelled' as const, value: create(CancelledSchema, { reason: 'cancel' }) } }
}

describe('InMemoryInvocationJournal', () => {
  it('creates RUNNING entries atomically and dedups repeated starts', () => {
    const journal = new InMemoryInvocationJournal()
    const first = journal.start('inv-1')
    expect(first.created).toBe(true)
    expect(first.entry.state).toBe('RUNNING')
    expect(journal.find('inv-1')).toBe(first.entry)
    const second = journal.start('inv-1')
    expect(second.created).toBe(false)
    expect(second.entry).toBe(first.entry)
  })

  it('commits a terminal state once and rejects later completions', () => {
    const journal = new InMemoryInvocationJournal()
    journal.start('inv-1')
    expect(journal.complete('inv-1', terminal(MessageType.COMPLETED))).toBe(true)
    expect(journal.find('inv-1')?.state).toBe('COMPLETED')
    expect(journal.complete('inv-1', terminal(MessageType.FAILED))).toBe(false)
    expect(journal.find('inv-1')?.terminalMessage?.messageType).toBe(MessageType.COMPLETED)
  })

  it('never completes an unknown invocation', () => {
    const journal = new InMemoryInvocationJournal()
    expect(journal.complete('missing', terminal(MessageType.CANCELLED))).toBe(false)
  })

  it('maps terminal message types to states', () => {
    const journal = new InMemoryInvocationJournal()
    journal.start('a')
    journal.start('b')
    journal.complete('a', terminal(MessageType.FAILED))
    journal.complete('b', terminal(MessageType.CANCELLED))
    expect(journal.find('a')?.state).toBe('FAILED')
    expect(journal.find('b')?.state).toBe('CANCELLED')
    expect(isTerminalState('RUNNING')).toBe(false)
    expect(isTerminalState('COMPLETED')).toBe(true)
  })

  it('bounds retained terminal entries and never evicts RUNNING ones', () => {
    const journal = new InMemoryInvocationJournal(2)
    journal.start('term-1')
    journal.complete('term-1', terminal(MessageType.COMPLETED))
    journal.start('running')
    journal.start('term-2')
    journal.complete('term-2', terminal(MessageType.FAILED))
    journal.start('term-3')
    journal.complete('term-3', terminal(MessageType.CANCELLED))

    expect(journal.find('running')?.state).toBe('RUNNING')
    expect(journal.find('term-1')).toBeUndefined()
    expect(journal.find('term-2')?.state).toBe('FAILED')
    expect(journal.find('term-3')?.state).toBe('CANCELLED')
  })

  it('records independent exit confirmations and rejects unknown entries', () => {
    const journal = new InMemoryInvocationJournal()
    journal.start('inv-1')
    expect(journal.find('inv-1')?.exitConfirmed).toBe(false)
    expect(journal.confirmExit('inv-1')).toBe(true)
    expect(journal.find('inv-1')?.exitConfirmed).toBe(true)
    expect(journal.confirmExit('missing')).toBe(false)
  })

  it('rejects non-positive bounds', () => {
    expect(() => new InMemoryInvocationJournal(0)).toThrow(/positive integer/)
  })
})
