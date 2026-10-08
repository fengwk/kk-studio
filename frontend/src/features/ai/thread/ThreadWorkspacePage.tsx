import { useQuery } from '@tanstack/react-query'
import { useLayoutEffect, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import type { ThreadPresentation } from '@/features/ai/runtime/thread-presentation'
import { ThreadPresentationActions } from '@/features/ai/runtime/ThreadPresentationActions'
import { Button } from '@/shared/ui/controls/Button'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'
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
        <Button variant="ghost" onClick={() => navigate('/chats')}>
          {t('ai.chat.backToList')}
        </Button>
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
        <Button variant="ghost" onClick={() => navigate('/chats')}>
          {t('ai.chat.backToList')}
        </Button>
      </div>
    )
  }

  return <ThreadWorkspaceContent key={threadId} thread={threadQuery.data.thread}
    agents={agentsQuery.data?.results ?? []} environments={environmentsQuery.data ?? []} />
}

/** 路由身份同时隔离 presentation：新地址即使缓存命中也不复用上一地址的报告/动作。 */
function ThreadWorkspaceContent({ thread, agents, environments }: {
  thread: HarnessThreadDTO
  agents: AgentDefinitionDTO[]
  environments: EnvironmentCardDTO[]
}) {
  const { t } = useI18n()
  const threadId = thread.threadId
  const [presentation, setPresentation] = useState<ThreadPresentation | null>(null)
  const title = thread.parentThreadId && thread.name === 'main' ? thread.branchSettings.agentName : thread.name
  const debugActive = presentation?.mode === 'debug'
  useLayoutEffect(() => {
    if (presentation?.mode === 'conversation') {
      presentation.act(presentation.viewKey, 'restore-focus')
    }
  }, [presentation])

  return (
    <section className="chat-workspace screen active" onKeyDown={(event) => {
      if (debugActive && event.key === 'Escape' && !event.defaultPrevented
        && !event.repeat && !event.nativeEvent.isComposing && event.keyCode !== 229
        && !shouldDeferToBlockingModal(event.currentTarget)) {
        event.preventDefault()
        presentation.act(presentation.viewKey, 'close-debug')
      }
    }}>
      <header className="chat-workspace-header">
        <div className="chat-workspace-title">
          {debugActive ? <Button variant="ghost" size="compact"
            onClick={() => presentation.act(presentation.viewKey, 'close-debug')}>
            {t('ai.runtime.debug.close')}
          </Button> : <Link
            className="chat-workspace-back"
            to="/chats"
            title={t('ai.chat.backToConversation')}
            aria-label={t('ai.chat.backToConversation')}
          >
            <ArrowLeft aria-hidden="true" />
            <span>{t('ai.chat.backToConversation')}</span>
          </Link>}
          <span className="thread-breadcrumb-separator" aria-hidden="true">/</span>
          <h1>{presentation?.name ?? title}</h1>
          <ThreadPresentationActions view={presentation} />
        </div>
      </header>
      <div className="chat-pane-grid layout-single">
          <AgentPane
            key={threadId}
            paneId={`thread-${threadId}`}
            agents={agents}
            environments={environments}
            initialTarget={{ kind: 'BOUND_THREAD', threadId }}
            capabilities={{ allowNewSession: false }}
            focused
            onPresentation={setPresentation}
          />
      </div>
    </section>
  )
}
