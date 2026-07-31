import { apiClient, type HttpClient } from '@/shared/api/client'
import type { ChatCreateDTO, ChatDTO, ChatUpdateDTO } from '@/shared/api/contracts/ai-chat'
import type {
  HarnessThreadDTO,
  HarnessThreadPage,
  ThreadListSort,
} from '@/shared/api/contracts/ai-runtime'

export interface ChatThreadListOptions {
  sort?: ThreadListSort
  cursor?: string
  limit?: number
}

export function createChatService(client: HttpClient = apiClient) {
  return {
    listChats: (): Promise<ChatDTO[]> => client.get('/ai/chat'),
    createChat: (data: ChatCreateDTO): Promise<ChatDTO> => client.post('/ai/chat', data),
    getChat: (chatId: string): Promise<ChatDTO> => client.get(`/ai/chat/${encodeURIComponent(chatId)}`),
    updateChat: (chatId: string, data: ChatUpdateDTO): Promise<ChatDTO> =>
      client.put(`/ai/chat/${encodeURIComponent(chatId)}`, data),
    deleteChat: (chatId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/chat/${encodeURIComponent(chatId)}`, { params: { expectedVersion } }),
    listChatThreads: (
      chatId: string,
      options: ChatThreadListOptions = {},
    ): Promise<HarnessThreadPage> => {
      const params: Record<string, unknown> = {
        sort: options.sort ?? 'recent',
        limit: options.limit ?? 20,
      }
      if (options.cursor) {
        params.cursor = options.cursor
      }
      return client.get(`/ai/chat/${encodeURIComponent(chatId)}/threads`, { params })
    },
    createChatThread: (chatId: string): Promise<HarnessThreadDTO> =>
      client.post(`/ai/chat/${encodeURIComponent(chatId)}/threads`),
    associateThread: (chatId: string, threadId: string): Promise<void> =>
      client.put(
        `/ai/chat/${encodeURIComponent(chatId)}/threads/${encodeURIComponent(threadId)}`,
      ),
  }
}

export const chatService = createChatService()
