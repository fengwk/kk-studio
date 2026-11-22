import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { HistoryTree } from '@/features/thread/HistoryTree'
import {
  renderPaneInteraction,
  type PaneInteractionContext,
} from '@/features/thread/runtime/panes/root-agent-pane-interactions'
import { setLocale, translate } from '@/shared/i18n'
import type { PaneTarget } from '@/features/thread/runtime/agent-pane'
import type {
  HarnessSessionEntryDTO,
  HarnessSessionSummaryDTO,
  HarnessThreadSummaryDTO,
} from '@/shared/api/generated/contracts'

setLocale(
  new URLSearchParams(window.location.search).get('locale') === 'en-US' ? 'en-US' : 'zh-CN',
)

/** 历史树 fixture：助手正文的命中词在第 40 个字符处，窄行单行省略会把它挤出可见区。 */
function entry(
  entryId: string,
  entryType: HarnessSessionEntryDTO['entryType'],
  parentEntryId: string | null,
  text: string | null,
  role: 'USER' | 'ASSISTANT' = 'USER',
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-second',
    parentEntryId,
    entryType,
    payload: text == null
      ? {}
      : { message: { role, contents: [{ type: 'text', text }] } },
    createdAt: 0,
    usageCost: null,
  }
}

const HISTORY_ENTRIES: HarnessSessionEntryDTO[] = [
  entry('entry-root', 'ROOT', null, null),
  entry('entry-user', 'MESSAGE', 'entry-root', '服务停机流程要怎么落地？'),
  entry(
    'entry-assistant',
    'MESSAGE',
    'entry-user',
    '## 结论 服务停机时先停止接收新请求，再等待在途请求完成，最后释放连接池并记录停机耗时',
    'ASSISTANT',
  ),
  entry('entry-turn-end', 'TURN_END', 'entry-assistant', null),
  entry('entry-user-2', 'MESSAGE', 'entry-turn-end', '还有别的注意事项吗？'),
  // 长拉丁命中词位于 220 字预览上限之外：搜索预览必须从全文截取。
  entry(
    'entry-assistant-2',
    'MESSAGE',
    'entry-user-2',
    `${'背景说明'.repeat(60)} 最后把 terminationGracePeriodSeconds 调大到 60 秒`,
    'ASSISTANT',
  ),
  entry('entry-turn-end-2', 'TURN_END', 'entry-assistant-2', null),
]

const SESSIONS: HarnessSessionSummaryDTO[] = [
  { sessionId: 'session-main', name: 'main', createdAt: 0, lastActivityAt: 0, firstMessagePreview: null, threadCount: 1 },
  {
    sessionId: 'session-second',
    name: '第二个会话',
    createdAt: 0,
    lastActivityAt: 0,
    firstMessagePreview: null,
    threadCount: 1,
  },
]

function thread(threadId: string, name: string): HarnessThreadSummaryDTO {
  return {
    threadId,
    name,
    kind: 'USER',
    parentThreadId: null,
    createdAt: 0,
    updatedAt: 0,
    status: 'IDLE',
    executionControl: 'RUNNABLE',
    execution: { driver: 'builtin', settings: { modelName: 'offline', variant: 'default' } },
    headMessagePreview: null,
  }
}

const THREADS = [thread('thread-main', 'main'), thread('thread-second', '第二个 Thread')]

/**
 * 只提供选择面板渲染读取到的字段；条目投影仍由生产构建器完成。
 */
function paneOf(
  target: PaneTarget,
  interaction: 'thread-sessions' | 'thread-threads',
  currentSessionId: string | null,
): PaneInteractionContext['pane'] {
  return {
    target,
    interaction,
    currentSessionId,
    sessions: SESSIONS,
    sessionsLoading: false,
    sessionsHasMore: false,
    sessionsLoadingMore: false,
    loadMoreSessions: () => undefined,
    threads: THREADS,
    threadsLoading: false,
    selectionQueries: {
      sessionsError: null,
      retrySessions: () => undefined,
      threadsError: null,
      retryThreads: () => undefined,
    },
    selectSession: () => undefined,
    selectThread: () => undefined,
    openRenameWithBackTo: () => undefined,
    openInteraction: () => undefined,
    closeInteraction: () => undefined,
  } as unknown as PaneInteractionContext['pane']
}

function selectionPanel(
  target: PaneTarget,
  interaction: 'thread-sessions' | 'thread-threads',
  currentSessionId: string | null,
) {
  return renderPaneInteraction({
    pane: paneOf(target, interaction, currentSessionId),
    tree: {} as PaneInteractionContext['tree'],
    boundThreadId: target.kind === 'BOUND_THREAD' ? target.threadId : null,
    agents: [],
    t: translate,
  })
}

export function Harness() {
  const sections = [
    { id: 'sessions', interaction: 'thread-sessions' as const },
    { id: 'threads', interaction: 'thread-threads' as const },
  ]
  return (
    <MemoryRouter>
      <main style={{ display: 'grid', gap: 16, padding: 12 }}>
        <section data-testid="history">
          <HistoryTree
            entries={HISTORY_ENTRIES}
            headEntryId="entry-turn-end"
            loading={false}
            queryError={null}
            onClose={() => undefined}
            onFork={() => undefined}
            onForkSession={() => undefined}
          />
        </section>
        {/* 绑定 Thread 的 pane：两个列表都要标出当前项。 */}
        {sections.map(({ id, interaction }) => (
          <section key={id} data-testid={`bound-${id}`}>
            {selectionPanel(
              { kind: 'BOUND_THREAD', threadId: 'thread-second' },
              interaction,
              'session-second',
            )}
          </section>
        ))}
        {/* 未绑定 Thread 的草稿 pane：两个列表都不能有当前项。 */}
        {sections.map(({ id, interaction }) => (
          <section key={id} data-testid={`unbound-${id}`}>
            {selectionPanel({ kind: 'NEW_SESSION_DRAFT' }, interaction, null)}
          </section>
        ))}
      </main>
    </MemoryRouter>
  )
}

createRoot(document.getElementById('root')!).render(<Harness />)
