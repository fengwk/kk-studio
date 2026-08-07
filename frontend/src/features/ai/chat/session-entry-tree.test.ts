import { describe, expect, it } from 'vitest'
import {
  activeAncestry,
  branchTarget,
  buildSessionEntryTree,
  isOnActivePath,
  parseHistorySearchTokens,
  resolveSelection,
} from '@/features/ai/chat/session-entry-tree'
import type { EntryType, HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'

const entries: HarnessSessionEntryDTO[] = [
  entry('root', null, 'ROOT', {}),
  entry('user', 'root', 'MESSAGE', message('USER', 'original prompt')),
  entry('assistant', 'user', 'MESSAGE', message('ASSISTANT', 'answer')),
  entry('tool', 'assistant', 'MESSAGE', message('TOOL', 'tool result')),
  entry('custom', 'assistant', 'CUSTOM_MESSAGE', message('SYSTEM', 'custom text')),
  entry('error', 'root', 'ASSISTANT_ERROR', { error: { kind: 'INVALID_REQUEST', message: 'bad turn' } }),
]

describe('Session Entry Tree', () => {
  it('walks the full parent tree in server child order and applies the two KISS views', () => {
    expect(buildSessionEntryTree(entries, 'conversation').map((item) => item.entry.entryId)).toEqual(['user', 'assistant', 'custom'])
    expect(buildSessionEntryTree(entries, 'all').map((item) => item.entry.entryId)).toEqual(['root', 'user', 'assistant', 'tool', 'custom', 'error'])
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
    // 拆分后的首个线性响应保持更深一层，因此它被视为第一条 branch 的一部分，
    // 而不是 root 的第三个兄弟。
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
    expect(byEntryId.get('custom')?.depth).toBe(3)
    expect(byEntryId.get('custom')?.isLastSibling).toBe(true)
    expect(byEntryId.get('error')?.depth).toBe(1)
    expect(byEntryId.get('error')?.isLastSibling).toBe(true)
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
    // `root` 和 `tool` 被隐藏；`assistant` 不带缩进地重新挂到 `user` 下。
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
    // 隐藏 `tool` 后，`assistant` 和 `custom` 作为 `user1` 的直接子节点重新挂载。
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

  it('rewinds the head to the parent for editable USER/CUSTOM entries and to itself otherwise', () => {
    expect(branchTarget(entries[1])).toEqual({ headEntryId: 'root', draft: 'original prompt' })
    expect(branchTarget(entries[4])).toEqual({ headEntryId: 'assistant', draft: 'custom text' })
    expect(branchTarget(entries[2])).toEqual({ headEntryId: 'assistant', draft: '' })
    expect(branchTarget(entries[0])).toEqual({ headEntryId: 'root', draft: '' })
  })

  it('classifies unknown and malformed Entries while keeping the conversation view free of system records', () => {
    const unknown = entry('unknown', null, 'MESSAGE', { message: { role: 'OTHER' } })
    const custom = entry('custom', null, 'CUSTOM_MESSAGE', {})
    expect(buildSessionEntryTree([unknown, custom], 'all').map((row) => row.kind)).toEqual(['other', 'custom'])
    expect(buildSessionEntryTree([unknown, custom], 'conversation').map((row) => row.entry.entryId)).toEqual(['custom'])
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

    expect(buildSessionEntryTree([assistant], 'all')[0]?.preview).toBe('面向用户的正文')
    expect(buildSessionEntryTree([assistant], 'all', parseHistorySearchTokens('内部计划'))).toEqual([])
    expect(branchTarget(custom).draft).toBe(fullDraft)
    expect(buildSessionEntryTree([custom], 'all')[0]?.preview).toHaveLength(220)
  })

  it('flattens whitespace and truncates the projected preview to the configured limit', () => {
    const long = 'a'.repeat(500)
    const assistant = entry('long', null, 'MESSAGE', message('ASSISTANT', long))
    expect(buildSessionEntryTree([assistant], 'all')[0]?.preview).toHaveLength(220)
    const compact = entry('compact', null, 'MESSAGE', message('ASSISTANT', 'line one\n\nline two'))
    expect(buildSessionEntryTree([compact], 'all')[0]?.preview).toBe('line one line two')
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
    // 没有祖先的 root entry 会回退到最后一行可见 row。
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
  return { entryId, parentEntryId, entryType, payloadJson: JSON.stringify(payload), createTime: null }
}

function message(role: string, text: string) {
  return { message: { role, contents: [{ type: 'text', text }] } }
}