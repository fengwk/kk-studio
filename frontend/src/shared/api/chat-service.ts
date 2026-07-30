import { apiClient, type HttpClient } from '@/shared/api/client'
import type { ChatCreateDTO, ChatDTO, ChatUpdateDTO } from '@/shared/api/contracts'

export function createChatService(client: HttpClient = apiClient) {
  return {
    listChats: (): Promise<ChatDTO[]> => client.get('/chats'),
    createChat: (data: ChatCreateDTO = {}): Promise<ChatDTO> => client.post('/chats', data),
    getChat: (chatId: string): Promise<ChatDTO> => client.get(`/chats/${encodeURIComponent(chatId)}`),
    updateChat: (chatId: string, data: ChatUpdateDTO): Promise<ChatDTO> =>
      client.put(`/chats/${encodeURIComponent(chatId)}`, data),
    deleteChat: (chatId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/chats/${encodeURIComponent(chatId)}`, { params: { expectedVersion } }),
  }
}

export const chatService = createChatService()
