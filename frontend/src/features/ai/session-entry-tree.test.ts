import { describe, expect, it } from 'vitest'
import { branchTarget, buildSessionEntryTree, matchesSessionTreeFilter, sessionEntryKind } from '@/features/ai/session-entry-tree'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'

const entries: HarnessSessionEntryDTO[] = [
  entry('snapshot', null, 'agent_snapshot', {}),
  entry('user', 'snapshot', 'message', message('USER', 'original prompt')),
  entry('assistant', 'user', 'message', message('ASSISTANT', 'answer')),
  entry('tool', 'assistant', 'message', message('TOOL', 'tool result')),
  entry('custom', 'assistant', 'custom_message', message('SYSTEM', 'custom text')),
  entry('label', 'assistant', 'label', { label: 'checkpoint' }),
  entry('config', 'snapshot', 'model_change', { modelId: 'm1', variant: 'v1' }),
]

describe('Session Entry Tree', () => {
  it('walks the full parent tree in server child order and applies every UI filter', () => {
    expect(buildSessionEntryTree(entries, 'default').map((item) => item.entry.entryId)).toEqual(['user', 'assistant', 'custom'])
    expect(buildSessionEntryTree(entries, 'no-tools').map((item) => item.entry.entryId)).toEqual(['snapshot', 'user', 'assistant', 'custom', 'label', 'config'])
    expect(buildSessionEntryTree(entries, 'user-only').map((item) => item.entry.entryId)).toEqual(['user', 'custom'])
    expect(buildSessionEntryTree(entries, 'assistant-only').map((item) => item.entry.entryId)).toEqual(['assistant'])
    expect(buildSessionEntryTree(entries, 'labeled-only').map((item) => item.entry.entryId)).toEqual(['label'])
    expect(buildSessionEntryTree(entries, 'all').map((item) => item.entry.entryId)).toEqual(['snapshot', 'user', 'assistant', 'tool', 'custom', 'label', 'config'])
    expect(buildSessionEntryTree(entries, 'all').find((item) => item.entry.entryId === 'tool')?.depth).toBe(3)
  })

  it('branches editable USER/CUSTOM entries from their parent and all other entries from themselves', () => {
    expect(branchTarget(entries[1])).toEqual({ fromEntryId: 'snapshot', draft: 'original prompt' })
    expect(branchTarget(entries[4])).toEqual({ fromEntryId: 'assistant', draft: 'custom text' })
    expect(branchTarget(entries[2])).toEqual({ fromEntryId: 'assistant', draft: '' })
    expect(branchTarget(entries[0])).toEqual({ fromEntryId: 'snapshot', draft: '' })
  })

  it('classifies unknown, malformed and all supported filter variants deterministically', () => {
    expect(sessionEntryKind(entry('unknown', null, 'MESSAGE', { message: { role: 'OTHER' } }))).toBe('other')
    expect(sessionEntryKind(entry('custom', null, 'CUSTOM_MESSAGE', {}))).toBe('custom')
    expect(matchesSessionTreeFilter('tool', 'default')).toBe(false)
    expect(matchesSessionTreeFilter('tool', 'no-tools')).toBe(false)
    expect(matchesSessionTreeFilter('user', 'user-only')).toBe(true)
    expect(matchesSessionTreeFilter('assistant', 'assistant-only')).toBe(true)
    expect(matchesSessionTreeFilter('label', 'labeled-only')).toBe(true)
    expect(matchesSessionTreeFilter('other', 'all')).toBe(true)
  })
})

function entry(entryId: string, parentEntryId: string | null, entryType: string, payload: unknown): HarnessSessionEntryDTO {
  return { entryId, sessionId: 's1', parentEntryId, entryType, payloadJson: JSON.stringify(payload), createTime: null }
}

function message(role: string, text: string) {
  return { message: { role, contents: [{ type: 'text', text }] } }
}
