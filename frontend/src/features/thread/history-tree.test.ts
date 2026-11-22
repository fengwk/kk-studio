import { describe, expect, it } from 'vitest'
import {
  activeAncestry,
  buildHistoryTree,
  historyRowMatches,
  historyRowSearchPreview,
  historySearchTokens,
  isForkBoundary,
  type HistoryTreeRow,
} from '@/features/thread/history-tree'
import type { EntryType } from '@/shared/api/contracts/ai-runtime'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

const SESSION_ID = 'session-1'

/** 构造真实形状的 Session Entry：payload 只放投影真正读取的字段。 */
function entry(
  entryId: string,
  entryType: EntryType,
  options: { parentEntryId?: string | null; payload?: Record<string, unknown> } = {},
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: SESSION_ID,
    parentEntryId: options.parentEntryId ?? null,
    entryType,
    payload: options.payload ?? {},
    createdAt: 0,
    usageCost: null,
  }
}

function messageEntry(
  entryId: string,
  role: 'USER' | 'ASSISTANT' | 'TOOL',
  text: string,
  parentEntryId: string | null,
): HarnessSessionEntryDTO {
  return entry(entryId, 'MESSAGE', {
    parentEntryId,
    payload: { message: { role, contents: [{ type: 'text', text }] } },
  })
}

function laneOf(rows: HistoryTreeRow[], entryId: string): number {
  const row = rows.find((candidate) => candidate.entry.entryId === entryId)
  expect(row, `row ${entryId}`).toBeDefined()
  return row!.lane
}

/** 每个 Entry 恰好一行，且顺序与服务端 pre-order 一致。 */
function ids(rows: HistoryTreeRow[]): string[] {
  return rows.map((row) => row.entry.entryId)
}

/** 单个 MESSAGE Entry 对应的历史树行，用于预览与搜索断言。 */
function searchRow(text: string): HistoryTreeRow {
  return buildHistoryTree([messageEntry('m', 'ASSISTANT', text, null)])[0]!
}

