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
    expect(queryKeys.environments.events('env-1')).toEqual(['environments', 'events', 'env-1'])

    expect(queryKeys.mcpServers.all).toEqual(['mcp-servers'])
    expect(queryKeys.mcpServers.list(1, 50)).toEqual(['mcp-servers', 'list', 1, 50])
    expect(queryKeys.mcpServers.detail('fs')).toEqual(['mcp-servers', 'detail', 'fs'])
  })

  it('builds deterministic query keys for Projects', () => {
    // 测试意图：验证 Projects 模块实际使用的查询键保持确定性。
    expect(queryKeys.projects.all).toEqual(['projects'])
    expect(queryKeys.projects.list(false)).toEqual(['projects', 'list', false])
    expect(queryKeys.projects.list(true)).toEqual(['projects', 'list', true])
    expect(queryKeys.projects.detail('proj-1')).toEqual(['projects', 'detail', 'proj-1'])
    expect(queryKeys.projects.snapshot('proj-1')).toEqual(['projects', 'snapshot', 'proj-1'])
  })
})
