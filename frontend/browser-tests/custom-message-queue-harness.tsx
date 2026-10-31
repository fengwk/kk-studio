import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { ThreadView } from '@/features/chat/runtime/ChildThreadView'
import { buildThreadTimeline } from '@/features/chat/runtime/thread-timeline'
import { extractQueuedCustomMessages } from '@/features/chat/runtime/queued-custom-messages'
import { ApplicationEventProvider } from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'
import type { ThreadProjection } from '@/features/chat/runtime/useThreadProjection'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
} from '@/shared/api/generated/contracts'

setLocale('zh-CN')

export const HARNESS_CHILD_THREAD_ID = '22222222-2222-4333-8444-555555555555'
export const HARNESS_ROOT_THREAD_ID = '11111111-2222-4333-8444-555555555555'

export const INITIAL_TASK_TEXT = '子代理初始任务：分析代码结构'
export const SUPPLEMENTARY_TEXT = '请补充自动化测试覆盖率'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: false },
  },
})

function makeInitialEntries(): HarnessSessionEntryDTO[] {
  return [
    {
      entryId: 'entry-task-1',
      sessionId: 'session-child-1',
      parentEntryId: null,
      entryType: 'CUSTOM_MESSAGE',
      payloadJson: JSON.stringify({
        message: {
          role: 'USER',
          contents: [{ type: 'text', text: INITIAL_TASK_TEXT }],
        },
      }),
      createTime: 1774958400,
      usageCost: null,
    },
  ]
}

function makeInitialQueuedCommands(): HarnessThreadCommandDTO[] {
  return [
    {
      threadId: HARNESS_CHILD_THREAD_ID,
      sequence: '2',
      type: 'CUSTOM_MESSAGE',
      state: 'QUEUED',
      idempotencyKey: 'cmd-supp-1',
      payloadJson: JSON.stringify({
        message: {
          role: 'USER',
          contents: [{ type: 'text', text: SUPPLEMENTARY_TEXT }],
        },
      }),
      cancelledAt: null,
      createTime: 1774958420,
    },
  ]
}

export function CustomMessageQueueHarness() {
  const [entries, setEntries] = useState<HarnessSessionEntryDTO[]>(makeInitialEntries)
  const [queuedCommands, setQueuedCommands] = useState<HarnessThreadCommandDTO[]>(
    makeInitialQueuedCommands,
  )
  const [currentLocale, setCurrentLocale] = useState<'zh-CN' | 'en-US'>('zh-CN')

  const handleApply = () => {
    setQueuedCommands([])
    setEntries((prev) => [
      ...prev,
      {
        entryId: 'entry-supp-2',
        sessionId: 'session-child-1',
        parentEntryId: 'entry-task-1',
        entryType: 'CUSTOM_MESSAGE',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [{ type: 'text', text: SUPPLEMENTARY_TEXT }],
          },
        }),
        createTime: 1774958460,
        usageCost: null,
      },
    ])
  }

  const handleToggleLocale = () => {
    const next = currentLocale === 'zh-CN' ? 'en-US' : 'zh-CN'
    setLocale(next)
    setCurrentLocale(next)
  }

  const timeline = buildThreadTimeline(
    entries,
    queuedCommands,
    [],
    null,
    new Map(),
    [],
    null,
  )
  const queuedCustomMessages = extractQueuedCustomMessages(queuedCommands)

  const projection: ThreadProjection = {
    threadId: HARNESS_CHILD_THREAD_ID,
    sessionId: 'session-child-1',
    agents: [],
    models: [],
    thread: {
      threadId: HARNESS_CHILD_THREAD_ID,
      parentThreadId: HARNESS_ROOT_THREAD_ID,
      name: 'worker-subagent',
      sessionId: 'session-child-1',
      headEntryId: entries.at(-1)?.entryId ?? 'head',
      rootThreadId: HARNESS_ROOT_THREAD_ID,
      yoloEnabled: false,
      nextCommandSequence: '3',
      version: '1',
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'worker',
        model: { modelName: 'MiniMax', variant: 'default' },
        environmentName: null,
        goal: null,
      },
      createTime: 1774958400,
      updateTime: 1774958400,
    },
    bound: true,
    isRoot: false,
    title: 'worker-subagent',
    timeline,
    events: [],
    runtimeLabels: { environmentName: null, contextWindow: undefined },
    branchUsage: null,
    working: true,
    entries,
    messagesLoading: false,
    messagesError: null,
    bodyRef: { current: null },
    queuedCommands,
    queuedCustomMessages,
    stopReceipts: [],
    unconfirmedExecutions: [],
    activeAssistantOutput: null,
    toolInvocations: [],
    generationFailures: [],
    snapshotQuery: {
      isSuccess: true,
      isLoading: false,
      error: null,
      refetch: async () => {},
    } as never,
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <header
        style={{
          display: 'flex',
          gap: 12,
          padding: '8px 16px',
          background: 'var(--surface-raised, #181d1a)',
          borderBottom: '1px solid var(--border, #2d3830)',
          alignItems: 'center',
          flexShrink: 0,
        }}
      >
        <button
          type="button"
          data-testid="apply-button"
          onClick={handleApply}
          style={{ padding: '4px 10px', cursor: 'pointer' }}
        >
          应用补充要求
        </button>
        <button
          type="button"
          data-testid="toggle-locale-button"
          onClick={handleToggleLocale}
          style={{ padding: '4px 10px', cursor: 'pointer' }}
        >
          切换语言 ({currentLocale})
        </button>
      </header>
      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        <ThreadView
          threadId={HARNESS_CHILD_THREAD_ID}
          projection={projection}
          environments={[]}
          readOnly
        />
      </div>
    </div>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <MemoryRouter>
          <CustomMessageQueueHarness />
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
}
