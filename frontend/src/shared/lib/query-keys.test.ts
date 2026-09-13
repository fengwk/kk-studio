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
   * 测试意图：验证 Environment 相关的 Skill 来源、持久库存与管理操作具有确定性且互相隔离的查询键。
   */
  it('builds deterministic query keys for Environment skill sources, inventory, and operations', () => {
    expect(queryKeys.environments.skillSources('env-1')).toEqual([
      'environments',
      'detail',
      'env-1',
      'skill-sources',
    ])
    expect(queryKeys.environments.skillSource('env-1', 'src-1')).toEqual([
      'environments',
      'detail',
      'env-1',
      'skill-sources',
      'src-1',
    ])
    expect(queryKeys.environments.inventory('env-1')).toEqual([
      'environments',
      'detail',
      'env-1',
      'inventory',
    ])
    expect(queryKeys.environments.inventorySkills('env-1', true)).toEqual([
      'environments',
      'detail',
      'env-1',
      'inventory',
      'skills',
      true,
    ])
    expect(queryKeys.environments.inventorySkills('env-1', false)).toEqual([
      'environments',
      'detail',
      'env-1',
      'inventory',
      'skills',
      false,
    ])
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
