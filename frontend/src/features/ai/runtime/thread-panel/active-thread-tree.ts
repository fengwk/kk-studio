/** 仅 IDLE 且回合已结束时由后端给出的终态；未知字符串不视为成功。 */
const THREAD_OUTCOMES = ['COMPLETED', 'FAILED', 'STOPPED', 'CANCELLED'] as const

export type ThreadTreeOutcome = (typeof THREAD_OUTCOMES)[number]

/** 活跃树排序所需的节点形状；不依赖 Harness DTO，便于展示层保持无 API 依赖。 */
export interface ActiveThreadTreeNode {
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

export interface ActiveThreadTreeRow {
  node: ActiveThreadTreeNode
  /** 相对执行根子节点的缩进层级；根自身不出行，故其子节点为 0。 */
  depth: number
}

/**
 * 自动活跃树投影：基于完整执行树筛选 processing 节点，并保留连接它们所需的祖先。
 *
 * - 根自身不重复出一行（根面板已在展示根）；
 * - 祖先即使空闲也保留为层级，但 `processing` 保持如实，调用方据此统计活跃数量；
 * - 兄弟按 threadId 稳定排序，深层祖先逐层保留；
 * - 非法树（重复 id、缺失父、缺少唯一根、成环）抛出，调用方不把它伪装成最新数据；
 * - 没有 processing 节点时返回空数组（无活跃后代不留空壳）。
 */
export function projectActiveThreadTree(
  nodes: readonly ActiveThreadTreeNode[],
): ActiveThreadTreeRow[] {
  if (nodes.length === 0) {
    return []
  }
  const byId = new Map<string, ActiveThreadTreeNode>()
  for (const node of nodes) {
    if (byId.has(node.threadId)) {
      throw new Error('duplicate thread in active tree')
    }
    byId.set(node.threadId, node)
  }
  const roots = nodes.filter((node) => node.parentThreadId == null)
  if (roots.length !== 1) {
    throw new Error('active tree must contain exactly one root')
  }
  for (const node of nodes) {
    if (node.parentThreadId != null && !byId.has(node.parentThreadId)) {
      throw new Error('active tree parent is missing')
    }
  }
  const root = roots[0]!
  const children = new Map<string, ActiveThreadTreeNode[]>()
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

  // 需要显示的时刻：processing 节点本身 + 其到根的完整祖先链。
  const visible = new Set<string>()
  const markChain = (node: ActiveThreadTreeNode) => {
    let cursor: ActiveThreadTreeNode | undefined = node
    while (cursor != null && !visible.has(cursor.threadId)) {
      visible.add(cursor.threadId)
      cursor = cursor.parentThreadId == null ? undefined : byId.get(cursor.parentThreadId)
    }
  }
  for (const node of nodes) {
    if (node.processing) {
      markChain(node)
    }
  }

  const rows: ActiveThreadTreeRow[] = []
  const visit = (parentId: string, depth: number) => {
    for (const child of children.get(parentId) ?? []) {
      if (!visible.has(child.threadId)) {
        continue
      }
      rows.push({ node: child, depth })
      visit(child.threadId, depth + 1)
    }
  }
  visit(root.threadId, 0)
  return rows
}

/** IDLE 且回合已结束才展示终态；其余 outcome 保持运行状态，不伪造成功。 */
export function threadTreeOutcome(node: ActiveThreadTreeNode): ThreadTreeOutcome | null {
  if (node.status !== 'IDLE' || node.outcome == null) {
    return null
  }
  return THREAD_OUTCOMES.includes(node.outcome as ThreadTreeOutcome)
    ? node.outcome as ThreadTreeOutcome
    : null
}
