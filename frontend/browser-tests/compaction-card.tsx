import { useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
// .thread-dialogue/.thread-blocks 的滚动与布局随 styles 拆分移入 thread 面板样式；
// 本 harness 只挂载 MessageList 而不经过 ThreadPanel，因此必须显式引入产品样式，
// 不能复制一份布局，否则外层滚动区退化为不可滚动的普通块。
import '@/features/thread/runtime/thread-panel/thread-conversation.css'
import { MessageList } from '@/features/thread/runtime/thread-panel/messages/MessageList'
import { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline'
import { transcriptStreamRevision } from '@/features/thread/runtime/transcript-reading'
import { useChatTranscriptAutoScroll } from '@/features/thread/runtime/useChatTranscriptAutoScroll'
import { setLocale } from '@/shared/i18n'
import type { EntryType } from '@/shared/api/contracts/ai-runtime'
import {
  FULL_COMPACTION_SUMMARY,
  LONG_COMPACTION_SUMMARY,
  UNSAFE_COMPACTION_SUMMARY,
} from '@/test-support/resources/compaction-summary'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

setLocale('zh-CN')

let entrySequence = 0

function entry(
  entryType: EntryType,
  payload: Record<string, unknown>,
  entryId = `${entryType.toLowerCase()}-${entrySequence += 1}`,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost: null,
  }
}

function userEntry(text: string): HarnessSessionEntryDTO {
  return entry('MESSAGE', { message: { role: 'USER', contents: [{ type: 'text', text }] } })
}

/** 一个已完成并继续 owner model 的压缩回合，正文为给定摘要。 */
function compactionTurn(summaryText: string): HarnessSessionEntryDTO[] {
  const startEntryId = `turn-start-${entrySequence += 1}`
  return [
    entry('TURN_START', {
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
        cutEntryId: 'cut-1',
      },
    }, startEntryId),
    entry('COMPACTION', { summaryText }, `compaction-${entrySequence}`),
    entry('TURN_END', { turnStartEntryId: startEntryId, outcome: 'COMPLETED', continueModel: true }),
  ]
}

// Entry 身份只构建一次：刷新时重建投影会生成新的 message 对象，但摘要 Entry id 稳定，
// MessageList 因此复用同一 React key，展开状态不重置。
const HARNESS_ENTRIES: HarnessSessionEntryDTO[] = [
  userEntry('压缩前的用户提问'),
  ...compactionTurn(LONG_COMPACTION_SUMMARY),
  ...compactionTurn(FULL_COMPACTION_SUMMARY),
  ...compactionTurn(UNSAFE_COMPACTION_SUMMARY),
  userEntry('压缩后的用户提问'),
]

/**
 * 真实 MessageList、外层滚动 Hook 与现行 styles：数据来自 buildThreadTimeline 投影，
 * 不依赖后端、模型或任何主机服务。
 */
export function CompactionCardHarnessApp() {
  const [, setGeneration] = useState(0)
  const [streamLines, setStreamLines] = useState(0)
  const bodyRef = useRef<HTMLDivElement>(null)
  // 每次渲染都重建投影（新的 message 对象、相同的摘要 Entry 身份）。
  const messages = buildThreadTimeline(HARNESS_ENTRIES, [], []).messages
  if (streamLines > 0) {
    messages.push({
      id: 'streaming-assistant',
      subjectEntryId: null,
      role: 'assistant',
      text: Array.from({ length: streamLines }, (_, index) => `stream line ${index + 1}`).join('\n\n'),
      createdAt: 1785258000000,
      status: 'streaming',
    })
  }
  useChatTranscriptAutoScroll(
    bodyRef,
    messages.length,
    HARNESS_ENTRIES.length,
    undefined,
    'compaction-harness',
    transcriptStreamRevision(messages),
  )

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <div style={{ display: 'flex', flex: '0 0 auto', gap: 8 }}>
        <button
          type="button"
          id="refresh-timeline"
          onClick={() => setGeneration((current) => current + 1)}
        >
          refresh
        </button>
        <button type="button" id="start-stream" onClick={() => setStreamLines(2)}>
          start stream
        </button>
        <button type="button" id="grow-stream" onClick={() => setStreamLines((current) => current + 40)}>
          grow stream
        </button>
      </div>
      <div ref={bodyRef} className="thread-dialogue">
        <div className="thread-blocks">
          <MessageList messages={messages} />
        </div>
      </div>
    </div>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<CompactionCardHarnessApp />)
}
