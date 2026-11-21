import type { ReactNode } from 'react'
import {
  AgentSelectionPanel,
  SelectionPanel,
} from '@/features/thread/SelectionPanel'
import { HistoryTree } from '@/features/thread/HistoryTree'
import { ThreadShortcutsPanel } from '@/features/thread/runtime/thread-panel'
import { NameRenamePanel } from '@/features/thread/runtime/thread-panel/NameRenamePanel'
import { SubagentTreePanel } from '@/features/thread/runtime/panes/SubagentTreePanel'
import {
  sessionSelectionItem,
  threadSelectionItem,
} from '@/features/thread/runtime/root-thread/session-selection-items'
import type { useRootThreadControl } from '@/features/thread/runtime/controllers/useRootThreadControl'
import type { useActiveThreadTree } from '@/features/thread/runtime/queries/useActiveThreadTree'
import type {
  AgentPaneCapabilities,
} from '@/features/thread/runtime/root-thread/types'
import type { AgentDTO } from '@/shared/api/generated/contracts'
import type { useI18n } from '@/shared/i18n'

/** 交互面板渲染所需的 pane 控制面与宿主能力。 */
export interface PaneInteractionContext {
  pane: ReturnType<typeof useRootThreadControl>
  tree: ReturnType<typeof useActiveThreadTree>
  boundThreadId: string | null
  agents: AgentDTO[]
  capabilities?: AgentPaneCapabilities
  t: ReturnType<typeof useI18n>['t']
}

/**
 * 侧边交互面板：子代理树、重命名、Agent 选择、快捷键、历史树与 Session/Thread 选择。
 *
 * 宿主显式关闭的入口（只读宿主禁用切换 Agent / 分支）不渲染面板本体，与命令可用性一致。
 */
export function renderPaneInteraction({
  pane,
  tree,
  boundThreadId,
  agents,
  capabilities,
  t,
}: PaneInteractionContext): ReactNode {
  if (pane.interaction === 'subagent') {
    return (
      <SubagentTreePanel
        tree={tree}
        currentThreadId={boundThreadId ?? undefined}
        onClose={pane.closeInteraction}
      />
    )
  }
  if (pane.interaction === 'rename-session' || pane.interaction === 'rename-thread') {
    return renderRename(pane, t)
  }
  if (pane.interaction === 'agent') {
    if (capabilities?.allowSwitchAgent === false) {
      return null
    }
    return (
      <AgentSelectionPanel
        agents={agents}
        selectionError={pane.error}
        selectedAgentName={pane.activeDraft?.agentName}
        selectionPending={pane.pending}
        onClose={pane.closeInteraction}
        onSelect={pane.selectAgent}
      />
    )
  }
  if (pane.interaction === 'shortcuts') {
    return <ThreadShortcutsPanel onClose={pane.closeInteraction} />
  }
  if (pane.interaction === 'history') {
    if (capabilities?.allowBranching === false) {
      return null
    }
    return renderHistory(pane)
  }
  if (pane.interaction === 'thread-sessions') {
    if (capabilities?.allowBranching === false) {
      return null
    }
    return renderSessionSelection(pane, t)
  }
  if (pane.interaction === 'thread-threads') {
    if (capabilities?.allowBranching === false) {
      return null
    }
    return renderThreadSelection(pane, t)
  }
  return null
}

function renderRename(
  pane: PaneInteractionContext['pane'],
  t: PaneInteractionContext['t'],
): ReactNode {
  if (pane.renameTarget == null) {
    return null
  }
  const renameTarget = pane.renameTarget
  return (
    <NameRenamePanel
      title={renameTarget.kind === 'session'
        ? t('ai.runtime.rename.sessionTitle')
        : t('ai.runtime.rename.threadTitle')}
      initialName={renameTarget.name}
      busy={pane.renameBusy}
      pending={pane.renamePending}
      error={pane.renameError}
      onSubmit={(name) => pane.submitRename(name)}
      onClose={pane.closeRename}
    />
  )
}

/** 历史树：head 在绑定 Thread 取当前 head，在草稿目标取所选切点。 */
function renderHistory(pane: PaneInteractionContext['pane']): ReactNode {
  return (
    <HistoryTree
      entries={pane.treeEntries}
      headEntryId={
        pane.target.kind === 'BOUND_THREAD'
          ? pane.controller.thread?.headEntryId ?? null
          : pane.target.kind === 'NEW_THREAD_DRAFT' || pane.target.kind === 'FORK_SESSION_DRAFT'
            ? pane.target.startEntryId
            : null
      }
      loading={pane.treeEntriesLoading}
      queryError={pane.treeEntriesError}
      onClose={pane.closeInteraction}
      onFork={pane.requestBranchFromEntry}
      onForkSession={pane.canForkSession ? pane.requestSessionForkFromEntry : undefined}
    />
  )
}

function renderSessionSelection(
  pane: PaneInteractionContext['pane'],
  t: PaneInteractionContext['t'],
): ReactNode {
  return (
    <SelectionPanel
      title={t('ai.chat.selectSession')}
      items={pane.sessions.map(sessionSelectionItem)}
      loading={pane.sessionsLoading}
      loadError={pane.selectionQueries.sessionsError != null ? t('ai.chat.sessionsLoadFailed') : null}
      onRetryLoad={pane.selectionQueries.retrySessions}
      hasMore={pane.sessionsHasMore}
      loadingMore={pane.sessionsLoadingMore}
      onLoadMore={pane.loadMoreSessions}
      emptyText={t('ai.chat.noSessions')}
      renameLabel={t('ai.runtime.rename.titleAria')}
      onClose={pane.closeInteraction}
      onRename={(id) => {
        const session = pane.sessions.find((item) => item.sessionId === id)
        if (session) {
          pane.openRenameWithBackTo('session', session.sessionId, session.name, 'thread-sessions')
        }
      }}
      onSelect={(id) => {
        const session = pane.sessions.find((item) => item.sessionId === id)
        if (session) {
          pane.selectSession(session)
        }
      }}
    />
  )
}

function renderThreadSelection(
  pane: PaneInteractionContext['pane'],
  t: PaneInteractionContext['t'],
): ReactNode {
  return (
    <SelectionPanel
      title={t('ai.chat.selectThread')}
      items={pane.threads.map(threadSelectionItem)}
      loading={pane.threadsLoading}
      loadError={pane.selectionQueries.threadsError != null ? t('ai.chat.threadsLoadFailed') : null}
      onRetryLoad={pane.selectionQueries.retryThreads}
      emptyText={t('ai.chat.noThreads')}
      renameLabel={t('ai.runtime.rename.titleAria')}
      onClose={() => {
        pane.openInteraction('thread-sessions')
      }}
      onRename={(id) => {
        const thread = pane.threads.find((item) => item.threadId === id)
        if (thread) {
          pane.openRenameWithBackTo('thread', thread.threadId, thread.name, 'thread-threads')
        }
      }}
      onSelect={(id) => {
        const thread = pane.threads.find((item) => item.threadId === id)
        if (thread) {
          pane.selectThread(thread)
        }
      }}
    />
  )
}
