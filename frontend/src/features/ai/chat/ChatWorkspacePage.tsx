import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import {
  applyChatLayout,
  focusPane,
  loadChatPaneState,
  saveChatPaneState,
  updatePaneThread,
  visibleChatPanes,
  type ChatLayout,
  type ChatPaneState,
  type PaneSortPreference,
} from '@/features/ai/chat/chat-pane-state'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
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
  const queryClient = useQueryClient()
  const [paneState, setPaneState] = useState<ChatPaneState>(() => loadChatPaneState(chatId))

  useEffect(() => {
    setPaneState(loadChatPaneState(chatId))
  }, [chatId])

  useEffect(() => {
    if (!chatId) {
      return
    }
    saveChatPaneState(chatId, paneState)
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
  const agents = agentsQuery.data?.results ?? []
  const environments = environmentsQuery.data ?? []

  const updateChatMutation = useMutation({
    mutationFn: ({
      agentName,
      environmentName,
      yoloEnabled,
      expectedVersion,
    }: {
      agentName?: string
      environmentName?: string | null
      yoloEnabled?: boolean
      expectedVersion: string
    }) => chatService.updateChat(chatId, {
      ...(agentName === undefined ? {} : { agentName }),
      ...(environmentName === undefined ? {} : { environmentName }),
      ...(yoloEnabled === undefined ? {} : { yoloEnabled }),
      expectedVersion,
    }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.chats.detail(chatId) })
      await queryClient.invalidateQueries({ queryKey: queryKeys.chats.list })
    },
  })

  function setLayout(layout: ChatLayout) {
    setPaneState((current) => applyChatLayout(current, layout))
  }

  function setFocused(paneId: string) {
    setPaneState((current) => focusPane(current, paneId))
  }

  function setThread(paneId: string, threadId: string | null) {
    setPaneState((current) => updatePaneThread(current, paneId, threadId))
  }

  function setSessionSort(sort: PaneSortPreference) {
    setPaneState((current) => ({ ...current, sessionSort: sort }))
  }

  function setThreadSort(sort: PaneSortPreference) {
    setPaneState((current) => ({ ...current, threadSort: sort }))
  }

  if (chatQuery.isLoading) {
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
  const title = chat.title || chat.id
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
          <h1>{title}</h1>
        </div>
        <div className="chat-workspace-actions">
          <div className="chat-layout-switch" role="group" aria-label={t('ai.chat.layout')}>
            {LAYOUTS.map((layout) => (
              <button
                key={layout.id}
                type="button"
                className={paneState.layout === layout.id ? 'active' : undefined}
                onClick={() => setLayout(layout.id)}
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
            key={pane.id}
            chat={chat}
            agents={agents}
            environments={environments}
            pane={pane}
            settingsPending={updateChatMutation.isPending}
            focused={paneState.focusedPaneId === pane.id}
            sessionSort={paneState.sessionSort}
            threadSort={paneState.threadSort}
            onFocus={() => setFocused(pane.id)}
            onThreadChange={(threadId) => setThread(pane.id, threadId)}
            onSessionSortChange={setSessionSort}
            onThreadSortChange={setThreadSort}
            onAgentChange={async (agentName) => {
              await updateChatMutation.mutateAsync({
                agentName,
                expectedVersion: chat.version,
              })
            }}
            onEnvironmentChange={async (environmentName) => {
              await updateChatMutation.mutateAsync({
                environmentName,
                expectedVersion: chat.version,
              })
            }}
            onYoloChange={async (yoloEnabled) => {
              await updateChatMutation.mutateAsync({
                yoloEnabled,
                expectedVersion: chat.version,
              })
            }}
          />
        ))}
      </div>
    </section>
  )
}
