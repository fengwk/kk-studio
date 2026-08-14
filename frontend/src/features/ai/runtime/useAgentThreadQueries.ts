import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { toAgentModelViews } from '@/features/ai/catalog'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentThreadQueries(threadId: string) {
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
  }
}
