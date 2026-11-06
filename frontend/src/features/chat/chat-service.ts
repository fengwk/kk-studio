import { request } from '@/shared/api/client'
import type {
  ChatCreateDTO,
  ChatDTO,
  ChatPageDTO,
  ChatSessionPageDTO,
  ChatUpdateDTO,
  ChatYoloUpdateDTO,
} from '@/shared/api/generated/contracts'

/** keyset 分页参数只在调用方给出时发送，首屏请求保持无查询串。 */
function pagedQuery(cursor: string | null, limit?: number) {
  const query: { cursor?: string; limit?: number } = {}
  if (cursor) {
    query.cursor = cursor
  }
  if (limit != null) {
    query.limit = limit
  }
  return query
}

export const chatService = {
  listChats: (cursor: string | null = null, limit?: number): Promise<ChatPageDTO> =>
    request('GET /api/chat/chats', { query: pagedQuery(cursor, limit) }),
  createChat: (data: ChatCreateDTO): Promise<ChatDTO> =>
    request('POST /api/chat/chats', { body: data }),
  getChat: (chatId: string): Promise<ChatDTO> =>
    request('GET /api/chat/chats/{chatId}', { path: { chatId } }),
  updateChat: (chatId: string, data: ChatUpdateDTO): Promise<ChatDTO> =>
    request('PUT /api/chat/chats/{chatId}', { path: { chatId }, body: data }),
  updateYolo: (chatId: string, data: ChatYoloUpdateDTO): Promise<ChatDTO> =>
    request('PUT /api/chat/chats/{chatId}/yolo', { path: { chatId }, body: data }),
  deleteChat: (chatId: string, expectedVersion: string): Promise<void> =>
    request('DELETE /api/chat/chats/{chatId}', { path: { chatId }, query: { expectedVersion } }),
  listChatSessions: (
    chatId: string,
    cursor: string | null = null,
    limit?: number,
  ): Promise<ChatSessionPageDTO> =>
    request('GET /api/chat/chats/{chatId}/sessions', {
      path: { chatId },
      query: pagedQuery(cursor, limit),
    }),
  getChatSession: (chatId: string, sessionId: string) =>
    request('GET /api/chat/chats/{chatId}/sessions/{sessionId}', { path: { chatId, sessionId } }),
}
