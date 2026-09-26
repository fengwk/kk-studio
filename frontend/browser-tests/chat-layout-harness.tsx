import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ChatLayoutSelector } from '@/features/ai/chat/ChatWorkspacePage'
import {
  applyChatLayout,
  visibleChatPanes,
  type ChatLayout,
  type ChatPaneState,
} from '@/features/ai/chat/chat-pane-state'
import { ThreadStatusFooter } from '@/features/ai/runtime/thread-panel/ThreadStatusFooter'
import { UserMessageBlock } from '@/features/ai/runtime/thread-panel/messages/UserMessageBlock'
import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'

setLocale('zh-CN')

const INITIAL_PANE_STATE: ChatPaneState = {
  activePaneId: 'pane-1',
  layout: 'split-2',
  panes: [
    { id: 'pane-1', threadId: 'thread-1', agentId: 'agent-1', environmentName: 'production' },
    { id: 'pane-2', threadId: 'thread-2', agentId: 'agent-1', environmentName: 'production' },
    { id: 'pane-3', threadId: 'thread-3', agentId: 'agent-1', environmentName: 'production' },
    { id: 'pane-4', threadId: 'thread-4', agentId: 'agent-1', environmentName: 'production' },
    { id: 'pane-5', threadId: 'thread-5', agentId: 'agent-1', environmentName: 'production' },
    { id: 'pane-6', threadId: 'thread-6', agentId: 'agent-1', environmentName: 'production' },
    { id: 'pane-7', threadId: 'thread-7', agentId: 'agent-1', environmentName: 'production' },
    { id: 'pane-8', threadId: 'thread-8', agentId: 'agent-1', environmentName: 'production' },
    { id: 'pane-9', threadId: 'thread-9', agentId: 'agent-1', environmentName: 'production' },
  ],
}

const SAMPLE_USER_MESSAGE: TextDialogueMessage = {
  id: 'msg-user-1',
  role: 'user',
  text: '首行文字内容',
  createdAt: 1000,
  contents: [
    { type: 'text', text: '首行文字内容' },
    {
      type: 'attachment',
      attachment: {
        type: 'image',
        name: 'test-preview.png',
        mime: 'image/png',
        data: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
      },
    },
    { type: 'text', text: '尾行文字内容' },
  ],
}

const SAMPLE_BRANCH_USAGE: TurnUsage = {
  input: 12000,
  output: 800,
  cacheRead: 4000,
  cacheWrite: 0,
  reasoning: 150,
  providerTotal: 16950,
  cost: 0.042,
  decodeTokens: 950,
  decodeDurationMillis: 2000,
  contextInputTokens: 16000,
}

export function ChatLayoutHarnessApp() {
  const [paneState, setPaneState] = useState<ChatPaneState>(INITIAL_PANE_STATE)
  const visiblePanes = visibleChatPanes(paneState)

  return (
    <div className="harness-chat-workspace" style={{ display: 'flex', flexDirection: 'column', height: '100vh', width: '100vw', overflow: 'hidden' }}>
      <header className="chat-workspace-header" style={{ padding: '8px 16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '1px solid var(--border)' }}>
        <h1 style={{ margin: 0, fontSize: '16px' }}>Chat Workspace Layout Harness</h1>
        <div className="chat-workspace-actions">
          <ChatLayoutSelector
            layout={paneState.layout}
            onChange={(nextLayout: ChatLayout) => {
              setPaneState((current) => applyChatLayout(current, nextLayout))
            }}
          />
        </div>
      </header>

      {/* 真实 ChatPaneGrid 容器 */}
      <main style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
        <div
          data-testid="chat-pane-grid"
          className={`chat-pane-grid layout-${paneState.layout}`}
          style={{ width: '100%', height: '100%' }}
        >
          {visiblePanes.map((pane, index) => (
            <section
              key={pane.id}
              data-testid={`pane-item-${index + 1}`}
              data-pane-id={pane.id}
              className="chat-pane chat-workspace-pane"
              style={{
                background: 'var(--panel)',
                border: '1px solid var(--border)',
                display: 'flex',
                flexDirection: 'column',
                height: '100%',
                overflow: 'hidden',
                padding: '12px',
              }}
            >
              <h3>{`Pane ${index + 1} (${pane.id})`}</h3>
              <div style={{ flex: 1, overflowY: 'auto' }}>
                {index === 0 && (
                  <div data-testid="pane-1-messages" style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                    {/* 测试用户消息图片与文字的 6px 间距 */}
                    <UserMessageBlock message={SAMPLE_USER_MESSAGE} />
                  </div>
                )}
              </div>
              {/* 测试 ThreadStatusFooter 左对齐与各单元顺序 */}
              <footer data-testid={`pane-${index + 1}-footer`} style={{ marginTop: 'auto' }}>
                <ThreadStatusFooter
                  environment={{ environmentName: 'production' }}
                  contextWindow={128000}
                  branchUsage={SAMPLE_BRANCH_USAGE}
                />
              </footer>
            </section>
          ))}
        </div>
      </main>
    </div>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<ChatLayoutHarnessApp />)
}
