import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { MessageList } from '@/features/ai/runtime/thread-panel/messages/MessageList'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline'
import { setLocale } from '@/shared/i18n'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'

setLocale('zh-CN')

/** 固定回执信封：当前 fixture 正文不包含需要 XML 转义的字符。 */
function envelope(options: {
  agent: string
  state: 'completed' | 'error' | 'cancelled'
  threadId: string
  task: string
  result?: string
  error?: string
  partial?: string
}): string {
  const lines = [
    `<subagent_result thread_id="${options.threadId}" agent="${options.agent}" state="${options.state}">`,
    '<task>',
    options.task,
    '</task>',
  ]
  if (options.result != null) {
    lines.push('<result>', options.result, '</result>')
  }
  if (options.error != null) {
    lines.push('<error>', options.error, '</error>')
  }
  if (options.partial != null) {
    lines.push('<partial_result>', options.partial, '</partial_result>')
  }
  lines.push('</subagent_result>')
  return lines.join('\n')
}

function notificationEntry(
  text: string,
  entryId: string,
  kind = 'SUBAGENT_RESULT',
  sourceThreadId = 'child-thread-1',
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType: 'NOTIFICATION',
    payloadJson: JSON.stringify({
      notificationId: `notification-${entryId}`,
      kind,
      sourceThreadId,
      message: { role: 'USER', contents: [{ type: 'text', text }] },
    }),
    createTime: '2026-07-28T10:00:00Z',
  }
}

/** 回执来源必须与通知声明的 sourceThreadId 一致，否则信封会被严格解析判为非法。 */
function receiptEntry(
  threadId: string,
  entryId: string,
  options: {
    agent: string
    state: 'completed' | 'error' | 'cancelled'
    task: string
    result?: string
    error?: string
    partial?: string
  },
): HarnessSessionEntryDTO {
  return notificationEntry(
    envelope({ ...options, threadId }),
    entryId,
    'SUBAGENT_RESULT',
    threadId,
  )
}

const LONG_TASK = `${'Refactor the receipt rendering path and verify every delegated run. '.repeat(4)}FINAL_TASK_TAIL_MARKER`

const ENTRIES: HarnessSessionEntryDTO[] = [
  receiptEntry('child-thread-1', 'entry-completed', {
    agent: 'coder',
    state: 'completed',
    task: LONG_TASK,
    result: '## Report\n\n- delivered the change\n- updated the tests',
  }),
  receiptEntry('child-thread-2', 'entry-error', {
    agent: 'explorer',
    state: 'error',
    task: 'Explore the runtime and report back.',
    error: 'provider **failed** while streaming',
    partial: 'partial findings: three call sites',
  }),
  receiptEntry('child-thread-3', 'entry-cancelled', {
    agent: 'coder',
    state: 'cancelled',
    task: 'Cancelled run task preview text.',
    error: 'Cancelled by user',
  }),
  receiptEntry('child-thread-4', 'entry-long-agent', {
    agent: 'explorer-with-an-extremely-long-subagent-identity-name-that-must-wrap-inside-the-card',
    state: 'completed',
    task: LONG_TASK,
    result: 'done',
  }),
  // 同一 subagent 的第二次报告：来源与 entry-completed 相同，仍是独立的一条回执。
  receiptEntry('child-thread-1', 'entry-same-source', {
    agent: 'coder',
    state: 'completed',
    task: 'second run of the same subagent',
    result: 'second report',
  }),
  notificationEntry('budget **exhausted**', 'entry-budget', 'TASK_BUDGET'),
  notificationEntry('opaque system fact', 'entry-unknown', 'SOMETHING_ELSE'),
]

const MESSAGES = buildThreadTimeline(ENTRIES, [], []).messages

/**
 * 真实 MessageList 与现行 styles：只渲染 NOTIFICATION 时间线，
 * 数据来自 buildThreadTimeline 投影，不依赖后端、模型或任何主机服务。
 */
export function SubagentReceiptHarnessApp() {
  return (
    <MemoryRouter>
      <div className="thread-dialogue">
        <div className="thread-blocks">
          <MessageList messages={MESSAGES} />
        </div>
      </div>
    </MemoryRouter>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<SubagentReceiptHarnessApp />)
}
