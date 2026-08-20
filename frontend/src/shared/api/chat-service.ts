import { apiClient, type HttpClient } from '@/shared/api/client'
import type { ChatCreateDTO, ChatDTO } from '@/shared/api/contracts/ai-chat'

export function createChatService(client: HttpClient = apiClient) {
  return {
    listChats: (): Promise<ChatDTO[]> => client.get('/ai/chat'),
    createChat: (data: ChatCreateDTO): Promise<ChatDTO> => client.post('/ai/chat', data),
    getChat: (chatId: string): Promise<ChatDTO> => client.get(`/ai/chat/${encodeURIComponent(chatId)}`),
    deleteChat: (chatId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/chat/${encodeURIComponent(chatId)}`, { params: { expectedVersion } }),
  }
}

export const chatService = createChatService()
