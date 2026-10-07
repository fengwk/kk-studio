import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { SubagentTreePanel } from '@/features/ai/runtime/SubagentTreePanel'
import { ActiveThreadTree } from '@/features/ai/runtime/ActiveThreadTree'
import { projectThreadTree, type ActiveThreadTreeNode } from '@/features/ai/runtime/thread-panel/active-thread-tree'
import { ThreadNavigationContext } from '@/features/ai/runtime/thread-navigation-context'

setLocale('zh-CN')
const node = (threadId: string, parentThreadId: string | null, agentName: string, processing = false, outcome: string | null = null): ActiveThreadTreeNode => ({
  threadId, parentThreadId, agentName, name: 'main', processing, outcome,
  updateTime: 1774958400, status: processing ? 'MODEL_RUNNING' : 'IDLE',
  model: { providerName: 'provider', modelName: 'Known-model', variant: 'default' },
  turnCount: 7, toolCallCount: 12,
})
export function Harness() {
  const [selected, setSelected] = useState('deep')
  const [active, setActive] = useState(true)
  const projection = projectThreadTree([
    node('root', null, 'Root'), node('planner', 'root', 'Planner'),
    node('worker', 'planner', 'Explorer'), node('deep', 'worker', 'Explorer', active, active ? null : 'COMPLETED'),
    node('failed', 'root', 'Reviewer', false, 'FAILED'), node('stopped', 'root', 'Explorer', false, 'STOPPED'),
  ])
  const tree = { ...projection, activeCount: active ? 1 : 0, rootThreadId: 'root', isLoading: false, isError: false, refreshFailed: false, refetch: () => {} }
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
