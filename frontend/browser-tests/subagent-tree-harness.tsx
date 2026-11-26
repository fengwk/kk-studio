import { useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ActiveThreadTree } from '@/features/thread/runtime/panes/ActiveThreadTree'
import { projectThreadTree, type ActiveThreadTreeNode } from '@/features/thread/runtime/thread-panel/active-thread-tree'
import { ThreadPanel } from '@/features/thread/runtime/thread-panel/ThreadPanel'
import { ThreadNavigationContext } from '@/features/thread/runtime/panes/thread-navigation-context'
import { Button } from '@/shared/ui/controls/Button'

setLocale('zh-CN')

const node = (
  threadId: string,
  parentThreadId: string | null,
  agentName: string,
  status: 'RUNNING' | 'WAITING' | 'IDLE' = 'IDLE',
): ActiveThreadTreeNode => ({
  threadId,
  kind: parentThreadId == null ? 'USER' : 'SUBAGENT',
  parentThreadId,
  agentName,
  name: 'main',
  executionControl: 'RUNNABLE',
  outcome: null,
  updatedAt: 1774958400,
  status,
  execution: { driver: 'builtin', settings: { modelName: 'Known-model', variant: 'default' } },
  turnCount: 7,
  toolCallCount: 12,
})

export function Harness() {
  const [selected, setSelected] = useState('root')
  const [active, setActive] = useState(true)
  const bodyRef = useRef<HTMLDivElement>(null)
  const projection = projectThreadTree([
    node('root', null, 'Root'),
    node('planner', 'root', 'Planner'),
    node('worker', 'planner', 'Explorer', active ? 'WAITING' : 'IDLE'),
    node(
      'deep',
      'worker',
      'A deliberately long agent identity that must truncate on narrow screens',
      active ? 'RUNNING' : 'IDLE',
    ),
    node('finished', 'root', 'Finished task'),
  ])
  const tree = {
    ...projection,
    activeCount: active ? 2 : 0,
    rootThreadId: 'root',
    isLoading: false,
    isError: false,
    refreshFailed: false,
    refetch: () => undefined,
  }
  const transcript = {
    messages: [],
    queuedMessages: [],
    loading: false,
    error: null,
    bodyRef,
    initialScrollTop: null,
  }

  return <MemoryRouter><ThreadNavigationContext.Provider value={setSelected}>
    <main style={{ width: '100%', maxWidth: 760, height: 'min(800px, 100vh)', margin: '0 auto', padding: 8, boxSizing: 'border-box' }}>
      <Button variant="secondary" onClick={() => setActive(false)}>finish</Button>
      <output aria-label="selected">{selected}</output>
      <ThreadPanel transcript={transcript} activity={{
        working: false,
        widgets: <ActiveThreadTree tree={tree} currentThreadId={selected} />,
      }} />
    </main>
  </ThreadNavigationContext.Provider></MemoryRouter>
}

createRoot(document.getElementById('root')!).render(<Harness />)
