/* eslint-disable react-refresh/only-export-components */
import { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Link, MemoryRouter, Navigate, Route, Routes, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import {
  ApplicationEventProvider,
  useApplicationEventIdentity,
  useApplicationEvents,
} from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ChatIdeWorkspace } from '@/features/environment/ide/ChatIdeWorkspace'
import { useIdePanel } from '@/features/environment/ide/ide-panel-context'
import { testDaemonConfig } from '@/test-support/environment-test-fixtures'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'

setLocale('zh-CN')

/**
 * Chat 专属 IDE 宿主的浏览器基座。路由结构复刻 WorkbenchShell：
 * 只有 `/chats/:chatId` 把内容放进持久宿主；其它页面内容在宿主外全宽渲染。
 * WS/HTTP/iframe 全部由 Playwright 路由提供，不启动后端或 code-server。
 */
export const ENV_A = 'a1000000-0000-4000-8000-000000000001'
export const ENV_B = 'a1000000-0000-4000-8000-000000000002'

export const CARD_A: EnvironmentDTO = {
  id: ENV_A,
  name: 'local-node',
  daemonConfig: testDaemonConfig(),
  ready: true,
  status: 'READY',
  version: '1',
  createdAt: 1790812800,
  updatedAt: 1790812800,
  capabilities: [],
  statusExpiresAt: null,
  lastSeenAt: null,
  homeDirectory: '/workspace/a',
}

export const CARD_B: EnvironmentDTO = {
  id: ENV_B,
  name: 'remote-node',
  daemonConfig: testDaemonConfig(),
  ready: true,
  status: 'READY',
  version: '1',
  createdAt: 1790812800,
  updatedAt: 1790812800,
  capabilities: [],
  statusExpiresAt: null,
  lastSeenAt: null,
  homeDirectory: '/workspace/b',
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
})

function ChatPage() {
  const { activateChatEnvironment, openEnvironment, togglePanel } = useIdePanel()
  const identity = useApplicationEventIdentity()
  const events = useApplicationEvents()
  const [pane1Draft, setPane1Draft] = useState('pane 1 initial text')
  const [pane2Draft, setPane2Draft] = useState('pane 2 initial text')
  const activatedRef = useRef<string | null>(null)
  useEffect(() => {
    const key = `${CARD_A.id}:${CARD_A.name}:${String(CARD_A.ready)}`
    if (activatedRef.current === key) {
      return
    }
    activatedRef.current = key
    activateChatEnvironment({ id: CARD_A.id, name: CARD_A.name, ready: CARD_A.ready })
  }, [activateChatEnvironment])
  return (
    <div className="harness-container" data-testid="chat-page">
      <div className="harness-controls">
        <span style={{ fontWeight: 600 }}>Chat IDE Host Harness</span>
        <Link to="/other" data-testid="other-link">其他页面</Link>
        <Button size="compact" data-testid="toggle-a" onClick={() => togglePanel(CARD_A)}>切换 IDE</Button>
        <span data-testid="harness-event-status" data-connected={identity != null ? 'true' : 'false'}>
          {identity != null ? 'connected' : 'reconnecting'}
        </span>
        <Button
          size="compact"
          variant="ghost"
          data-testid="disconnect-events"
          onClick={() => {
            events.disconnect()
            window.setTimeout(() => events.connect(), 250)
          }}
        >
          断开并重连事件
        </Button>
      </div>

      <div className="harness-panes">
        <div className="test-chat-pane" data-testid="test-pane-1" data-pane-id="pane-1" style={{ flex: 1 }}>
          <div className="test-chat-pane-body">
            <h3>Pane 1</h3>
            <p>IDE 宿主属于 Chat，不属于 pane；切换布局不卸载宿主和 iframe。</p>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button
                size="compact"
                variant="primary"
                data-testid="pane-1-open-a"
                onClick={() => openEnvironment({
                  environment: { id: CARD_A.id, name: CARD_A.name, ready: CARD_A.ready },
                  mode: 'files',
                })}
              >
                打开环境 A
              </Button>
              <Button
                size="compact"
                variant="ghost"
                data-testid="pane-1-open-a-shell"
                onClick={() => openEnvironment({
                  environment: { id: CARD_A.id, name: CARD_A.name, ready: CARD_A.ready },
                  mode: 'shell',
                })}
              >
                打开 A 终端
              </Button>
            </div>
            <div className="test-pane-composer">
              <textarea
                data-testid="composer-input-pane-1"
                value={pane1Draft}
                onChange={(event) => setPane1Draft(event.target.value)}
              />
            </div>
          </div>
        </div>

        <div className="test-chat-pane" data-testid="test-pane-2" data-pane-id="pane-2" style={{ flex: 1 }}>
          <div className="test-chat-pane-body">
            <h3>Pane 2</h3>
            <p>切换 Chat 环境复用同一个宿主。</p>
            <Button
              size="compact"
              variant="primary"
              data-testid="pane-2-open-b"
              onClick={() => openEnvironment({
                environment: { id: CARD_B.id, name: CARD_B.name, ready: CARD_B.ready },
                mode: 'files',
              })}
            >
              打开环境 B
            </Button>
            <div className="test-pane-composer">
              <textarea
                data-testid="composer-input-pane-2"
                value={pane2Draft}
                onChange={(event) => setPane2Draft(event.target.value)}
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function OtherPage() {
  return (
    <div className="harness-other-page" data-testid="other-page">
      <Link to="/chats/chat-1" data-testid="back-link">返回对话</Link>
      <h1>Other Page</h1>
      <p data-testid="other-page-copy">Chat IDE host is outside this page.</p>
    </div>
  )
}

function HarnessShell() {
  const location = useLocation()
  const chatDetail = /^\/chats\/[^/]+$/.test(location.pathname)
  return (
    <div className="harness-shell" data-testid="harness-shell">
      <ChatIdeWorkspace active={chatDetail}>
        {chatDetail ? <ChatPage /> : null}
      </ChatIdeWorkspace>
      {chatDetail ? null : <OtherPage />}
    </div>
  )
}

function HarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <MemoryRouter initialEntries={['/chats/chat-1']}>
          <Routes>
            <Route path="/chats/:chatId" element={<HarnessShell />} />
            <Route path="/other" element={<HarnessShell />} />
            <Route path="*" element={<Navigate to="/chats/chat-1" replace />} />
          </Routes>
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<HarnessApp />)
}
