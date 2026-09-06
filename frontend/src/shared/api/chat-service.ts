import { apiClient, type HttpClient } from '@/shared/api/client'
import type { ChatCreateDTO, ChatDTO, ChatUpdateDTO } from '@/shared/api/contracts/ai-chat'
import type { RuntimeSessionSummaryDTO } from '@/shared/api/contracts/ai-runtime'

export function createChatService(client: HttpClient = apiClient) {
  return {
    listChats: (): Promise<ChatDTO[]> => client.get('/ai/chats'),
    createChat: (data: ChatCreateDTO): Promise<ChatDTO> => client.post('/ai/chats', data),
    getChat: (chatId: string): Promise<ChatDTO> => client.get(`/ai/chats/${encodeURIComponent(chatId)}`),
    updateChat: (chatId: string, data: ChatUpdateDTO): Promise<ChatDTO> =>
      client.put(`/ai/chats/${encodeURIComponent(chatId)}`, data),
    deleteChat: (chatId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/chats/${encodeURIComponent(chatId)}`, { params: { expectedVersion } }),
    listChatSessions: (chatId: string): Promise<RuntimeSessionSummaryDTO[]> =>
      client.get(`/ai/chats/${encodeURIComponent(chatId)}/sessions`),
  }
}

export const chatService = createChatService()
