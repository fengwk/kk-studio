import { describe, expect, it } from 'vitest'
import {
  projectActiveThreadTree,
  projectThreadTree,
  threadTreeOutcome,
  type ActiveThreadTreeNode,
} from '@/features/ai/runtime/thread-panel/active-thread-tree'

function node(
  threadId: string,
  parentThreadId: string | null,
  overrides: Partial<ActiveThreadTreeNode> = {},
): ActiveThreadTreeNode {
  return {
    threadId,
    parentThreadId,
    name: threadId,
    agentName: 'assistant',
    model: { providerName: 'p', modelName: 'm', variant: 'default' },
    status: 'IDLE',
    processing: false,
    turnCount: 1,
    toolCallCount: 0,
    outcome: null,
    updateTime: '2026-03-31T12:00:00.000Z',
    ...overrides,
  }
}

describe('projectActiveThreadTree', () => {
  it('deduplicates identical resumed records but keeps same-name executions and history', () => {
    const a = node('a', 'root', { name: 'main', agentName: 'Explorer', processing: true })
    const tree = projectThreadTree([node('root', null), a, { ...a }, node('b', 'a', { agentName: 'Explorer', outcome: 'FAILED' })])
    expect(tree.historyRows.map((row) => row.node.threadId)).toEqual(['a', 'b'])
    expect(tree.rows.map((row) => row.node.threadId)).toEqual(['a'])
    expect(projectThreadTree([node('root', null), { ...a, processing: false }]).historyRows).toHaveLength(1)
    expect(projectThreadTree([]).root).toBeNull()
  })

  it('sorts each sibling group by processing, time and ID, retaining real connectors', () => {
    const tree = projectThreadTree([
      node('root', null), node('old', 'root', { updateTime: null }),
      node('z', 'root', { updateTime: [2026, 4, 1] }),
      node('a', 'root', { updateTime: '2026-04-01T00:00:00Z' }),
      node('active', 'root', { processing: true }),
      node('grand', 'active', { updateTime: [2026] }),
    ])
    expect(tree.historyRows.map((row) => row.node.threadId)).toEqual(['active', 'grand', 'a', 'z', 'old'])
    expect(tree.historyRows[1]).toMatchObject({ depth: 1, ancestorContinues: [true], isLast: true })
    expect(tree.historyRows.at(-1)?.isLast).toBe(true)
    expect(() => projectThreadTree([node('root', null, { updateTime: 'invalid' })])).toThrow(/updateTime/)
    expect(() => projectThreadTree([node('root', null), node('x', 'y'), node('y', 'x')])).toThrow(/cycle/)
    expect(() => projectThreadTree([node('root', null), node('other', null)])).toThrow(/root/)
  })
  it('keeps processing nodes with their idle ancestors, rooted at the execution root children', () => {
    const rows = projectActiveThreadTree([
      node('root', null),
      node('a', 'root', { processing: true, status: 'MODEL_STREAM' }),
      node('b', 'a'),
      node('c', 'b', { processing: true, status: 'TOOL_RUNNING' }),
      node('idle', 'root'),
    ])

    expect(rows.map((row) => [row.node.threadId, row.depth])).toEqual([
      ['a', 0],
      ['b', 1],
      ['c', 2],
    ])
    // 祖先保持如实空闲状态，活跃数量由调用方按 processing 统计。
    expect(rows.find((row) => row.node.threadId === 'b')?.node.processing).toBe(false)
  })

  it('does not emit the root itself and hides everything when no descendant is processing', () => {
    expect(projectActiveThreadTree([node('root', null)])).toEqual([])
    expect(projectActiveThreadTree([
      node('root', null, { processing: true }),
      node('a', 'root'),
    ])).toEqual([])
  })

  it('keeps sibling order stable and independent of input order', () => {
    const rows = projectActiveThreadTree([
      node('root', null),
      node('b', 'root', { processing: true }),
      node('a', 'root', { processing: true }),
      node('c', 'root', { processing: true }),
    ])
    expect(rows.map((row) => row.node.threadId)).toEqual(['a', 'b', 'c'])
  })

  it('preserves deep ancestor chains for nested active subagents', () => {
    const rows = projectActiveThreadTree([
      node('root', null),
      node('a', 'root'),
      node('b', 'a'),
      node('c', 'b'),
      node('d', 'c', { processing: true }),
    ])
    expect(rows.map((row) => [row.node.threadId, row.depth])).toEqual([
      ['a', 0],
      ['b', 1],
      ['c', 2],
      ['d', 3],
    ])
  })

  it('rejects malformed trees instead of fabricating rows', () => {
    expect(() => projectActiveThreadTree([
      node('root', null),
      node('root', null, { processing: true }),
    ])).toThrow(/duplicate/)
    expect(() => projectActiveThreadTree([
      node('a', 'missing', { processing: true }),
    ])).toThrow(/root/)
    expect(() => projectActiveThreadTree([
      node('root', null),
      node('orphan', 'ghost', { processing: true }),
    ])).toThrow(/parent/)
  })

  it('only reports terminal outcomes for idle finished turns', () => {
    expect(threadTreeOutcome(node('a', 'root', { status: 'IDLE', outcome: 'COMPLETED' }))).toBe('COMPLETED')
    expect(threadTreeOutcome(node('a', 'root', { status: 'MODEL_STREAM', outcome: 'COMPLETED' }))).toBeNull()
    expect(threadTreeOutcome(node('a', 'root', { status: 'IDLE', outcome: 'UNKNOWN' }))).toBeNull()
  })
})
