import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { AccountProvider } from '@/features/account/AccountProvider'
import { DraftPaneRenderer, ThreadPaneRenderer } from '@/features/thread/workspace'
import type { PaneTarget } from '@/features/workspace/pane-target'
import { PaneLayout } from '@/features/workspace/panes'
import type { PaneState } from '@/features/workspace/panes/model'
import { setLocale } from '@/shared/i18n'
import '@/styles.css'

setLocale('en-US')
const params = new URLSearchParams(location.search)
const space = {
  id: '10000000-0000-4000-8000-000000000001', title: 'Thread harness',
  version: '1', defaultYoloEnabled: false, defaultAgentName: params.has('no-default') ? null : 'assistant',
}
const paneId = '10000000-0000-4000-8000-000000000002'
const draftId = '10000000-0000-4000-8000-000000000003'
const draft: PaneTarget = { kind: 'draft', draftId }

export function Harness() {
  const [state, setState] = useState<PaneState<PaneTarget>>({ layout: { paneId }, focusedPaneId: paneId, bindings: { [paneId]: draft } })
  const [fullscreen, setFullscreen] = useState(false)
  const [unsent, setUnsent] = useState(false)
  const [settings, setSettings] = useState(false)
  const bind = (target: PaneTarget) => setState((current) => ({ ...current, bindings: { [paneId]: target } }))
  return <div style={{ height: '100%' }} data-unsent={unsent} data-settings={settings}>
    <PaneLayout state={state} setState={setState} fullscreen={fullscreen} onFullscreenChange={setFullscreen}
      describePane={(id, target = draft) => {
        const props = { space, active: state.focusedPaneId === id, replace: bind, open: bind, openBeside: bind,
          onUnsentChange: setUnsent, openSpaceSettings: () => setSettings(true) }
        return target.kind === 'thread'
          ? { title: `Thread ${target.threadId.at(-1)}`, content: <ThreadPaneRenderer {...props} target={target} /> }
          : { title: 'New session', content: target.kind === 'draft' ? <DraftPaneRenderer {...props} target={target} /> : null }
      }} />
  </div>
}

const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><BrowserRouter>
  <AccountProvider><Harness /></AccountProvider>
</BrowserRouter></QueryClientProvider>)
