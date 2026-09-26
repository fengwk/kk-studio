import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
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

export function ChatWorkspacePage() {
  const { chatId = '' } = useParams()
  const navigate = useNavigate()
  const { t } = useI18n()
  const [paneState, setPaneState] = useState<ChatPaneState>(() => loadChatPaneState(chatId))

  useEffect(() => {
    setPaneState(loadChatPaneState(chatId))
  }, [chatId])

  useEffect(() => {
    if (chatId) {
      saveChatPaneState(chatId, paneState)
    }
  }, [chatId, paneState])

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
        {visiblePanes.map((pane) => (
          <ChatWorkspacePane
            key={`${chat.id}:${pane.id}`}
            chat={chat}
            agents={agentsQuery.data?.results ?? []}
            environments={environmentsQuery.data ?? []}
            pane={pane}
            focused={paneState.focusedPaneId === pane.id}
            onFocus={() => setPaneState((current) => focusPane(current, pane.id))}
          />
        ))}
      </div>
    </section>
  )
}
