import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import {
  applyChatLayout,
  focusPane,
  loadChatPaneState,
  saveChatPaneState,
  updatePaneTarget,
  visibleChatPanes,
  type ChatLayout,
  type ChatPaneState,
} from '@/features/ai/chat/chat-pane-state'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

const LAYOUTS: Array<{ id: ChatLayout; label: string }> = [
  { id: 'single', label: '1' },
  { id: 'split-2', label: '2' },
  { id: 'split-3', label: '3' },
  { id: 'grid-4', label: '4' },
  { id: 'grid-6', label: '6' },
  { id: 'grid-8', label: '8' },
]

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
          <div className="chat-layout-switch" role="group" aria-label={t('ai.chat.layout')}>
            {LAYOUTS.map((layout) => (
              <button
                key={layout.id}
                type="button"
                className={paneState.layout === layout.id ? 'active' : undefined}
                onClick={() => setPaneState((current) => applyChatLayout(current, layout.id))}
              >
                {layout.label}
              </button>
            ))}
          </div>
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
            onTargetChange={(target: PaneTarget) => {
              setPaneState((current) => updatePaneTarget(current, pane.id, target))
            }}
          />
        ))}
      </div>
    </section>
  )
}
