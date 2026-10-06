import { describe, expect, it } from 'vitest'
import {
  projectActiveThreadTree,
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
    ...overrides,
  }
}

describe('projectActiveThreadTree', () => {
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
