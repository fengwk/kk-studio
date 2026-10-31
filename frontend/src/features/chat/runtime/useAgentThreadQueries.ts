import { useMemo } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { toAgentModelViews } from '@/features/catalog'
import { queryKeys } from '@/shared/lib/query-keys'
import { readInteractionsChangedRoot } from '@/shared/app-events'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { useReadModelFreshnessRecheck } from '@/shared/lib/useReadModelFreshnessRecheck'
import { fetchThreadSnapshot } from '@/features/chat/runtime/thread-snapshot-cache'

export function useAgentThreadQueries(threadId: string) {
  const queryClient = useQueryClient()
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const snapshotQuery = useQuery({
    queryKey: queryKeys.threads.snapshot(threadId),
    queryFn: ({ signal }) => fetchThreadSnapshot(queryClient, threadId, { signal }),
    enabled: Boolean(threadId),
  })
  const snapshot = snapshotQuery.data
  const thread = snapshot?.thread
  const sessionId = thread?.sessionId ?? ''

  // 为派生数组提供稳定的身份，避免依赖它们的 hook 在每次渲染时都重跑。
  const agents = useMemo(() => agentsQuery.data?.results ?? [], [agentsQuery.data])
  const models = useMemo(
    () => toAgentModelViews(modelsQuery.data?.results ?? []),
    [modelsQuery.data],
  )
  const entries = useMemo(() => snapshot?.entries ?? [], [snapshot])
  const queuedCommands = useMemo(() => snapshot?.queuedCommands ?? [], [snapshot])
  const toolInvocations = useMemo(() => snapshot?.toolInvocations ?? [], [snapshot])
  const activeAssistantOutput = useMemo(
    () => snapshot?.activeAssistantOutput ?? null,
    [snapshot],
  )
  const generationFailures = useMemo(
    () => snapshot?.generationFailures ?? [],
    [snapshot],
  )
  const stopReceipts = useMemo(() => snapshot?.stopReceipts ?? [], [snapshot])
  // 未确认退出事实是后端快照的必需字段：只在快照尚未加载时为空，不静默兜底缺失契约。
  const unconfirmedExecutions = snapshot == null ? [] : snapshot.unconfirmedExecutions
  const environmentTools = toolInvocations.filter(
    (invocation) => invocation.status === 'READY' && invocation.requiredEnvironmentId != null,
  )
  const rootThreadId = thread?.rootThreadId
  const hasEnvironmentTools = environmentTools.length > 0
  const deadlines = environmentTools.flatMap((invocation) => {
    const at = invocation.environmentWaitFreshnessAt
    return at == null ? [] : [at * 1000]
  })
  const earliest = deadlines.length > 0 ? Math.min(...deadlines) / 1000 : null
  useReadModelFreshnessRecheck(queryKeys.threads.snapshot(threadId), earliest)
  useResourceSync(hasEnvironmentTools && rootThreadId ? { kind: 'interactions' } : null, {
    sync: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    },
    syncOn: (name, data) => name === 'changed' && readInteractionsChangedRoot(data) === rootThreadId,
  })

  return {
    agentsQuery,
    modelsQuery,
    snapshotQuery,
    agents,
    models,
    thread,
    sessionId,
    entries,
    queuedCommands,
    activeAssistantOutput,
    toolInvocations,
    generationFailures,
    stopReceipts,
    unconfirmedExecutions,
  }
}
