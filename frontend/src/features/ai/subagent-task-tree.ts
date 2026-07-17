import { harnessService } from '@/shared/api/harness-service'
import type { SubagentTaskDTO } from '@/shared/api/contracts'

export interface SubagentTaskNode {
  task: SubagentTaskDTO
  children: SubagentTaskNode[]
}

export async function loadSubagentTaskTree(
  rootSessionId: string,
  listTasks: (sessionId: string) => Promise<SubagentTaskDTO[]> = harnessService.listSessionTasks,
): Promise<SubagentTaskNode[]> {
  const cache = new Map<string, Promise<SubagentTaskNode[]>>()

  async function load(sessionId: string, ancestors: ReadonlySet<string>): Promise<SubagentTaskNode[]> {
    if (ancestors.has(sessionId)) {
      return []
    }
    const cached = cache.get(sessionId)
    if (cached) {
      return cached
    }
    const nextAncestors = new Set(ancestors)
    nextAncestors.add(sessionId)
    const pending = listTasks(sessionId).then(async (tasks) =>
      Promise.all(tasks.map(async (task) => ({ task, children: await load(task.childSessionId, nextAncestors) }))),
    )
    cache.set(sessionId, pending)
    return pending
  }

  return load(rootSessionId, new Set())
}
