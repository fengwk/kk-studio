import { useEffect, useMemo } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { toAgentModelViews } from '@/features/ai/catalog'
import { queryKeys } from '@/shared/lib/query-keys'
import { readInteractionsChangedRoot, useApplicationEvents } from '@/shared/app-events'
import { useReadModelFreshnessRecheck } from '@/shared/lib/useReadModelFreshnessRecheck'

export function useAgentThreadQueries(threadId: string) {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
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
    queryFn: () => harnessService.getThreadSnapshot(threadId),
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
  const modelInvocation = useMemo(() => snapshot?.modelInvocation ?? null, [snapshot])
  const modelAttemptFailures = useMemo(
    () => snapshot?.modelAttemptFailures ?? [],
    [snapshot],
  )
  const stopReceipts = useMemo(() => snapshot?.stopReceipts ?? [], [snapshot])
  const environmentTools = toolInvocations.filter(
    (invocation) => invocation.status === 'READY' && invocation.requiredEnvironmentId != null,
  )
  const rootThreadId = thread?.parentThreadId == null
    ? thread?.threadId
    : thread.yoloPolicy.rootThreadId
  const hasEnvironmentTools = environmentTools.length > 0
  const deadlines = environmentTools.flatMap((invocation) => {
    const at = invocation.environmentWaitFreshnessAt
    return at == null ? [] : [typeof at === 'number' ? at * 1000 : Date.parse(at)]
  })
  const earliest = deadlines.length > 0 ? Math.min(...deadlines) / 1000 : null
  useReadModelFreshnessRecheck(queryKeys.threads.snapshot(threadId), earliest)
  useEffect(() => {
    if (!hasEnvironmentTools || !rootThreadId) {
      return
    }
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    }
    return applicationEvents.subscribe(
      { kind: 'interactions' },
      {
        onSubscribed: invalidate,
        onResync: invalidate,
        onEvent: (name, data) => {
          if (name === 'changed' && readInteractionsChangedRoot(data) === rootThreadId) {
            invalidate()
          }
        },
      },
    )
  }, [applicationEvents, hasEnvironmentTools, queryClient, rootThreadId, threadId])

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
    modelInvocation,
    toolInvocations,
    modelAttemptFailures,
    stopReceipts,
    manualCompaction: snapshot?.manualCompaction ?? {
      available: false,
      disabledReason: 'Thread snapshot is not loaded',
    },
  }
}
