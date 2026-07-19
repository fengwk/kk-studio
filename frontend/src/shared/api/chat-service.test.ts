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
  it('maps Chat CRUD and membership endpoints', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.listChats()
    await service.createChat({ title: 'A', defaultAgentId: '9' })
    await service.getChat('chat /1')
    await service.updateChat('chat /1', { title: 'B', defaultAgentId: '' })
    await service.deleteChat('chat /1')
    await service.listChatSessions('chat /1')
    await service.attachChatSession('chat /1', { sessionId: 's1' })
    await service.detachChatSession('chat /1', 'session /2')

    expect(client.get).toHaveBeenNthCalledWith(1, '/chats')
    expect(client.post).toHaveBeenNthCalledWith(1, '/chats', { title: 'A', defaultAgentId: '9' })
    expect(client.get).toHaveBeenNthCalledWith(2, '/chats/chat%20%2F1')
    expect(client.put).toHaveBeenNthCalledWith(1, '/chats/chat%20%2F1', { title: 'B', defaultAgentId: '' })
    expect(client.delete).toHaveBeenNthCalledWith(1, '/chats/chat%20%2F1')
    expect(client.get).toHaveBeenNthCalledWith(3, '/chats/chat%20%2F1/sessions')
    expect(client.post).toHaveBeenNthCalledWith(2, '/chats/chat%20%2F1/sessions', { sessionId: 's1' })
    expect(client.delete).toHaveBeenNthCalledWith(2, '/chats/chat%20%2F1/sessions/session%20%2F2')
  })
})
