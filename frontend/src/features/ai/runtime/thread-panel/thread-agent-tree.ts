/** 面板打开时的关系树轮询间隔。关闭后组件卸载，不再请求。 */
export const THREAD_AGENT_TREE_REFETCH_INTERVAL_MS = 5_000

/** 仅 IDLE 且回合已结束时由后端给出的终态；未知字符串不视为成功。 */
const THREAD_OUTCOMES = ['COMPLETED', 'FAILED', 'STOPPED', 'CANCELLED'] as const

export type ThreadTreeOutcome = (typeof THREAD_OUTCOMES)[number]

/** 关系树排序所需的节点形状；不依赖 Harness DTO，便于展示层保持无 API 依赖。 */
export interface ThreadAgentTreeNode {
  threadId: string
  parentThreadId: string | null
  name: string
  agentName: string
  model: { providerName: string; modelName: string; variant: string }
  status: string
  processing: boolean
  turnCount: number
  toolCallCount: number
  outcome: string | null
}

export interface ThreadAgentTreeRow {
  node: ThreadAgentTreeNode
  depth: number
  role: 'root' | 'current' | 'member'
  outcome: ThreadTreeOutcome | null
}

/**
 * 按 parentThreadId 把完整同根树排成根到多层子节点的前序。
 * 当前 Thread 不在结果中、缺少真实根、父节点不在结果中、重复 id 或成环都拒绝，
 * 不把缺失父记录补成根。空数组表示没有关系，保留为空。
 */
export function orderThreadAgentTree(
  nodes: readonly ThreadAgentTreeNode[],
  currentThreadId: string,
): ThreadAgentTreeRow[] {
  if (nodes.length === 0) {
    return []
  }
  if (!nodes.some((node) => node.threadId === currentThreadId)) {
    throw new Error('current thread is missing from agent tree')
  }
  const byId = new Map<string, ThreadAgentTreeNode>()
  for (const node of nodes) {
    if (byId.has(node.threadId)) {
      throw new Error('duplicate thread in agent tree')
    }
    byId.set(node.threadId, node)
  }
  const roots = nodes.filter((node) => node.parentThreadId == null)
  if (roots.length !== 1) {
    throw new Error('agent tree must contain exactly one root')
  }
  for (const node of nodes) {
    if (node.parentThreadId != null && !byId.has(node.parentThreadId)) {
      throw new Error('agent tree parent is missing')
    }
  }

  const children = new Map<string, ThreadAgentTreeNode[]>()
  for (const node of nodes) {
    if (node.parentThreadId == null) {
      continue
    }
    const siblings = children.get(node.parentThreadId) ?? []
    siblings.push(node)
    children.set(node.parentThreadId, siblings)
  }
  for (const siblings of children.values()) {
    siblings.sort((left, right) => left.threadId.localeCompare(right.threadId))
  }

  const rows: ThreadAgentTreeRow[] = []
  const visit = (node: ThreadAgentTreeNode, depth: number) => {
    rows.push({
      node,
      depth,
      role: node.parentThreadId == null
        ? 'root'
        : node.threadId === currentThreadId
          ? 'current'
          : 'member',
      outcome: threadTreeOutcome(node),
    })
    for (const child of children.get(node.threadId) ?? []) {
      visit(child, depth + 1)
    }
  }
  visit(roots[0]!, 0)
  if (rows.length !== nodes.length) {
    throw new Error('agent tree is not a single tree')
  }
  return rows
}

/** IDLE 且回合已结束才展示终态；其余 outcome 保持运行状态，不伪造成功。 */
export function threadTreeOutcome(node: ThreadAgentTreeNode): ThreadTreeOutcome | null {
  if (node.status !== 'IDLE' || node.outcome == null) {
    return null
  }
  return THREAD_OUTCOMES.includes(node.outcome as ThreadTreeOutcome)
    ? node.outcome as ThreadTreeOutcome
    : null
}

export function formatThreadModelLabel(node: ThreadAgentTreeNode): string {
  const model = node.model
  const variant = model.variant && model.variant !== 'default' ? `/${model.variant}` : ''
  return `${model.providerName}/${model.modelName}${variant}`
}
