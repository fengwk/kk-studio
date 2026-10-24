import { describe, expect, it } from 'vitest'
import {
  projectActiveThreadTree,
  projectThreadTree,
  threadTreeOutcome,
  type ActiveThreadTreeNode,
} from '@/features/chat/runtime/thread-panel/active-thread-tree'

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
    model: { modelName: 'm', variant: 'default' },
    status: 'IDLE',
    executionControl: 'RUNNABLE',
    turnCount: 1,
    toolCallCount: 0,
    outcome: null,
    updateTime: 1774958400,
    ...overrides,
  }
}

describe('projectActiveThreadTree', () => {
  it('deduplicates identical resumed records but keeps same-name executions and history', () => {
    const a = node('a', 'root', { name: 'main', agentName: 'Explorer', status: 'RUNNING' })
    const tree = projectThreadTree([node('root', null), a, { ...a }, node('b', 'a', { agentName: 'Explorer', outcome: 'FAILED' })])
    expect(tree.historyRows.map((row) => row.node.threadId)).toEqual(['a', 'b'])
    expect(tree.rows.map((row) => row.node.threadId)).toEqual(['a'])
    expect(projectThreadTree([node('root', null), { ...a, status: 'IDLE' }]).historyRows).toHaveLength(1)
    expect(projectThreadTree([]).root).toBeNull()
  })

  it('sorts each sibling group by active status, time and ID, retaining real connectors', () => {
    const tree = projectThreadTree([
      node('root', null), node('old', 'root', { updateTime: 0 }),
      node('z', 'root', { updateTime: 1775001600 }),
      node('a', 'root', { updateTime: 1775001600 }),
      node('active', 'root', { status: 'RUNNING' }),
      node('grand', 'active', { updateTime: 1767225600 }),
    ])
    expect(tree.historyRows.map((row) => row.node.threadId)).toEqual(['active', 'grand', 'a', 'z', 'old'])
    expect(tree.historyRows[1]).toMatchObject({ depth: 1, ancestorContinues: [true], isLast: true })
    expect(tree.historyRows.at(-1)?.isLast).toBe(true)
    expect(() => projectThreadTree([node('root', null, { updateTime: Number.NaN })])).toThrow(/updateTime/)
    expect(() => projectThreadTree([node('root', null), node('x', 'y'), node('y', 'x')])).toThrow(/cycle/)
    expect(() => projectThreadTree([node('root', null), node('other', null)])).toThrow(/root/)
  })

  it('compares epoch seconds against ISO milliseconds, including fractional seconds', () => {
    const tree = projectThreadTree([
      node('root', null), node('a-older', 'root', { updateTime: 1774958400 }),
      node('z-newer', 'root', { updateTime: 1774958400.125 }),
    ])
    expect(tree.historyRows.map((row) => row.node.threadId)).toEqual(['z-newer', 'a-older'])
  })

  it.each([undefined, null, NaN, Infinity, -Infinity, [2026, 4, 1], '', 'invalid'])(
    'rejects missing or invalid Instant updateTime %j', (updateTime) => {
      // 模拟未经 TypeScript 校验的 HTTP 响应。
      const invalid = { ...node('root', null), updateTime } as unknown as ActiveThreadTreeNode
      expect(() => projectThreadTree([invalid])).toThrow(/updateTime/)
    },
  )
  it('keeps active (non-IDLE) nodes with their idle ancestors, rooted at the execution root children', () => {
    const rows = projectActiveThreadTree([
      node('root', null),
      node('a', 'root', { status: 'RUNNING' }),
      node('b', 'a'),
      node('c', 'b', { status: 'WAITING' }),
      node('idle', 'root'),
    ])

    expect(rows.map((row) => [row.node.threadId, row.depth])).toEqual([
      ['a', 0],
      ['b', 1],
      ['c', 2],
    ])
    // 祖先保持如实空闲状态，活跃数量由调用方按 status !== 'IDLE' 统计。
    expect(rows.find((row) => row.node.threadId === 'b')?.node.status).toBe('IDLE')
  })

  it('does not emit the root itself and hides everything when no descendant is active', () => {
    expect(projectActiveThreadTree([node('root', null)])).toEqual([])
    expect(projectActiveThreadTree([
      node('root', null, { status: 'RUNNING' }),
      node('a', 'root'),
    ])).toEqual([])
  })

  it('keeps sibling order stable and independent of input order', () => {
    const rows = projectActiveThreadTree([
      node('root', null),
      node('b', 'root', { status: 'RUNNING' }),
      node('a', 'root', { status: 'RUNNING' }),
      node('c', 'root', { status: 'RUNNING' }),
    ])
    expect(rows.map((row) => row.node.threadId)).toEqual(['a', 'b', 'c'])
  })

  it('preserves deep ancestor chains for nested active subagents', () => {
    const rows = projectActiveThreadTree([
      node('root', null),
      node('a', 'root'),
      node('b', 'a'),
      node('c', 'b'),
      node('d', 'c', { status: 'WAITING' }),
    ])
    expect(rows.map((row) => [row.node.threadId, row.depth])).toEqual([
      ['a', 0],
      ['b', 1],
      ['c', 2],
      ['d', 3],
    ])
  })

  it('treats STOPPED orthogonally as executionControl and not as a fourth activity', () => {
    // STOPPED 是 executionControl：如果节点 status 为 IDLE 且无活跃后代，不进入活跃树
    expect(projectActiveThreadTree([
      node('root', null),
      node('idle-stopped', 'root', { status: 'IDLE', executionControl: 'STOPPED' }),
    ])).toEqual([])

    // 如果节点 status 为 RUNNING，即使 executionControl 为 STOPPED，仍保留在活跃树
    const rows = projectActiveThreadTree([
      node('root', null),
      node('running-stopped', 'root', { status: 'RUNNING', executionControl: 'STOPPED' }),
    ])
    expect(rows).toHaveLength(1)
    expect(rows[0]!.node.threadId).toBe('running-stopped')
    expect(rows[0]!.node.status).toBe('RUNNING')
    expect(rows[0]!.node.executionControl).toBe('STOPPED')
  })

  it('rejects malformed trees instead of fabricating rows', () => {
    expect(() => projectActiveThreadTree([
      node('root', null),
      node('root', null, { status: 'RUNNING' }),
    ])).toThrow(/duplicate/)
    expect(() => projectActiveThreadTree([
      node('a', 'missing', { status: 'RUNNING' }),
    ])).toThrow(/root/)
    expect(() => projectActiveThreadTree([
      node('root', null),
      node('orphan', 'ghost', { status: 'RUNNING' }),
    ])).toThrow(/parent/)
  })

  it('only reports terminal outcomes for idle finished turns and suppresses COMPLETED on non-IDLE', () => {
    expect(threadTreeOutcome(node('a', 'root', { status: 'IDLE', outcome: 'COMPLETED' }))).toBe('COMPLETED')
    expect(threadTreeOutcome(node('a', 'root', { status: 'RUNNING', outcome: 'COMPLETED' }))).toBeNull()
    expect(threadTreeOutcome(node('a', 'root', { status: 'WAITING', outcome: 'COMPLETED' }))).toBeNull()
    expect(threadTreeOutcome(node('a', 'root', { status: 'IDLE', outcome: 'UNKNOWN' }))).toBeNull()
  })
})
