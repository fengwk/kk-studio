import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ProjectSnapshotDTO } from '../types'

export interface CoordinatorConversationProps {
  projectId: string
  snapshot?: ProjectSnapshotDTO | null
  isLoadingSnapshot?: boolean
  agents?: AgentDefinitionDTO[]
  environments?: EnvironmentCardDTO[]
}

/**
 * CoordinatorConversation 为 Project 侧的 Coordinator 对话面板。
 * 彻底复用 AI 运行时底座 AgentPane，消除独立的状态机与会话轮询，
 * 并严格遵守 Project 单 Session 约束（不允许创建新 Session，仅支持在根节点分支）与归档只读约束。
 */
export function CoordinatorConversation({
  projectId,
  snapshot,
  isLoadingSnapshot = false,
  agents: externalAgents,
  environments: externalEnvironments,
}: CoordinatorConversationProps) {
  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: externalEnvironments == null,
  })

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
    enabled: externalAgents == null,
  })
  const coordinatorThreadId = snapshot?.coordinatorThread?.threadId
  const initialTarget = useMemo<PaneTarget>(
    () => coordinatorThreadId
      ? { kind: 'BOUND_THREAD', threadId: coordinatorThreadId }
      : { kind: 'NEW_SESSION_DRAFT' },
    [coordinatorThreadId],
  )

  if (isLoadingSnapshot || snapshot == null) {
    return (
      <aside className="coordinator-sidebar" aria-label="Coordinator 对话">
        <div
          className="coordinator-loading"
          style={{ padding: '32px', textAlign: 'center', color: 'var(--fg-muted)' }}
        >
          加载 Coordinator 对话中...
        </div>
      </aside>
    )
  }

  const { project } = snapshot
  const isArchived = Boolean(project.archivedAt)
  const coordinatorAgentName = project.coordinatorAgentName || 'coordinator'
  const resolvedAgents = externalAgents ?? agentsQuery.data?.results ?? []
  const resolvedEnvironments = externalEnvironments ?? environmentsQuery.data ?? []

  return (
    <aside className="coordinator-sidebar" aria-label="Coordinator 对话">
      <AgentPane
        key={projectId}
        owner={{ type: 'PROJECT', id: projectId }}
        paneId="coordinator"
        agents={resolvedAgents}
        environments={resolvedEnvironments}
        defaults={{
          agentName: coordinatorAgentName,
        }}
        capabilities={{
          allowNewSession: false,
          readOnly: isArchived,
        }}
        initialTarget={initialTarget}
        focused
      />
    </aside>
  )
}
