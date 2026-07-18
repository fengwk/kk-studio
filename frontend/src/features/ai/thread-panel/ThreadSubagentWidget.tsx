import type { SubagentTaskNode } from '@/features/ai/subagent-task-tree'

/** Flat running-subagent list inspired by pi-base widget lines (not a multi-column tree). */
export function ThreadSubagentWidget({ taskTree }: { taskTree: SubagentTaskNode[] }) {
  const running = flattenRunning(taskTree)
  if (running.length === 0) {
    return null
  }
  return (
    <div className="thread-subagent-widget" aria-label="运行中的子代理">
      <div className="thread-subagent-widget-title">
        ⟳ subagents running (
        {running.length}
        )
      </div>
      <ul>
        {running.map((item) => (
          <li key={item.id}>
            <span className="thread-subagent-prefix">{item.prefix}</span>
            <span className="thread-subagent-name">{item.name}</span>
            <span className="thread-subagent-meta">
              {item.status}
              {item.detail ? ` · ${item.detail}` : ''}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function flattenRunning(
  nodes: SubagentTaskNode[],
  depth = 0,
  acc: Array<{ id: string; name: string; status: string; detail: string; prefix: string }> = [],
) {
  for (const [index, node] of nodes.entries()) {
    const isLast = index === nodes.length - 1
    const prefix = `${'  '.repeat(depth)}${isLast ? '└─' : '├─'}`
    const status = String(node.task.status ?? 'running').toLowerCase()
    if (status === 'running' || status === 'queued' || status === 'waiting') {
      acc.push({
        id: node.task.parentInvocationId || node.task.childSessionId || `${depth}-${index}`,
        name: node.task.targetAgent || node.task.childSessionId || 'subagent',
        status,
        detail: node.task.report?.finalReport?.slice(0, 80) || '',
        prefix,
      })
    }
    if (node.children?.length) {
      flattenRunning(node.children, depth + 1, acc)
    }
  }
  return acc
}
