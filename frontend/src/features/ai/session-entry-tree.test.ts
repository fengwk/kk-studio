import { describe, expect, it } from 'vitest'
import {
  activeAncestry,
  branchTarget,
  buildSessionEntryTree,
  isOnActivePath,
  matchesSessionTreeFilter,
  parseHistorySearchTokens,
  resolveSelection,
  sessionEntryKind,
  sessionEntryPreview,
} from '@/features/ai/session-entry-tree'
import type { EntryType, HarnessSessionEntryDTO } from '@/shared/api/contracts'

const entries: HarnessSessionEntryDTO[] = [
  entry('root', null, 'ROOT', {}),
  entry('user', 'root', 'MESSAGE', message('USER', 'original prompt')),
  entry('assistant', 'user', 'MESSAGE', message('ASSISTANT', 'answer')),
  entry('tool', 'assistant', 'MESSAGE', message('TOOL', 'tool result')),
  entry('custom', 'assistant', 'CUSTOM_MESSAGE', message('SYSTEM', 'custom text')),
  entry('label', 'assistant', 'LABEL', { label: 'checkpoint' }),
  entry('config', 'root', 'RUNTIME_CONFIG', {}),
]

describe('Session Entry Tree', () => {
  it('walks the full parent tree in server child order and applies the two KISS views', () => {
    expect(buildSessionEntryTree(entries, 'conversation').map((item) => item.entry.entryId)).toEqual(['user', 'assistant', 'custom'])
    expect(buildSessionEntryTree(entries, 'all').map((item) => item.entry.entryId)).toEqual(['root', 'user', 'assistant', 'tool', 'custom', 'label', 'config'])
  })

  it('treats an omitted root parentEntryId as null at the HTTP boundary', () => {
    const rootWithoutParent = {
      entryId: 'root-without-parent',
      sessionId: 's1',
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    } as HarnessSessionEntryDTO
    const user = entry('child', 'root-without-parent', 'MESSAGE', message('USER', 'child prompt'))

    expect(buildSessionEntryTree([rootWithoutParent, user], 'conversation').map((item) => item.entry.entryId)).toEqual(['child'])
  })

  it('keeps linear chains compact: depth advances only on visible sibling splits', () => {
    const chain: HarnessSessionEntryDTO[] = [
      entry('root', null, 'MESSAGE', message('USER', 'first')),
      entry('a', 'root', 'MESSAGE', message('ASSISTANT', 'second')),
      entry('b', 'a', 'MESSAGE', message('ASSISTANT', 'third')),
      entry('c', 'b', 'MESSAGE', message('ASSISTANT', 'fourth')),
      entry('d', 'c', 'MESSAGE', message('ASSISTANT', 'fifth')),
    ]
    const rows = buildSessionEntryTree(chain, 'all')
    expect(rows.map((row) => row.depth)).toEqual([0, 0, 0, 0, 0])
    expect(rows.every((row) => row.ancestorConnectors.length === 0)).toBe(true)
  })

  it('emits branch connectors only when a row has multiple visible children', () => {
    const rows = buildSessionEntryTree(entries, 'all')
    const byEntryId = new Map(rows.map((row) => [row.entry.entryId, row]))
    expect(byEntryId.get('root')?.depth).toBe(0)
    expect(byEntryId.get('root')?.ancestorConnectors).toEqual([])
    expect(byEntryId.get('root')?.isLastSibling).toBe(true)
    expect(byEntryId.get('user')?.depth).toBe(1)
    expect(byEntryId.get('user')?.hasBranchConnector).toBe(true)
    expect(byEntryId.get('user')?.isBranchPoint).toBe(false)
    expect(byEntryId.get('user')?.ancestorConnectors).toEqual([{ continues: false }])
    // The first linear response after a split stays one level deeper, so it reads as part of
    // the first branch rather than as a third sibling of the root.
    expect(byEntryId.get('assistant')?.depth).toBe(2)
    expect(byEntryId.get('assistant')?.hasBranchConnector).toBe(false)
    expect(byEntryId.get('assistant')?.isBranchPoint).toBe(true)
    expect(byEntryId.get('assistant')?.ancestorConnectors).toEqual([
      { continues: true },
      { continues: false },
    ])
    expect(byEntryId.get('tool')?.depth).toBe(3)
    expect(byEntryId.get('tool')?.hasBranchConnector).toBe(true)
    expect(byEntryId.get('tool')?.ancestorConnectors).toEqual([
      { continues: true },
      { continues: false },
      { continues: false },
    ])
    expect(byEntryId.get('tool')?.isLastSibling).toBe(false)
    expect(byEntryId.get('label')?.depth).toBe(3)
    expect(byEntryId.get('label')?.isLastSibling).toBe(true)
    expect(byEntryId.get('config')?.depth).toBe(1)
    expect(byEntryId.get('config')?.isLastSibling).toBe(true)
  })

  it('keeps system and tool Entries out of the conversation view while re-attaching visible descendants', () => {
    const tree: HarnessSessionEntryDTO[] = [
      entry('root', null, 'ROOT', {}),
      entry('user', 'root', 'MESSAGE', message('USER', 'a user prompt')),
      entry('tool', 'user', 'MESSAGE', message('TOOL', 'a tool result')),
      entry('assistant', 'tool', 'MESSAGE', message('ASSISTANT', 'final answer')),
    ]
    const rows = buildSessionEntryTree(tree, 'conversation')
    expect(rows.map((row) => row.entry.entryId)).toEqual(['user', 'assistant'])
    // `root` and `tool` are hidden; `assistant` re-attaches to `user` without indentation.
    expect(rows[1]?.parentId).toBe('user')
    expect(rows[1]?.depth).toBe(0)
    expect(rows[1]?.ancestorConnectors).toEqual([])
  })

  it('keeps depth shallow when filtered intermediates hide a branch point', () => {
    const tree: HarnessSessionEntryDTO[] = [
      entry('root', null, 'ROOT', {}),
      entry('user1', 'root', 'MESSAGE', message('USER', 'first user')),
      entry('tool', 'user1', 'MESSAGE', message('TOOL', 'a tool result')),
      entry('assistant', 'tool', 'MESSAGE', message('ASSISTANT', 'second reply')),
      entry('custom', 'tool', 'CUSTOM_MESSAGE', message('SYSTEM', 'second branch')),
      entry('user2', 'root', 'MESSAGE', message('USER', 'second user')),
      entry('response', 'user2', 'MESSAGE', message('ASSISTANT', 'reply')),
    ]
    const rows = buildSessionEntryTree(tree, 'conversation')
    const byEntryId = new Map(rows.map((row) => [row.entry.entryId, row]))
    expect(rows.map((row) => row.entry.entryId)).toEqual(['user1', 'assistant', 'custom', 'user2', 'response'])
    // With `tool` hidden, `assistant` and `custom` re-attach as immediate children of `user1`.
    expect(byEntryId.get('user1')?.depth).toBe(1)
    expect(byEntryId.get('user1')?.isBranchPoint).toBe(true)
    expect(byEntryId.get('assistant')?.depth).toBe(2)
    expect(byEntryId.get('custom')?.depth).toBe(2)
    expect(byEntryId.get('user2')?.depth).toBe(1)
    expect(byEntryId.get('response')?.depth).toBe(2)
  })

  it('preserves server pre-order when hidden intermediates expose visible siblings', () => {
    const tree: HarnessSessionEntryDTO[] = [
      entry('root', null, 'ROOT', {}),
      entry('left', 'root', 'MESSAGE', message('USER', 'left')),
      entry('hidden', 'root', 'MESSAGE', message('TOOL', 'hidden tool')),
      entry('middle', 'hidden', 'MESSAGE', message('ASSISTANT', 'middle')),
      entry('right', 'root', 'MESSAGE', message('USER', 'right')),
    ]

    const rows = buildSessionEntryTree(tree, 'conversation')
    expect(rows.map((row) => row.entry.entryId)).toEqual(['left', 'middle', 'right'])
    expect(rows.map((row) => row.parentId)).toEqual([null, null, null])
    expect(rows.map((row) => row.hasBranchConnector)).toEqual([true, true, true])
  })

  it('branches editable USER/CUSTOM entries from their parent and all other entries from themselves', () => {
    expect(branchTarget(entries[1])).toEqual({ fromEntryId: 'root', draft: 'original prompt' })
    expect(branchTarget(entries[4])).toEqual({ fromEntryId: 'assistant', draft: 'custom text' })
    expect(branchTarget(entries[2])).toEqual({ fromEntryId: 'assistant', draft: '' })
    expect(branchTarget(entries[0])).toEqual({ fromEntryId: 'root', draft: '' })
  })

  it('classifies unknown and malformed Entries while keeping the conversation view free of system records', () => {
    expect(sessionEntryKind(entry('unknown', null, 'MESSAGE', { message: { role: 'OTHER' } }))).toBe('other')
    expect(sessionEntryKind(entry('custom', null, 'CUSTOM_MESSAGE', {}))).toBe('custom')
    expect(matchesSessionTreeFilter('user', 'conversation')).toBe(true)
    expect(matchesSessionTreeFilter('tool', 'conversation')).toBe(false)
    expect(matchesSessionTreeFilter('label', 'conversation')).toBe(false)
    expect(matchesSessionTreeFilter('other', 'conversation')).toBe(false)
    expect(matchesSessionTreeFilter('other', 'all')).toBe(true)
  })

  it('excludes explicit thinking content from previews and search without truncating editable drafts', () => {
    const assistant = entry('assistant-thinking-block', 'root', 'MESSAGE', {
      message: {
        role: 'ASSISTANT',
        contents: [
          { type: 'thinking', text: '内部计划' },
          { type: 'text', text: '面向用户的正文' },
        ],
      },
    })
    const fullDraft = '用户原始内容 '.repeat(40)
    const custom = entry('custom-draft', 'root', 'CUSTOM_MESSAGE', message('SYSTEM', fullDraft))

    expect(sessionEntryPreview(assistant, 'assistant')).toBe('面向用户的正文')
    expect(buildSessionEntryTree([assistant], 'all', parseHistorySearchTokens('内部计划'))).toEqual([])
    expect(branchTarget(custom).draft).toBe(fullDraft)
    expect(sessionEntryPreview(custom, 'custom')).toHaveLength(220)
  })

  it('flattens whitespace and truncates the projected preview to the configured limit', () => {
    const long = 'a'.repeat(500)
    const assistant = entry('long', null, 'MESSAGE', message('ASSISTANT', long))
    expect(sessionEntryPreview(assistant, 'assistant')).toHaveLength(220)
    const compact = entry('compact', null, 'MESSAGE', message('ASSISTANT', 'line one\n\nline two'))
    expect(sessionEntryPreview(compact, 'assistant')).toBe('line one line two')
  })

  it('returns the active ancestry chain from a target back to the root in raw parent order', () => {
    expect(activeAncestry(entries, 'custom')).toEqual(['custom', 'assistant', 'user', 'root'])
    expect(activeAncestry(entries, null)).toEqual([])
    expect(isOnActivePath(activeAncestry(entries, 'tool'), 'assistant')).toBe(true)
    expect(isOnActivePath(activeAncestry(entries, 'tool'), 'user')).toBe(true)
    expect(isOnActivePath(activeAncestry(entries, 'tool'), 'custom')).toBe(false)
  })

  it('resolves a hidden selection to the nearest visible ancestor and falls back to the last row', () => {
    const rows = buildSessionEntryTree(entries, 'conversation')
    const resolved = resolveSelection(rows, entries, 'tool')
    expect(resolved?.entryId).toBe('assistant')
    // Root entry with no ancestors resolves to the last visible row.
    expect(resolveSelection(rows, entries, 'root')?.entryId).toBe('custom')
    expect(resolveSelection(rows, entries, 'missing')?.entryId).toBe('custom')
    expect(resolveSelection([], entries, 'assistant')).toBeNull()
  })

  it('parses search tokens with whitespace AND semantics and matches only visible text', () => {
    const assistant = entry(
      'assistant-search',
      null,
      'MESSAGE',
      message('ASSISTANT', '客户需要一份季度报告'),
    )
    const tokens = parseHistorySearchTokens('客户 报告')
    const rows = buildSessionEntryTree([assistant], 'all', tokens)
    expect(rows.map((row) => row.entry.entryId)).toEqual(['assistant-search'])
    const noMatch = buildSessionEntryTree([assistant], 'all', parseHistorySearchTokens('missing token'))
    expect(noMatch).toEqual([])
  })

  it('ignores empty search tokens', () => {
    expect(parseHistorySearchTokens('   ')).toEqual([])
    expect(parseHistorySearchTokens(' 季度 \t 报告 ')).toEqual(['季度', '报告'])
  })
})

function entry(entryId: string, parentEntryId: string | null, entryType: EntryType, payload: unknown): HarnessSessionEntryDTO {
  return { entryId, sessionId: 's1', parentEntryId, entryType, payloadJson: JSON.stringify(payload), createTime: null }
}

function message(role: string, text: string) {
  return { message: { role, contents: [{ type: 'text', text }] } }
}