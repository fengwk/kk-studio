import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import '@/features/ai/ide/ide-overlay-panel.css'
import { setLocale } from '@/shared/i18n'
import { IdeLifecycleProvider } from '@/features/ai/ide/IdeLifecycleProvider'
import { IdeOverlayPanel, type IdeCommandAction, type ResolvedEnvironmentInfo } from '@/features/ai/ide/IdeOverlayPanel'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { Button } from '@/shared/ui/controls/Button'

setLocale('zh-CN')

/**
 * 声明：此基座仅供 UI 响应式布局与 iframe 节点生命周期保活验证。
 * 所使用的 API 拦截与 iframe 页面皆为轻量 UI fixture，不证明后端真实 code-server 接入或脏稿协议；
 * 容器内 mock composer 仅为辅助 DOM 挂载探针，不声称真实 AgentPane 端到端回归已完成。
 */
export const HARNESS_ENV_ID = 'e1000000-0000-4000-8000-000000000001'
export const PANE_1_VIEW_ID = 'c1000000-0000-4000-8000-000000000001'
export const PANE_2_VIEW_ID = 'c2000000-0000-4000-8000-000000000002'

const TEST_ENV: ResolvedEnvironmentInfo = {
  id: HARNESS_ENV_ID,
  name: 'Isolated Dev Node',
  ready: true,
  homeDirectory: '/workspace/project',
}

const AVAILABLE_ENVS: EnvironmentCardDTO[] = [
  {
    id: HARNESS_ENV_ID,
    name: 'Isolated Dev Node',
    ready: true,
    status: 'READY',
    version: '1',
    createTime: '2026-10-01T00:00:00Z',
    updateTime: '2026-10-01T00:00:00Z',
    capabilities: [],
    statusExpiresAt: null,
    lastSeen: null,
    homeDirectory: '/workspace/project',
  },
]

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
})

