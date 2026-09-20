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
   * 测试意图：验证 Skill 全局目录、Skill Packages、Environment 以及 MCP Server 具有确定性的查询键。
   */
  it('builds deterministic query keys for Skills, Environments, MCP servers, and Plugins', () => {
    expect(queryKeys.skills.all).toEqual(['skills'])
    expect(queryKeys.skills.packages).toEqual(['skills', 'packages'])

    expect(queryKeys.plugins.all).toEqual(['plugins'])
    expect(queryKeys.plugins.list).toEqual(['plugins', 'list'])

    expect(queryKeys.environments.all).toEqual(['environments'])
    expect(queryKeys.environments.list).toEqual(['environments', 'list'])
    expect(queryKeys.environments.detail('env-1')).toEqual(['environments', 'detail', 'env-1'])

    expect(queryKeys.mcpServers.all).toEqual(['mcp-servers'])
    expect(queryKeys.mcpServers.list(1, 50)).toEqual(['mcp-servers', 'list', 1, 50])
    expect(queryKeys.mcpServers.detail('fs')).toEqual(['mcp-servers', 'detail', 'fs'])
  })
})
