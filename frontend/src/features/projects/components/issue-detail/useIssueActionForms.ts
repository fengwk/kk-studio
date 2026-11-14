import { useEffect, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import type { IssueDetailDTO } from '@/shared/api/generated/contracts'
import type { IssueActionKind, PendingIssueAction } from '../../pending-action-sidecar'
import { MAX_RUNS, parseMaxRuns } from '../../workflow-draft'
import type { ProjectsApi } from '../../projects-api'
import { useKeyedRequest } from './useKeyedRequest'

type Issue = IssueDetailDTO['issue']
type ActivityKind = 'COMMENT' | 'INSTRUCTION'

/** 四个写操作表单共用的协作方：目标 issue、API、未决未知操作与统一执行器。 */
interface IssueActionFormContext {
  issue: Issue | undefined
  api: ProjectsApi
  pendingUnknownAction: PendingIssueAction | null
  setActionError: (message: string | null) => void
  executeIssueAction: (
    kind: IssueActionKind,
    requestKey: string,
    payload: PendingIssueAction['payload'],
    runApi: (expectedVersion: string) => Promise<unknown>,
    onExplicitSuccess: () => void,
  ) => Promise<void>
}

/**
 * 该表单是否被同类未决未知操作锁定：锁定期间只提示，不修改输入也不发送，
 * 避免同一 requestKey 被换成不同 payload。
 */
function isLockedByPendingAction(
  pendingUnknownAction: PendingIssueAction | null,
  kind: IssueActionKind,
): boolean {
  return pendingUnknownAction?.kind === kind
}

/**
 * 活动发布表单：COMMENT 只进时间线，INSTRUCTION 投递给活跃 Run。
 */
export function useActivityForm(context: IssueActionFormContext) {
  const { issue, api, pendingUnknownAction, setActionError, executeIssueAction } = context
  const { t } = useI18n()
  const [kind, setKind] = useState<ActivityKind>('COMMENT')
  const [body, setBody] = useState('')
  const request = useKeyedRequest<{ kind: ActivityKind; body: string }>()

  const reset = () => {
    setBody('')
    request.reset()
  }

  /** 未决同类操作期间拒绝修改；否则更新值，偏离冻结 payload 即换新请求身份。 */
  const guardedChange = (apply: () => void) => {
    if (isLockedByPendingAction(pendingUnknownAction, 'ACTIVITY')) {
      setActionError(t('projects.issue.pendingActivityModifyBlocked'))
      return
    }
    apply()
  }

  const onChangeKind = (next: ActivityKind) =>
    guardedChange(() => {
      setKind(next)
      request.invalidateIfChanged((frozen) => frozen.kind === next)
    })

  const onChangeBody = (next: string) =>
    guardedChange(() => {
      setBody(next)
      request.invalidateIfChanged((frozen) => frozen.body === next)
    })

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!issue) return
    const trimmed = body.trim()
    if (!trimmed) {
      setActionError(t('projects.issue.contentEmpty'))
      return
    }
    const requestKey = request.requestKey
    request.freeze({ kind, body: trimmed })
    await executeIssueAction(
      'ACTIVITY',
      requestKey,
      { kind, body: trimmed },
      (expectedVersion) => api.appendIssueActivity(issue.id, { expectedVersion, requestKey, kind, body: trimmed }),
      reset,
    )
  }

  return { kind, body, onChangeKind, onChangeBody, onSubmit, reset }
}

/** 阻塞原因表单：多行文本，空原因不允许提交。 */
export function useBlockForm(context: IssueActionFormContext) {
  const { issue, api, pendingUnknownAction, setActionError, executeIssueAction } = context
  const { t } = useI18n()
  const [isOpen, setIsOpen] = useState(false)
  const [reason, setReason] = useState('')
  const request = useKeyedRequest<{ reason: string }>()

  const reset = () => {
    setIsOpen(false)
    setReason('')
    request.reset()
  }

  const onChangeReason = (next: string) => {
    if (isLockedByPendingAction(pendingUnknownAction, 'BLOCK')) {
      setActionError(t('projects.issue.pendingBlockModifyBlocked'))
      return
    }
    setReason(next)
    request.invalidateIfChanged((frozen) => frozen.reason === next)
  }

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!issue) return
    const trimmed = reason.trim()
    if (!trimmed) {
      setActionError(t('projects.issue.blockReasonRequired'))
      return
    }
    const requestKey = request.requestKey
    request.freeze({ reason: trimmed })
    await executeIssueAction(
      'BLOCK',
      requestKey,
      { reason: trimmed },
      (expectedVersion) => api.blockIssue(issue.id, { expectedVersion, requestKey, reason: trimmed }),
      reset,
    )
  }

  return {
    isOpen,
    reason,
    open: () => setIsOpen(true),
    close: () => setIsOpen(false),
    onChangeReason,
    onSubmit,
    reset,
  }
}

