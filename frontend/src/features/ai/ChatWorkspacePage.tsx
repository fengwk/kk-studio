import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { ChatWorkspacePane } from '@/features/ai/ChatWorkspacePane'
import {
  applyChatLayout,
  focusPane,
  loadChatPaneState,
  saveChatPaneState,
  updatePaneThread,
  type ChatLayout,
  type ChatPaneState,
  type PaneSortPreference,
} from '@/features/ai/chat-pane-state'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'

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
  const agents = agentsQuery.data?.results ?? []

  const updateChatMutation = useMutation({
    mutationFn: (defaultAgentId: string) => chatService.updateChat(chatId, { defaultAgentId }),
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
    return <div className="thread-state">正在加载 Chat…</div>
  }
  if (chatQuery.error || !chatQuery.data) {
    return (
      <div className="thread-state danger">
        Chat 加载失败
        <button type="button" onClick={() => navigate('/chats')}>
          返回列表
        </button>
      </div>
    )
  }

  const chat = chatQuery.data
  const title = chat.title || chat.id

  return (
    <section className="chat-workspace screen active">
      <header className="chat-workspace-header">
        <div className="chat-workspace-title">
          <Link className="sidebar-icon-btn" to="/chats" title="返回 Chat 列表" aria-label="返回 Chat 列表">
            <ArrowLeft aria-hidden="true" />
          </Link>
          <h1>{title}</h1>
        </div>
        <div className="chat-layout-switch" role="group" aria-label="布局">
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
      </header>
      <div className={`chat-pane-grid layout-${paneState.layout}`}>
        {paneState.panes.map((pane) => (
          <ChatWorkspacePane
            key={pane.id}
            chat={chat}
            agents={agents}
            pane={pane}
            focused={paneState.focusedPaneId === pane.id}
            sessionSort={paneState.sessionSort}
            threadSort={paneState.threadSort}
            onFocus={() => setFocused(pane.id)}
            onThreadChange={(threadId) => setThread(pane.id, threadId)}
            onSessionSortChange={setSessionSort}
            onThreadSortChange={setThreadSort}
            onDefaultAgentChange={async (agentId) => {
              await updateChatMutation.mutateAsync(agentId)
            }}
          />
        ))}
      </div>
    </section>
  )
}
