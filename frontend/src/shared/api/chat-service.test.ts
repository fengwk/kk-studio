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
  it('keeps Chat collection/detail CRUD and update', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.listChats()
    await service.createChat({ title: 'A', agentName: 'assistant', workspacePath: 'proj/a' })
    await service.getChat('chat /1')
    await service.updateChat('chat /1', { title: 'B', expectedVersion: '2' })
    await service.deleteChat('chat /1', '5')

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/chat')
    expect(client.post).toHaveBeenCalledWith('/ai/chat', {
      title: 'A',
      agentName: 'assistant',
      workspacePath: 'proj/a',
    })
    expect(client.get).toHaveBeenNthCalledWith(2, '/ai/chat/chat%20%2F1')
    expect(client.put).toHaveBeenCalledWith('/ai/chat/chat%20%2F1', {
      title: 'B',
      expectedVersion: '2',
    })
    expect(client.delete).toHaveBeenCalledWith(
      '/ai/chat/chat%20%2F1',
      { params: { expectedVersion: '5' } },
    )
  })

  it('transmits nullable workspacePath directly on Chat creation without environment object', async () => {
    const client = createClient()
    const service = createChatService(client)
    await service.createChat({
      title: 'A',
      agentName: 'assistant',
      workspacePath: 'proj/a',
    })

    expect(client.post).toHaveBeenCalledWith('/ai/chat', {
      title: 'A',
      agentName: 'assistant',
      workspacePath: 'proj/a',
    })
  })
})
