import { describe, expect, it } from 'vitest'
import {
  createTaskLevelStateMap,
  parseTaskStatus,
  taskStatusFingerprint,
  taskStatusFromMessage,
  type TaskStatusState,
} from '@/features/ai/runtime/task-status'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

/** 与后端 TaskTool.publishStatus 完全一致的规范心跳。 */
function heartbeat(overrides: Partial<Record<string, unknown>> = {}): string {
  const value = {
    kind: 'task.status',
    threadId: '101',
    subagentType: 'explorer',
    state: 'running_tool',
    depth: 1,
    turns: 3,
    toolCalls: 5,
    lastActivity: 'running read',
    approvals: [{ invocationId: '201', toolName: 'read', reason: null }],
    descendants: [],
    ...overrides,
  }
  return JSON.stringify(value)
}

function taskMessage(partial: string | undefined): ToolDialogueMessage {
  return {
    id: 'task-call-1',
    role: 'tool',
    subjectEntryId: 'e1',
    createdAt: null,
    status: 'streaming',
    phase: 'call',
    text: '',
    toolCallId: 'call-task',
    toolName: 'task',
    rendererKey: 'task',
    arguments: '{"subagent_type":"explorer","prompt":"inspect"}',
    attachments: [],
    invocationId: 'inv-101',
    partial,
  }
}

describe('parseTaskStatus', () => {
  it('parses a canonical heartbeat into the normalized state', () => {
    const status = parseTaskStatus(heartbeat())
    expect(status).toEqual({
      threadId: '101',
      subagentType: 'explorer',
      state: 'running_tool',
      depth: 1,
      turns: 3,
      toolCalls: 5,
      lastActivity: 'running read',
      approvals: [{ invocationId: '201', toolName: 'read', reason: null }],
    })
  })

  it('treats reordered fields and whitespace as semantically identical', () => {
    const canonical = heartbeat()
    const shuffled =
      '{\n  "approvals" : [ {"toolName":"read","reason":null,"invocationId":"201"} ],\n'
      + '  "toolCalls" : 5,\n  "turns" : 3,\n  "lastActivity" : "running read",\n'
      + '  "depth" : 1,\n  "state" : "running_tool",\n'
      + '  "subagentType" : "explorer",\n  "threadId" : "101",\n  "kind" : "task.status"\n}\n'
    expect(parseTaskStatus(shuffled)).toEqual(parseTaskStatus(canonical))
  })

  it('drops non-numeric counters instead of coercing them', () => {
    const status = parseTaskStatus(
      heartbeat({ turns: '3', toolCalls: 5.5, depth: 1, approvals: [] }),
    )
    expect(status?.turns).toBeNull()
    expect(status?.toolCalls).toBeNull()
    expect(status?.depth).toBe(1)
    // 数字 0 是合法计数，不能误判为非法。
    expect(parseTaskStatus(heartbeat({ turns: 0, toolCalls: 0 }))?.turns).toBe(0)
  })

  it('drops invalid depth while rejecting invalid identity/state fields', () => {
    expect(parseTaskStatus(heartbeat({ depth: '1' }))?.depth).toBeNull()
    expect(parseTaskStatus(heartbeat({ depth: -1 }))?.depth).toBeNull()
    expect(parseTaskStatus(heartbeat({ threadId: '' }))).toBeNull()
    expect(parseTaskStatus(heartbeat({ subagentType: 42 }))).toBeNull()
    expect(parseTaskStatus(heartbeat({ state: 'suspended' }))).toBeNull()
    expect(parseTaskStatus(heartbeat({ kind: 'task.result' }))).toBeNull()
    expect(parseTaskStatus('not json')).toBeNull()
    expect(parseTaskStatus('[1,2]')).toBeNull()
  })

  it('builds one semantic fingerprint independent of JSON order and optional-field noise', () => {
    const shuffled =
      '{"approvals":[{"reason":null,"toolName":"read","invocationId":"201"}],'
      + '"lastActivity":"running read","toolCalls":5,"turns":3,"depth":1,'
      + '"state":"running_tool","subagentType":"explorer","threadId":"101","kind":"task.status"}'
    expect(taskStatusFingerprint(shuffled)).toBe(taskStatusFingerprint(heartbeat()))
    expect(taskStatusFingerprint(heartbeat({ turns: '3' }))).toBe(
      taskStatusFingerprint(heartbeat({ turns: null })),
    )
  })

  it('includes normalized descendant relay states in the semantic fingerprint', () => {
    const descendant = {
      threadId: '102',
      subagentType: 'coder',
      state: 'waiting_approval',
      depth: 2,
      turns: 1,
      toolCalls: 1,
      lastActivity: 'waiting bash',
      approvals: [{ invocationId: '301', toolName: 'bash', reason: 'confirm' }],
    }
    expect(taskStatusFingerprint(heartbeat({ descendants: [descendant] }))).not.toBe(
      taskStatusFingerprint(heartbeat()),
    )
  })

  it('keeps only approvals with a valid invocationId and toolName', () => {
    const status = parseTaskStatus(
      heartbeat({
        approvals: [
          { invocationId: '201', toolName: 'read', reason: 'needed' },
          { invocationId: '', toolName: 'write' },
          { toolName: 'run', reason: null },
          { invocationId: '203', toolName: 'bash', reason: '   ' },
          'garbage',
          null,
        ],
      }),
    )
    expect(status?.approvals).toEqual([
      { invocationId: '201', toolName: 'read', reason: 'needed' },
      { invocationId: '203', toolName: 'bash', reason: null },
    ])
  })

  it('accepts a missing or non-array approvals list as empty', () => {
    const parsed = JSON.parse(heartbeat({})) as Record<string, unknown>
    delete parsed.approvals
    expect(parseTaskStatus(JSON.stringify(parsed))?.approvals).toEqual([])
    expect(parseTaskStatus(heartbeat({ approvals: 'nope' }))?.approvals).toEqual([])
  })
})

