import type { HarnessThreadTreeNodeDTO } from '@/shared/api/contracts/ai-runtime'
import type { BackendDateTime } from '@/shared/api/contracts/base'

const THREAD_OUTCOMES = ['COMPLETED', 'FAILED', 'STOPPED', 'CANCELLED'] as const
export type ThreadTreeOutcome = (typeof THREAD_OUTCOMES)[number]
export type ActiveThreadTreeNode = HarnessThreadTreeNodeDTO

export interface ActiveThreadTreeRow {
  node: ActiveThreadTreeNode
  /** 执行根不出行，根子节点深度为 0。 */
  depth: number
  ancestorContinues: boolean[]
  isLast: boolean
}

/** 完整执行树是唯一事实源；非法父链与冲突快照不得伪造成有效树。 */
export function projectThreadTree(nodes: readonly ActiveThreadTreeNode[]) {
  const nodesById = new Map<string, ActiveThreadTreeNode>()
  for (const node of nodes) {
    const previous = nodesById.get(node.threadId)
    if (previous != null) {
      if (signature(previous) !== signature(node)) {
        throw new Error('conflicting duplicate thread in tree')
      }
      continue
    }
    timeValue(node.updateTime)
    nodesById.set(node.threadId, node)
  }
  const unique = [...nodesById.values()]
  if (unique.length === 0) {
    return { root: null, nodesById, rows: [], historyRows: [] }
  }
  const roots = unique.filter((node) => node.parentThreadId == null)
  if (roots.length !== 1) {
    throw new Error('thread tree must contain exactly one root')
  }
  for (const node of unique) {
    if (node.parentThreadId != null && !nodesById.has(node.parentThreadId)) {
      throw new Error('thread tree parent is missing')
    }
  }
  const root = roots[0]!
  const connected = new Set([root.threadId])
  const children = new Map<string, ActiveThreadTreeNode[]>()
  for (const node of unique) {
    const chain = new Set<string>()
    let cursor = node
    while (!connected.has(cursor.threadId)) {
      if (chain.has(cursor.threadId)) {
        throw new Error('thread tree parent cycle')
      }
      chain.add(cursor.threadId)
      cursor = nodesById.get(cursor.parentThreadId!)!
    }
    for (const id of chain) {
      connected.add(id)
    }
    if (node.parentThreadId != null) {
      const siblings = children.get(node.parentThreadId) ?? []
      siblings.push(node)
      children.set(node.parentThreadId, siblings)
    }
  }
  for (const siblings of children.values()) {
    siblings.sort((a, b) => Number(b.processing) - Number(a.processing)
      || timeValue(b.updateTime) - timeValue(a.updateTime)
      || a.threadId.localeCompare(b.threadId))
  }
  const visible = new Set<string>()
  for (const node of unique) {
    if (!node.processing) {
      continue
    }
    let cursor: ActiveThreadTreeNode | undefined = node
    while (cursor != null && !visible.has(cursor.threadId)) {
      visible.add(cursor.threadId)
      cursor = cursor.parentThreadId == null ? undefined : nodesById.get(cursor.parentThreadId)
    }
  }
  const flatten = (filter?: Set<string>): ActiveThreadTreeRow[] => {
    const rows: ActiveThreadTreeRow[] = []
    const visit = (parentId: string, ancestorContinues: boolean[]) => {
      const siblings = (children.get(parentId) ?? [])
        .filter((child) => filter == null || filter.has(child.threadId))
      for (const [index, child] of siblings.entries()) {
        const isLast = index === siblings.length - 1
        rows.push({ node: child, depth: ancestorContinues.length, ancestorContinues, isLast })
        visit(child.threadId, [...ancestorContinues, !isLast])
      }
    }
    visit(root.threadId, [])
    return rows
  }
  return { root, nodesById, rows: flatten(visible), historyRows: flatten() }
}

export function projectActiveThreadTree(nodes: readonly ActiveThreadTreeNode[]): ActiveThreadTreeRow[] {
  return projectThreadTree(nodes).rows
}

function signature(node: ActiveThreadTreeNode): string {
  return JSON.stringify([
    node.threadId, node.parentThreadId, node.updateTime, node.name, node.agentName,
    node.model.providerName, node.model.modelName, node.model.variant,
    node.status, node.processing, node.turnCount, node.toolCallCount, node.outcome,
  ])
}

function timeValue(value: BackendDateTime): number {
  if (value == null) {
    return 0
  }
  const time = typeof value === 'string' ? Date.parse(value)
    : Date.UTC(value[0]!, (value[1] ?? 1) - 1, value[2] ?? 1,
      value[3] ?? 0, value[4] ?? 0, value[5] ?? 0, (value[6] ?? 0) / 1_000_000)
  if (!Number.isFinite(time)) {
    throw new Error('invalid thread updateTime')
  }
  return time
}

/** 未知 outcome 或非 IDLE 不伪造终态。 */
export function threadTreeOutcome(node: ActiveThreadTreeNode): ThreadTreeOutcome | null {
  if (node.status !== 'IDLE' || node.outcome == null) {
    return null
  }
  return THREAD_OUTCOMES.includes(node.outcome as ThreadTreeOutcome)
    ? node.outcome as ThreadTreeOutcome : null
}
