import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  ChatCreateDTO,
  ChatDTO,
  ChatSessionAttachDTO,
  ChatUpdateDTO,
  HarnessSessionDTO,
} from '@/shared/api/contracts'

export function createChatService(client: HttpClient = apiClient) {
  return {
    listChats: (): Promise<ChatDTO[]> => client.get('/chats'),
    createChat: (data: ChatCreateDTO = {}): Promise<ChatDTO> => client.post('/chats', data),
    getChat: (chatId: string): Promise<ChatDTO> => client.get(`/chats/${encodeURIComponent(chatId)}`),
    updateChat: (chatId: string, data: ChatUpdateDTO): Promise<ChatDTO> =>
      client.put(`/chats/${encodeURIComponent(chatId)}`, data),
    deleteChat: (chatId: string): Promise<void> => client.delete(`/chats/${encodeURIComponent(chatId)}`),
    listChatSessions: (chatId: string): Promise<HarnessSessionDTO[]> =>
      client.get(`/chats/${encodeURIComponent(chatId)}/sessions`),
    attachChatSession: (chatId: string, data: ChatSessionAttachDTO): Promise<HarnessSessionDTO> =>
      client.post(`/chats/${encodeURIComponent(chatId)}/sessions`, data),
    detachChatSession: (chatId: string, sessionId: string): Promise<void> =>
      client.delete(`/chats/${encodeURIComponent(chatId)}/sessions/${encodeURIComponent(sessionId)}`),
  }
}

export const chatService = createChatService()
