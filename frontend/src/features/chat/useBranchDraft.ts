import { useCallback, useEffect, useRef, useState } from 'react'
import { chatPaneTarget, focusPane, revealChatPane, type ChatPaneState } from '@/features/chat/chat-pane-state'
import type { BranchRequestInput, PaneReport } from '@/features/thread/runtime/root-thread/types'
import { samePaneTarget, type PaneTarget } from '@/features/thread/runtime/agent-pane'
import { hasThreadNameConflict } from '@/features/chat/thread-name-conflict'
import { harnessService } from '@/shared/api/harness-service'
import { useI18n } from '@/shared/i18n'

export interface BranchDraftController {
  request: BranchRequestInput | null
  sourcePaneId: string | null
  formError: string | null
  checking: boolean
  /** 目标 pane 有未发送草稿、需要用户再次确认覆盖时非 null。 */
  overwritePaneId: string | null
  handleRequestBranch: (sourcePaneId: string, request: BranchRequestInput) => void
  /** 命名流程的唯一提交点：先守在途操作与未发送草稿，再把草稿目标路由到目标 pane。 */
  handleBranchConfirm: (input: { name: string; paneId: string }) => Promise<void>
  handleBranchInputChange: () => void
  closeBranchDialog: () => void
  /** 目标 pane 名称校验：命名冲突与校验失败都返回可直接展示的文案。 */
  validateDraftName: (paneId: string, sessionId: string, name: string) => Promise<string | null>
}

export interface BranchDraftOptions {
  paneStateRef: React.RefObject<ChatPaneState>
  reportsRef: React.RefObject<Record<string, PaneReport>>
  pendingTargetsRef: React.RefObject<Record<string, PaneTarget>>
  commitPaneState: (update: (current: ChatPaneState) => ChatPaneState) => void
  setPendingTargets: React.Dispatch<React.SetStateAction<Record<string, PaneTarget>>>
}

/**
 * 新建 thread 分支的命名流程。
 *
 * 创建请求仍由目标 pane 在用户首次输入时原子提交，这里绝不预创建；这里只决定
 * NEW_THREAD_DRAFT 落到哪个 pane（隐藏布局先显露并聚焦），并把域名冲突交给调用方
 * 呈现。每次异步检查绑定自身的 requestId，期间任何输入变化都会作废在途检查。
 */
export function useBranchDraft({
  paneStateRef,
  reportsRef,
  pendingTargetsRef,
  commitPaneState,
  setPendingTargets,
}: BranchDraftOptions): BranchDraftController {
  const { t } = useI18n()
  const [branchRequest, setBranchRequest] = useState<BranchRequestInput | null>(null)
  const [branchSourcePaneId, setBranchSourcePaneId] = useState<string | null>(null)
  const [branchFormError, setBranchFormError] = useState<string | null>(null)
  const [overwritePaneId, setOverwritePaneId] = useState<string | null>(null)
  const [branchChecking, setBranchChecking] = useState(false)
  const branchCheckId = useRef(0)
  useEffect(() => () => { branchCheckId.current += 1 }, [])

  const validateDraftName = useCallback(async (paneId: string, sessionId: string, name: string) => {
    try {
      const threads = await harnessService.listSessionThreads(sessionId)
      const targets = {
        ...Object.fromEntries(paneStateRef.current.panes.map((pane) => [pane.id, pane.target])),
        ...pendingTargetsRef.current,
      }
      return hasThreadNameConflict(name, sessionId, paneId, threads, targets)
        ? t('ai.chat.branch.nameConflict') : null
    } catch {
      return t('ai.chat.branch.nameCheckFailed')
    }
  }, [paneStateRef, pendingTargetsRef, t])

  const handleRequestBranch = useCallback((sourcePaneId: string, request: BranchRequestInput) => {
    branchCheckId.current += 1
    setBranchChecking(false)
    setBranchRequest(request)
    setBranchSourcePaneId(sourcePaneId)
    setBranchFormError(null)
    setOverwritePaneId(null)
  }, [])

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
    const currentTarget = (paneId: string): PaneTarget =>
      chatPaneTarget(paneStateRef.current, paneId) ?? { kind: 'NEW_SESSION_DRAFT' }
    const sourceTarget = branchSourcePaneId == null ? null : currentTarget(branchSourcePaneId)
    const destinationTarget = currentTarget(input.paneId)
    const destinationDraftKey = report?.draftKey
    setBranchChecking(true)
    setBranchFormError(null)
    try {
      const nameError = await validateDraftName(input.paneId, branchRequest.sessionId, input.name)
      if (requestId !== branchCheckId.current) {
        return
      }
      if ((sourceTarget != null && branchSourcePaneId != null
          && !samePaneTarget(sourceTarget, currentTarget(branchSourcePaneId)))
        || !samePaneTarget(destinationTarget, currentTarget(input.paneId))
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
      commitPaneState((current) => focusPane(revealChatPane(current, input.paneId), input.paneId))
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
  }, [
    branchChecking,
    branchRequest,
    branchSourcePaneId,
    commitPaneState,
    overwritePaneId,
    paneStateRef,
    reportsRef,
    setPendingTargets,
    t,
    validateDraftName,
  ])

  const closeBranchDialog = useCallback(() => {
    branchCheckId.current += 1
    setBranchChecking(false)
    setBranchRequest(null)
    setBranchSourcePaneId(null)
    setBranchFormError(null)
    setOverwritePaneId(null)
  }, [])

  const handleBranchInputChange = useCallback(() => {
    branchCheckId.current += 1
    setBranchChecking(false)
    setBranchFormError(null)
    setOverwritePaneId(null)
  }, [])

  return {
    request: branchRequest,
    sourcePaneId: branchSourcePaneId,
    formError: branchFormError,
    checking: branchChecking,
    overwritePaneId,
    handleRequestBranch,
    handleBranchConfirm,
    handleBranchInputChange,
    closeBranchDialog,
    validateDraftName,
  }
}
