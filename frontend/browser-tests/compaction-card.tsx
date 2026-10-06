import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { MessageList } from '@/features/ai/runtime/thread-panel/messages/MessageList'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline'
import { setLocale } from '@/shared/i18n'
import type { EntryType, HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import {
  FULL_COMPACTION_SUMMARY,
  LONG_COMPACTION_SUMMARY,
  UNSAFE_COMPACTION_SUMMARY,
} from '@/test-support/resources/compaction-summary'

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
    payloadJson: JSON.stringify(payload),
    createTime: '2026-01-01T00:00:00Z',
  }
}

function userEntry(text: string): HarnessSessionEntryDTO {
  return entry('MESSAGE', { message: { role: 'USER', contents: [{ type: 'text', text }] } })
}

/** 一个完整成功（FULL + COMPLETED）的压缩回合，正文为给定摘要。 */
function compactionTurn(summaryText: string): HarnessSessionEntryDTO[] {
  const startEntryId = `turn-start-${entrySequence += 1}`
  return [
    entry('TURN_START', {
      reason: 'COMPACTION',
      settings: {
        agentName: 'coder',
        model: { providerName: 'openai', modelName: 'gpt', variant: 'high' },
        environmentName: null,
      },
      ownerThreadId: 'thread-1',
      contextWindow: 200000,
      maxOutputTokens: 8192,
      compaction: {
        phase: 'FULL',
        trigger: 'THRESHOLD',
        executionModel: { providerName: 'openai', modelName: 'gpt', variant: 'high' },
        cutEntryId: 'cut-1',
        turnPrefixStartEntryId: null,
        historyCompactionEntryId: null,
      },
    }, startEntryId),
    entry('COMPACTION', { summaryText }, `compaction-${entrySequence}`),
    entry('TURN_END', { turnStartEntryId: startEntryId, outcome: 'COMPLETED', continueModel: false }),
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
 * 只渲染真实 MessageList 与现行 styles：数据来自真实 buildThreadTimeline 投影，
 * 不依赖后端、模型或任何主机服务。
 */
export function CompactionCardHarnessApp() {
  const [, setGeneration] = useState(0)
  // 每次渲染都重建投影（新的 message 对象、相同的摘要 Entry 身份）。
  const messages = buildThreadTimeline(HARNESS_ENTRIES, [], []).messages

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <button
        type="button"
        id="refresh-timeline"
        style={{ flex: '0 0 auto' }}
        onClick={() => setGeneration((current) => current + 1)}
      >
        refresh
      </button>
      <div className="thread-dialogue">
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
