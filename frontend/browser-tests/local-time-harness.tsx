import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { ChatCard } from '@/features/chat/ChatCard'
import { SelectionPanel } from '@/features/chat/SelectionPanel'
import { sessionSelectionItem, threadSelectionItem } from '@/features/chat/runtime/root-thread/session-selection-items'
import { setLocale } from '@/shared/i18n'

setLocale('zh-CN')

// Both selectors use their production item builders and renderers, without a backend.
// Wire Instants are epoch seconds; the fractional value keeps sub-second precision.
const dates = [
  { id: 'instant', value: Date.parse('2026-10-08T13:15:00Z') / 1000 },
  { id: 'cross-day', value: Date.parse('2026-10-08T01:15:00Z') / 1000 },
  { id: 'fractional', value: Date.parse('2026-10-08T13:15:42.250Z') / 1000 },
]

export function Harness() {
  return (
    <MemoryRouter>
      <main style={{ padding: 16, maxWidth: 700, margin: '0 auto' }}>
        <section data-testid="chats">
          <h1>Chat</h1>
          {dates.map(({ id, value }) => (
            <div key={id} data-testid={`chat-${id}`} style={{ marginBottom: 8 }}>
              <ChatCard
                chat={{
                  id, title: id, agentName: 'agent', yoloEnabled: false,
                  environmentName: null,
                  version: '1', createTime: value, updateTime: value,
                }}
                agents={[]}
              />
            </div>
          ))}
        </section>
        <section data-testid="sessions">
          <SelectionPanel
            title="Session"
            items={dates.map(({ id, value }) => sessionSelectionItem({
              sessionId: id, name: id, createdAt: value, lastActivityAt: value,
              firstMessagePreview: null, threadCount: 1,
            }))}
            onSelect={() => {}}
            onClose={() => {}}
          />
        </section>
        <section data-testid="threads">
          <SelectionPanel
            title="Thread"
            items={dates.map(({ id, value }) => threadSelectionItem({
              threadId: id, name: id, parentThreadId: null, createdAt: value,
              updatedAt: value, status: 'IDLE', executionControl: 'RUNNABLE',
              model: { modelName: 'offline', variant: 'default' },
              headMessagePreview: null,
            }))}
            onSelect={() => {}}
            onClose={() => {}}
          />
        </section>
      </main>
    </MemoryRouter>
  )
}

createRoot(document.getElementById('root')!).render(<Harness />)
