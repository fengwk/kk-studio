import { describe, expect, it } from 'vitest'
import { queryKeys } from '@/shared/lib/query-keys'

describe('queryKeys', () => {
  it('keeps one authoritative snapshot key per Thread', () => {
    expect(queryKeys.threads.snapshot('thread-1')).toEqual(['threads', 'snapshot', 'thread-1'])
  })

  it('keeps Chat-scoped Thread lists and chat details in distinct cache scopes', () => {
    expect(queryKeys.chats.detail('chat-1')).toEqual(['chats', 'detail', 'chat-1'])
    expect(queryKeys.chats.threads('chat-1')).toEqual(['chats', 'threads', 'chat-1'])
  })

  /**
   * 测试意图：验证 Skill 全局目录、Skill Packages 以及 Environment 管理操作具有确定性的查询键。
   */
  it('builds deterministic query keys for Skills and Environment operations', () => {
    expect(queryKeys.skills.all).toEqual(['skills'])
    expect(queryKeys.skills.list).toEqual(['skills', 'list'])
    expect(queryKeys.skills.packages).toEqual(['skills', 'packages'])
    expect(queryKeys.skills.packageDetail('pkg-1')).toEqual(['skills', 'packages', 'pkg-1'])

    expect(queryKeys.environments.operations('env-1', 20)).toEqual([
      'environments',
      'detail',
      'env-1',
      'operations',
      20,
    ])
    expect(queryKeys.environments.operation('env-1', 'op-1')).toEqual([
      'environments',
      'detail',
      'env-1',
      'operations',
      'op-1',
    ])
  })
})
