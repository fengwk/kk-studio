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
    /** 与此 Chat 关联的全部 Thread（关联顺序为最新在前）；由客户端排序。 */
    listChatThreads: (chatId: string): Promise<HarnessThreadDTO[]> =>
      client.get(`/ai/chat/${encodeURIComponent(chatId)}/threads`),
    /** 以完整的 branch 草稿原子化创建 Thread 并返回其快照。 */
    createChatThread: (chatId: string, data: HarnessThreadCreateDTO): Promise<HarnessThreadSnapshotDTO> =>
      client.post(`/ai/chat/${encodeURIComponent(chatId)}/threads`, data),
    associateThread: (chatId: string, threadId: string): Promise<void> =>
      client.put(
        `/ai/chat/${encodeURIComponent(chatId)}/threads/${encodeURIComponent(threadId)}`,
      ),
  }
}

export const chatService = createChatService()
