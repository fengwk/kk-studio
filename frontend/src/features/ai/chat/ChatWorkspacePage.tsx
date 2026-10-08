import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import { NewBranchDialog } from '@/features/ai/chat/NewBranchDialog'
import { loadPaneTarget, samePaneTarget, type PaneTarget } from '@/features/ai/runtime/agent-pane'
import { hasThreadNameConflict } from '@/features/ai/chat/thread-name-conflict'
import { harnessService } from '@/shared/api/harness-service'
import { loadPendingAcceptance } from '@/features/ai/runtime/agent-pane'
import { restoreComposerDraft } from '@/features/ai/composer/composer-draft'
import { partsKey, trimMessageParts } from '@/features/ai/composer/composer-parts'
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
import { Button } from '@/shared/ui/controls/Button'
import { ThreadPresentationActions } from '@/features/ai/runtime/ThreadPresentationActions'
import type { ThreadPresentation } from '@/features/ai/runtime/thread-presentation'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'
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
  const [presentations, setPresentations] = useState<Record<string, ThreadPresentation | null>>({})
  const [debugSource, setDebugSource] = useState<{ paneId: string; viewKey: string } | null>(null)
  const paneStateRef = useRef(paneState)
  useLayoutEffect(() => { paneStateRef.current = paneState }, [paneState])
  const handlePresentation = useCallback((paneId: string, report: ThreadPresentation | null) => {
    setPresentations((current) => current[paneId] === report ? current : { ...current, [paneId]: report })
    setDebugSource((current) => {
      if (current != null) {
        if (current.paneId !== paneId) {
          return current
        }
        return report?.mode === 'debug' && report.viewKey === current.viewKey
          && (!report.viewKey.startsWith('thread:') || report.threadId != null) ? current : null
      }
      return report?.mode === 'debug' && paneStateRef.current.focusedPaneId === paneId
        && visibleChatPanes(paneStateRef.current).some((pane) => pane.id === paneId)
        && (!report.viewKey.startsWith('thread:') || report.threadId != null)
        ? { paneId, viewKey: report.viewKey } : null
    })
  }, [])
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
  const [branchRequest, setBranchRequest] = useState<BranchRequestInput | null>(null)
  const [branchSourcePaneId, setBranchSourcePaneId] = useState<string | null>(null)
  const [branchFormError, setBranchFormError] = useState<string | null>(null)
  const [overwritePaneId, setOverwritePaneId] = useState<string | null>(null)
  const [branchChecking, setBranchChecking] = useState(false)
  const branchCheckId = useRef(0)
  // Bootstrap hidden local drafts without loading any Thread snapshots. Subsequent
  // target changes come exclusively through PaneReport (including before unmount).
  const [storedTargets] = useState(() => Object.fromEntries(
    paneState.panes.map((pane) => [pane.id, loadPaneTarget({ type: 'CHAT', chatId: chat.id }, pane.id)]),
  ))
  const localTargets = useRef<Record<string, PaneTarget>>(storedTargets)
  const [paneReports, setPaneReports] = useState<Record<string, PaneReport>>(() => Object.fromEntries(
    paneState.panes.map((pane) => {
      const target = storedTargets[pane.id]!
      const parts = target.kind === 'BOUND_THREAD' ? [] : restoreComposerDraft(`agent-pane:CHAT:${chat.id}:${pane.id}`, [])
      return [pane.id, {
        target,
        draftKey: partsKey(parts),
        pending: loadPendingAcceptance({ type: 'CHAT', chatId: chat.id }, pane.id) != null,
        hasUnsentDraft: trimMessageParts(parts).length > 0,
        sessionId: target.kind === 'NEW_THREAD_DRAFT' ? target.sessionId : null,
        branchName: target.kind === 'NEW_THREAD_DRAFT' ? target.threadName : null,
      }]
    }),
  ))
  const reportsRef = useRef(paneReports)
  const pendingTargetsRef = useRef(pendingTargets)
  useEffect(() => { reportsRef.current = paneReports }, [paneReports])
  useEffect(() => { pendingTargetsRef.current = pendingTargets }, [pendingTargets])
  useEffect(() => () => { branchCheckId.current += 1 }, [])

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
    localTargets.current[paneId] = report.target
    setPaneReports((current) => {
      const previous = current[paneId]
      if (
        previous != null
        && previous.pending === report.pending
        && previous.hasUnsentDraft === report.hasUnsentDraft
        && previous.draftKey === report.draftKey
        && previous.sessionId === report.sessionId
        && previous.branchName === report.branchName
        && samePaneTarget(previous.target, report.target)
      ) {
        return current
      }
      return { ...current, [paneId]: report }
    })
  }, [])

  const handleRequestBranch = useCallback((sourcePaneId: string, request: BranchRequestInput) => {
    branchCheckId.current += 1
    setBranchChecking(false)
    setBranchRequest(request)
    setBranchSourcePaneId(sourcePaneId)
    setBranchFormError(null)
    setOverwritePaneId(null)
  }, [])

  const validateDraftName = useCallback(async (paneId: string, sessionId: string, name: string) => {
    try {
      const threads = await harnessService.listSessionThreads(sessionId)
      return hasThreadNameConflict(name, sessionId, paneId, threads, {
        ...localTargets.current, ...pendingTargetsRef.current,
      }) ? t('ai.chat.branch.nameConflict') : null
    } catch {
      return t('ai.chat.branch.nameCheckFailed')
    }
  }, [t])

  /**
   * 命名流程的唯一提交点：先按目标 pane 的实时摘要守住在途操作与未发送草稿，
   * 再把 NEW_THREAD_DRAFT 路由到目标 pane（隐藏布局先显露）并把焦点移过去。
   * 创建请求仍由目标 pane 在用户首次输入时原子提交，这里绝不预创建。
   */
  const handleBranchConfirm = useCallback(async (input: { name: string; paneId: string }) => {
    if (branchRequest == null || branchChecking) {
      return
    }
    const report = reportsRef.current[input.paneId]
    if (report?.pending) {
      setBranchFormError(t('ai.chat.branch.destinationBusy'))
      return
    }
    if (report?.hasUnsentDraft && overwritePaneId !== input.paneId) {
      setOverwritePaneId(input.paneId)
      setBranchFormError(null)
      return
    }
    const requestId = ++branchCheckId.current
    const sourceTarget = branchSourcePaneId == null ? null : localTargets.current[branchSourcePaneId]
    const destinationTarget = localTargets.current[input.paneId]
    const destinationDraftKey = report?.draftKey
    setBranchChecking(true)
    setBranchFormError(null)
    try {
      const nameError = await validateDraftName(input.paneId, branchRequest.sessionId, input.name)
      if (requestId !== branchCheckId.current) {
        return
      }
      if ((sourceTarget != null && branchSourcePaneId != null
          && !samePaneTarget(sourceTarget, localTargets.current[branchSourcePaneId]))
        || !samePaneTarget(destinationTarget, localTargets.current[input.paneId])
        || destinationDraftKey !== reportsRef.current[input.paneId]?.draftKey) {
        setOverwritePaneId(null)
        setBranchFormError(t('ai.chat.branch.targetChanged'))
        return
      }
      if (reportsRef.current[input.paneId]?.pending) {
        setBranchFormError(t('ai.chat.branch.destinationBusy'))
        return
      }
      if (reportsRef.current[input.paneId]?.hasUnsentDraft && overwritePaneId !== input.paneId) {
        setOverwritePaneId(input.paneId)
        return
      }
      if (nameError != null) {
        setBranchFormError(nameError)
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
    } finally {
      if (requestId === branchCheckId.current) {
        setBranchChecking(false)
      }
    }
  }, [branchRequest, branchSourcePaneId, branchChecking, overwritePaneId, validateDraftName, t])

  const closeBranchDialog = useCallback(() => {
    branchCheckId.current += 1
    setBranchChecking(false)
    setBranchRequest(null)
    setBranchSourcePaneId(null)
    setBranchFormError(null)
    setOverwritePaneId(null)
  }, [])

  const destinations = paneState.panes.slice(0, 9).map((pane) => {
    const position = chatPanePosition(pane.id)
    const label = position == null ? pane.id : String(position)
    const branchName = paneReports[pane.id]?.branchName
    const draft = paneReports[pane.id]?.target.kind === 'NEW_THREAD_DRAFT'
    return { value: pane.id, label: branchName
      ? `${label} · ${draft ? `${t('ai.chat.branch.draftLabel')} · ` : ''}${branchName}`
      : label }
  })
  const visiblePanes = visibleChatPanes(paneState)
  const visiblePaneIds = new Set(visiblePanes.map((pane) => pane.id))
  const mountedPanes = paneState.panes.filter((pane) => visiblePaneIds.has(pane.id) || paneReports[pane.id]?.pending)
  const currentView = presentations[debugSource?.paneId ?? paneState.focusedPaneId] ?? null
  const focusedPaneVisible = visiblePaneIds.has(paneState.focusedPaneId)
  useLayoutEffect(() => {
    if (debugSource == null && currentView?.mode === 'conversation' && focusedPaneVisible) {
      currentView.act(currentView.viewKey, 'restore-focus')
    }
  }, [currentView, debugSource, focusedPaneVisible])
  const closeDebug = () => currentView?.act(debugSource?.viewKey ?? currentView.viewKey, 'close-debug')
  return (
    <section className="chat-workspace screen active" onKeyDown={(event) => {
      if (debugSource && event.key === 'Escape' && !event.defaultPrevented
        && !event.repeat && !event.nativeEvent.isComposing && event.keyCode !== 229
        && !shouldDeferToBlockingModal(event.currentTarget)) {
        event.preventDefault()
        closeDebug()
      }
    }}>
      <header className="chat-workspace-header">
        <div className="chat-workspace-title">
          {debugSource ? <Button variant="ghost" size="compact" onClick={closeDebug}>
            {t('ai.runtime.debug.close')}
          </Button> : currentView?.parentThreadId ? <ThreadPresentationActions view={currentView} /> : <Link
            className="chat-workspace-back"
            to="/chats"
            title={t('ai.chat.backToConversation')}
            aria-label={t('ai.chat.backToConversation')}
          >
            <ArrowLeft aria-hidden="true" />
            <span>{t('ai.chat.backToConversation')}</span>
          </Link>}
          <nav
            className="chat-workspace-breadcrumb"
            aria-label={t('ai.chat.branch.breadcrumb')}
            data-focused-pane={paneState.focusedPaneId}
          >
            <h1 className="chat-workspace-breadcrumb-item" data-breadcrumb="chat">{chat.title || chat.id}</h1>
            {currentView?.parentThreadId == null && focusedSessionId != null ? (
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
            {(currentView?.parentThreadId ? currentView.name : focusedReport?.branchName) ? (
              <>
                <span className="thread-breadcrumb-separator" aria-hidden="true">/</span>
                <span className="chat-workspace-breadcrumb-item" data-breadcrumb="branch">
                  {currentView?.parentThreadId ? currentView.name : focusedReport?.branchName}
                </span>
              </>
            ) : null}
          </nav>
        </div>
        <div className="chat-workspace-actions">
          {debugSource == null ? <ChatLayoutSelector
            layout={paneState.layout}
            onChange={(nextLayout) => {
              setPaneState((current) => applyChatLayout(current, nextLayout))
            }}
          /> : null}
        </div>
      </header>
      <div className={`chat-pane-grid layout-${debugSource ? 'single' : paneState.layout}`}>
        {mountedPanes.map((pane, index) => {
          const isFocused = paneState.focusedPaneId === pane.id || (index === 0 && !paneState.focusedPaneId)
          return (
            <ChatWorkspacePane
              key={`${chat.id}:${pane.id}`}
              chat={chat}
              agents={agents}
              environments={environments}
              pane={pane}
              focused={isFocused}
              hidden={!visiblePaneIds.has(pane.id) || (debugSource != null && debugSource.paneId !== pane.id)}
              onValidateDraftName={(target, name) => target.kind === 'NEW_THREAD_DRAFT'
                ? validateDraftName(pane.id, target.sessionId, name)
                : Promise.resolve(t('ai.chat.branch.targetChanged'))}
              onFocus={() => setPaneState((current) => focusPane(current, pane.id))}
              initialTarget={pendingTargets[pane.id]}
              onTargetConsumed={(consumed) => handleTargetConsumed(pane.id, consumed)}
              onRequestBranch={handleRequestBranch}
              onPaneReport={handlePaneReport}
              onPresentation={handlePresentation}
            />
          )
        })}
      </div>
      {branchRequest != null ? (
        <NewBranchDialog
          destinations={destinations}
          defaultDestination={branchSourcePaneId ?? paneState.focusedPaneId}
          formError={branchFormError}
          checking={branchChecking}
          onInputChange={() => {
            branchCheckId.current += 1
            setBranchChecking(false)
            setBranchFormError(null)
            setOverwritePaneId(null)
          }}
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
