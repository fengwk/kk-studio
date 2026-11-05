import { useEffect, useRef, useState } from 'react'
import { useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { compareCatalogVersions } from '@/shared/lib/catalog-version'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { normalizeThreadName } from '@/features/thread/thread-name'
import {
  isBoundTarget,
  isNewThreadTarget,
  samePaneTarget,
  type PaneTarget,
} from '@/features/thread/runtime/agent-pane'
import {
  errorMessage,
  type PaneInteraction,
  type RenameKind,
  type RenameTarget,
} from '@/features/thread/runtime/root-thread/types'
import type {
  SessionSummaryPage,
  SessionSummaryResolver,
} from '@/features/thread/runtime/root-thread/usePaneHistory'
import type {
  HarnessSessionSummaryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadSummaryDTO,
} from '@/shared/api/generated/contracts'

interface PaneRenameOptions {
  ownerKey: string | null
  target: PaneTarget
  targetRef: { current: PaneTarget }
  setTarget: (next: PaneTarget) => void
  setFocusIntent: (next: PaneTarget | null) => void
  generationRef: { current: number }
  isMountedRef: { current: boolean }
  /** rename PUT 在途栅栏：由 root 持有，使 hasPendingOperation 在 state commit 前也能看到。 */
  renamePendingRef: { current: boolean }
  boundThread: { threadId: string; name: string } | null
  boundSessionId: string | null
  /** 已加载的 Session 摘要页；重命名面板优先就地解析名称，未命中再按 id 查询。 */
  sessions: HarnessSessionSummaryDTO[]
  sessionsPending: boolean
  sessionsError: unknown
  resolveSession?: SessionSummaryResolver
  hasPendingOperation: () => boolean
  setInteraction: (next: PaneInteraction) => void
  setActionError: (message: string | null) => void
  onValidateDraftName?: (target: PaneTarget, name: string) => Promise<string | null>
}

/**
 * 单输入重命名面板：Session/Thread 的重命名目标解析、提交与缓存修补。
 * 新建分支草稿的重命名只改本 pane 的目标，不触达服务端。
 */
export function usePaneRename(options: PaneRenameOptions) {
  const {
    ownerKey,
    target,
    targetRef,
    setTarget,
    setFocusIntent,
    generationRef,
    isMountedRef,
    renamePendingRef,
    boundThread,
    boundSessionId,
    sessions,
    sessionsPending,
    sessionsError,
    resolveSession,
    hasPendingOperation,
    setInteraction,
    setActionError,
    onValidateDraftName,
  } = options
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [renameTarget, setRenameTargetState] = useState<RenameTarget | null>(null)
  const [renamePending, setRenamePending] = useState(false)
  const [renameError, setRenameError] = useState<string | null>(null)
  const renameTargetRef = useRef<RenameTarget | null>(null)

  // session 重命名面板的 on-demand 名称解析：优先使用已加载的 Session 摘要页，
  // 未命中时按 id 向宿主解析（分页后目标不一定在当前页）。解析失败或目标不存在
  // 都会终止 busy 态并提示错误，绝不把面板留在永久 busy。
  const sessionsLoadFailed = sessionsError != null
    ? errorMessage(sessionsError, t('ai.runtime.action.requestFailed'))
    : null
  useEffect(() => {
    if (renameTarget == null || renameTarget.kind !== 'session' || renameTarget.name != null) {
      return
    }
    const loaded = sessions.find((item) => item.sessionId === renameTarget.id)
    if (loaded != null) {
      setRenameTargetState((current) =>
        current != null && current.id === renameTarget.id ? { ...current, name: loaded.name } : current,
      )
      return
    }
    if (sessionsLoadFailed != null) {
      setRenameTargetState((current) =>
        current != null && current.kind === 'session' && current.name == null
          ? { ...current, name: '' }
          : current,
      )
      setRenameError(sessionsLoadFailed)
      return
    }
    if (sessionsPending) {
      return
    }
    if (resolveSession == null) {
      // 没有单条解析能力时以已加载页为唯一来源；穷尽后目标仍不存在即不可用。
      setRenameTargetState((current) =>
        current != null && current.kind === 'session' && current.name == null
          ? { ...current, name: '' }
          : current,
      )
      setRenameError(t('ai.runtime.rename.sessionUnavailable'))
      return
    }
    let active = true
    void resolveSession(renameTarget.id)
      .then((summary) => {
        if (!active) {
          return
        }
        setRenameTargetState((current) => (
          current != null && current.id === renameTarget.id && current.name == null
            ? { ...current, name: summary?.name ?? '' }
            : current
        ))
        if (summary == null) {
          setRenameError(t('ai.runtime.rename.sessionUnavailable'))
        }
      })
      .catch((error: unknown) => {
        if (!active) {
          return
        }
        setRenameTargetState((current) =>
          current != null && current.kind === 'session' && current.name == null
            ? { ...current, name: '' }
            : current,
        )
        setRenameError(errorMessage(error, t('ai.runtime.action.requestFailed')))
      })
    return () => {
      active = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- t 是稳定 useCallback。
  }, [renameTarget, sessions, sessionsPending, sessionsLoadFailed, resolveSession])

  /** 打开指定 kind 的单输入重命名面板。name 为空/未知时为 null（解析态）。 */
  function openRename(
    kind: RenameKind,
    id: string,
    name: string | null,
    backTo: RenameTarget['backTo'] = null,
  ): void {
    setRenameTargetState({ kind, id, name, backTo })
    setRenameError(null)
    setRenamePending(false)
    setInteraction(kind === 'session' ? 'rename-session' : 'rename-thread')
  }

  /** 当前 target 可重命名的实体；不可用返回 null（矩阵保证可达才调用）。 */
  function renameTargetOf(kind: RenameKind): { id: string; name: string | null } | null {
    if (kind === 'thread') {
      if (isNewThreadTarget(target)) {
        return { id: target.sessionId, name: target.threadName }
      }
      if (isBoundTarget(target) && boundThread?.threadId === target.threadId) {
        return { id: target.threadId, name: boundThread.name }
      }
      return null
    }
    if (isNewThreadTarget(target)) {
      const summary = sessions.find((item) => item.sessionId === target.sessionId)
      return { id: target.sessionId, name: summary?.name ?? null }
    }
    if (isBoundTarget(target) && boundSessionId) {
      const summary = sessions.find((item) => item.sessionId === boundSessionId)
      return { id: boundSessionId, name: summary?.name ?? null }
    }
    return null
  }

  function openRenameForTarget(kind: RenameKind): void {
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return
    }
    const targetEntity = renameTargetOf(kind)
    if (targetEntity == null) {
      setActionError(kind === 'thread'
        ? t('ai.runtime.rename.threadUnavailable')
        : t('ai.runtime.rename.sessionUnavailable'))
      return
    }
    openRename(kind, targetEntity.id, targetEntity.name)
  }

  function closeRename(): void {
    if (renamePendingRef.current) {
      return
    }
    const backTo = renameTarget?.backTo ?? null
    renameTargetRef.current = null
    renamePendingRef.current = false
    setRenameTargetState(null)
    setRenameError(null)
    setRenamePending(false)
    // 从 Session/Thread picker 行内进入时，关闭后返回该 picker。
    setInteraction(backTo)
  }

  async function submitRename(name: string): Promise<void> {
    if (renameTarget == null || renamePendingRef.current || hasPendingOperation()) {
      return
    }
    const trimmed = normalizeThreadName(name)
    if (trimmed == null) {
      setRenameError(t('ai.runtime.rename.nameRequired'))
      return
    }
    setRenameError(null)
    setRenamePending(true)
    renamePendingRef.current = true
    const current = renameTarget
    renameTargetRef.current = current
    try {
      if (current.kind === 'thread' && isNewThreadTarget(target)) {
        const frozenTarget = target
        const nameError = onValidateDraftName == null
          ? t('ai.chat.branch.nameCheckFailed')
          : await onValidateDraftName(frozenTarget, trimmed)
        if (!isMountedRef.current || renameTargetRef.current !== current
          || !samePaneTarget(targetRef.current, frozenTarget)) {
          return
        }
        if (nameError != null) {
          renamePendingRef.current = false
          setRenamePending(false)
          setRenameError(nameError)
          return
        }
        const next = { ...target, threadName: trimmed }
        targetRef.current = next
        setTarget(next)
        generationRef.current += 1
        renamePendingRef.current = false
        closeRename()
        setActionError(null)
        setFocusIntent(next)
        return
      }
      if (current.kind === 'session') {
        const renamed = await harnessService.renameSession(current.id, { name: trimmed })
        if (renameTargetRef.current !== current) {
          return
        }
        patchSessionNameCache(current.id, renamed.name)
      } else {
        const renamed = await harnessService.renameThread(current.id, { name: trimmed })
        if (renameTargetRef.current !== current) {
          return
        }
        patchThreadNameCache(current.id, renamed)
      }
      await invalidateAfterRename(current)
      renamePendingRef.current = false
      closeRename()
    } catch (error) {
      if (renameTargetRef.current !== current) {
        return
      }
      renamePendingRef.current = false
      setRenameError(errorMessage(error, t('ai.runtime.rename.failed')))
      setRenamePending(false)
    }
  }

  /**
   * 更新已加载缓存中的 Session 名称（owner session summaries 分页缓存）。保持简单：
   * 直接 setQueryData 到已存在的 key，随后 invalidateAfterRename 做必要失效。
   */
  function patchSessionNameCache(sessionId: string, name: string): void {
    if (!ownerKey) {
      return
    }
    for (const [key, data] of queryClient.getQueriesData<InfiniteData<SessionSummaryPage>>({
      queryKey: ['agent-pane', 'sessions', ownerKey],
    })) {
      if (data == null) {
        continue
      }
      queryClient.setQueryData<InfiniteData<SessionSummaryPage>>(key, {
        ...data,
        pages: data.pages.map((page) => ({
          ...page,
          items: page.items.map((item) => (
            item.sessionId === sessionId ? { ...item, name } : item
          )),
        })),
      })
    }
  }

  /** 重命名成功后失效相应查询：Session 摘要、Thread 投影与 Chat 列表。 */
  async function invalidateAfterRename(renamed: RenameTarget): Promise<void> {
    await Promise.all([
      ...(ownerKey
        ? [
            queryClient.invalidateQueries({
              queryKey: ['agent-pane', 'sessions', ownerKey],
            }),
          ]
        : []),
      ...(renamed.kind === 'thread'
        ? [
            queryClient.invalidateQueries({
              queryKey: ['agent-pane', 'threads'],
            }),
            queryClient.invalidateQueries({
              queryKey: queryKeys.threads.snapshot(renamed.id),
            }),
          ]
        : []),
      queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
    ])
  }

  /**
   * 把 Thread 重命名成功响应 patch 进已加载的 snapshot/thread summary 缓存：
   * snapshot cache 的 thread 本体直接采纳权威响应（含规范化的 name）；thread
   * summary 只更新 name 字段。之后 invalidateAfterRename 会按需重新拉取权威数据。
   */
  function patchThreadNameCache(threadId: string, thread: HarnessThreadDTO): void {
    const snapshotKey = queryKeys.threads.snapshot(threadId)
    const snapshot = queryClient.getQueryData<HarnessThreadSnapshotDTO>(snapshotKey)
    if (
      snapshot != null
      && snapshot.thread.threadId === threadId
      && compareCatalogVersions(snapshot.version, thread.version) <= 0
    ) {
      queryClient.setQueryData<HarnessThreadSnapshotDTO>(
        snapshotKey,
        { ...snapshot, thread, version: thread.version, baseEntryId: null },
      )
    }
    for (const [key, data] of queryClient.getQueriesData<HarnessThreadSummaryDTO[]>({
      queryKey: ['agent-pane', 'threads'],
    })) {
      if (data == null) {
        continue
      }
      queryClient.setQueryData<HarnessThreadSummaryDTO[]>(
        key,
        data.map((item) => item.threadId === threadId ? { ...item, name: thread.name } : item),
      )
    }
  }

  return {
    renameTarget,
    renamePending,
    renameError,
    setRenameError,
    renameBusy: renameTarget != null
      && renameTarget.kind === 'session'
      && renameTarget.name == null
      && renameError == null,
    openRename,
    openRenameForTarget,
    closeRename,
    submitRename,
  }
}
