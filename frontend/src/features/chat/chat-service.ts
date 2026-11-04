import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  ChatCreateDTO,
  ChatDTO,
  ChatPageDTO,
  ChatSessionPageDTO,
  ChatUpdateDTO,
  HarnessSessionSummaryDTO,
} from '@/shared/api/generated/contracts'

/** keyset 分页参数只在调用方给出时发送，首屏请求保持无查询串与单参数调用。 */
function pagedConfig(cursor: string | null, limit?: number) {
  const params: Record<string, unknown> = {}
  if (cursor) {
    params.cursor = cursor
  }
  if (limit != null) {
    params.limit = String(limit)
  }
  return Object.keys(params).length === 0 ? undefined : { params }
}

function pagedGet<T>(
  client: HttpClient,
  path: string,
  cursor: string | null,
  limit?: number,
): Promise<T> {
  const config = pagedConfig(cursor, limit)
  return config == null ? client.get<T>(path) : client.get<T>(path, config)
}

export function createChatService(client: HttpClient = apiClient) {
  return {
    listChats: (cursor: string | null = null, limit?: number): Promise<ChatPageDTO> =>
      pagedGet(client, '/chats', cursor, limit),
    createChat: (data: ChatCreateDTO): Promise<ChatDTO> => client.post('/chats', data),
    getChat: (chatId: string): Promise<ChatDTO> => client.get(`/chats/${encodeURIComponent(chatId)}`),
    updateChat: (chatId: string, data: ChatUpdateDTO): Promise<ChatDTO> =>
      client.put(`/chats/${encodeURIComponent(chatId)}`, data),
    deleteChat: (chatId: string, expectedVersion: string): Promise<void> =>
      client.delete(`/chats/${encodeURIComponent(chatId)}`, { params: { expectedVersion } }),
    listChatSessions: (
      chatId: string,
      cursor: string | null = null,
      limit?: number,
    ): Promise<ChatSessionPageDTO> =>
      pagedGet(client, `/chats/${encodeURIComponent(chatId)}/sessions`, cursor, limit),
    getChatSession: (chatId: string, sessionId: string): Promise<HarnessSessionSummaryDTO> =>
      client.get(
        `/chats/${encodeURIComponent(chatId)}/sessions/${encodeURIComponent(sessionId)}`,
      ),
  }
}

export const chatService = createChatService()