describe('taskStatusFromMessage', () => {
  it('reads only call-phase messages whose rendererKey is task', () => {
    const partial = heartbeat()
    expect(taskStatusFromMessage(taskMessage(partial))?.threadId).toBe('101')
    expect(
      taskStatusFromMessage(taskMessage(partial))?.subagentType,
    ).toBe('explorer')
    expect(taskStatusFromMessage({ ...taskMessage(partial), rendererKey: 'other' })).toBeNull()
    expect(
      taskStatusFromMessage({ ...taskMessage(partial), phase: 'result' }),
    ).toBeNull()
    expect(taskStatusFromMessage(taskMessage(undefined))).toBeNull()
    expect(taskStatusFromMessage(taskMessage('{"kind":"other"}'))).toBeNull()
  })
})

describe('createTaskLevelStateMap', () => {
  it('replaces the heartbeat of the same child thread without accumulating duplicates', () => {
    const messages = [
      taskMessage(heartbeat({ state: 'queued', turns: 0, toolCalls: 0, lastActivity: 'queued' })),
      taskMessage(heartbeat({ state: 'running_model', turns: 1, lastActivity: 'running_model' })),
      taskMessage(heartbeat({ state: 'waiting_approval', turns: 2, lastActivity: 'waiting_approval' })),
    ]
    const levels = createTaskLevelStateMap(messages)
    // 同一 thread 的三帧心跳只保留最新一帧，且只挂在一个 level 下。
    expect(levels.size).toBe(1)
    expect(levels.get(1)).toEqual([
      {
        threadId: '101',
        subagentType: 'explorer',
        state: 'waiting_approval',
        depth: 1,
        turns: 2,
        toolCalls: 5,
        lastActivity: 'waiting_approval',
        approvals: [{ invocationId: '201', toolName: 'read', reason: null }],
      },
    ])
  })

  it('moves a heartbeat to the new level when its depth changes', () => {
    const levels = createTaskLevelStateMap([
      taskMessage(heartbeat({ depth: 1 })),
      taskMessage(heartbeat({ depth: 2 })),
    ])
    expect(levels.size).toBe(1)
    expect(levels.get(2)?.[0]?.depth).toBe(2)
    expect(levels.get(1)).toBeUndefined()
  })

  it('applies the parentTaskLevel offset and aggregates multiple nodes at one level', () => {
    const first = taskMessage(heartbeat({ threadId: '101', depth: 1 }))
    const second = taskMessage(
      heartbeat({ threadId: '102', subagentType: 'coder', depth: 1, approvals: [] }),
    )
    const third = taskMessage(
      heartbeat({ threadId: '103', subagentType: 'helper', depth: 2, approvals: [] }),
    )
    const levels = createTaskLevelStateMap([first, second, third], 3)
    // 父级偏移 +3：depth 1 -> level 4，depth 2 -> level 5。
    expect([...levels.keys()].sort((a, b) => a - b)).toEqual([4, 5])
    expect(levels.get(4)?.map((status) => status.threadId)).toEqual(['101', '102'])
    expect(levels.get(5)?.map((status) => status.threadId)).toEqual(['103'])
  })

  it('projects relayed descendants as independently actionable task rows', () => {
    const levels = createTaskLevelStateMap([
      taskMessage(
        heartbeat({
          descendants: [
            {
              threadId: '102',
              subagentType: 'coder',
              state: 'waiting_approval',
              depth: 2,
              turns: 1,
              toolCalls: 1,
              lastActivity: 'waiting bash',
              approvals: [{ invocationId: '301', toolName: 'bash', reason: 'confirm' }],
            },
          ],
        }),
      ),
    ])

    expect(levels.get(1)?.map((status) => status.threadId)).toEqual(['101'])
    expect(levels.get(2)).toEqual([
      {
        threadId: '102',
        subagentType: 'coder',
        state: 'waiting_approval',
        depth: 2,
        turns: 1,
        toolCalls: 1,
        lastActivity: 'waiting bash',
        approvals: [{ invocationId: '301', toolName: 'bash', reason: 'confirm' }],
      },
    ])
  })

  it('ignores non-tool messages and non-task tool messages', () => {
    const levels = createTaskLevelStateMap([
      { id: 'u1', role: 'user', subjectEntryId: null, createdAt: null, text: 'hi' },
      { ...taskMessage(heartbeat()), rendererKey: 'read' },
      { ...taskMessage(heartbeat()), phase: 'result' },
    ])
    expect(levels.size).toBe(0)
  })

  it('deduplicates identical statuses parsed from different messages of the same thread', () => {
    const same = heartbeat()
    const levels = createTaskLevelStateMap([taskMessage(same), taskMessage(same)])
    expect(levels.size).toBe(1)
    expect(levels.get(1)).toHaveLength(1)
  })

  it('is typed for the exact normalized snapshot shape', () => {
    const status = parseTaskStatus(heartbeat()) as TaskStatusState
    expect(status.state).toBe('running_tool')
    expect(status.approvals[0]?.invocationId).toBe('201')
  })
})
