import { useCallback, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import {
  applyChatLayout,
  focusPane,
  loadChatPaneState,
  parseLayout,
  saveChatPaneState,
  visibleChatPanes,
  type ChatLayout,
  type ChatPaneState,
} from '@/features/ai/chat/chat-pane-state'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { Select } from '@/shared/ui/console/Select'

const LAYOUTS: Array<{ value: ChatLayout; label: string }> = [
  { value: 'single', label: '1' },
  { value: 'split-2', label: '2' },
  { value: 'split-3', label: '3' },
  { value: 'grid-4', label: '4' },
  { value: 'grid-5', label: '5' },
  { value: 'grid-6', label: '6' },
  { value: 'grid-7', label: '7' },
  { value: 'grid-8', label: '8' },
  { value: 'grid-9', label: '9' },
]

export interface ChatLayoutSelectorProps {
  layout: ChatLayout
  onChange: (layout: ChatLayout) => void
  selectId?: string
}

export function ChatLayoutSelector({
  layout,
  onChange,
  selectId = 'chat-layout-select',
}: ChatLayoutSelectorProps) {
  const { t } = useI18n()
  return (
    <Select
      id={selectId}
      className="chat-layout-selector"
      compact
      value={layout}
      options={LAYOUTS}
      aria-label={t('ai.chat.layout')}
      onChange={(next) => onChange(parseLayout(next))}
    />
  )
}

interface ChatWorkspaceContentProps {
  chat: ChatDTO
  agents: AgentDefinitionDTO[]
  environments: EnvironmentCardDTO[]
}

function ChatWorkspaceContent({
  chat,
  agents,
  environments,
}: ChatWorkspaceContentProps) {
  const [searchParams, setSearchParams] = useSearchParams()
  const targetThreadId = searchParams.get('thread')
  const { t } = useI18n()
  const [paneState, setPaneState] = useState<ChatPaneState>(() => loadChatPaneState(chat.id))

  useEffect(() => {
    saveChatPaneState(chat.id, paneState)
  }, [chat.id, paneState])

  // 通过 callback 确认消费：目标 pane 成功消费（挂载初始化或无 pending 切换成功）后，通过 URL replace 消费掉参数
  const handleTargetConsumed = useCallback((consumed: PaneTarget) => {
    const currentThread = searchParams.get('thread')
    if (currentThread && consumed.kind === 'BOUND_THREAD' && consumed.threadId === currentThread) {
      const nextParams = new URLSearchParams(searchParams)
      nextParams.delete('thread')
      setSearchParams(nextParams, { replace: true })
    }
  }, [searchParams, setSearchParams])

  const visiblePanes = visibleChatPanes(paneState)
  return (
    <section className="chat-workspace screen active">
      <header className="chat-workspace-header">
        <div className="chat-workspace-title">
          <Link
            className="sidebar-icon-btn"
            to="/chats"
            title={t('ai.chat.backToChatList')}
            aria-label={t('ai.chat.backToChatList')}
          >
            <ArrowLeft aria-hidden="true" />
          </Link>
          <h1>{chat.title || chat.id}</h1>
        </div>
        <div className="chat-workspace-actions">
          <ChatLayoutSelector
            layout={paneState.layout}
            onChange={(nextLayout) => {
              setPaneState((current) => applyChatLayout(current, nextLayout))
            }}
          />
        </div>
      </header>
      <div className={`chat-pane-grid layout-${paneState.layout}`}>
        {visiblePanes.map((pane, index) => {
          const isFocused = paneState.focusedPaneId === pane.id || (index === 0 && !paneState.focusedPaneId)
          const deepLinkTarget: PaneTarget | undefined =
            targetThreadId && isFocused
              ? { kind: 'BOUND_THREAD', threadId: targetThreadId }
              : undefined
          return (
            <ChatWorkspacePane
              key={`${chat.id}:${pane.id}`}
              chat={chat}
              agents={agents}
              environments={environments}
              pane={pane}
              focused={isFocused}
              onFocus={() => setPaneState((current) => focusPane(current, pane.id))}
              initialTarget={deepLinkTarget}
              onTargetConsumed={isFocused ? handleTargetConsumed : undefined}
            />
          )
        })}
      </div>
    </section>
  )
}

export function ChatWorkspacePage() {
  const { chatId = '' } = useParams()
  const navigate = useNavigate()
  const { t } = useI18n()

  const chatQuery = useQuery({
    queryKey: queryKeys.chats.detail(chatId),
    queryFn: () => chatService.getChat(chatId),
    enabled: Boolean(chatId),
  })
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })
  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })

  if (chatQuery.isLoading || !chatId) {
    return <div className="thread-state">{t('ai.chat.loading')}</div>
  }
  if (chatQuery.error || !chatQuery.data) {
    return (
      <div className="thread-state danger">
        {t('ai.chat.loadFailed')}
        <button type="button" onClick={() => navigate('/chats')}>
          {t('ai.chat.backToList')}
        </button>
      </div>
    )
  }

  const chat = chatQuery.data
  return (
    <ChatWorkspaceContent
      key={chat.id}
      chat={chat}
      agents={agentsQuery.data?.results ?? []}
      environments={environmentsQuery.data ?? []}
    />
  )
}