describe('History tree lane projection', () => {
  it('keeps a linear chain on one lane without directory-style depth growth', () => {
    const entries = [
      entry('root', 'ROOT'),
      entry('start', 'TURN_START', { parentEntryId: 'root' }),
      messageEntry('user', 'USER', 'hello', 'start'),
      messageEntry('assistant', 'ASSISTANT', 'hi', 'user'),
      entry('end', 'TURN_END', { parentEntryId: 'assistant' }),
    ]
    const rows = buildHistoryTree(entries)

    expect(ids(rows)).toEqual(['root', 'start', 'user', 'assistant', 'end'])
    expect(rows.map((row) => row.lane)).toEqual([0, 0, 0, 0, 0])
    expect(rows.at(-1)!.gutter).toEqual([false])
    expect(rows.every((row) => !row.startsLane)).toBe(true)
  })

  it('opens a new lane only for real sibling forks and marks the fork row', () => {
    const entries = [
      entry('root', 'ROOT'),
      entry('end-a', 'TURN_END', { parentEntryId: 'root' }),
      entry('end-b', 'TURN_END', { parentEntryId: 'root' }),
      entry('end-c', 'TURN_END', { parentEntryId: 'root' }),
    ]
    const rows = buildHistoryTree(entries)

    expect(laneOf(rows, 'root')).toBe(0)
    expect(laneOf(rows, 'end-a')).toBe(0)
    expect(laneOf(rows, 'end-b')).toBe(1)
    expect(laneOf(rows, 'end-c')).toBe(2)
    const root = rows[0]!
    expect(root.isFork).toBe(true)
    expect(root.gutter).toEqual([true, true, true])
    expect(rows.find((row) => row.entry.entryId === 'end-b')?.startsLane).toBe(true)
    expect(rows.find((row) => row.entry.entryId === 'end-a')?.startsLane).toBe(false)
    expect(rows.find((row) => row.entry.entryId === 'end-a')?.isFork).toBe(false)
  })

  it('reserves each sibling lane even when an entry id occurs twice', () => {
    // 重复行仍由 walked 去重，但 sibling 预留列不能改成当前 Entry 的列复用规则。
    const rows = buildHistoryTree([
      entry('root', 'ROOT'),
      entry('child', 'TURN_END', { parentEntryId: 'root' }),
      entry('child', 'TURN_END', { parentEntryId: 'root' }),
    ])

    expect(ids(rows)).toEqual(['root', 'child'])
    expect(rows[0]!.gutter).toEqual([true, true])
    expect(rows[1]!.gutter).toEqual([false, true])
  })

  it('allocates deeper lanes for nested forks without reindenting linear descendants', () => {
    const entries = [
      entry('root', 'ROOT'),
      entry('a', 'TURN_END', { parentEntryId: 'root' }),
      messageEntry('a-child', 'ASSISTANT', 'a1', 'a'),
      entry('b', 'TURN_END', { parentEntryId: 'root' }),
      entry('a-fork', 'TURN_END', { parentEntryId: 'a' }),
      entry('a-fork-child', 'TURN_END', { parentEntryId: 'a-fork' }),
    ]
    const rows = buildHistoryTree(entries)

    expect(ids(rows)).toEqual(['root', 'a', 'a-child', 'a-fork', 'a-fork-child', 'b'])
    expect(laneOf(rows, 'a')).toBe(0)
    expect(laneOf(rows, 'a-child')).toBe(0)
    expect(laneOf(rows, 'a-fork')).toBe(2)
    expect(laneOf(rows, 'a-fork-child')).toBe(2)
    expect(laneOf(rows, 'b')).toBe(1)
    // 祖先的 sibling 分支在整段子树下方保持连通。
    expect(rows.find((row) => row.entry.entryId === 'a-child')?.gutter[1]).toBe(true)
  })

  it('keeps every Entry in one row (no turn reconstruction) and only exposes boundaries as forkable', () => {
    const entries = [
      entry('root', 'ROOT'),
      entry('start', 'TURN_START', { parentEntryId: 'root' }),
      messageEntry('user', 'USER', 'hello', 'start'),
      messageEntry('assistant', 'ASSISTANT', 'hi', 'user'),
      entry('failure', 'MODEL_ATTEMPT_FAILURE', { parentEntryId: 'assistant' }),
      entry('end', 'TURN_END', { parentEntryId: 'failure' }),
    ]
    const rows = buildHistoryTree(entries)

    expect(rows).toHaveLength(entries.length)
    expect(rows.filter((row) => row.canFork).map((row) => row.entry.entryId)).toEqual(['root', 'end'])
    expect(isForkBoundary(entry('m', 'MESSAGE', { payload: { message: { role: 'USER', contents: [] } } })))
      .toBe(false)
    // 行不含执行状态字段：只标记结构、路径与 head。
    for (const row of rows) {
      expect(Object.keys(row).sort()).toEqual([
        'canFork',
        'entry',
        'gutter',
        'isFork',
        'isHead',
        'kind',
        'lane',
        'onActivePath',
        'preview',
        'searchText',
        'startsLane',
        'text',
      ])
    }
  })

  it('classifies kinds from real Entry types and message roles', () => {
    const entries = [
      entry('root', 'ROOT'),
      messageEntry('user', 'USER', 'hello', 'root'),
      messageEntry('assistant', 'ASSISTANT', 'hi', 'user'),
      entry('tool', 'MESSAGE', {
        parentEntryId: 'assistant',
        payload: { message: { role: 'TOOL', contents: [{ type: 'text', text: 'output' }] } },
      }),
      entry('custom', 'CUSTOM_MESSAGE', { parentEntryId: 'tool' }),
      entry('note', 'NOTIFICATION', { parentEntryId: 'custom' }),
      entry('start', 'TURN_START', { parentEntryId: 'note' }),
    ]
    const kinds = buildHistoryTree(entries).map((row) => row.kind)
    expect(kinds).toEqual(['root', 'user', 'assistant', 'tool', 'custom', 'notification', 'other'])
  })

  it('marks the active path and head without changing the graph', () => {
    const entries = [
      entry('root', 'ROOT'),
      entry('a', 'TURN_END', { parentEntryId: 'root' }),
      entry('b', 'TURN_END', { parentEntryId: 'root' }),
      entry('b-child', 'TURN_END', { parentEntryId: 'b' }),
    ]
    const rows = buildHistoryTree(entries, { headEntryId: 'b-child' })
    expect(activeAncestry(entries, 'b-child')).toEqual(['b-child', 'b', 'root'])
    expect(rows.filter((row) => row.onActivePath).map((row) => row.entry.entryId))
      .toEqual(['root', 'b', 'b-child'])
    expect(rows.filter((row) => row.isHead).map((row) => row.entry.entryId)).toEqual(['b-child'])
    // head 只影响标记，不影响 lane 分配。
    expect(rows.map((row) => row.lane)).toEqual([0, 0, 1, 1])
  })

  it('keeps orphaned or cyclic Entries visible instead of dropping them', () => {
    const entries = [
      entry('root', 'ROOT'),
      entry('orphan', 'TURN_END', { parentEntryId: 'missing' }),
      entry('cycle-a', 'TURN_END', { parentEntryId: 'cycle-b' }),
      entry('cycle-b', 'TURN_END', { parentEntryId: 'cycle-a' }),
    ]
    const rows = buildHistoryTree(entries)
    expect(ids(rows).sort()).toEqual(['cycle-a', 'cycle-b', 'orphan', 'root'])
  })
})

