/* eslint-disable react-refresh/only-export-components */
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { useApplicationEventIdentity, useApplicationEvents, ApplicationEventProvider } from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { IdePanelProvider } from '@/features/environment/ide/IdePanelProvider'
import { useIdePanel } from '@/features/environment/ide/ide-panel-context'
import type { EnvironmentCardDTO } from '@/shared/api/generated/contracts'

setLocale('zh-CN')

/**
 * 全局面板浏览器基座。真实挂载 ApplicationEventProvider 与 IdePanelProvider；
 * WS/HTTP/iframe 全由 Playwright 路由提供，不启动后端或 code-server。
 */
export const ENV_A = 'a1000000-0000-4000-8000-000000000001'
export const ENV_B = 'a1000000-0000-4000-8000-000000000002'

export const CARD_A: EnvironmentCardDTO = {
  id: ENV_A,
  name: 'local-node',
  ready: true,
  status: 'READY',
  version: '1',
  createTime: 1790812800,
  updateTime: 1790812800,
  capabilities: [],
  statusExpiresAt: null,
  lastSeen: null,
  homeDirectory: '/workspace/a',
}

export const CARD_B: EnvironmentCardDTO = {
  id: ENV_B,
  name: 'remote-node',
  ready: true,
  status: 'READY',
  version: '1',
  createTime: 1790812800,
  updateTime: 1790812800,
  capabilities: [],
  statusExpiresAt: null,
  lastSeen: null,
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

function Harness() {
  const { openEnvironment } = useIdePanel()
  const identity = useApplicationEventIdentity()
  const events = useApplicationEvents()
  const [pane1Draft, setPane1Draft] = useState('pane 1 initial text')
  const [pane2Draft, setPane2Draft] = useState('pane 2 initial text')

  return (
    <div className="harness-container" data-testid="ide-harness-root">
      <div className="harness-controls">
        <span style={{ fontWeight: 600 }}>Global IDE Panel Harness</span>
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
            <p>全局 IDE 不属于 pane；此输入在面板打开、切换环境时保持挂载。</p>
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
            <p>同一 tab 的第二个环境入口仍打开同一个全局面板。</p>
            <div style={{ display: 'flex', gap: 8 }}>
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
              <Button
                size="compact"
                variant="ghost"
                data-testid="pane-2-open-b-shell"
                onClick={() => openEnvironment({
                  environment: { id: CARD_B.id, name: CARD_B.name, ready: CARD_B.ready },
                  mode: 'shell',
                })}
              >
                打开 B 终端
              </Button>
            </div>
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

function HarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <IdePanelProvider>
          <Harness />
        </IdePanelProvider>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<HarnessApp />)
}
