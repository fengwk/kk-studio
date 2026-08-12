import { useQuery } from '@tanstack/react-query'
import { CanvasBlankThread } from '@/features/canvas/agent/CanvasBlankThread'
import { CanvasBoundThread } from '@/features/canvas/agent/CanvasBoundThread'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasDocumentDTO } from '@/shared/api/contracts/studio'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * Canvas Chat 面板主体：按 document.threadId 在真实 Harness Thread
 * （绑定）与 blank 首次发送流程之间切换。环境列表在两个流程间共享；
 * agents 由绑定 controller（useAgentThreadQueries）自行加载。
 */
export function CanvasAgentThread() {
  const { snapshot, bindThreadDocument } = useCanvasRuntime()
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
  const document = snapshot.document
  const threadId = document.threadId

  if (threadId) {
    return (
      <CanvasBoundThread
        threadId={threadId}
        environments={environmentsQuery.data ?? []}
      />
    )
  }
  return (
    <CanvasBlankThread
      canvasId={document.id}
      agents={agentsQuery.data?.results ?? []}
      environments={environmentsQuery.data ?? []}
      onThreadBound={(boundDocument: CanvasDocumentDTO) => bindThreadDocument(boundDocument)}
    />
  )
}
