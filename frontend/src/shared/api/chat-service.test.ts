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
    await service.updateChat('chat /1', { title: 'B', defaultAgentId: '', expectedVersion: '4' })
    await service.deleteChat('chat /1', '5')

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/chat')
    expect(client.post).toHaveBeenNthCalledWith(1, '/ai/chat', { title: 'A', defaultAgentId: '9' })
    expect(client.get).toHaveBeenNthCalledWith(2, '/ai/chat/chat%20%2F1')
    expect(client.put).toHaveBeenNthCalledWith(1, '/ai/chat/chat%20%2F1', {
      title: 'B',
      defaultAgentId: '',
      expectedVersion: '4',
    })
    expect(client.delete).toHaveBeenNthCalledWith(1, '/ai/chat/chat%20%2F1', { params: { expectedVersion: '5' } })
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

  it('maps Chat-scoped Thread pagination, creation, and idempotent association', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.listChatThreads('chat /1', { sort: 'created', cursor: 'opaque/cursor', limit: 7 })
    await service.createChatThread('chat /1')
    await service.associateThread('chat /1', 'thread /2')

    expect(client.get).toHaveBeenCalledWith('/ai/chat/chat%20%2F1/threads', {
      params: { sort: 'created', limit: 7, cursor: 'opaque/cursor' },
    })
    expect(client.post).toHaveBeenCalledWith('/ai/chat/chat%20%2F1/threads')
    expect(client.put).toHaveBeenCalledWith('/ai/chat/chat%20%2F1/threads/thread%20%2F2')
  })
})
