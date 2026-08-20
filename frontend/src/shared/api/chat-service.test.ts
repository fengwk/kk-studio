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
  it('keeps only Chat collection/detail CRUD without Thread membership endpoints', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.listChats()
    await service.createChat({ title: 'A', agentName: '9' })
    await service.getChat('chat /1')
    await service.deleteChat('chat /1', '5')

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/chat')
    expect(client.post).toHaveBeenCalledWith('/ai/chat', {
      title: 'A',
      agentName: '9',
    })
    expect(client.get).toHaveBeenNthCalledWith(2, '/ai/chat/chat%20%2F1')
    expect(client.delete).toHaveBeenCalledWith(
      '/ai/chat/chat%20%2F1',
      { params: { expectedVersion: '5' } },
    )
    expect((service as Record<string, unknown>).updateChat).toBeUndefined()
    expect((service as Record<string, unknown>).createChatThread).toBeUndefined()
    expect((service as Record<string, unknown>).associateThread).toBeUndefined()
  })

  it('keeps the complete EnvironmentBinding atomic on Chat creation', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.createChat({
      title: 'A',
      agentName: '9',
      environment: { name: 'local', workspacePath: 'proj/a' },
    })

    expect(client.post).toHaveBeenCalledWith('/ai/chat', {
      title: 'A',
      agentName: '9',
      environment: { name: 'local', workspacePath: 'proj/a' },
    })
  })
})