export function IdeHarnessApp() {
  const [pane1Width, setPane1Width] = useState<number>(1000)
  const [pane1Narrow, setPane1Narrow] = useState<boolean>(false)
  const [pane1Open, setPane1Open] = useState<boolean>(false)
  const [pane1Action, setPane1Action] = useState<IdeCommandAction | null>(null)

  const [pane2Width, setPane2Width] = useState<number>(1000)
  const [pane2Narrow, setPane2Narrow] = useState<boolean>(false)
  const [pane2Open, setPane2Open] = useState<boolean>(false)
  const [pane2Action, setPane2Action] = useState<IdeCommandAction | null>(null)

  return (
    <div className="harness-container" data-testid="ide-harness-root">
      <div className="harness-controls">
        <span style={{ fontWeight: 600 }}>IDE Overlay UI Test Controls</span>
        <Button
          size="compact"
          variant="ghost"
          data-testid="p1-set-wide"
          onClick={() => {
            setPane1Width(1000)
            setPane1Narrow(false)
          }}
        >
          P1 宽桌面 (1000px)
        </Button>
        <Button
          size="compact"
          variant="ghost"
          data-testid="p1-set-narrow"
          onClick={() => {
            setPane1Width(360)
            setPane1Narrow(true)
          }}
        >
          P1 窄屏 (360px)
        </Button>
        <Button
          size="compact"
          variant="primary"
          data-testid="p1-open-btn"
          onClick={() => {
            setPane1Open(true)
            setPane1Action({ id: Date.now(), mode: 'files' })
          }}
        >
          打开 P1
        </Button>
        <Button
          size="compact"
          variant="ghost"
          data-testid="p1-collapse-btn"
          onClick={() => setPane1Open(false)}
        >
          收起 P1
        </Button>
        <Button
          size="compact"
          variant="ghost"
          data-testid="p1-restore-btn"
          onClick={() => setPane1Open(true)}
        >
          恢复 P1
        </Button>

        <span style={{ borderLeft: '1px solid rgba(255,255,255,0.2)', height: 20, margin: '0 8px' }} />

        <Button
          size="compact"
          variant="ghost"
          data-testid="p2-set-wide"
          onClick={() => {
            setPane2Width(1000)
            setPane2Narrow(false)
          }}
        >
          P2 宽桌面 (1000px)
        </Button>
        <Button
          size="compact"
          variant="ghost"
          data-testid="p2-set-narrow"
          onClick={() => {
            setPane2Width(360)
            setPane2Narrow(true)
          }}
        >
          P2 窄屏 (360px)
        </Button>
        <Button
          size="compact"
          variant="primary"
          data-testid="p2-open-btn"
          onClick={() => {
            setPane2Open(true)
            setPane2Action({ id: Date.now(), mode: 'files' })
          }}
        >
          打开 P2
        </Button>
        <Button
          size="compact"
          variant="ghost"
          data-testid="p2-collapse-btn"
          onClick={() => setPane2Open(false)}
        >
          收起 P2
        </Button>
        <Button
          size="compact"
          variant="ghost"
          data-testid="p2-restore-btn"
          onClick={() => setPane2Open(true)}
        >
          恢复 P2
        </Button>
      </div>

      <div className="harness-panes">
        <div
          className="test-chat-pane"
          data-testid="test-pane-1"
          data-pane-id="pane-1"
          data-pane-layout={pane1Narrow ? 'narrow' : 'wide'}
          style={{ width: `${pane1Width}px`, height: '100%', flexShrink: 0 }}
        >
          <div className="test-chat-pane-body">
            <h3>Pane 1</h3>
            <p>UI 隔离探针：验证主 Pane DOM 节点在 Overlay 打开时保留。</p>
            <div className="test-pane-composer">
              <textarea
                data-testid="composer-input-pane-1"
                placeholder="Pane 1 Composer Draft"
                defaultValue="pane 1 initial text"
              />
            </div>
          </div>
          <IdeOverlayPanel
            isOpen={pane1Open}
            viewId={PANE_1_VIEW_ID}
            paneId="pane-1"
            environment={TEST_ENV}
            availableEnvironments={AVAILABLE_ENVS}
            action={pane1Action}
            isNarrow={pane1Narrow}
            onCollapse={() => setPane1Open(false)}
            onClose={() => setPane1Open(false)}
          />
        </div>

        <div
          className="test-chat-pane"
          data-testid="test-pane-2"
          data-pane-id="pane-2"
          data-pane-layout={pane2Narrow ? 'narrow' : 'wide'}
          style={{ width: `${pane2Width}px`, height: '100%', flexShrink: 0 }}
        >
          <div className="test-chat-pane-body">
            <h3>Pane 2</h3>
            <p>UI 隔离探针：验证双 Pane 独立 viewId 与状态不串扰。</p>
            <div className="test-pane-composer">
              <textarea
                data-testid="composer-input-pane-2"
                placeholder="Pane 2 Composer Draft"
                defaultValue="pane 2 initial text"
              />
            </div>
          </div>
          <IdeOverlayPanel
            isOpen={pane2Open}
            viewId={PANE_2_VIEW_ID}
            paneId="pane-2"
            environment={TEST_ENV}
            availableEnvironments={AVAILABLE_ENVS}
            action={pane2Action}
            isNarrow={pane2Narrow}
            onCollapse={() => setPane2Open(false)}
            onClose={() => setPane2Open(false)}
          />
        </div>
      </div>
    </div>
  )
}

function HarnessAppWithProviders() {
  return (
    <QueryClientProvider client={queryClient}>
      <IdeLifecycleProvider>
        <IdeHarnessApp />
      </IdeLifecycleProvider>
    </QueryClientProvider>
  )
}

const router = createMemoryRouter([
  {
    path: '*',
    element: <HarnessAppWithProviders />,
  },
])

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<RouterProvider router={router} />)
}
