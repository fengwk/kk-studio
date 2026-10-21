import { useEffect, useMemo, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { chatService } from '@/shared/api/chat-service'
import type {
  AgentRuntimeOwnerDTO,
  RuntimeSessionSummaryDTO,
} from '@/shared/api/contracts/ai-runtime'
import { useI18n } from '@/shared/i18n'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import { threadDraftPath } from '@/features/ai/chat/thread-draft-path'
import { isDraftHistoryTarget, type PaneTarget } from '@/features/ai/runtime/agent-pane'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline'
import { buildThreadEventTimeline } from '@/features/ai/runtime/thread-events'
import {
  branchDraftFromEntry,
  cloneDraft,
} from '@/features/ai/runtime/root-thread/branch-draft-replay'
import type { PaneInteraction } from '@/features/ai/runtime/root-thread/types'

/**
 * Pane 的 owner 范围导航查询：Session 列表、Session 下 Thread 列表与完整 Entry Tree。
 * 三者都按需读取（只在对应交互打开或草稿历史目标存在时启用）。
 */
export function usePaneNavigationQueries(options: {
  owner: AgentRuntimeOwnerDTO | undefined
  ownerKey: string | null
  target: PaneTarget
  interaction: PaneInteraction
  threadNavigationSessionId: string | null
  historySessionId: string | null
}) {
  const { owner, ownerKey, target, interaction, threadNavigationSessionId, historySessionId } = options
  const sessionsQuery = useQuery<RuntimeSessionSummaryDTO[]>({
    queryKey: ['agent-pane', 'sessions', ownerKey],
    queryFn: () => {
      if (!owner) {
        return Promise.resolve([])
      }
      if (owner.type === 'CHAT') {
        return chatService.listChatSessions(owner.chatId)
      }
      return Promise.resolve([])
    },
    enabled: owner?.type === 'CHAT' && (interaction === 'thread-sessions' || interaction === 'rename-session'),
  })
  const threadsQuery = useQuery({
    queryKey: ['agent-pane', 'threads', threadNavigationSessionId],
    queryFn: () => harnessService.listSessionThreads(threadNavigationSessionId!),
    enabled: interaction === 'thread-threads' && threadNavigationSessionId != null,
  })
  const treeEntriesQuery = useQuery({
    queryKey: ['agent-pane', 'entries', historySessionId],
    queryFn: () => harnessService.listSessionEntries(historySessionId!),
    enabled:
      (interaction === 'history' || isDraftHistoryTarget(target))
      && historySessionId != null,
  })
  return { sessionsQuery, threadsQuery, treeEntriesQuery }
}

type TreeEntriesQuery = ReturnType<typeof usePaneNavigationQueries>['treeEntriesQuery']

/**
 * 草稿历史目标（NEW_THREAD_DRAFT / FORK_SESSION_DRAFT）的只读历史：从 Entry Tree 解出起点路径，
 * 生成对话/事件时间线，并把路径上的设置重放成本地草稿基线（每个目标身份只初始化一次）。
 */
export function usePaneDraftHistory(options: {
  target: PaneTarget
  treeEntriesQuery: TreeEntriesQuery
  activeDraft: BranchDraft | null
  setLocalDraft: (draft: BranchDraft | null) => void
}) {
  const { target, treeEntriesQuery, activeDraft, setLocalDraft } = options
  const { t } = useI18n()
  const initializedEntryDraftRef = useRef<string | null>(null)

  const draftHistory = useMemo(() => {
    if (!isDraftHistoryTarget(target) || treeEntriesQuery.data == null || treeEntriesQuery.isError) {
      return { entries: [], error: null }
    }
    try {
      return { entries: threadDraftPath(treeEntriesQuery.data, target.sessionId, target.startEntryId), error: null }
    } catch {
      return { entries: [], error: new Error(t('ai.chat.branch.historyInvalid')) }
    }
  }, [target, treeEntriesQuery.data, treeEntriesQuery.isError, t])
  const draftHistoryReady = !isDraftHistoryTarget(target)
    || (treeEntriesQuery.data != null && !treeEntriesQuery.isError && draftHistory.error == null)
  const draftTimeline = useMemo(() => buildThreadTimeline(draftHistory.entries, [], []), [draftHistory.entries])
  const draftEvents = useMemo(() => buildThreadEventTimeline({
    entries: draftHistory.entries,
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    modelStream: null,
    toolStreams: null,
  }), [draftHistory.entries])

  const entryBaseDraft = useMemo(() => {
    if (!isDraftHistoryTarget(target)) {
      return activeDraft
    }
    if (!draftHistoryReady) {
      return null
    }
    return draftHistory.entries.reduce<BranchDraft | null>((draft, entry) => branchDraftFromEntry(entry, draft), activeDraft)
  }, [activeDraft, target, draftHistory, draftHistoryReady])

  useEffect(() => {
    if (!isDraftHistoryTarget(target) || treeEntriesQuery.data == null || entryBaseDraft == null) {
      return
    }
    const identity = `${target.sessionId}:${target.startEntryId}`
    if (initializedEntryDraftRef.current === identity) {
      return
    }
    initializedEntryDraftRef.current = identity
    setLocalDraft(cloneDraft(entryBaseDraft))
  }, [entryBaseDraft, target, treeEntriesQuery.data, setLocalDraft])

  return {
    draftHistory,
    draftHistoryReady,
    draftTimeline,
    draftEvents,
    entryBaseDraft,
    /** 目标切换后允许同一历史身份重新初始化本地草稿。 */
    resetEntryDraftInit: () => {
      initializedEntryDraftRef.current = null
    },
  }
}
