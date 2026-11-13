import { useRef, useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { MemoryRouter, Route, Routes, useParams, useSearchParams } from 'react-router'
import '@/styles.css'
// 真实 transcript 滚动与卡片布局由产品样式提供；harness 不复制布局。
import '@/features/thread/runtime/thread-panel/thread-conversation.css'
import { MessageList } from '@/features/thread/runtime/thread-panel/messages/MessageList'
import { CompactionActionsContext } from '@/features/thread/runtime/thread-panel/compaction-actions-context'
import { CompactionBranchView } from '@/features/thread/runtime/CompactionBranchView'
import { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline'
import { transcriptStreamRevision } from '@/features/thread/runtime/transcript-reading'
import { useChatTranscriptAutoScroll } from '@/features/thread/runtime/useChatTranscriptAutoScroll'
import { setLocale } from '@/shared/i18n'
import type { EntryType } from '@/shared/api/contracts/ai-runtime'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

setLocale('zh-CN')

/** 分支视图只从这些规范 UUID 加载：切点是分支 FORK 的父 Entry。 */
const FAILED_CHILD_ID = '00000000-0000-4000-8000-000000000010'
const FAILED_CUT_ID = '00000000-0000-4000-8000-000000000011'
const RETAINED_USER_ID = '00000000-0000-4000-8000-000000000002'

function entry(
  entryId: string,
  entryType: EntryType,
  payload: Record<string, unknown>,
  usageCost: HarnessSessionEntryDTO['usageCost'] = null,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost,
  }
}

function userEntry(entryId: string, text: string): HarnessSessionEntryDTO {
  return entry(entryId, 'MESSAGE', {
    message: { role: 'USER', contents: [{ type: 'text', text }] },
  })
}

function compactionStart(
  startEntryId: string,
  childThreadId: string,
  cutEntryId: string,
): HarnessSessionEntryDTO {
  return entry(startEntryId, 'TURN_START', {
    reason: 'COMPACTION',
    settings: {
      agentName: 'coder',
      execution: { driver: 'builtin', settings: { modelName: 'gpt', variant: 'high' } },
      environmentName: null,
    },
    ownerThreadId: 'thread-1',
    contextWindow: 200000,
    maxOutputTokens: 8192,
    compaction: {
      trigger: 'THRESHOLD',
      execution: { driver: 'builtin', settings: { modelName: 'gpt', variant: 'high' } },
      outputBudget: 4096,
      cutEntryId,
      childThreadId,
      joinInvocationId: '00000000-0000-4000-8000-0000000000ff',
    },
  })
}

function turnEnd(startEntryId: string, outcome: string, continueModel = false): HarnessSessionEntryDTO {
  return entry(`${startEntryId}-end`, 'TURN_END', {
    turnStartEntryId: startEntryId,
    outcome,
    continueModel,
    reason: outcome === 'STOPPED' ? 'USER_STOP' : outcome === 'FAILED' ? 'TURN_FAILED' : null,
  })
}

/**
 * 四种持久状态各一张卡：进行中（未关闭）、已压缩、失败（截断）与已停止。
 * 最新一次成功压缩的切点指向保留消息之前的 Entry，因此首条保留消息前出现分隔线。
 */
const HARNESS_ENTRIES: HarnessSessionEntryDTO[] = [
  userEntry('00000000-0000-4000-8000-000000000001', '压缩前的用户提问'),
  // 切点本身是上一条已关闭回合的结束事实；成功压缩从这里继续保留后续 Entry。
  entry('00000000-0000-4000-8000-00000000000a', 'TURN_END', {
    turnStartEntryId: '00000000-0000-4000-8000-000000000009',
    outcome: 'COMPLETED',
    continueModel: true,
  }),
  userEntry(RETAINED_USER_ID, '保留的最近消息'),
  compactionStart(
    '00000000-0000-4000-8000-000000000020',
    '00000000-0000-4000-8000-000000000021',
    '00000000-0000-4000-8000-00000000000a',
  ),
  entry('00000000-0000-4000-8000-000000000022', 'COMPACTION', {
    summaryText: '## 会话摘要\n\n- 已完成的压缩摘要内容。',
  }),
  turnEnd('00000000-0000-4000-8000-000000000020', 'COMPLETED', true),
  compactionStart(
    '00000000-0000-4000-8000-000000000030',
    FAILED_CHILD_ID,
    FAILED_CUT_ID,
  ),
  entry('00000000-0000-4000-8000-000000000031', 'ASSISTANT_ERROR', {
    error: { code: 'COMPACTION_OUTPUT_TRUNCATED', message: 'summary was truncated' },
  }),
  turnEnd('00000000-0000-4000-8000-000000000030', 'FAILED'),
  compactionStart(
    '00000000-0000-4000-8000-000000000040',
    '00000000-0000-4000-8000-000000000041',
    '00000000-0000-4000-8000-00000000000a',
  ),
  entry('00000000-0000-4000-8000-000000000042', 'ASSISTANT_ERROR', {
    error: { code: 'CANCELLED', message: 'compaction branch was stopped' },
  }),
  turnEnd('00000000-0000-4000-8000-000000000040', 'STOPPED'),
  // 最后一条压缩回合保持打开：卡片显示进行中。
  compactionStart(
    '00000000-0000-4000-8000-000000000050',
    '00000000-0000-4000-8000-000000000051',
    '00000000-0000-4000-8000-00000000000a',
  ),
]

function TranscriptRoute() {
  const [retries, setRetries] = useState(0)
  const bodyRef = useRef<HTMLDivElement>(null)
  const messages = buildThreadTimeline(HARNESS_ENTRIES, [], []).messages
  useChatTranscriptAutoScroll(
    bodyRef,
    messages.length,
    HARNESS_ENTRIES.length,
    undefined,
    'compaction-lifecycle-harness',
    transcriptStreamRevision(messages),
  )
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <output id="retry-count" data-testid="retry-count" hidden>{retries}</output>
      <CompactionActionsContext.Provider
        value={{
          compact: () => setRetries((current) => current + 1),
          compactPending: false,
          availability: { status: 'ready', availability: { available: true, disabledReason: null } },
        }}
      >
        <div ref={bodyRef} className="thread-dialogue">
          <div className="thread-blocks">
            <MessageList messages={messages} />
          </div>
        </div>
      </CompactionActionsContext.Provider>
    </div>
  )
}

function BranchRoute() {
  const { threadId = '' } = useParams<{ threadId: string }>()
  const [searchParams] = useSearchParams()
  const afterEntryId = searchParams.get('afterEntryId') ?? ''
  return (
    <div style={{ height: '100vh' }}>
      <CompactionBranchView threadId={threadId} afterEntryId={afterEntryId} />
    </div>
  )
}

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

export function CompactionLifecycleHarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<TranscriptRoute />} />
          <Route path="/threads/:threadId" element={<BranchRoute />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<CompactionLifecycleHarnessApp />)
}
