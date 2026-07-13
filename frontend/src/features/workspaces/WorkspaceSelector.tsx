import { useQuery } from '@tanstack/react-query'
import { ChevronDown, Menu } from 'lucide-react'
import { Link } from 'react-router-dom'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function WorkspaceSelector({ workspaceId }: { workspaceId: string }) {
  const workspacesQuery = useQuery({
    queryKey: queryKeys.workspaces.list,
    queryFn: () => agentService.listWorkspaces(),
  })
  const workspaces = workspacesQuery.data?.results ?? []
  const activeWorkspace = workspaces.find((workspace) => workspace.id === workspaceId)

  return (
    <details className="workspace">
      <summary>
        <Menu aria-hidden="true" className="icon-main" />
        <span>{activeWorkspace?.name ?? 'Workspace'}</span>
        <ChevronDown aria-hidden="true" className="icon-chev" />
      </summary>
      <div className="workspace-menu">
        <Link to="/workspaces">全部工作区</Link>
        {workspaces.map((workspace) => (
          <Link key={workspace.id} to={`/workspaces/${encodeURIComponent(workspace.id)}/sessions`}>
            {workspace.name}
          </Link>
        ))}
      </div>
    </details>
  )
}