describe('History tree search', () => {
  const entries = [
    entry('root', 'ROOT'),
    entry('a', 'TURN_END', { parentEntryId: 'root' }),
    messageEntry('b', 'USER', 'Fix the parser bug', 'root'),
    messageEntry('c', 'ASSISTANT', 'Parser fixed', 'b'),
  ]

  it('parses whitespace-separated tokens with AND semantics', () => {
    expect(historySearchTokens('  Parser   Bug ')).toEqual(['parser', 'bug'])
    expect(historySearchTokens('   ')).toEqual([])
  })

  it('locates matches without hiding or reordering any row', () => {
    const rows = buildHistoryTree(entries)
    const tokens = historySearchTokens('parser')
    expect(rows.map((row) => row.entry.entryId)).toEqual(['root', 'a', 'b', 'c'])
    expect(rows.filter((row) => historyRowMatches(row, tokens)).map((row) => row.entry.entryId))
      .toEqual(['b', 'c'])
    // 多 token AND：只有同时命中两者才高亮。
    expect(historyRowMatches(rows[2]!, historySearchTokens('parser bug'))).toBe(true)
    expect(historyRowMatches(rows[3]!, historySearchTokens('parser bug'))).toBe(false)
  })

  it('flattens whitespace and truncates long previews while searching the full text', () => {
    const long = 'x'.repeat(300)
    const rows = buildHistoryTree([messageEntry('m', 'USER', `  spaced   ${long}`, null)])
    const row = rows[0]!
    expect(row.preview.startsWith('spaced xxx')).toBe(true)
    expect(row.preview.length).toBeLessThanOrEqual(220)
    expect(row.preview.endsWith('…')).toBe(true)
    // 搜索使用未截断文本。
    expect(historyRowMatches(row, historySearchTokens('x'.repeat(250)))).toBe(true)
  })

  // 测试意图：命中落在预览保留的字符之外时，搜索预览从全文命中处截取，用户能看到为什么这一行匹配。
  it('builds the search preview from the full text when the hit lies beyond the preview limit', () => {
    const text = `${'背景'.repeat(150)} 最后调大 terminationGracePeriodSeconds 即可`
    const row = searchRow(text)
    expect(row.preview).not.toContain('termination')
    const preview = historyRowSearchPreview(row, historySearchTokens('terminationGracePeriodSeconds'))
    expect(preview).toBe('…最后调大 terminationGracePeriodSeconds 即可')
    // 在预览内的命中与无命中都沿用普通预览。
    expect(historyRowSearchPreview(row, historySearchTokens('背景'))).toBe(row.preview)
    expect(historyRowSearchPreview(row, historySearchTokens('parser'))).toBe(row.preview)
    expect(historyRowSearchPreview(row, [])).toBe(row.preview)
  })

  // 是否换成全文窗口由首个命中能否完整留在普通预览里决定。
  it('keeps the plain preview only while the first hit fits in the retained characters', () => {
    const inside = searchRow(`${'甲'.repeat(216)}hit ${'乙'.repeat(300)}`)
    expect(inside.preview).toContain('hit')
    expect(historyRowSearchPreview(inside, historySearchTokens('hit'))).toBe(inside.preview)
    // 命中跨过保留字符的末位时普通预览只剩半个命中，高亮不到，改用全文窗口。
    const straddling = searchRow(`${'甲'.repeat(217)}hit ${'乙'.repeat(300)}`)
    expect(straddling.preview).not.toContain('hit')
    expect(historyRowSearchPreview(straddling, historySearchTokens('hit')).startsWith('…甲甲甲甲甲甲hit '))
      .toBe(true)
    // 命中完全在保留字符之外时同样改用全文窗口。
    const beyond = searchRow(`${'甲'.repeat(219)}hit ${'乙'.repeat(300)}`)
    expect(beyond.preview).not.toContain('hit')
    expect(historyRowSearchPreview(beyond, historySearchTokens('hit')).startsWith('…甲甲甲甲甲甲hit '))
      .toBe(true)
    // 全文恰好 220 字时预览不截断，末位的命中仍然看得见。
    const exact = searchRow(`${'甲'.repeat(219)}h`)
    expect(exact.preview).toHaveLength(220)
    expect(historyRowSearchPreview(exact, historySearchTokens('h'))).toBe(exact.preview)
  })

  // 测试意图：从全文截取时沿用原有的拉丁词边界规则，不把命中前的词截成半个。
  it('starts the full-text window at the word boundary before a deep Latin hit', () => {
    const row = searchRow(`${'abcd'.repeat(60)} termination`)
    expect(historyRowSearchPreview(row, historySearchTokens('termination'))).toBe('…termination')
  })

  // 测试意图：截取起点落在代理对中间时向前退一格，不把 emoji 拆成坏字符。
  it('never starts the full-text window in the middle of a surrogate pair', () => {
    const row = searchRow(`${'😀'.repeat(150)}ahit`)
    expect(historyRowSearchPreview(row, historySearchTokens('hit'))).toBe(`…${'😀'.repeat(3)}ahit`)
  })

  // 测试意图：全文窗口仍受预览上限约束，长命中后的正文不会让单行预览变长。
  it('caps the full-text window at the preview limit', () => {
    const row = searchRow(`${'甲'.repeat(219)}hit ${'乙'.repeat(300)}`)
    const preview = historyRowSearchPreview(row, historySearchTokens('hit'))
    expect(preview).toContain('hit')
    expect(preview).toHaveLength(220)
    expect(preview.endsWith('…')).toBe(true)
  })

  it('excludes explicit thinking content from preview and search', () => {
    const entries = [
      entry('m', 'MESSAGE', {
        payload: {
          message: {
            role: 'ASSISTANT',
            contents: [
              { type: 'thinking', text: 'secret reasoning' },
              { type: 'text', text: 'visible answer' },
            ],
          },
        },
      }),
    ]
    const row = buildHistoryTree(entries)[0]!
    expect(row.preview).toBe('visible answer')
    expect(historyRowMatches(row, historySearchTokens('secret'))).toBe(false)
    expect(historyRowMatches(row, historySearchTokens('answer'))).toBe(true)
  })

  it('falls back to the empty-body label when a message has no visible text', () => {
    const rows = buildHistoryTree([
      entry('m', 'MESSAGE', { payload: { message: { role: 'ASSISTANT', contents: [] } } }),
    ])
    expect(rows[0]!.preview).not.toBe('')
    expect(rows[0]!.searchText).toBe('')
  })

  it('exposes FORK entries with the fixed English notice preview while keeping them non-forkable', () => {
    const rows = buildHistoryTree([
      entry('root', 'ROOT'),
      entry('fork-1', 'FORK', {
        parentEntryId: 'root',
        payload: { mode: 'BRANCH', sourceEntryId: 'root', sourceThreadId: null },
      }),
    ])
    const forkRow = rows[1]!
    expect(forkRow.canFork).toBe(false)
    expect(forkRow.preview).toContain('This thread was forked.')
    expect(historyRowMatches(forkRow, historySearchTokens('forked subagent'))).toBe(true)
  })
})
