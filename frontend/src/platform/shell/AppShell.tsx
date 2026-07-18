import { Bot, Grid2X2, Layers3, UserRound, Workflow } from 'lucide-react'
import type { PropsWithChildren } from 'react'
import { Link, useLocation } from 'react-router-dom'

function isAiRoute(pathname: string) {
  return (
    pathname === '/'
    || pathname.startsWith('/sessions')
    || pathname.startsWith('/agents')
    || pathname.startsWith('/models')
    || pathname.startsWith('/providers')
  )
}

export function AppShell({ children }: PropsWithChildren) {
  const location = useLocation()
  const canvasMode = location.pathname.startsWith('/canvas')
  const comfyuiMode = location.pathname.startsWith('/comfyui')
  const aiActive = !canvasMode && !comfyuiMode && isAiRoute(location.pathname)

  return (
    <div className="app-frame">
      <header className="topbar">
        <div className="topbar-left">
          <Link
            to={canvasMode ? '/canvas' : comfyuiMode ? '/comfyui' : '/sessions'}
            className="brand"
            aria-label="KK Studio"
          >
            <span className="brand-mark" aria-hidden="true">K</span>
            <span>KK Studio</span>
          </Link>
        </div>
        <div className="topbar-center">
          <nav className="topnav" aria-label="Primary">
            <Link className={aiActive ? 'active' : undefined} to="/sessions">
              <Bot aria-hidden="true" />
              <span>AI</span>
            </Link>
            <Link className={canvasMode ? 'active' : undefined} to="/canvas">
              <Grid2X2 aria-hidden="true" />
              <span>画布</span>
            </Link>
            <Link className={comfyuiMode ? 'active' : undefined} to="/comfyui">
              <Workflow aria-hidden="true" />
              <span>ComfyUI</span>
            </Link>
            <button type="button" disabled title="资产库将在后续版本开放">
              <Layers3 aria-hidden="true" />
              <span>资产</span>
            </button>
          </nav>
        </div>
        <div className="topbar-right">
          <div className="avatar" title="当前工作区" aria-label="当前工作区">
            <UserRound aria-hidden="true" />
          </div>
        </div>
      </header>
      <main className="stage">{children}</main>
    </div>
  )
}
