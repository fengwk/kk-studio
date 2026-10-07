import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import { ChildThreadRootLink, ChildThreadView } from '@/features/ai/runtime/ChildThreadView'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import '@/features/ai/chat/chat-workspace.css'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function ThreadWorkspacePage() {
  const { threadId = '' } = useParams<{ threadId: string }>()
  const navigate = useNavigate()
  const { t } = useI18n()

  const isValidUuid = Boolean(threadId) && UUID_PATTERN.test(threadId)

  const threadQuery = useQuery({
    queryKey: queryKeys.threads.snapshot(threadId),
    queryFn: () => harnessService.getThreadSnapshot(threadId),
    enabled: isValidUuid,
  })

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })

  if (!isValidUuid) {
    return (
      <div className="thread-state danger">
        {t('ai.thread.invalidId')}
        <button type="button" onClick={() => navigate('/chats')}>
          {t('ai.chat.backToList')}
        </button>
      </div>
    )
  }

  if (threadQuery.isLoading) {
    return <div className="thread-state">{t('ai.chat.loading')}</div>
  }

  if (threadQuery.isError || !threadQuery.data) {
    return (
      <div className="thread-state danger">
        {t('ai.thread.loadFailed')}
        <button type="button" onClick={() => navigate('/chats')}>
          {t('ai.chat.backToList')}
        </button>
      </div>
    )
  }

  const thread = threadQuery.data.thread
  const title = thread.name || thread.threadId
  // 子代理是只读视图：独立地址只挂载 Thread 投影，不挂草稿、上传与人工执行 Hook，
  // 并保留返回执行根的入口；根地址仍然进入完整的 pane（含根控制区）。
  const isChildThread = thread.parentThreadId != null
  const rootThreadId = thread.yoloPolicy.rootThreadId ?? null

  return (
    <section className="chat-workspace screen active">
      <header className="chat-workspace-header">
        <div className="chat-workspace-title">
          <Link
            className="chat-workspace-back"
            to="/chats"
            title={t('ai.chat.backToConversation')}
            aria-label={t('ai.chat.backToConversation')}
          >
            <ArrowLeft aria-hidden="true" />
            <span>{t('ai.chat.backToConversation')}</span>
          </Link>
          <span className="thread-breadcrumb-separator" aria-hidden="true">/</span>
          <h1>{title}</h1>
        </div>
      </header>
      <div className="chat-pane-grid layout-single">
        {isChildThread ? (
          <section className="chat-pane focused" data-pane-id={`thread-${threadId}`}>
            <ChildThreadView
              threadId={threadId}
              environments={environmentsQuery.data ?? []}
              navigation={rootThreadId == null
                ? null
                : <ChildThreadRootLink rootThreadId={rootThreadId} />}
            />
          </section>
        ) : (
          <AgentPane
            paneId={`thread-${threadId}`}
            agents={agentsQuery.data?.results ?? []}
            environments={environmentsQuery.data ?? []}
            initialTarget={{ kind: 'BOUND_THREAD', threadId }}
            capabilities={{ allowNewSession: false }}
            focused
          />
        )}
      </div>
    </section>
  )
}
