import { describe, expect, it } from 'vitest'

import { InMemoryInvocationJournal, isTerminalState } from '../src/journal.js'

const terminal = (messageType: 'COMPLETED' | 'FAILED' | 'CANCELLED', payloadJson = '{}') => ({ messageType, payloadJson })

describe('InMemoryInvocationJournal', () => {
  it('creates RUNNING entries atomically and dedups repeated starts', () => {
    const journal = new InMemoryInvocationJournal()
    const first = journal.start('inv-1')
    expect(first.created).toBe(true)
    expect(first.entry.state).toBe('RUNNING')
    const second = journal.start('inv-1')
    expect(second.created).toBe(false)
    expect(second.entry).toBe(first.entry)
  })

  it('commits a terminal state once and rejects later completions', () => {
    const journal = new InMemoryInvocationJournal()
    journal.start('inv-1')
    expect(journal.complete('inv-1', terminal('COMPLETED', '{"result":{}}'))).toBe(true)
    expect(journal.find('inv-1')?.state).toBe('COMPLETED')
    expect(journal.complete('inv-1', terminal('FAILED'))).toBe(false)
    expect(journal.find('inv-1')?.terminalMessage?.messageType).toBe('COMPLETED')
  })

  it('never completes an unknown invocation', () => {
    const journal = new InMemoryInvocationJournal()
    expect(journal.complete('missing', terminal('CANCELLED'))).toBe(false)
  })

  it('maps terminal message types to states', () => {
    const journal = new InMemoryInvocationJournal()
    journal.start('a')
    journal.start('b')
    journal.complete('a', terminal('FAILED'))
    journal.complete('b', terminal('CANCELLED'))
    expect(journal.find('a')?.state).toBe('FAILED')
    expect(journal.find('b')?.state).toBe('CANCELLED')
    expect(isTerminalState('RUNNING')).toBe(false)
    expect(isTerminalState('COMPLETED')).toBe(true)
  })

  it('bounds retained terminal entries and never evicts RUNNING ones', () => {
    const journal = new InMemoryInvocationJournal(2)
    journal.start('running')
    journal.start('done-1')
    journal.complete('done-1', terminal('COMPLETED'))
    journal.start('done-2')
    journal.complete('done-2', terminal('COMPLETED'))
    // 第 3 个终态条目触发淘汰最旧的终态条目 done-1，而 running 保留。
    journal.start('done-3')
    journal.complete('done-3', terminal('COMPLETED'))
    expect(journal.size()).toBe(3)
    expect(journal.find('running')?.state).toBe('RUNNING')
    expect(journal.find('done-1')).toBeUndefined()
    expect(journal.find('done-3')?.state).toBe('COMPLETED')
  })

  it('rejects a non-positive bound', () => {
    expect(() => new InMemoryInvocationJournal(0)).toThrow()
  })
})
