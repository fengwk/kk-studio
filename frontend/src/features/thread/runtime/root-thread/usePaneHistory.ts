import { useEffect, useMemo, useRef } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import { useI18n } from '@/shared/i18n'
import type { BranchDraft } from '@/features/thread/branch-draft'
import { threadDraftPath } from '@/features/thread/thread-draft-path'
import { isDraftHistoryTarget, type PaneTarget } from '@/features/thread/runtime/agent-pane'
import { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline'
import { buildThreadEventTimeline } from '@/features/thread/runtime/thread-events'
import {
  branchDraftFromEntry,
  cloneDraft,
} from '@/features/thread/runtime/root-thread/branch-draft-replay'
import { collectSessionEntries } from '@/features/thread/runtime/root-thread/session-entry-pages'
import type { PaneInteraction } from '@/features/thread/runtime/root-thread/types'
import type { HarnessSessionSummaryDTO } from '@/shared/api/generated/contracts'

/** 宿主返回的 Owner Session 摘要一页：items 按宿主顺序，nextCursor 非空表示还有下一页。 */
export interface SessionSummaryPage {
  items: HarnessSessionSummaryDTO[]
  nextCursor?: string | null
}

/** 宿主按页读取 Owner Session 摘要；cursor 为 null 表示首屏。 */
export type SessionSummaryLoader = (
  owner: AgentRuntimeOwnerDTO,
  cursor: string | null,
) => Promise<SessionSummaryPage>

/** 宿主按 id 解析单个 Owner Session 摘要；不存在或不属于当前 owner 时返回 null。 */
export type SessionSummaryResolver = (
  sessionId: string,
) => Promise<HarnessSessionSummaryDTO | null>

const EMPTY_SESSION_PAGE: SessionSummaryPage = { items: [], nextCursor: null }

/**
 * Pane 的 owner 范围导航查询：Session 列表（keyset 分页）、Session 下 Thread 列表与完整 Entry Tree。
 * 三者都按需读取（只在对应交互打开或草稿历史目标存在时启用）。
 */
export function usePaneNavigationQueries(options: {
  owner: AgentRuntimeOwnerDTO | undefined
  ownerKey: string | null
  target: PaneTarget
  interaction: PaneInteraction
  threadNavigationSessionId: string | null
  historySessionId: string | null
  /** 宿主提供的 Owner Session 摘要分页读取；未提供时该导航入口不可用。 */
  loadSessions?: SessionSummaryLoader
}) {
  const {
    owner,
    ownerKey,
    target,
    interaction,
    threadNavigationSessionId,
    historySessionId,
    loadSessions,
  } = options
  const sessionsQuery = useInfiniteQuery({
    queryKey: ['agent-pane', 'sessions', ownerKey],
    queryFn: ({ pageParam }) => {
      if (!owner || !loadSessions) {
        return Promise.resolve(EMPTY_SESSION_PAGE)
      }
      return loadSessions(owner, pageParam)
    },
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled:
      owner != null
      && loadSessions != null
      && (interaction === 'thread-sessions' || interaction === 'rename-session'),
  })
  const sessions = useMemo(
    () => sessionsQuery.data?.pages.flatMap((page) => page.items) ?? [],
    [sessionsQuery.data],
  )
  const loadMoreSessions = () => {
    if (sessionsQuery.hasNextPage && !sessionsQuery.isFetchingNextPage) {
      void sessionsQuery.fetchNextPage()
    }
  }
  const threadsQuery = useQuery({
    queryKey: ['agent-pane', 'threads', threadNavigationSessionId],
    queryFn: () => harnessService.listSessionThreads(threadNavigationSessionId!),
    enabled: interaction === 'thread-threads' && threadNavigationSessionId != null,
  })
  const treeEntriesQuery = useQuery({
    queryKey: ['agent-pane', 'entries', historySessionId],
    queryFn: ({ signal }) => collectSessionEntries(historySessionId!, signal),
    enabled:
      (interaction === 'history' || isDraftHistoryTarget(target))
      && historySessionId != null,
  })
  return {
    sessionsQuery,
    sessions,
    sessionsHasMore: sessionsQuery.hasNextPage,
    sessionsLoadingMore: sessionsQuery.isFetchingNextPage,
    loadMoreSessions,
    threadsQuery,
    treeEntriesQuery,
  }
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
    activeAssistantOutput: null,
    toolInvocations: [],
    generationFailures: [],
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
