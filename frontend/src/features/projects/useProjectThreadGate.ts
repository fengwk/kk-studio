import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/features/environment/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ProjectsApi } from './projects-api'

interface UseProjectThreadGateInput {
  projectId: string
  issueId: string | null
  threadId: string | null
  api: ProjectsApi
}

/**
 * 受控 AgentPane 的门禁验证：目标 thread 必须属于当前 Issue 的 Agent 线程或 Run，且带 Agent 名称。
 *
 * <p>agents/environments 只在真正要挂载线程时才查询，避免看板轮询多拉两份目录。
 */
export function useProjectThreadGate({ projectId, issueId, threadId, api }: UseProjectThreadGateInput) {
  const issueQuery = useQuery({
    queryKey: queryKeys.projects.issue(projectId, issueId ?? ''),
    queryFn: () => api.getIssue(issueId!),
    enabled: Boolean(issueId),
  })
  const issueDetail = issueQuery.data

  const matchedAgentThread = issueDetail?.agentThreads?.find((entry) => entry.threadId === threadId)
  const matchedRun = issueDetail?.runs?.find((entry) => entry.threadId === threadId)
  const matchedAgentName = matchedAgentThread?.agentName || matchedRun?.agentName || null
  const isThreadValid = Boolean((matchedAgentThread || matchedRun) && matchedAgentName)

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
    enabled: Boolean(threadId),
  })

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: Boolean(threadId),
  })

  return {
    issueTitle: issueDetail?.issue.title,
    isLoading: issueQuery.isLoading,
    isThreadValid,
    agentName: matchedAgentName,
    agents: agentsQuery.data ?? [],
    environments: environmentsQuery.data ?? [],
  }
}
