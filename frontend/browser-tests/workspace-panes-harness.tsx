import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { PaneLayout, usePaneState } from '@/features/workspace/panes'
import { Button } from '@/shared/ui/controls/Button'
import { setLocale } from '@/shared/i18n'
import '@/styles.css'

setLocale('en-US')
type Target = { title: string }
const isTarget = (value: unknown): value is Target =>
  typeof value === 'object' && value !== null && 'title' in value && typeof value.title === 'string'

export function Harness() {
  const [spaceId, setSpaceId] = useState('first')
  const [state, setState] = usePaneState('r130-browser-user', spaceId, isTarget)
  const [fullscreen, setFullscreen] = useState(false)
  const [pending, setPending] = useState<Target>()
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const bind = (paneId: string, target: Target) => setState(current => ({
    ...current, bindings: { ...current.bindings, [paneId]: target }, focusedPaneId: paneId,
  }))
  const open = (title: string) => {
    const target = { title }, id = state.focusedPaneId
    if (!state.bindings[id] && !drafts[id]) bind(id, target)
    else setPending(target)
  }
  return <div className="harness">
    <nav aria-label="Workspace actions">
      <Button variant="secondary" onClick={() => open('Alpha')}>Open Alpha</Button>
      <Button variant="secondary" onClick={() => open('Beta')}>Open Beta</Button>
      <Button variant="secondary" onClick={() => setSpaceId(spaceId === 'first' ? 'second' : 'first')}>Switch space</Button>
    </nav>
    <main>
      <PaneLayout state={state} setState={setState} fullscreen={fullscreen} onFullscreenChange={setFullscreen}
        describePane={(id, target) => ({ title: target?.title ?? 'New view', content: <div className="demo-view">
          <iframe title={`Fixture ${id}`} srcDoc="<!doctype html><title>Persistent fixture</title>" />
          {!target && <textarea aria-label="Draft" placeholder="Start writing" value={drafts[id] ?? ''} onChange={event => {
            const value = event.currentTarget.value
            setDrafts(previous => ({ ...previous, [id]: value }))
          }} />}
        </div> })}
        picker={pending ? { onSelect: id => { bind(id, pending); setPending(undefined) }, onCancel: () => setPending(undefined) } : undefined} />
    </main>
  </div>
}

createRoot(document.getElementById('root')!).render(<Harness />)
