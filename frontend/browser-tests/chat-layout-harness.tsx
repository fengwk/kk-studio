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
import { ThinkingBlock } from '@/features/ai/runtime/thread-panel/messages/ThinkingBlock'
import { UserMessageBlock } from '@/features/ai/runtime/thread-panel/messages/UserMessageBlock'
import { MetaMessageBlock } from '@/features/ai/runtime/thread-panel/messages/MetaMessageBlock'
import { formatTurnUsageText } from '@/features/ai/runtime/thread-timeline/content-utils'
import type {
  MetaDialogueMessage,
  TextDialogueMessage,
  TurnUsage,
} from '@/features/ai/runtime/thread-timeline-types'

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

const SAMPLE_TURN_USAGE_MESSAGE: MetaDialogueMessage = {
  id: 'msg-turn-usage-1',
  role: 'meta',
  kind: 'turn_usage',
  text: formatTurnUsageText({
    input: 12400,
    output: 1800,
    cacheRead: 4100,
    cacheWrite: 2300,
    cost: 0.045,
    decodeTokens: 1800,
    decodeDurationMillis: 2000,
  }),
  createdAt: 1001,
}

/** 思考收起态必须超出 pane 宽度：前置长段落 + 尾部 Markdown 结构，覆盖省略、尾部更新与展开还原。 */
const INITIAL_THINKING = [
  `前置排查记录：${'逐项核对容器宽度与路径顺序。'.repeat(10)}`,
  '',
  '# 结论',
  '',
  '路径 /usr/local/lib/node_modules/kk-studio 保持原顺序，**未被反转**。',
  '',
  '- 第一项',
  '- 第二项',
  '- 第三项 emoji 👨‍👩‍👧‍👦 与组合 e\u0301 字符',
].join('\n')

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

const SAMPLE_LONG_BRANCH_USAGE: TurnUsage = {
  input: 123456,
  output: 654321,
  cacheRead: 234567,
  cacheWrite: 345678,
  reasoning: 42000,
  providerTotal: 1357900,
  cost: 123456.789,
  decodeTokens: 987654321,
  decodeDurationMillis: 10000,
  contextInputTokens: 890123,
}

export function ChatLayoutHarnessApp() {
  const [paneState, setPaneState] = useState<ChatPaneState>(INITIAL_PANE_STATE)
  const [thinking, setThinking] = useState(INITIAL_THINKING)
  const [streamedThinking, setStreamedThinking] = useState('')
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
                    {/* 测试真实 MetaMessageBlock 用量统计单行截断与完整 title */}
                    <MetaMessageBlock message={SAMPLE_TURN_USAGE_MESSAGE} />
                    {/* 真实 ThinkingBlock：收起单行尾部省略、追加后尾部更新、展开还原原始 Markdown */}
                    <div data-testid="pane-1-thinking">
                      <ThinkingBlock thinking={thinking} />
                      <button
                        type="button"
                        data-testid="thinking-append"
                        onClick={() => setThinking((current) => `${current}\n\n补充：尾部更新`)}
                      >
                        append thinking
                      </button>
                    </div>
                    {/* 空 -> 流式非空：验证收起态节点出现后才注册宽度测量 */}
                    <div data-testid="pane-1-thinking-stream">
                      <ThinkingBlock thinking={streamedThinking} />
                      <button
                        type="button"
                        data-testid="thinking-stream"
                        onClick={() => setStreamedThinking(INITIAL_THINKING)}
                      >
                        stream thinking
                      </button>
                    </div>
                    {/* 隔离验证基础 meta 样式，不构造类型契约外的消息。 */}
                    <div className="thread-block-meta" data-testid="base-meta-style">
                      <div className="thread-block-body thread-meta-text">
                        这是一段包含较多字符的辅助提示信息，在窄屏视口下保持自然折行与完整文本展示。
                      </div>
                    </div>
                  </div>
                )}
              </div>
              {/* 测试 ThreadStatusFooter 唯一一行的字段分隔、超宽省略与完整 hover 事实 */}
              <footer data-testid={`pane-${index + 1}-footer`} style={{ marginTop: 'auto' }}>
                {index === 0 && (
                  <ThreadStatusFooter
                    environment={{ environmentId: 'env-prod-1', environmentName: 'production' }}
                    contextWindow={128000}
                    branchUsage={SAMPLE_BRANCH_USAGE}
                  />
                )}
                {index === 1 && (
                  <ThreadStatusFooter
                    environment={{
                      environmentId: 'env-cluster-primary-us-east-999',
                      environmentName: 'production-us-east-long-cluster-primary-node',
                    }}
                    contextWindow={256000}
                    branchUsage={SAMPLE_LONG_BRANCH_USAGE}
                  />
                )}
                {index >= 2 && (
                  <ThreadStatusFooter
                    environment={null}
                    branchUsage={null}
                  />
                )}
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
