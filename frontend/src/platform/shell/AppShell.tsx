import { Bot, Grid2X2, Menu, UserRound, Wrench, X } from 'lucide-react'
import { useEffect, useRef, useState, type PropsWithChildren } from 'react'
import { Link, useLocation } from 'react-router-dom'

function isAiRoute(pathname: string) {
  return (
    pathname === '/'
    || pathname.startsWith('/chats')
    || pathname.startsWith('/agents')
    || pathname.startsWith('/models')
    || pathname.startsWith('/providers')
    || pathname.startsWith('/environments')
    || pathname.startsWith('/settings')
  )
}

function isToolsRoute(pathname: string) {
  return pathname.startsWith('/comfyui') || pathname.startsWith('/tools')
}

/** Chat 工作区沉浸页：`/chats/:chatId`，不含列表 `/chats`。 */
function isChatWorkspaceRoute(pathname: string) {
  return /^\/chats\/[^/]+/.test(pathname)
}

export function AppShell({ children }: PropsWithChildren) {
  const location = useLocation()
  const canvasMode = location.pathname.startsWith('/canvas')
  const toolsMode = isToolsRoute(location.pathname)
  const chatWorkspaceMode = isChatWorkspaceRoute(location.pathname)
  const aiActive = !canvasMode && !toolsMode && isAiRoute(location.pathname)
  const [navOpen, setNavOpen] = useState(false)
  const navToggleRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    setNavOpen(false)
  }, [location.pathname])

  useEffect(() => {
    if (!navOpen) {
      return
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setNavOpen(false)
        navToggleRef.current?.focus()
      }
    }
    document.addEventListener('keydown', closeOnEscape)
    return () => document.removeEventListener('keydown', closeOnEscape)
  }, [navOpen])

  return (
    <div
      className={`app-frame${chatWorkspaceMode ? ' chat-immersive' : ''}`}
      data-nav-open={navOpen ? 'true' : 'false'}
    >
      {!chatWorkspaceMode ? (
        <header className="topbar">
          <div className="topbar-left">
            <Link
              to={canvasMode ? '/canvas' : toolsMode ? '/comfyui' : '/chats'}
              className="brand"
              aria-label="KK Studio"
              onClick={() => setNavOpen(false)}
            >
              <span className="brand-mark" aria-hidden="true">
                <img src="/favicon.svg?v=11" alt="" width={23} height={23} draggable={false} />
              </span>
              <span>KK Studio</span>
            </Link>
          </div>
          <button
            ref={navToggleRef}
            type="button"
            className="nav-toggle"
            aria-label={navOpen ? '关闭导航' : '打开导航'}
            aria-expanded={navOpen}
            aria-controls="primary-navigation"
            onClick={() => setNavOpen((open) => !open)}
          >
            {navOpen ? <X aria-hidden="true" /> : <Menu aria-hidden="true" />}
          </button>
          <div className="topbar-center">
            <nav id="primary-navigation" className="topnav" aria-label="Primary">
              <Link
                className={aiActive ? 'active' : undefined}
                to="/chats"
                aria-label="智能 AI"
                onClick={() => setNavOpen(false)}
              >
                <Bot aria-hidden="true" />
                <span>智能</span>
                <small aria-hidden="true">AI</small>
              </Link>
              <Link
                className={canvasMode ? 'active' : undefined}
                to="/canvas"
                aria-label="画布 Canvas"
                onClick={() => setNavOpen(false)}
              >
                <Grid2X2 aria-hidden="true" />
                <span>画布</span>
                <small aria-hidden="true">Canvas</small>
              </Link>
              <Link
                className={toolsMode ? 'active' : undefined}
                to="/comfyui"
                aria-label="工具 Tools"
                onClick={() => setNavOpen(false)}
              >
                <Wrench aria-hidden="true" />
                <span>工具</span>
                <small aria-hidden="true">Tools</small>
              </Link>
              {/* 移动端收起后并入汉堡面板；桌面由 .topbar-right 展示 */}
              <div className="topnav-user" title="当前工作区" aria-label="当前工作区">
                <div className="avatar" aria-hidden="true">
                  <UserRound aria-hidden="true" />
                </div>
                <span>工作区</span>
              </div>
            </nav>
          </div>
          <div className="topbar-right">
            <div className="avatar" title="当前工作区" aria-label="当前工作区">
              <UserRound aria-hidden="true" />
            </div>
          </div>
        </header>
      ) : null}
      <main className="stage">{children}</main>
    </div>
  )
}
