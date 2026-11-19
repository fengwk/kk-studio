import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { ApiError } from '@/shared/api/client'
import { setLocale } from '@/shared/i18n'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import {
  ResourceOpenInIdeContext,
  ResourceSessionContext,
} from '@/shared/ui/markdown/ResourceUriContext'

setLocale('en-US')

const sessionId = '11111111-2222-3333-4444-555555555555'
const resourceId = '12345678-1234-1234-1234-123456789abc'
const fail = new URLSearchParams(window.location.search).has('fail')

function openAttachment(): Promise<void> {
  return new Promise((resolve, reject) => {
    window.setTimeout(() => {
      if (fail) {
        reject(new ApiError('too large', 413))
      } else {
        resolve()
      }
    }, 250)
  })
}

export function App() {
  return (
    <main style={{ maxWidth: 760, padding: 24 }}>
      <h1>Chat attachment</h1>
      <ResourceSessionContext.Provider value={sessionId}>
        <ResourceOpenInIdeContext.Provider value={{ open: openAttachment }}>
          <MarkdownRenderer content={`[notes.txt](resource://files/${resourceId})`} />
        </ResourceOpenInIdeContext.Provider>
      </ResourceSessionContext.Provider>
    </main>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<App />)
}
