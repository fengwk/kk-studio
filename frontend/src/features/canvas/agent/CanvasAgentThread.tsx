import { useQuery } from '@tanstack/react-query'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'

/** Canvas 仅提供 CANVAS owner；Canvas graph 不保存 Session/Thread 绑定。 */
export function CanvasAgentThread() {
  const { snapshot } = useCanvasRuntime()
  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })

  if (!snapshot) {
    return null
  }
  return (
    <AgentPane
      key={snapshot.document.id}
      owner={{ type: 'CANVAS', id: snapshot.document.id }}
      paneId="canvas-agent"
      agents={agentsQuery.data?.results ?? []}
      environments={environmentsQuery.data ?? []}
      defaults={{ yoloEnabled: false }}
      focused
    />
  )
}
