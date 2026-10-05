import { describe, expect, it } from 'vitest'
import {
  orderThreadAgentTree,
  threadTreeOutcome,
  type ThreadAgentTreeNode,
} from '@/features/ai/runtime/thread-panel/thread-agent-tree'

function node(overrides: Partial<ThreadAgentTreeNode>): ThreadAgentTreeNode {
  return {
    threadId: 'root',
    parentThreadId: null,
    name: 'Root',
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    status: 'IDLE',
    processing: false,
    turnCount: 0,
    toolCallCount: 0,
    outcome: null,
    ...overrides,
  }
}

describe('orderThreadAgentTree', () => {
  it('orders root, siblings, and grandchildren by parent relationship and thread id', () => {
    // 扁平返回不保证顺序；同级必须按 threadId 稳定，深层子节点跟在自己的父节点后。
    const rows = orderThreadAgentTree([
      node({ threadId: 'child-b', parentThreadId: 'root', name: 'Same' }),
      node({ threadId: 'grand', parentThreadId: 'child-a', name: 'Grand' }),
      node({ threadId: 'child-a', parentThreadId: 'root', name: 'Same' }),
      node({ threadId: 'root', name: 'Main' }),
    ], 'child-a')

    expect(rows.map((row) => [row.node.threadId, row.depth, row.role])).toEqual([
      ['root', 0, 'root'],
      ['child-a', 1, 'current'],
      ['grand', 2, 'member'],
      ['child-b', 1, 'member'],
    ])
  })

  it('keeps a lone root and only accepts the four idle terminal outcomes', () => {
    const running = node({ status: 'IDLE', outcome: null })
    const failed = node({ threadId: 'child', parentThreadId: 'root', status: 'IDLE', outcome: 'FAILED' })
    expect(threadTreeOutcome(running)).toBeNull()
    expect(threadTreeOutcome(failed)).toBe('FAILED')
    expect(threadTreeOutcome(node({ status: 'IDLE', outcome: 'COMPLETED' }))).toBe('COMPLETED')
    expect(threadTreeOutcome(node({ status: 'IDLE', outcome: 'STOPPED' }))).toBe('STOPPED')
    expect(threadTreeOutcome(node({ status: 'IDLE', outcome: 'CANCELLED' }))).toBe('CANCELLED')
    expect(threadTreeOutcome(node({ status: 'MODEL_RUNNING', outcome: 'COMPLETED' }))).toBeNull()
    expect(threadTreeOutcome(node({ status: 'IDLE', outcome: 'SUCCESS' }))).toBeNull()
    expect(threadTreeOutcome(node({ status: 'IDLE', outcome: 'ERROR' }))).toBeNull()
    expect(threadTreeOutcome(node({ status: 'IDLE', outcome: 'UNKNOWN' }))).toBeNull()
    expect(orderThreadAgentTree([running], 'root')).toEqual([
      expect.objectContaining({ role: 'root', depth: 0 }),
    ])
    expect(orderThreadAgentTree([], 'root')).toEqual([])
  })

  it('rejects an incomplete tree instead of inventing a root or ignoring the current thread', () => {
    const root = node({ threadId: 'root' })
    const child = node({ threadId: 'child', parentThreadId: 'root' })
    const orphan = node({ threadId: 'orphan', parentThreadId: 'missing-parent' })

    expect(() => orderThreadAgentTree([root], 'child')).toThrow(/current thread/)
    expect(() => orderThreadAgentTree([orphan], 'orphan')).toThrow(/exactly one root/)
    expect(() => orderThreadAgentTree([root, orphan], 'root')).toThrow(/parent is missing/)
    expect(() => orderThreadAgentTree([root, root], 'root')).toThrow(/duplicate/)
    expect(() => orderThreadAgentTree([
      node({ threadId: 'a', parentThreadId: 'b' }),
      node({ threadId: 'b', parentThreadId: 'a' }),
    ], 'a')).toThrow(/cycle|exactly one root/)
    expect(() => orderThreadAgentTree([
      root,
      node({ threadId: 'a', parentThreadId: 'b' }),
      node({ threadId: 'b', parentThreadId: 'a' }),
    ], 'root')).toThrow(/not a single tree/)
    expect(orderThreadAgentTree([root, child], 'child').map((row) => row.node.threadId)).toEqual(['root', 'child'])
  })
})
