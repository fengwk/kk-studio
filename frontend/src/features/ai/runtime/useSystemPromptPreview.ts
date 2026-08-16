import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * 仅在 Events 视图启用。进入 `/events` 拉一次；turn 从 working 回到 idle 后再拉一次。
 */
export function useSystemPromptPreview(threadId: string, enabled: boolean, working: boolean) {
  const query = useQuery({
    queryKey: queryKeys.threads.systemPrompt(threadId),
    queryFn: () => harnessService.getSystemPromptPreview(threadId),
    enabled: Boolean(threadId) && enabled,
    staleTime: Infinity,
  })
  const wasWorkingRef = useRef(working)

  useEffect(() => {
    const turnEnded = wasWorkingRef.current && !working
    wasWorkingRef.current = working
    if (!enabled || !threadId || !turnEnded) {
      return
    }
    void query.refetch()
  }, [enabled, query, threadId, working])

  return query.data?.text ?? ''
}
