import { useQuery } from '@tanstack/react-query'
import { chatService } from '@/features/chat/chat-service'
import { isNotFoundError } from '@/shared/api/client'

/**
 * 顶栏面包屑用的 Session 名称。
 *
 * 按 id 单条读取，不依赖分页窗口；目标 Session 已删除时返回 null，让面包屑退化为
 * id，而不是把整页打成错误态。
 */
export function useFocusedSessionName(chatId: string, sessionId: string | null): string | null {
  const sessionQuery = useQuery({
    queryKey: ['chat-workspace', 'session', chatId, sessionId],
    queryFn: async () => {
      try {
        return await chatService.getChatSession(chatId, sessionId!)
      } catch (error) {
        if (isNotFoundError(error)) {
          return null
        }
        throw error
      }
    },
    enabled: sessionId != null,
  })
  return sessionQuery.data?.name ?? null
}
