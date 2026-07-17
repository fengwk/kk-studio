import { Bot, Grid2X2, Layers3, UserRound } from 'lucide-react'
import type { PropsWithChildren } from 'react'
import { Link, useLocation } from 'react-router-dom'

function isAiRoute(pathname: string) {
  return (
    pathname === '/'
    || pathname.startsWith('/sessions')
    || pathname.startsWith('/agents')
    || pathname.startsWith('/models')
    || pathname.startsWith('/providers')
    || pathname.startsWith('/comfyui')
  )
}

export function AppShell({ children }: PropsWithChildren) {
  const location = useLocation()
  const canvasMode = location.pathname.startsWith('/canvas')
  const aiActive = !canvasMode && isAiRoute(location.pathname)

  return (
    <div className={`app-frame ${canvasMode ? 'app-frame-canvas' : 'app-frame-ai'}`}>
      <header className="topbar">
        <div className="topbar-left">
          <Link to={canvasMode ? '/canvas' : '/sessions'} className="brand" aria-label="KK Studio">
            {canvasMode ? <span className="brand-mark" aria-hidden="true">K</span> : null}
            <span>KK Studio</span>
          </Link>
        </div>
        <div className="topbar-center">
          <nav className="topnav" aria-label="Primary">
            <Link className={aiActive ? 'active' : undefined} to="/sessions">
              {!canvasMode ? <Bot aria-hidden="true" /> : null}
              <span>AI</span>
            </Link>
            <Link className={canvasMode ? 'active' : undefined} to="/canvas">
              {!canvasMode ? <Grid2X2 aria-hidden="true" /> : null}
              <span>画布</span>
            </Link>
            <button type="button" disabled title="资产库将在后续版本开放">
              {!canvasMode ? <Layers3 aria-hidden="true" /> : null}
              <span>资产</span>
            </button>
          </nav>
        </div>
        <div className="topbar-right">
          {canvasMode ? (
            <div className="avatar" title="当前工作区" aria-label="当前工作区">FL</div>
          ) : (
            <div className="avatar" title="KK Studio">
              <UserRound aria-hidden="true" />
            </div>
          )}
        </div>
      </header>
      <main className="stage">{children}</main>
    </div>
  )
}