/** 终止说明表单：说明可选，空说明提交为 null。 */
export function useStopForm(context: IssueActionFormContext) {
  const { issue, api, pendingUnknownAction, setActionError, executeIssueAction } = context
  const { t } = useI18n()
  const [isOpen, setIsOpen] = useState(false)
  const [detail, setDetail] = useState('')
  const request = useKeyedRequest<{ detail: string | null }>()

  const reset = () => {
    setIsOpen(false)
    setDetail('')
    request.reset()
  }

  const onChangeDetail = (next: string) => {
    if (isLockedByPendingAction(pendingUnknownAction, 'STOP')) {
      setActionError(t('projects.issue.pendingStopModifyBlocked'))
      return
    }
    setDetail(next)
    request.invalidateIfChanged((frozen) => frozen.detail === (next.trim() || null))
  }

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!issue) return
    const trimmedDetail = detail.trim() || null
    const requestKey = request.requestKey
    request.freeze({ detail: trimmedDetail })
    await executeIssueAction(
      'STOP',
      requestKey,
      { detail: trimmedDetail },
      (expectedVersion) => api.stopIssue(issue.id, { expectedVersion, requestKey, detail: trimmedDetail }),
      reset,
    )
  }

  return {
    isOpen,
    detail,
    open: () => setIsOpen(true),
    close: () => setIsOpen(false),
    onChangeDetail,
    onSubmit,
    reset,
  }
}

/** 阶段预算重置表单：阶段编码必填，额度必须是 1..MAX_RUNS 的规范整数。 */
export function useResetBudgetForm(context: IssueActionFormContext) {
  const { issue, api, pendingUnknownAction, setActionError, executeIssueAction } = context
  const { t } = useI18n()
  const [isOpen, setIsOpen] = useState(false)
  const [state, setState] = useState('')
  const [maxRuns, setMaxRuns] = useState(3)
  const request = useKeyedRequest<{ state: string; maxRuns: number }>()

  const reset = () => {
    setIsOpen(false)
    request.reset()
  }

  /** 打开时若无输入，用首个阶段预算作为默认值；已有输入不覆盖。 */
  const open = (defaultState: string | undefined) => {
    setIsOpen(true)
    if (!state && defaultState) {
      setState(defaultState)
    }
  }

  const onChangeState = (next: string) => {
    if (isLockedByPendingAction(pendingUnknownAction, 'RESET_BUDGET')) {
      setActionError(t('projects.issue.pendingResetBudgetModifyBlocked'))
      return
    }
    setState(next)
    request.invalidateIfChanged((frozen) => frozen.state === next)
  }

  const onChangeMaxRuns = (next: number) => {
    if (isLockedByPendingAction(pendingUnknownAction, 'RESET_BUDGET')) {
      setActionError(t('projects.issue.pendingResetBudgetModifyBlocked'))
      return
    }
    setMaxRuns(next)
    request.invalidateIfChanged((frozen) => frozen.maxRuns === next)
  }

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!issue) return
    if (!state) {
      setActionError(t('projects.issue.resetBudgetStateRequired'))
      return
    }
    const parsedMaxRuns = parseMaxRuns(String(maxRuns))
    if (parsedMaxRuns === null) {
      setActionError(t('projects.edit.maxRunsInvalid', { max: MAX_RUNS }))
      return
    }
    const requestKey = request.requestKey
    request.freeze({ state, maxRuns: parsedMaxRuns })
    await executeIssueAction(
      'RESET_BUDGET',
      requestKey,
      { state, maxRuns: parsedMaxRuns },
      (expectedVersion) => api.resetStageBudget(issue.id, { expectedVersion, requestKey, state, maxRuns: parsedMaxRuns }),
      reset,
    )
  }

  return {
    isOpen,
    state,
    maxRuns,
    open,
    close: () => setIsOpen(false),
    onChangeState,
    onChangeMaxRuns,
    onSubmit,
    reset,
  }
}

/**
 * Issue 写操作的表单集合：活动、阻塞、终止与阶段预算。
 *
 * <p>每个表单各自持有幂等请求身份与未决守卫；关闭弹窗时统一丢弃临时输入。
 * 精确重试成功后按操作类型重置对应表单，使其回到未提交状态。
 */
export function useIssueActionForms({
  isOpen,
  issue,
  api,
  pendingUnknownAction,
  setActionError,
  executeIssueAction,
}: IssueActionFormContext & { isOpen: boolean }) {
  const context: IssueActionFormContext = {
    issue,
    api,
    pendingUnknownAction,
    setActionError,
    executeIssueAction,
  }
  const activity = useActivityForm(context)
  const block = useBlockForm(context)
  const stop = useStopForm(context)
  const budget = useResetBudgetForm(context)

  /** 精确重试成功后按操作类型重置对应表单，使其回到未提交状态。 */
  const resetForRetriedKind = (kind: IssueActionKind) => {
    if (kind === 'ACTIVITY') {
      activity.reset()
    } else if (kind === 'BLOCK') {
      block.reset()
    } else if (kind === 'STOP') {
      stop.reset()
    } else if (kind === 'RESET_BUDGET') {
      budget.reset()
    }
  }

  // 只在开合时重置：关闭后丢弃全部临时输入与未提交的侧车身份。
  // reset 函数每次渲染都会重建，显式只为开合变化触发。
  const resetAllForms = () => {
    activity.reset()
    block.reset()
    stop.reset()
    budget.reset()
  }
  useEffect(() => {
    if (isOpen) {
      return
    }
    resetAllForms()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen])

  return { activity, block, stop, budget, resetForRetriedKind }
}
