import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { WorkspaceShell } from '@/features/workspace/WorkspaceShell'
import type { PaneRendererProps, PaneRenderers } from '@/features/workspace/pane-target'
import { workspaceKeys } from '@/features/workspace/queries'
import { listSpaces } from '@/features/workspace/workspace-api'
import { ApplicationEventProvider } from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { TextArea } from '@/shared/ui/controls/TextArea'
import '@/styles.css'

const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
export function Draft({ onUnsentChange, openBeside }: PaneRendererProps<{ kind: 'draft'; draftId: string; folderId?: string }>) {
  return <div style={{ display: 'grid', alignContent: 'end', height: '100%', padding: 'var(--space-4)', gap: 'var(--space-2)' }}>
    <TextArea aria-label="Draft text" placeholder="Send a message" onChange={event => onUnsentChange(Boolean(event.target.value))} />
    <div><Button variant="secondary" onClick={() => openBeside({ kind: 'canvas', canvasId: '10000000-0000-4000-8000-000000000007' })}>Open beside</Button></div>
  </div>
}
const renderers = {
  draft: Draft, thread: ({ target }) => <div data-testid="thread">{target.threadId}</div>,
  canvas: () => <div data-testid="canvas">Canvas editor</div>, vscode: () => null,
} satisfies PaneRenderers
export function Harness() {
  const [search, setSearch] = useState(location.search)
  const [selected, select] = useState<string>()
  const { data: spaces = [] } = useQuery({ queryKey: workspaceKeys.spaces(), queryFn: ({ signal }) => listSpaces(signal) })
  const space = spaces.find(item => item.id === selected) ?? spaces[0]
  if (!space) return null
  return <WorkspaceShell key={space.id} userId="browser-test" space={space} spaces={spaces} selectSpace={select}
    openInteraction={(id, threadId) => { select(id); setSearch(`?thread=${threadId}`) }}
    goToSettings={() => {}} renderers={renderers} account={() => <Button variant="secondary" aria-label="Account">S</Button>}
    closeView={async () => true} wrapPanes={content => content} link={{ search, consume: () => setSearch('') }} />
}
setLocale('en-US')
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><ApplicationEventProvider><Harness /></ApplicationEventProvider></QueryClientProvider>)
