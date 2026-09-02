import { apiClient, type HttpClient } from '@/shared/api/client'
import type { ChatCreateDTO, ChatDTO, ChatUpdateDTO } from '@/shared/api/contracts/ai-chat'

export function createChatService(client: HttpClient = apiClient) {
  return {
    listChats: (): Promise<ChatDTO[]> => client.get('/ai/chat'),
    createChat: (data: ChatCreateDTO): Promise<ChatDTO> => client.post('/ai/chat', data),
    getChat: (chatId: string): Promise<ChatDTO> => client.get(`/ai/chat/${encodeURIComponent(chatId)}`),
    updateChat: (chatId: string, data: ChatUpdateDTO): Promise<ChatDTO> =>
      client.put(`/ai/chat/${encodeURIComponent(chatId)}`, data),
    deleteChat: (chatId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/chat/${encodeURIComponent(chatId)}`, { params: { expectedVersion } }),
  }
}

export const chatService = createChatService()
