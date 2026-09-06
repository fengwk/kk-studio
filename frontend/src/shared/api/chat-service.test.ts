import { describe, expect, it, vi } from 'vitest'
import { createChatService } from '@/shared/api/chat-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('chatService', () => {
  /**
   * 测试意图：验证 Chat 集合与详情 CRUD API 使用规范化复数路径 /ai/chats/**，
   * 确保列表、创建、详情、更新和删除端点与最新契约一致。
   */
  it('keeps Chat collection/detail CRUD and update under /ai/chats/**', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.listChats()
    await service.createChat({ title: 'A', agentName: 'assistant', workspacePath: 'proj/a' })
    await service.getChat('chat /1')
    await service.updateChat('chat /1', { title: 'B', expectedVersion: '2' })
    await service.deleteChat('chat /1', '5')

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/chats')
    expect(client.post).toHaveBeenCalledWith('/ai/chats', {
      title: 'A',
      agentName: 'assistant',
      workspacePath: 'proj/a',
    })
    expect(client.get).toHaveBeenNthCalledWith(2, '/ai/chats/chat%20%2F1')
    expect(client.put).toHaveBeenCalledWith('/ai/chats/chat%20%2F1', {
      title: 'B',
      expectedVersion: '2',
    })
    expect(client.delete).toHaveBeenCalledWith(
      '/ai/chats/chat%20%2F1',
      { params: { expectedVersion: '5' } },
    )
  })

  /**
   * 测试意图：验证创建 Chat 时直接传递可空的 workspacePath 字段，无需嵌套 environment 对象。
   */
  it('transmits nullable workspacePath directly on Chat creation without environment object', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.createChat({
      title: 'A',
      agentName: 'assistant',
      workspacePath: 'proj/a',
    })

    expect(client.post).toHaveBeenCalledWith('/ai/chats', {
      title: 'A',
      agentName: 'assistant',
      workspacePath: 'proj/a',
    })
  })

  /**
   * 测试意图：验证 Chat owner 的 Session 列表查询收敛移入 chat-service，
   * 且端点使用规范化的 /ai/chats/{chatId}/sessions。
   */
  it('queries chat sessions via GET /ai/chats/{chatId}/sessions', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.listChatSessions('chat /1')

    expect(client.get).toHaveBeenCalledWith('/ai/chats/chat%20%2F1/sessions')
  })
})
