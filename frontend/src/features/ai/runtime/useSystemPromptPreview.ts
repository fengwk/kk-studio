import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * 仅在 Debug 视图启用。进入 `/debug` 拉一次；turn 开始与结束时都重拉，
 * 使同批 SET_ENVIRONMENT 等设置在模型工作期间即可反映到只读预览。
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
    const workingChanged = wasWorkingRef.current !== working
    wasWorkingRef.current = working
    if (!enabled || !threadId || !workingChanged) {
      return
    }
    void query.refetch()
  }, [enabled, query, threadId, working])

  return query.data?.text ?? ''
}
