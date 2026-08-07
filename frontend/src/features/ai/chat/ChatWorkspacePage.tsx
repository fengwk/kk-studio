import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import {
  mergeChatIntoList,
  preferNewerChat,
} from '@/features/ai/chat/chat-utils'
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
import { isConflictError } from '@/shared/api/client'
import { chatService } from '@/shared/api/chat-service'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
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
  const [authoritativeChat, setAuthoritativeChat] = useState<{
    chatId: string
    chat: ChatDTO
  } | null>(null)
  const authoritativeChatRef = useRef<{ chatId: string; chat: ChatDTO } | null>(null)

  useEffect(() => {
    setPaneState(loadChatPaneState(chatId))
  }, [chatId])

  useEffect(() => {
    if (!chatId) {
      return
    }
    saveChatPaneState(chatId, paneState)
  }, [chatId, paneState])

  function rememberedChat(): ChatDTO | undefined {
    if (authoritativeChatRef.current?.chatId !== chatId) {
      return undefined
    }
    return authoritativeChatRef.current.chat
  }

  function rememberChat(incoming: ChatDTO): ChatDTO {
    const current = rememberedChat()
    const authoritative = preferNewerChat(current, incoming)
    authoritativeChatRef.current = { chatId, chat: authoritative }
    setAuthoritativeChat({ chatId, chat: authoritative })
    return authoritative
  }

  function currentChat(): ChatDTO | undefined {
    const cached = queryClient.getQueryData<ChatDTO>(queryKeys.chats.detail(chatId))
    const incoming = cached ?? chatQuery.data
    const current = incoming ? preferNewerChat(rememberedChat(), incoming) : rememberedChat()
    if (current) {
      rememberChat(current)
    }
    return current
  }

  const chatQuery = useQuery({
    queryKey: queryKeys.chats.detail(chatId),
    queryFn: async () => {
      const incoming = await chatService.getChat(chatId)
      const cached = queryClient.getQueryData<ChatDTO>(queryKeys.chats.detail(chatId))
      return preferNewerChat(rememberedChat(), preferNewerChat(cached, incoming))
    },
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

  useEffect(() => {
    const incoming = chatQuery.data
    if (!incoming || !chatId) {
      return
    }
    const current = authoritativeChatRef.current?.chatId === chatId
      ? authoritativeChatRef.current.chat
      : undefined
    const authoritative = preferNewerChat(current, incoming)
    authoritativeChatRef.current = { chatId, chat: authoritative }
    setAuthoritativeChat({ chatId, chat: authoritative })
    if (authoritative !== incoming) {
      queryClient.setQueryData(queryKeys.chats.detail(chatId), authoritative)
    }
  }, [chatId, chatQuery.data, queryClient])

  function applyAuthoritativeChat(incoming: ChatDTO) {
    const authoritative = rememberChat(incoming)
    queryClient.setQueryData(queryKeys.chats.detail(chatId), authoritative)
    queryClient.setQueryData<ChatDTO[] | undefined>(
      queryKeys.chats.list,
      (current) => mergeChatIntoList(current, authoritative),
    )
  }

  const updateChatMutation = useMutation({
    mutationFn: ({
      agentName,
      yoloEnabled,
      environmentName,
      expectedVersion,
    }: {
      agentName?: string
      yoloEnabled?: boolean
      environmentName?: string | null
      expectedVersion: string
    }) => chatService.updateChat(chatId, {
      ...(agentName === undefined ? {} : { agentName }),
      ...(yoloEnabled === undefined ? {} : { yoloEnabled }),
      // 显式 null 表示清空默认 Environment（environmentNameProvided 由 DTO setter 置位）。
      ...(environmentName === undefined ? {} : { environmentName }),
      expectedVersion,
    }),
    onSuccess: (updatedChat: ChatDTO) => {
      // PUT 响应是完整的，在任何 refetch 开始前就会成为权威数据。
      applyAuthoritativeChat(updatedChat)
      void queryClient.invalidateQueries({ queryKey: queryKeys.chats.detail(chatId) })
      void queryClient.invalidateQueries({ queryKey: queryKeys.chats.list })
    },
  })

  async function refreshAfterConflict() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.chats.detail(chatId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.chats.list }),
    ])
  }

  async function updateChatSettings(
    patch: {
      agentName?: string
      yoloEnabled?: boolean
      environmentName?: string | null
    },
  ) {
    const chat = currentChat()
    if (!chat) {
      return
    }
    try {
      await updateChatMutation.mutateAsync({
        ...patch,
        expectedVersion: chat.version,
      })
    } catch (error) {
      if (isConflictError(error)) {
        // 在显示冲突前先刷新，以便重试使用当前 Chat 版本。
        try {
          await refreshAfterConflict()
        } catch {
          // 刷新当前 Chat 也失败时，保留原始 409 错误。
        }
      }
      throw error
    }
  }

  function setLayout(layout: ChatLayout) {
    setPaneState((current) => applyChatLayout(current, layout))
  }

  function setFocused(paneId: string) {
    setPaneState((current) => focusPane(current, paneId))
  }

  function setThread(paneId: string, threadId: string | null) {
    setPaneState((current) => updatePaneThread(current, paneId, threadId))
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

  const chat = (
    authoritativeChat?.chatId === chatId
      ? authoritativeChat.chat
      : chatQuery.data
  )
  if (!chat) {
    return <div className="thread-state">{t('ai.chat.loading')}</div>
  }
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
            key={`${chat.id}:${pane.id}`}
            chat={chat}
            agents={agents}
            environments={environments}
            pane={pane}
            focused={paneState.focusedPaneId === pane.id}
            threadSort={paneState.threadSort}
            onFocus={() => setFocused(pane.id)}
            onThreadChange={(threadId) => setThread(pane.id, threadId)}
            onThreadSortChange={setThreadSort}
            onAgentChange={async (agentName) => {
              await updateChatSettings({ agentName })
            }}
            onYoloChange={async (yoloEnabled) => {
              await updateChatSettings({ yoloEnabled })
            }}
            onEnvironmentChange={async (environmentName) => {
              await updateChatSettings({ environmentName })
            }}
          />
        ))}
      </div>
    </section>
  )
}
