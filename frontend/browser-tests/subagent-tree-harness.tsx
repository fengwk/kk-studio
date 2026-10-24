import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { SubagentTreePanel } from '@/features/chat/runtime/SubagentTreePanel'
import { ActiveThreadTree } from '@/features/chat/runtime/ActiveThreadTree'
import { projectThreadTree, type ActiveThreadTreeNode } from '@/features/chat/runtime/thread-panel/active-thread-tree'
import { ThreadNavigationContext } from '@/features/chat/runtime/thread-navigation-context'

setLocale('zh-CN')
const node = (
  threadId: string,
  parentThreadId: string | null,
  agentName: string,
  status: 'RUNNING' | 'WAITING' | 'IDLE' = 'IDLE',
  executionControl: 'RUNNABLE' | 'STOPPED' = 'RUNNABLE',
  outcome: string | null = null,
): ActiveThreadTreeNode => ({
  threadId,
  parentThreadId,
  agentName,
  name: 'main',
  executionControl,
  outcome,
  updateTime: 1774958400,
  status,
  model: { modelName: 'Known-model', variant: 'default' },
  turnCount: 7,
  toolCallCount: 12,
})
export function Harness() {
  const [selected, setSelected] = useState('deep')
  const [active, setActive] = useState(true)
  const projection = projectThreadTree([
    node('root', null, 'Root'),
    node('planner', 'root', 'Planner'),
    node('worker', 'planner', 'Explorer', active ? 'WAITING' : 'IDLE'),
    node('deep', 'worker', 'Explorer', active ? 'RUNNING' : 'IDLE', 'RUNNABLE', active ? null : 'COMPLETED'),
    node('failed', 'root', 'Reviewer', 'IDLE', 'RUNNABLE', 'FAILED'),
    node('stopped', 'root', 'Explorer', 'IDLE', 'STOPPED', 'STOPPED'),
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
  return <MemoryRouter><ThreadNavigationContext.Provider value={setSelected}>
    <main style={{ maxWidth: 760, margin: '20px auto', padding: 8 }}>
      <button className="ghost-btn" onClick={() => setActive(false)}>finish</button>
      <output aria-label="selected">{selected}</output>
      <ActiveThreadTree tree={tree} currentThreadId={selected} />
      <SubagentTreePanel tree={tree} currentThreadId={selected} />
    </main>
  </ThreadNavigationContext.Provider></MemoryRouter>
}
createRoot(document.getElementById('root')!).render(<Harness />)
