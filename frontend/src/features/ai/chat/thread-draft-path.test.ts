import { describe, expect, it } from 'vitest'
import { threadDraftPath } from './thread-draft-path'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'

const entry = (entryId: string, parentEntryId: string | null, entryType = 'MESSAGE'): HarnessSessionEntryDTO => ({
  entryId, parentEntryId, entryType, sessionId: 'session', payloadJson: '{}', createTime: null,
})

describe('closed Thread draft prefix', () => {
  it('orders only the selected ancestors, excluding siblings and future entries', () => {
    const root = entry('root', null, 'ROOT')
    const message = entry('message', 'root')
    const end = entry('end', 'message', 'TURN_END')
    expect(threadDraftPath([end, entry('sibling', 'root'), root, entry('future', 'end'), message], 'session', 'end'))
      .toEqual([root, message, end])
    expect(threadDraftPath([root], 'session', 'root')).toEqual([root])
  })

  it.each([
    [[], 'missing'],
    [[entry('end', 'missing')], 'end'],
    [[entry('a', 'b'), entry('b', 'a')], 'a'],
    [[entry('a', null)], 'a'],
    [[{ ...entry('root', null, 'ROOT'), sessionId: 'other' }], 'root'],
  ] as const)('rejects incomplete, cyclic, non-ROOT and cross-Session paths', (entries, start) => {
    expect(() => threadDraftPath([...entries], 'session', start)).toThrow()
  })
})
