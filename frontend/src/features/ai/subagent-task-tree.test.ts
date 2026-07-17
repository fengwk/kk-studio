import { describe, expect, it, vi } from 'vitest'
import { loadSubagentTaskTree } from '@/features/ai/subagent-task-tree'
import type { SubagentTaskDTO } from '@/shared/api/contracts'

describe('subagent task tree', () => {
  it('loads descendants recursively and shares duplicate child-session queries', async () => {
    const listTasks = vi.fn(async (sessionId: string) => tasksBySession[sessionId] ?? [])

    const tree = await loadSubagentTaskTree('root', listTasks)

    expect(tree).toHaveLength(2)
    expect(tree[0]).toMatchObject({ task: { parentInvocationId: 'task-1' }, children: [{ task: { parentInvocationId: 'task-3' } }] })
    expect(tree[1]).toMatchObject({ task: { parentInvocationId: 'task-2' }, children: [{ task: { parentInvocationId: 'task-3' } }] })
    expect(listTasks).toHaveBeenCalledTimes(3)
    expect(listTasks).toHaveBeenCalledWith('root')
    expect(listTasks).toHaveBeenCalledWith('child-a')
    expect(listTasks).toHaveBeenCalledWith('child-shared')
  })

  it('cuts a cyclic child session edge without issuing an unbounded query', async () => {
    const listTasks = vi.fn(async (sessionId: string) => cycleTasks[sessionId] ?? [])

    const tree = await loadSubagentTaskTree('root', listTasks)

    expect(tree[0]?.children[0]).toMatchObject({ task: { childSessionId: 'root' }, children: [] })
    expect(listTasks).toHaveBeenCalledTimes(2)
  })
})

const tasksBySession: Record<string, SubagentTaskDTO[]> = {
  root: [task('task-1', 'root', 'child-a'), task('task-2', 'root', 'child-a')],
  'child-a': [task('task-3', 'child-a', 'child-shared')],
  'child-shared': [],
}

const cycleTasks: Record<string, SubagentTaskDTO[]> = {
  root: [task('cycle-1', 'root', 'child')],
  child: [task('cycle-2', 'child', 'root')],
}

function task(parentInvocationId: string, parentSessionId: string, childSessionId: string): SubagentTaskDTO {
  return {
    parentInvocationId,
    parentSessionId,
    childSessionId,
    childRunId: `${parentInvocationId}-run`,
    targetAgent: 'researcher',
    workingCopyPolicy: 'SHARED',
    workingCopyRevision: null,
    maxTurns: 10,
    idleTimeoutMillis: null,
    status: 'RUNNING',
    report: null,
    createTime: '2026-06-20T02:00:00Z',
    updateTime: '2026-06-20T02:00:00Z',
  }
}
