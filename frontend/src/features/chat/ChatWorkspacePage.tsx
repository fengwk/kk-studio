import { useCallback, useId, useLayoutEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/features/environment/environment-service'
import { chatService } from '@/features/chat/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { ChatWorkspaceHeader } from '@/features/chat/ChatWorkspaceHeader'
import { ChatWorkspacePane } from '@/features/chat/ChatWorkspacePane'
import { NewBranchDialog } from '@/features/chat/NewBranchDialog'
import {
  applyChatLayout,
  chatPanePosition,
  focusPane,
  setChatPaneTarget,
  visibleChatPanes,
} from '@/features/chat/chat-pane-state'
import { useBranchDraft } from '@/features/chat/useBranchDraft'
import { useChatIdeToggle } from '@/features/chat/useChatIdeToggle'
import { useOptionalIdePanel } from '@/features/environment/ide/ide-panel-context'
import { useChatPaneState } from '@/features/chat/useChatPaneState'
import { useFocusedSessionName } from '@/features/chat/useFocusedSessionName'
import { usePaneReports } from '@/features/chat/usePaneReports'
import { usePendingTargets } from '@/features/chat/usePendingTargets'
import { usePresentations } from '@/features/chat/usePresentations'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'
import '@/features/chat/chat-workspace.css'
import '@/features/thread/thread-state.css'
import type { AgentDTO, ChatDTO, EnvironmentDTO } from '@/shared/api/generated/contracts'
import type { PaneReport } from '@/features/thread/runtime/root-thread/types'
import type { PaneTarget } from '@/features/thread/runtime/agent-pane'

// 布局选择器有自己的模块；路由页面仍作为导入入口，避免破坏既有引用方。
export { ChatLayoutSelector, type ChatLayoutSelectorProps } from '@/features/chat/ChatLayoutSelector'

interface ChatWorkspaceContentProps {
  chat: ChatDTO
  agents: AgentDTO[]
  environments: EnvironmentDTO[]
}

/**
 * Chat workspace 拥有 1..9 个目标 pane。deep-link 与新建分支草稿都通过同一条
 * `initialTarget` + `onTargetConsumed` 队列落到目标 pane，绝不由一个 pane 直接写
 * 另一个 pane 的存储。这里只做编排：状态、命名流程、IDE 开关与呈现都在各自 hook。
 */
function ChatWorkspaceContent({ chat, agents, environments }: ChatWorkspaceContentProps) {
  const { t } = useI18n()
  const ideHintId = useId()
  const paneState = useChatPaneState(chat.id)
  const paneReports = usePaneReports(chat.id, paneState.paneState)
  const pendingTargets = usePendingTargets(paneState.paneState.focusedPaneId)
  const branch = useBranchDraft({
    paneStateRef: paneState.paneStateRef,
    reportsRef: paneReports.reportsRef,
    pendingTargetsRef: pendingTargets.pendingTargetsRef,
    commitPaneState: paneState.commitPaneState,
    setPendingTargets: pendingTargets.setPendingTargets,
  })
  const ide = useChatIdeToggle(chat, environments, ideHintId)

  const visiblePanes = visibleChatPanes(paneState.paneState)
  const visiblePaneIds = new Set(visiblePanes.map((pane) => pane.id))
  const focusedPaneId = paneState.paneState.focusedPaneId
  const focusedPaneVisible = visiblePaneIds.has(focusedPaneId)
  const focusedReport = paneReports.paneReports[focusedPaneId] ?? null
  const focusedSessionName = useFocusedSessionName(chat.id, focusedReport?.sessionId ?? null)
  const presentations = usePresentations(focusedPaneId, paneState.paneStateRef, focusedPaneVisible)

  const mountedPanes = paneState.paneState.panes.filter(
    (pane) => visiblePaneIds.has(pane.id) || paneReports.paneReports[pane.id]?.pending,
  )
  const destinations = paneState.paneState.panes.slice(0, 9).map((pane) => {
    const report = paneReports.paneReports[pane.id]
    return { value: pane.id, label: destinationLabel(pane.id, report, t) }
  })
  const { commitPaneState } = paneState
  const handlePaneTargetChange = useCallback((paneId: string, target: PaneTarget) => {
    commitPaneState((current) => setChatPaneTarget(current, paneId, target))
  }, [commitPaneState])
  const closeDebugWithEscape = (event: React.KeyboardEvent<HTMLElement>) => {
    if (presentations.debugSource && event.key === 'Escape' && !event.defaultPrevented
      && !event.repeat && !event.nativeEvent.isComposing && event.keyCode !== 229
      && !shouldDeferToBlockingModal(event.currentTarget)) {
      event.preventDefault()
      presentations.closeDebug()
    }
  }

  return (
    <section className="chat-workspace screen active" onKeyDown={closeDebugWithEscape}>
      <ChatWorkspaceHeader
        title={chat.title || chat.id}
        focusedPaneId={focusedPaneId}
        layout={paneState.paneState.layout}
        onLayoutChange={(nextLayout) => {
          commitPaneState((current) => applyChatLayout(current, nextLayout))
        }}
        currentView={presentations.currentView}
        focusedSessionId={focusedReport?.sessionId ?? null}
        focusedSessionName={focusedSessionName}
        focusedBranchName={focusedReport?.branchName ?? null}
        debugging={presentations.debugSource != null}
        onCloseDebug={presentations.closeDebug}
        ideHint={ide.hint}
        ideHintId={ide.hintId}
        ideHintAttached={ide.environmentUnavailable}
        ideToggleDisabled={ide.disabled}
        idePanelVisible={ide.panelVisible}
        onToggleIde={ide.toggle}
      />
      {paneState.storageUnavailable ? (
        <div className="form-error-banner" role="status">{t('ai.chat.tabStateNotSaved')}</div>
      ) : null}
      <div className={`chat-pane-grid layout-${presentations.debugSource ? 'single' : paneState.paneState.layout}`}>
        {mountedPanes.map((pane, index) => {
          const isFocused = focusedPaneId === pane.id || (index === 0 && !focusedPaneId)
          return (
            <ChatWorkspacePane
              key={`${chat.id}:${pane.id}`}
              chat={chat}
              agents={agents}
              environments={environments}
              pane={pane}
              focused={isFocused}
              hidden={!visiblePaneIds.has(pane.id)
                || (presentations.debugSource != null && presentations.debugSource.paneId !== pane.id)}
              onValidateDraftName={(target, name) => target.kind === 'NEW_THREAD_DRAFT'
                ? branch.validateDraftName(pane.id, target.sessionId, name)
                : Promise.resolve(t('ai.chat.branch.targetChanged'))}
              onFocus={() => commitPaneState((current) => focusPane(current, pane.id))}
              initialTarget={pendingTargets.pendingTargets[pane.id]}
              target={pane.target}
              onTargetChange={(target) => handlePaneTargetChange(pane.id, target)}
              onTargetConsumed={(consumed) => pendingTargets.handleTargetConsumed(pane.id, consumed)}
              onRequestBranch={branch.handleRequestBranch}
              onPaneReport={paneReports.handlePaneReport}
              onPresentation={presentations.handlePresentation}
            />
          )
        })}
      </div>
      {branch.request != null ? (
        <NewBranchDialog
          destinations={destinations}
          defaultDestination={branch.sourcePaneId ?? focusedPaneId}
          formError={branch.formError}
          checking={branch.checking}
          onInputChange={branch.handleBranchInputChange}
          overwriteWarning={branch.overwritePaneId == null ? null : t('ai.chat.branch.overwriteDraft')}
          onConfirm={branch.handleBranchConfirm}
          onClose={branch.closeBranchDialog}
        />
      ) : null}
    </section>
  )
}

/** 目标 pane 的选择项文案：序号，草稿目标附加草稿标记与分支名。 */
function destinationLabel(
  paneId: string,
  report: PaneReport | undefined,
  t: ReturnType<typeof useI18n>['t'],
): string {
  const position = chatPanePosition(paneId)
  const label = position == null ? paneId : String(position)
  if (!report?.branchName) {
    return label
  }
  const draft = report.target.kind === 'NEW_THREAD_DRAFT'
    ? `${t('ai.chat.branch.draftLabel')} · `
    : ''
  return `${label} · ${draft}${report.branchName}`
}

export function ChatWorkspacePage() {
  const { chatId = '' } = useParams()
  const navigate = useNavigate()
  const { t } = useI18n()
  const activateIde = useOptionalIdePanel()?.activateChatEnvironment
  const activateIdeRef = useRef(activateIde)
  useLayoutEffect(() => {
    activateIdeRef.current = activateIde
  })
  useLayoutEffect(() => {
    // 新 Chat 的环境事实尚未加载时，不能显示上一 Chat 的面板；iframe 仍保活。
    activateIdeRef.current?.(null)
  }, [chatId])

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
        <Button variant="ghost" onClick={() => navigate('/chats')}>
          {t('ai.chat.backToList')}
        </Button>
      </div>
    )
  }

  const chat = chatQuery.data
  return (
    <ChatWorkspaceContent
      key={chat.id}
      chat={chat}
      agents={agentsQuery.data ?? []}
      environments={environmentsQuery.data ?? []}
    />
  )
}
