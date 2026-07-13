import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function WorkspaceListPage() {
  const workspacesQuery = useQuery({
    queryKey: queryKeys.workspaces.list,
    queryFn: () => agentService.listWorkspaces(),
  })

  if (workspacesQuery.isLoading) {
    return <main className="screen active"><div className="screen-body">正在加载工作区</div></main>
  }
  if (workspacesQuery.error) {
    return <main className="screen active"><div className="screen-body">工作区加载失败</div></main>
  }

  return (
    <main className="screen active">
      <div className="screen-body">
        <h1>工作区</h1>
        <div className="cards-grid">
          {(workspacesQuery.data?.results ?? []).map((workspace) => (
            <Link className="resource-card" key={workspace.id} to={`/workspaces/${encodeURIComponent(workspace.id)}/sessions`}>
              <strong>{workspace.name}</strong>
              <span>{workspace.id}</span>
            </Link>
          ))}
        </div>
      </div>
    </main>
  )
}
