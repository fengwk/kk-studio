import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { ChatCard } from '@/features/ai/chat/ChatCard'
import { SelectionPanel } from '@/features/ai/chat/SelectionPanel'
import {
  sessionSelectionItem,
  threadSelectionItem,
} from '@/features/ai/runtime/useRootThreadControl'
import { setLocale } from '@/shared/i18n'

setLocale('zh-CN')

// Both selectors use their production item builders and renderers, without a backend.
const dates = [
  { id: 'instant', value: '2026-10-08T13:15:00Z' },
  { id: 'offset', value: '2026-10-08T21:15:00+08:00' },
  { id: 'cross-day', value: '2026-10-08T01:15:00Z' },
  { id: 'wall-clock', value: '2026-10-08T13:15:00' },
  { id: 'empty', value: null },
  { id: 'invalid', value: 'not-a-date' },
]
const chatDates = [
  ...dates,
  { id: 'seconds', value: Date.parse('2026-10-08T13:15:00Z') / 1000 },
  { id: 'milliseconds', value: Date.parse('2026-10-08T13:15:00Z') },
]
const selectorDates = [
  ...dates,
  { id: 'array', value: [2026, 10, 8, 13, 15] },
]

function Harness() {
  return (
    <MemoryRouter>
      <main style={{ padding: 16, maxWidth: 700, margin: '0 auto' }}>
        <section data-testid="chats">
          <h1>Chat</h1>
          {chatDates.map(({ id, value }) => (
            <div key={id} data-testid={`chat-${id}`} style={{ marginBottom: 8 }}>
              <ChatCard
                chat={{
                  id, title: id, agentName: 'agent', yoloEnabled: false,
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
            items={selectorDates.map(({ id, value }) => sessionSelectionItem({
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
            items={selectorDates.map(({ id, value }) => threadSelectionItem({
              threadId: id, name: id, parentThreadId: null, createdAt: value,
              updatedAt: value, status: 'IDLE', processing: false,
              model: { providerName: 'offline', modelName: 'offline', variant: null },
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
