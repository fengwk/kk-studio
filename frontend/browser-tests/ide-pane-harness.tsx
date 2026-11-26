/* eslint-disable react-refresh/only-export-components */
import { createRoot } from 'react-dom/client'
import { useMemo, useRef, useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApplicationEventProvider, useApplicationEvents } from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'
import { PaneLayout } from '@/features/workspace/panes/PaneLayout'
import { splitPane, closePane, swapPanes, paneIds } from '@/features/workspace/panes/model'
import { usePaneState } from '@/features/workspace/panes/usePaneState'
import { bindPane } from '@/features/workspace/pane-bindings'
import { isPaneTarget, type PaneOpenTarget } from '@/features/workspace/pane-target'
import { IdeWorkspaceProvider } from '@/features/environment/ide/IdeWorkspaceProvider'
import { VsCodePaneRenderer } from '@/features/environment/ide/VsCodePaneRenderer'
import { IdePaneCloseContext } from '@/features/environment/ide/ide-pane-close'
import { EnvironmentList } from '@/features/environment/ide/EnvironmentList'
import '@/styles.css'

setLocale('zh-CN')
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

function PaneHarness() {
  const [state, setState] = usePaneState('ide-pane-harness', 'ide-pane-space', isPaneTarget)
  const [fullscreen, setFullscreen] = useState(false)
  const [closing, setClosing] = useState(false)
  const events = useApplicationEvents()
  const guards = useRef(new Map<string, () => Promise<boolean>>())
  const closeContext = useMemo(() => ({
    register: (id: string, close: () => Promise<boolean>) => {
      guards.current.set(id, close)
      return () => { if (guards.current.get(id) === close) guards.current.delete(id) }
    },
    remove: (viewId: string) => setState(current => {
      const pane = paneIds(current.layout).find(id => {
        const target = current.bindings[id]
        return target?.kind === 'vscode' && target.viewId === viewId
      })
      if (!pane) return current
      const bindings = { ...current.bindings }
      delete bindings[pane]
      return { ...current, bindings }
    }),
  }), [setState])
  const bind = (id: string, target: PaneOpenTarget) => setState(current => bindPane(current, id, target))
  const open = (target: PaneOpenTarget) => {
    const existing = paneIds(state.layout).find(id => {
      const bound = state.bindings[id]
      return target.kind === 'vscode' && bound?.kind === 'vscode' && bound.viewId === target.viewId
    })
    if (existing) setState(current => ({ ...current, focusedPaneId: existing }))
    else bind(state.focusedPaneId, target)
  }
  const openBeside = (id: string, target: PaneOpenTarget) => setState(current => {
    const empty = paneIds(current.layout).find(pane => pane !== id && current.bindings[pane] == null)
    const next = empty ? current : splitPane(current, id, 'X')
    if (!empty && next === current) return current
    const destination = empty ?? next.focusedPaneId
    return bindPane(next, destination, target)
  })
  async function closeIde() {
    const target = state.bindings[state.focusedPaneId]
    if (target?.kind !== 'vscode') return
    setClosing(true)
    try {
      if (await guards.current.get(target.viewId)?.()) closeContext.remove(target.viewId)
    } finally {
      setClosing(false)
    }
  }
  const ids = paneIds(state.layout)
  return <IdePaneCloseContext.Provider value={closeContext}>
    <main className="ide-harness">
      <aside className="ide-harness-sidebar">
        <EnvironmentList open={open} />
      </aside>
      <div className="ide-harness-stage">
        <PaneLayout state={state} setState={setState} fullscreen={fullscreen} onFullscreenChange={setFullscreen}
          describePane={(id, target) => ({
            title: target?.kind === 'vscode' ? 'VS Code' : '新会话',
            content: target?.kind === 'vscode' ? <VsCodePaneRenderer space={{} as never} target={target}
              active={id === state.focusedPaneId} replace={next => bind(id, next)} open={open}
              openBeside={next => openBeside(id, next)} onUnsentChange={() => {}} openSpaceSettings={() => {}} /> : null,
          })} />
      </div>
    </main>
    <div className="ide-harness-controls" aria-label="测试操作">
      <button data-testid="split-pane" onClick={() => setState(current => splitPane(current, current.focusedPaneId, 'X'))}>Split</button>
      <button data-testid="close-sibling" onClick={() => setState(current => {
        const sibling = paneIds(current.layout).find(id => current.bindings[id] == null)
        return sibling ? closePane(current, sibling) : current
      })}>Close empty sibling</button>
      <button data-testid="swap-panes" onClick={() => { if (ids.length === 2) setState(current => swapPanes(current, ids[0]!, ids[1]!)) }}>Swap</button>
      <button data-testid="last-pane" onClick={() => setState(current => ({ ...current, focusedPaneId: paneIds(current.layout).at(-1)! }))}>Last</button>
      <button data-testid="toggle-fullscreen" onClick={() => setFullscreen(value => !value)}>Fullscreen</button>
      <button data-testid="close-ide" disabled={closing} onClick={() => void closeIde()}>Close IDE</button>
      <button data-testid="reconnect" onClick={() => { events.disconnect(); events.connect() }}>Reconnect</button>
    </div>
  </IdePaneCloseContext.Provider>
}

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <ApplicationEventProvider>
      <IdeWorkspaceProvider><PaneHarness /></IdeWorkspaceProvider>
    </ApplicationEventProvider>
  </QueryClientProvider>,
)
