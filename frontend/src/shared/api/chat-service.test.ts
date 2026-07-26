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
  it('maps Chat CRUD endpoints', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.listChats()
    await service.createChat({ title: 'A', defaultAgentId: '9' })
    await service.getChat('chat /1')
    await service.updateChat('chat /1', { title: 'B', defaultAgentId: '' })
    await service.deleteChat('chat /1')

    expect(client.get).toHaveBeenNthCalledWith(1, '/chats')
    expect(client.post).toHaveBeenNthCalledWith(1, '/chats', { title: 'A', defaultAgentId: '9' })
    expect(client.get).toHaveBeenNthCalledWith(2, '/chats/chat%20%2F1')
    expect(client.put).toHaveBeenNthCalledWith(1, '/chats/chat%20%2F1', { title: 'B', defaultAgentId: '' })
    expect(client.delete).toHaveBeenNthCalledWith(1, '/chats/chat%20%2F1')
    // Chat-Session membership is gone: Chat never touches /sessions sub-resources.
    expect(client.get).not.toHaveBeenCalledWith(expect.stringContaining('/sessions'))
    expect(client.post).not.toHaveBeenCalledWith(expect.stringContaining('/sessions'), expect.anything())
    expect(client.delete).not.toHaveBeenCalledWith(expect.stringContaining('/sessions'))
  })

  it('exposes no chat-session membership operations', () => {
    const service = createChatService(createClient()) as Record<string, unknown>
    expect(service.listChatSessions).toBeUndefined()
    expect(service.attachChatSession).toBeUndefined()
    expect(service.detachChatSession).toBeUndefined()
  })
})
