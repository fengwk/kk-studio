import { ArrowLeft, PanelRight } from 'lucide-react'
import { Link } from 'react-router'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ThreadPresentationActions } from '@/features/thread/runtime/ThreadPresentationActions'
import type { ThreadPresentation } from '@/features/thread/runtime/thread-presentation'
import { ChatLayoutSelector } from '@/features/chat/ChatLayoutSelector'
import type { ChatLayout } from '@/features/chat/chat-pane-state'

export interface ChatWorkspaceHeaderProps {
  title: string
  focusedPaneId: string
  layout: ChatLayout
  onLayoutChange: (layout: ChatLayout) => void
  /** 当前聚焦 pane 呈现的视图；debug 模式下是 debug 目标 pane 的视图。 */
  currentView: ThreadPresentation | null
  focusedSessionId: string | null
  focusedSessionName: string | null
  focusedBranchName: string | null
  /** 非 null 表示正在查看某个 pane 的 debug 视图。 */
  debugging: boolean
  onCloseDebug: () => void
  ideHint: string
  ideHintId: string
  /** 提示文案是否挂在开关的 aria-describedby 上：只有 Chat 环境不可用时挂。 */
  ideHintAttached: boolean
  ideToggleDisabled: boolean
  idePanelVisible: boolean
  onToggleIde: () => void
}

/**
 * Chat 工作区顶栏：返回/呈现动作、面包屑、布局选择与 IDE 开关。
 *
 * 面包屑只在能确认来源时增加层级：Session 层来自目标 pane 的实时摘要，分支层
 * 在 thread 视图里显示该 thread 的名字，否则显示目标 pane 的草稿分支名。
 */
export function ChatWorkspaceHeader({
  title,
  focusedPaneId,
  layout,
  onLayoutChange,
  currentView,
  focusedSessionId,
  focusedSessionName,
  focusedBranchName,
  debugging,
  onCloseDebug,
  ideHint,
  ideHintId,
  ideHintAttached,
  ideToggleDisabled,
  idePanelVisible,
  onToggleIde,
}: ChatWorkspaceHeaderProps) {
  const { t } = useI18n()
  const branchName = currentView?.parentThreadId ? currentView.name : focusedBranchName
  return (
    <header className="chat-workspace-header">
      <div className="chat-workspace-title">
        {debugging ? <Button variant="ghost" size="compact" onClick={onCloseDebug}>
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
          data-focused-pane={focusedPaneId}
        >
          <h1 className="chat-workspace-breadcrumb-item" data-breadcrumb="chat">{title}</h1>
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
          {branchName ? (
            <>
              <span className="thread-breadcrumb-separator" aria-hidden="true">/</span>
              <span className="chat-workspace-breadcrumb-item" data-breadcrumb="branch">
                {branchName}
              </span>
            </>
          ) : null}
        </nav>
      </div>
      <div className="chat-workspace-actions">
        {debugging ? null : <ChatLayoutSelector layout={layout} onChange={onLayoutChange} />}
        {debugging ? null : (
          <span title={ideHint}>
            <Button
              variant="ghost"
              size="compact"
              className="chat-ide-toggle"
              aria-label={t('ai.ide.toggle')}
              aria-describedby={ideHintAttached ? ideHintId : undefined}
              aria-pressed={idePanelVisible}
              disabled={ideToggleDisabled}
              onClick={onToggleIde}
              data-testid="chat-ide-toggle"
            >
              <PanelRight size={16} aria-hidden="true" />
            </Button>
            {ideHintAttached ? <span id={ideHintId} className="sr-only">{ideHint}</span> : null}
          </span>
        )}
      </div>
    </header>
  )
}
