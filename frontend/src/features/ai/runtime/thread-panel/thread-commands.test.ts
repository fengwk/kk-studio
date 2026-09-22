import { describe, expect, it } from 'vitest'
import {
  commandIdsForTarget,
  filterThreadCommands,
  threadCommandsForTarget,
  THREAD_COMMANDS,
} from '@/features/ai/runtime/thread-panel/thread-commands'

describe('AgentPane command registry', () => {
  it('has exactly the frozen command ids', () => {
    expect(THREAD_COMMANDS.map((command) => command.id)).toEqual([
      'thread',
      'agent',
      'yolo',
      'models',
      'tree',
      'stop',
      'new',
      'upload',
      'debug',
      'shortcuts',
      'compact',
      'rename-session',
      'rename-thread',
    ])
  })

  it('keeps compact disabled with the server reason and enables it when available', () => {
    const target = { kind: 'BOUND_THREAD' as const, threadId: 't1' }
    const disabled = threadCommandsForTarget(target, {
      available: false,
      disabledReason: 'already compacting',
    }).find((command) => command.id === 'compact')
    expect(disabled).toMatchObject({
      disabled: true,
      disabledReason: 'already compacting',
    })
    expect(
      threadCommandsForTarget(target, { available: true, disabledReason: null })
        .find((command) => command.id === 'compact')?.disabled,
    ).toBe(false)
  })

  it('filters command names, keywords, descriptions, and strips a slash prefix', () => {
    expect(filterThreadCommands('/comp', THREAD_COMMANDS).map((command) => command.id))
      .toEqual(['compact'])
    expect(filterThreadCommands('context', THREAD_COMMANDS).map((command) => command.id))
      .toEqual(['compact'])
    expect(filterThreadCommands('debug', THREAD_COMMANDS).map((command) => command.id))
      .toEqual(['debug'])
    expect(filterThreadCommands('no such command', THREAD_COMMANDS)).toEqual([])
    expect(filterThreadCommands(' ', THREAD_COMMANDS)).toEqual(THREAD_COMMANDS)
  })

  it('exposes the target command matrix without mutating the shared registry', () => {
    const target = { kind: 'NEW_THREAD_DRAFT' as const, sessionId: 's1', startEntryId: 'e1' }
    expect(commandIdsForTarget(target)).toEqual([
      'thread',
      'agent',
      'yolo',
      'models',
      'tree',
      'new',
      'upload',
      'shortcuts',
      'rename-session',
    ])
    expect(THREAD_COMMANDS.every((command) => command.disabled === undefined)).toBe(true)
  })

  it('limits rename commands by the durable target state', () => {
    // NEW_SESSION_DRAFT：Session 尚未持久化，session/thread 重命名都不可用。
    expect(commandIdsForTarget({ kind: 'NEW_SESSION_DRAFT' }))
      .not.toContain('rename-session')
    expect(commandIdsForTarget({ kind: 'NEW_SESSION_DRAFT' }))
      .not.toContain('rename-thread')
    // NEW_THREAD_DRAFT：可重命名父 Session，但没有持久化 Thread 可重命名。
    const newThread = { kind: 'NEW_THREAD_DRAFT' as const, sessionId: 's1', startEntryId: 'e1' }
    expect(commandIdsForTarget(newThread)).toContain('rename-session')
    expect(commandIdsForTarget(newThread)).not.toContain('rename-thread')
    // BOUND_THREAD：Session 与 Thread 都已持久化，两者可用。
    const bound = { kind: 'BOUND_THREAD' as const, threadId: 't1' }
    expect(commandIdsForTarget(bound)).toContain('rename-session')
    expect(commandIdsForTarget(bound)).toContain('rename-thread')
  })

  it('disables mutation commands when readOnly is enabled', () => {
    // 测试意图：验证只读模式（如项目已归档）下，状态修改类命令均被禁用，仅保留查看类命令
    const bound = { kind: 'BOUND_THREAD' as const, threadId: 't1' }
    const commands = threadCommandsForTarget(bound, { readOnly: true })
    const stopCommand = commands.find((c) => c.id === 'stop')
    const newCommand = commands.find((c) => c.id === 'new')
    const treeCommand = commands.find((c) => c.id === 'tree')
    const shortcutsCommand = commands.find((c) => c.id === 'shortcuts')

    expect(stopCommand?.disabled).toBe(true)
    expect(stopCommand?.disabledReason).toBe('只读模式')
    expect(newCommand?.disabled).toBe(true)
    expect(newCommand?.disabledReason).toBe('只读模式')
    expect(treeCommand?.disabled).toBe(false)
    expect(shortcutsCommand?.disabled).toBe(false)
  })

  it('disables new command when allowNewSession is false and cannot branch from root', () => {
    // 测试意图：单会话约束下，当无法从 root 分叉时禁用 new 按钮，不伪造按钮功能
    const bound = { kind: 'BOUND_THREAD' as const, threadId: 't1' }
    const commands = threadCommandsForTarget(bound, { allowNewSession: false, canBranchFromRoot: false })
    const newCommand = commands.find((c) => c.id === 'new')
    expect(newCommand?.disabled).toBe(true)
    expect(newCommand?.disabledReason).toBe('当前项目仅支持单会话')
  })
})
