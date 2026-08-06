import { apiClient, type HttpClient } from '@/shared/api/client'
import type { ChatCreateDTO, ChatDTO, ChatUpdateDTO } from '@/shared/api/contracts/ai-chat'
import type {
  HarnessThreadCreateDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'

export function createChatService(client: HttpClient = apiClient) {
  return {
    listChats: (): Promise<ChatDTO[]> => client.get('/ai/chat'),
    createChat: (data: ChatCreateDTO): Promise<ChatDTO> => client.post('/ai/chat', data),
    getChat: (chatId: string): Promise<ChatDTO> => client.get(`/ai/chat/${encodeURIComponent(chatId)}`),
    updateChat: (chatId: string, data: ChatUpdateDTO): Promise<ChatDTO> =>
      client.put(`/ai/chat/${encodeURIComponent(chatId)}`, data),
    deleteChat: (chatId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/chat/${encodeURIComponent(chatId)}`, { params: { expectedVersion } }),
    /** All Threads associated with this Chat (association order newest first); client sorts. */
    listChatThreads: (chatId: string): Promise<HarnessThreadDTO[]> =>
      client.get(`/ai/chat/${encodeURIComponent(chatId)}/threads`),
    /** Create a Thread atomically with the complete branch draft and return its snapshot. */
    createChatThread: (chatId: string, data: HarnessThreadCreateDTO): Promise<HarnessThreadSnapshotDTO> =>
      client.post(`/ai/chat/${encodeURIComponent(chatId)}/threads`, data),
    associateThread: (chatId: string, threadId: string): Promise<void> =>
      client.put(
        `/ai/chat/${encodeURIComponent(chatId)}/threads/${encodeURIComponent(threadId)}`,
      ),
  }
}

export const chatService = createChatService()
