import { Bot, Grid2X2, UserRound } from 'lucide-react'
import type { PropsWithChildren } from 'react'
import { Link } from 'react-router-dom'
import { WorkspaceSelector } from '@/features/workspaces/WorkspaceSelector'

export function AppShell({ workspaceId, children }: PropsWithChildren<{ workspaceId: string }>) {
  return (
    <div className="app-frame">
      <header className="topbar">
        <div className="topbar-left">
          <Link to="/workspaces" className="brand">KK Studio</Link>
          <div className="divider" />
          <WorkspaceSelector workspaceId={workspaceId} />
        </div>
        <div className="topbar-center">
          <nav className="topnav" aria-label="Primary">
            <Link className="active" to={`/workspaces/${encodeURIComponent(workspaceId)}/sessions`}>
              <Bot aria-hidden="true" />
              <span>AI</span>
            </Link>
            <button disabled title="Canvas is not part of the AI MVP">
              <Grid2X2 aria-hidden="true" />
              <span>画布</span>
            </button>
          </nav>
        </div>
        <div className="topbar-right"><div className="avatar" title="KK Studio"><UserRound aria-hidden="true" /></div></div>
      </header>
      <main className="stage">{children}</main>
    </div>
  )
}
