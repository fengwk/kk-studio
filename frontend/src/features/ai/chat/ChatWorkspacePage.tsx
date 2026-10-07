import { useCallback, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import { NewBranchDialog } from '@/features/ai/chat/NewBranchDialog'
import { samePaneTarget, type PaneTarget } from '@/features/ai/runtime/agent-pane'
import type { BranchRequestInput, PaneReport } from '@/features/ai/runtime/useRootThreadControl'
import {
  applyChatLayout,
  chatPanePosition,
  focusPane,
  loadChatPaneState,
  parseLayout,
  revealChatPane,
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
import { Select } from '@/shared/ui/controls/Select'
import '@/features/ai/chat/chat-workspace.css'

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

/**
 * Chat workspace 拥有 1..9 目标路由：deep-link 与新建分支草稿都通过同一个
 * `initialTarget` + `onTargetConsumed` 边界落到目标 pane，绝不由一个 pane 直接写另
 * 一个 pane 的本地存储（无跨 pane storage sidechannel）。
 */
function ChatWorkspaceContent({
  chat,
  agents,
  environments,
}: ChatWorkspaceContentProps) {
  const [searchParams, setSearchParams] = useSearchParams()
  const targetThreadId = searchParams.get('thread')
  const { t } = useI18n()
  const location = useLocation()
  const [paneState, setPaneState] = useState<ChatPaneState>(() => loadChatPaneState(chat.id))
  const [deepLinkOwner, setDeepLinkOwner] = useState(() => ({
    navigationKey: location.key,
    paneId: paneState.focusedPaneId,
  }))
  // 按 pane 路由的待应用目标：deep-link 与分支草稿共用同一队列。
  const [pendingTargets, setPendingTargets] = useState<Record<string, PaneTarget>>(
    () => (targetThreadId
      ? { [paneState.focusedPaneId]: { kind: 'BOUND_THREAD', threadId: targetThreadId } }
      : {}),
  )
  const [paneReports, setPaneReports] = useState<Record<string, PaneReport>>({})
  const [branchRequest, setBranchRequest] = useState<BranchRequestInput | null>(null)
  const [branchSourcePaneId, setBranchSourcePaneId] = useState<string | null>(null)
  const [branchFormError, setBranchFormError] = useState<string | null>(null)
  const [overwritePaneId, setOverwritePaneId] = useState<string | null>(null)

  // 每次导航固定目标 pane，清除 query 前的焦点变化不能转移请求。
  if (deepLinkOwner.navigationKey !== location.key) {
    setDeepLinkOwner({
      navigationKey: location.key,
      paneId: paneState.focusedPaneId,
    })
    if (targetThreadId) {
      setPendingTargets((current) => ({
        ...current,
        [paneState.focusedPaneId]: { kind: 'BOUND_THREAD', threadId: targetThreadId },
      }))
    }
  }

  useEffect(() => {
    saveChatPaneState(chat.id, paneState)
  }, [chat.id, paneState])

  const focusedReport = paneReports[paneState.focusedPaneId] ?? null
  const focusedSessionId = focusedReport?.sessionId ?? null
  // 只用于顶栏面包屑的 Session 名称；按需查询，不参与目标路由。
  const sessionsQuery = useQuery({
    queryKey: ['chat-workspace', 'sessions', chat.id],
    queryFn: () => chatService.listChatSessions(chat.id),
    enabled: focusedSessionId != null,
  })
  const focusedSessionName = focusedSessionId == null
    ? null
    : sessionsQuery.data?.find((session) => session.sessionId === focusedSessionId)?.name ?? null

  // 目标 pane 成功消费（挂载初始化或无 pending 切换成功）后清空该 pane 的待应用目标；
  // 命中 deep-link query 时一并清掉 URL 参数。
  const handleTargetConsumed = useCallback((paneId: string, consumed: PaneTarget) => {
    setPendingTargets((current) => {
      const pending = current[paneId]
      if (pending == null || !samePaneTarget(pending, consumed)) {
        return current
      }
      const next = { ...current }
      delete next[paneId]
      return next
    })
    const currentThread = searchParams.get('thread')
    if (currentThread && consumed.kind === 'BOUND_THREAD' && consumed.threadId === currentThread) {
      const nextParams = new URLSearchParams(searchParams)
      nextParams.delete('thread')
      setSearchParams(nextParams, { replace: true })
    }
  }, [searchParams, setSearchParams])

  const handlePaneReport = useCallback((paneId: string, report: PaneReport) => {
    setPaneReports((current) => {
      const previous = current[paneId]
      if (
        previous != null
        && previous.pending === report.pending
        && previous.hasUnsentDraft === report.hasUnsentDraft
        && previous.sessionId === report.sessionId
        && previous.branchName === report.branchName
      ) {
        return current
      }
      return { ...current, [paneId]: report }
    })
  }, [])

  const handleRequestBranch = useCallback((sourcePaneId: string, request: BranchRequestInput) => {
    setBranchRequest(request)
    setBranchSourcePaneId(sourcePaneId)
    setBranchFormError(null)
    setOverwritePaneId(null)
  }, [])

  /**
   * 命名流程的唯一提交点：先按目标 pane 的实时摘要守住在途操作与未发送草稿，
   * 再把 NEW_THREAD_DRAFT 路由到目标 pane（隐藏布局先显露）并把焦点移过去。
   * 创建请求仍由目标 pane 在用户首次输入时原子提交，这里绝不预创建。
   */
  const handleBranchConfirm = useCallback((input: { name: string; paneId: string }) => {
    if (branchRequest == null) {
      return
    }
    const report = paneReports[input.paneId]
    if (report?.pending) {
      setBranchFormError(t('ai.chat.branch.destinationBusy'))
      return
    }
    if (report?.hasUnsentDraft && overwritePaneId !== input.paneId) {
      setOverwritePaneId(input.paneId)
      setBranchFormError(null)
      return
    }
    const nextTarget: PaneTarget = {
      kind: 'NEW_THREAD_DRAFT',
      sessionId: branchRequest.sessionId,
      startEntryId: branchRequest.startEntryId,
      threadName: input.name,
    }
    setPaneState((current) => focusPane(revealChatPane(current, input.paneId), input.paneId))
    setPendingTargets((current) => ({ ...current, [input.paneId]: nextTarget }))
    setBranchRequest(null)
    setBranchSourcePaneId(null)
    setBranchFormError(null)
    setOverwritePaneId(null)
  }, [branchRequest, overwritePaneId, paneReports, t])

  const closeBranchDialog = useCallback(() => {
    setBranchRequest(null)
    setBranchSourcePaneId(null)
    setBranchFormError(null)
    setOverwritePaneId(null)
  }, [])

  const destinations = paneState.panes.slice(0, 9).map((pane) => {
    const position = chatPanePosition(pane.id)
    const label = position == null ? pane.id : String(position)
    const branchName = paneReports[pane.id]?.branchName
    return { value: pane.id, label: branchName ? `${label} · ${branchName}` : label }
  })
  const visiblePanes = visibleChatPanes(paneState)
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
          <nav
            className="chat-workspace-breadcrumb"
            aria-label={t('ai.chat.branch.breadcrumb')}
            data-focused-pane={paneState.focusedPaneId}
          >
            <h1 className="chat-workspace-breadcrumb-item" data-breadcrumb="chat">{chat.title || chat.id}</h1>
            {focusedSessionId != null ? (
              <>
                <span className="thread-breadcrumb-separator" aria-hidden="true">/</span>
                <span
                  className="chat-workspace-breadcrumb-item"
                  data-breadcrumb="session"
                  title={focusedSessionId}
                >
                  {focusedSessionName ?? focusedSessionId}
                </span>
              </>
            ) : null}
            {focusedReport?.branchName ? (
              <>
                <span className="thread-breadcrumb-separator" aria-hidden="true">/</span>
                <span className="chat-workspace-breadcrumb-item" data-breadcrumb="branch">
                  {focusedReport.branchName}
                </span>
              </>
            ) : null}
          </nav>
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
          return (
            <ChatWorkspacePane
              key={`${chat.id}:${pane.id}`}
              chat={chat}
              agents={agents}
              environments={environments}
              pane={pane}
              focused={isFocused}
              onFocus={() => setPaneState((current) => focusPane(current, pane.id))}
              initialTarget={pendingTargets[pane.id]}
              onTargetConsumed={(consumed) => handleTargetConsumed(pane.id, consumed)}
              onRequestBranch={handleRequestBranch}
              onPaneReport={handlePaneReport}
            />
          )
        })}
      </div>
      {branchRequest != null ? (
        <NewBranchDialog
          destinations={destinations}
          defaultDestination={branchSourcePaneId ?? paneState.focusedPaneId}
          formError={branchFormError}
          overwriteWarning={overwritePaneId == null ? null : t('ai.chat.branch.overwriteDraft')}
          onConfirm={handleBranchConfirm}
          onClose={closeBranchDialog}
        />
      ) : null}
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
