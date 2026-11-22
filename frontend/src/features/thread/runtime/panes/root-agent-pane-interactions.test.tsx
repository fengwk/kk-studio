import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  renderPaneInteraction,
  type PaneInteractionContext,
} from '@/features/thread/runtime/panes/root-agent-pane-interactions'
import type { PaneTarget } from '@/features/thread/runtime/agent-pane'
import { translate } from '@/shared/i18n'
import type {
  HarnessSessionSummaryDTO,
  HarnessThreadSummaryDTO,
} from '@/shared/api/generated/contracts'

const SESSIONS: HarnessSessionSummaryDTO[] = [
  {
    sessionId: 'session-main',
    name: 'main',
    createdAt: 0,
    lastActivityAt: 0,
    firstMessagePreview: null,
    threadCount: 1,
  },
  {
    sessionId: 'session-second',
    name: '第二个会话',
    createdAt: 0,
    lastActivityAt: 0,
    firstMessagePreview: null,
    threadCount: 1,
  },
]

const THREADS: HarnessThreadSummaryDTO[] = [
  {
    threadId: 'thread-main',
    name: 'main',
    kind: 'USER',
    parentThreadId: null,
    createdAt: 0,
    updatedAt: 0,
    status: 'IDLE',
    executionControl: 'RUNNABLE',
    execution: { driver: 'builtin', settings: { modelName: 'offline', variant: 'default' } },
    headMessagePreview: null,
  },
  {
    threadId: 'thread-second',
    name: '第二个 Thread',
    kind: 'USER',
    parentThreadId: null,
    createdAt: 0,
    updatedAt: 0,
    status: 'IDLE',
    executionControl: 'RUNNABLE',
    execution: { driver: 'builtin', settings: { modelName: 'offline', variant: 'default' } },
    headMessagePreview: null,
  },
]

/**
 * 只提供选择面板渲染读取到的字段：两个列表与绑定目标事实。
 * 面板真实渲染用的是生产选择项构建器，因此这里不复制条目投影。
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
    loadMoreSessions: vi.fn(),
    threads: THREADS,
    threadsLoading: false,
    selectionQueries: {
      sessionsError: null,
      retrySessions: vi.fn(),
      threadsError: null,
      retryThreads: vi.fn(),
    },
    selectSession: vi.fn(),
    selectThread: vi.fn(),
    openRenameWithBackTo: vi.fn(),
    openInteraction: vi.fn(),
    closeInteraction: vi.fn(),
  } as unknown as PaneInteractionContext['pane']
}

function renderPanel(
  target: PaneTarget,
  interaction: 'thread-sessions' | 'thread-threads',
  currentSessionId: string | null,
) {
  const boundThreadId = target.kind === 'BOUND_THREAD' ? target.threadId : null
  render(
    <>
      {renderPaneInteraction({
        pane: paneOf(target, interaction, currentSessionId),
        tree: {} as PaneInteractionContext['tree'],
        boundThreadId,
        agents: [],
        t: translate,
      })}
    </>,
  )
}

describe('pane Session/Thread selection', () => {
  // 测试意图：绑定 Thread 的 pane 打开 Session 列表时，当前项是绑定 Thread 所属的 Session，
  // 而不是列表里的第一项；已选项同时给出 aria-current 与“已选”文案。
  it('marks the bound Thread session as the current Session row', () => {
    renderPanel({ kind: 'BOUND_THREAD', threadId: 'thread-second' }, 'thread-sessions', 'session-second')

    const panel = screen.getByRole('region', { name: translate('ai.chat.selectSession') })
    const current = within(panel).getByRole('option', { name: /第二个会话/ })
    expect(current).toHaveAttribute('aria-current', 'true')
    expect(current).toHaveTextContent(translate('ai.chat.selected'))
    expect(within(panel).getByRole('option', { name: /main/ })).not.toHaveAttribute('aria-current')
  })

  // 测试意图：绑定 Thread 的 pane 打开 Thread 列表时，当前项是绑定的那个 Thread，首个 Thread 不能被误标。
  it('marks the bound Thread as the current Thread row', () => {
    renderPanel({ kind: 'BOUND_THREAD', threadId: 'thread-second' }, 'thread-threads', 'session-second')

    const panel = screen.getByRole('region', { name: translate('ai.chat.selectThread') })
    const current = within(panel).getByRole('option', { name: /第二个 Thread/ })
    expect(current).toHaveAttribute('aria-current', 'true')
    expect(current).toHaveTextContent(translate('ai.chat.selected'))
    expect(within(panel).getByRole('option', { name: /^main/ })).not.toHaveAttribute('aria-current')
  })

  // 测试意图：未绑定 Thread 的草稿 pane 没有已选 Session/Thread，两个列表都不标记当前项。
  it('marks no row while the pane target is not bound', () => {
    const cases: Array<['thread-sessions' | 'thread-threads', string]> = [
      ['thread-sessions', translate('ai.chat.selectSession')],
      ['thread-threads', translate('ai.chat.selectThread')],
    ]
    for (const [interaction, title] of cases) {
      const view = render(
        <>
          {renderPaneInteraction({
            pane: paneOf({ kind: 'NEW_SESSION_DRAFT' }, interaction, null),
            tree: {} as PaneInteractionContext['tree'],
            boundThreadId: null,
            agents: [],
            t: translate,
          })}
        </>,
      )
      const panel = screen.getByRole('region', { name: title })
      for (const option of within(panel).getAllByRole('option')) {
        expect(option).not.toHaveAttribute('aria-current')
        expect(option).not.toHaveTextContent(translate('ai.chat.selected'))
      }
      view.unmount()
    }
  })
})
