import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import {
  mergeCancelledGoalTexts,
  prependCancelledMessages,
} from '@/features/chat/runtime/cancelled-message-parts'
import type { BoundPendingMessage } from '@/features/chat/runtime/agent-pane/pane-target'
import {
  applyStopReceipt,
  loadThreadDraft,
  restoreThreadDraftParts,
  restoreThreadGoalText,
  saveThreadDraftParts,
  saveThreadGoalText,
  type DraftSaveOutcome,
  type ThreadDraftRecord,
} from '@/features/chat/runtime/thread-draft-store'
import { hasMessageContent, partsKey, type ComposerPart } from '@/features/chat/composer/composer-parts'
import { translate, useI18n } from '@/shared/i18n'
import type { HarnessStoppedThreadReceiptDTO } from '@/shared/api/generated/contracts'

interface ThreadDraftRecordInput {
  threadId: string
  initialParts: ComposerPart[]
  boundThreadIdRef: MutableRefObject<string>
  bindingEpochRef: MutableRefObject<number>
  isStillBound: (originThreadId: string, originEpoch: number) => boolean
  stopReceipts: readonly HarnessStoppedThreadReceiptDTO[]
  setActionError: (message: string | null) => void
}

/**
 * 绑定 Thread 的编辑区草稿（composer 与 Goal 编辑区）及其持久记录。
 *
 * 记录是权威：整份字段写入携带本 UI 已观察到的 Stop 恢复 generation，存储层据此拒绝陈旧覆盖写；Stop 回执经响应与快照两个通道投递，按
 * (threadId, stopRequestId) 原子幂等合并，同一回执不会重复回填，也不回退用户随后的编辑。所有异步收尾都先过「原 Thread + epoch」栅栏，旧
 * Thread 的晚期完成绝不改动新 Thread 的编辑区。
 */
export function useThreadDraftRecord({
  threadId,
  initialParts,
  boundThreadIdRef,
  bindingEpochRef,
  isStillBound,
  stopReceipts,
  setActionError,
}: ThreadDraftRecordInput) {
  const { t } = useI18n()
  const [draft, setDraftState] = useState<ComposerPart[]>(() => initialParts)
  const draftRef = useRef<ComposerPart[]>(draft)
  const [goalDraft, setGoalDraftState] = useState<string | null>(null)
  const goalDraftRef = useRef<string | null>(null)
  // 每次用户编辑自增：回执合并完成时据此判断编辑区是否已被更新的输入取代。
  const draftRevisionRef = useRef(0)
  // 挂载时的初始草稿（面板当前一律传空）；重绑时用它清空，避免上一 Thread 的草稿泄漏。
  const initialPartsRef = useRef(initialParts)
  // 合并失败、尚未恢复的回执；仅由可见的手动重试再次投递，不做自动无限重试。
  const failedReceiptsRef = useRef<HarnessStoppedThreadReceiptDTO[]>([])
  // 本标签页已经写进编辑区的回执身份：同一回执经响应与快照两个通道再次投递时不重复追加、也不回退用户随后的编辑。
  const appliedReceiptKeysRef = useRef<Set<string>>(new Set())
  // 本 UI 已观察到的恢复 generation（记录中已合并的 Stop 回执），只由 loadThreadDraft 与 applyStopReceipt 返回的 record 更新。
  const observedStopRequestIdsRef = useRef<readonly string[]>([])
  // 每个字段各自的「最新写入 token + 是否未落盘」：旧 completion 既不得把新编辑标成已保存，也不得清掉尚未落盘的编辑。
  const partsWriteRef = useRef({ token: 0, dirty: false })
  const goalWriteRef = useRef({ token: 0, dirty: false })
  const attemptedReceiptSignatureRef = useRef<string | null>(null)
  const initializedThreadRef = useRef<string | null>(null)
  const [draftRestoreError, setDraftRestoreError] = useState<string | null>(null)

  useEffect(() => {
    if (initializedThreadRef.current === threadId) {
      return
    }
    initializedThreadRef.current = threadId

    // 重绑必须丢弃上一 Thread 的全部本地草稿状态：草稿、Goal 文本、编辑修订与回执重试记录。
    draftRef.current = initialPartsRef.current
    setDraftState(initialPartsRef.current)
    goalDraftRef.current = null
    setGoalDraftState(null)
    draftRevisionRef.current = 0
    failedReceiptsRef.current = []
    appliedReceiptKeysRef.current = new Set()
    observedStopRequestIdsRef.current = []
    partsWriteRef.current = { token: 0, dirty: false }
    goalWriteRef.current = { token: 0, dirty: false }
    attemptedReceiptSignatureRef.current = null
    setDraftRestoreError(null)

    // 记录尚未到达前保留初始值，且绝不覆盖用户已经开始编辑的内容。
    let active = true
    void loadThreadDraft(threadId)
      .then((record) => {
        if (!active || boundThreadIdRef.current !== threadId || record == null) {
          return
        }
        // 记录是权威：编辑区为空才采纳内容；用户先编辑过就保留用户输入。
        const adoptParts = !hasMessageContent(draftRef.current)
        const adoptGoal = (goalDraftRef.current ?? '').length === 0
        if (adoptParts) {
          draftRef.current = record.parts
          setDraftState(record.parts)
        }
        if (adoptGoal) {
          goalDraftRef.current = record.goalText
          setGoalDraftState(record.goalText)
        }
        // generation 只有真的采纳了记录内容（或记录里该字段本就没有内容可丢、内容已与编辑区一致）时才推进：
        // 只推进 generation 而不采纳内容，会让后续整份写入把已恢复文本永久覆盖掉。
        const partsAccounted = adoptParts
          || !hasMessageContent(record.parts)
          || partsKey(record.parts) === partsKey(draftRef.current)
        const goalAccounted = adoptGoal
          || (record.goalText ?? '').length === 0
          || record.goalText === goalDraftRef.current
        if (partsAccounted && goalAccounted) {
          observedStopRequestIdsRef.current = record.appliedStopRequestIds
          return
        }
        if (!partsAccounted) {
          partsWriteRef.current.dirty = true
        }
        if (!goalAccounted) {
          goalWriteRef.current.dirty = true
        }
        if (record.appliedStopRequestIds.length > 0) {
          // 有已恢复内容没被采纳：保留原 generation 并提示，等待既有回执通道合并进编辑区。
          setDraftRestoreError(translate('ai.runtime.action.draftWriteConflict'))
        }
      })
      .catch(() => {
        if (active && boundThreadIdRef.current === threadId) {
          // 存储不可用必须可见：编辑区当前内容未必是该 Thread 的最新草稿。
          setActionError(translate('ai.runtime.action.draftLoadFailed'))
        }
      })
    return () => {
      active = false
    }
  }, [boundThreadIdRef, setActionError, threadId])

  /** 编辑区是否存在未落盘的输入：存在时绝不拿记录覆盖编辑区。 */
  function hasUnsavedDraft(): boolean {
    return partsWriteRef.current.dirty || goalWriteRef.current.dirty
  }

  /**
   * 整份字段写入的公共收尾：完成回调先过 originThreadId + epoch 栅栏与字段内 token 栅栏（旧 completion 绝不把新编辑标成已保存），再决定收尾。
   */
  function persistDraftField(
    field: { token: number; dirty: boolean },
    save: () => Promise<DraftSaveOutcome>,
  ): void {
    const originThreadId = threadId
    const originEpoch = bindingEpochRef.current
    const token = field.token + 1
    field.token = token
    // 该字段最新一次写入的完成才代表编辑区内容已落盘；同链 FIFO 保证在途写入先于任何后续合并落盘。
    void save()
      .then((outcome) => {
        if (!isStillBound(originThreadId, originEpoch) || field.token !== token) {
          return
        }
        if (outcome.status === 'STALE') {
          // 被拒：编辑区内容确定不在记录里，标记未落盘。
          field.dirty = true
          handleDraftWriteConflict()
          return
        }
        field.dirty = false
      })
      .catch(() => {
        if (!isStillBound(originThreadId, originEpoch) || field.token !== token) {
          return
        }
        field.dirty = true
        setActionError(t('ai.runtime.action.draftSaveFailed'))
      })
  }

  function persistParts(parts: ComposerPart[]): void {
    persistDraftField(partsWriteRef.current, () =>
      saveThreadDraftParts(threadId, parts, observedStopRequestIdsRef.current))
  }

  function persistGoalText(goalText: string | null): void {
    persistDraftField(goalWriteRef.current, () =>
      saveThreadGoalText(threadId, goalText, observedStopRequestIdsRef.current))
  }

  /**
   * 草稿写入口。`edit` 落盘到该 Thread 的持久记录；`history`（上下键浏览）与 `restore`（Stop 回执已原子落盘）只更新内存。
   */
  function setDraft(next: ComposerPart[], source: 'edit' | 'history' | 'restore' = 'edit') {
    draftRevisionRef.current += 1
    if (source === 'edit') {
      persistParts(next)
    }
    draftRef.current = next
    setDraftState(next)
  }

  function setGoalDraft(next: string | null, source: 'edit' | 'restore' = 'edit') {
    draftRevisionRef.current += 1
    if (source === 'edit') {
      persistGoalText(next)
    }
    goalDraftRef.current = next
    setGoalDraftState(next)
  }

  /** 消息已冻结进未决请求：清空 composer 并落盘，不计作用户编辑。 */
  function clearSubmittedParts(): void {
    persistParts([])
    draftRef.current = []
    setDraftState([])
  }

  /**
   * Stop 或确定失败后把未消费的本地草稿恢复到对应编辑区。已有内容的编辑器绝不覆盖；非当前绑定的 Thread 只写它自己的持久记录。
   */
  async function restorePendingDraft(
    originThreadId: string,
    originEpoch: number,
    pending: BoundPendingMessage,
  ): Promise<void> {
    try {
      if (pending.kind === 'MESSAGE') {
        const restored = await restoreThreadDraftParts(originThreadId, pending.localDraft)
        // await 之后重新校验身份：原 Thread 的失败消息绝不回填进新 Thread 的编辑区。
        if (restored && isStillBound(originThreadId, originEpoch) && !hasMessageContent(draftRef.current)) {
          draftRef.current = pending.localDraft
          setDraftState(pending.localDraft)
        }
        return
      }
      const goalText = pending.goalText
      if (goalText == null) {
        return
      }
      const restored = await restoreThreadGoalText(originThreadId, goalText)
      if (restored && isStillBound(originThreadId, originEpoch) && (goalDraftRef.current ?? '').length === 0) {
        goalDraftRef.current = goalText
        setGoalDraftState(goalText)
      }
    } catch {
      if (isStillBound(originThreadId, originEpoch)) {
        // 未消费的输入没能回到编辑区：明确告知用户，而不是当作已恢复。
        setActionError(t('ai.runtime.action.draftRestoreFailed'))
      }
    }
  }

  /** 用权威记录对齐编辑区；attachment 不落盘，因此保留当前页面仍持有的附件。 */
  function applyRecordToEditor(record: ThreadDraftRecord): void {
    const attachments = draftRef.current.filter((part) => part.type === 'attachment')
    const parts = attachments.length === 0 ? record.parts : [...record.parts, ...attachments]
    if (partsKey(parts) !== partsKey(draftRef.current)) {
      draftRef.current = parts
      setDraftState(parts)
    }
    if (record.goalText !== goalDraftRef.current) {
      goalDraftRef.current = record.goalText
      setGoalDraftState(record.goalText)
    }
  }

  /**
   * 按 (threadId, stopRequestId) 原子幂等合并一批 Stop 回执。
   *
   * 只有当前绑定 Thread（且 epoch 未变）的编辑区会被更新；后代 Thread 的回执写它们各自的持久记录。合并失败绝不静默：原回执保留下来供手动重试，
   * 并明确区分「Stop 已完成」与「草稿未恢复」。返回是否全部合并成功。
   */
  async function applyStopReceipts(
    receipts: readonly HarnessStoppedThreadReceiptDTO[],
    boundThreadId: string,
    epoch: number,
  ): Promise<boolean> {
    const failed: HarnessStoppedThreadReceiptDTO[] = []
    for (const receipt of receipts) {
      const revisionBefore = draftRevisionRef.current
      const key = `${receipt.threadId}:${receipt.stopRequestId}`
      const appliedInThisTab = appliedReceiptKeysRef.current.has(key)
      try {
        const record = await applyStopReceipt(receipt)
        // await 之后重新校验绑定身份：旧 Thread 的合并结果绝不写进新 Thread 的编辑区。
        if (receipt.threadId !== boundThreadId
          || boundThreadIdRef.current !== boundThreadId
          || bindingEpochRef.current !== epoch) {
          continue
        }
        appliedReceiptKeysRef.current.add(key)
        // 恢复内容已进入记录：本 UI 由此观察到这条回执的 generation。
        observedStopRequestIdsRef.current = record.appliedStopRequestIds
        if (appliedInThisTab) {
          // 本标签页此前已把这条回执写进编辑区：再次投递既不重复追加，也不回退用户随后的编辑。
          continue
        }
        if (!hasUnsavedDraft() && draftRevisionRef.current === revisionBefore) {
          // 编辑区没有未落盘的输入：无论是本次事务合并还是别的标签页已合并，记录都是权威结果。
          applyRecordToEditor(record)
          partsWriteRef.current.dirty = false
          goalWriteRef.current.dirty = false
          continue
        }
        // 合并期间用户又编辑过：不拿迟到结果覆盖用户输入，而是把回执内容与当前输入合并后沿同一条链落盘。
        const cancelledInputs = receipt.cancelledInputs ?? []
        setDraft(prependCancelledMessages(cancelledInputs, draftRef.current))
        const mergedGoal = mergeCancelledGoalTexts(cancelledInputs, goalDraftRef.current)
        if (mergedGoal !== goalDraftRef.current) {
          setGoalDraft(mergedGoal)
        }
      } catch {
        failed.push(receipt)
      }
    }
    if (!isStillBound(boundThreadId, epoch)) {
      // 旧 Thread 晚到的回执收尾不得污染新 Thread 的重试状态与提示。
      return failed.length === 0
    }
    if (failed.length > 0) {
      // 未恢复的回执不视为已处理：保留原回执，明确提示，等待可见的手动重试。
      failedReceiptsRef.current = failed
      setDraftRestoreError(t('ai.runtime.action.draftRestoreFailed'))
      return false
    }
    failedReceiptsRef.current = []
    if (receipts.length > 0) {
      // 真正处理过回执才清除提示：没有可投递回执时保留提示，避免把未同步状态显示成已恢复。
      setDraftRestoreError(null)
    }
    return true
  }

  /**
   * 整份写入被存储层判定为陈旧（记录里已有本 UI 尚未观察到的恢复内容）时的收尾：不清空、不回退用户输入，明确提示，并通过已有的 Stop
   * 回执通道把恢复内容合并进编辑区。没有可用回执时保留提示等待快照带来回执。
   */
  function handleDraftWriteConflict(): void {
    setDraftRestoreError(t('ai.runtime.action.draftWriteConflict'))
    const unobserved = stopReceipts.filter(
      (receipt) => !observedStopRequestIdsRef.current.includes(receipt.stopRequestId),
    )
    if (unobserved.length === 0) {
      return
    }
    void applyStopReceipts(unobserved, threadId, bindingEpochRef.current)
  }

  /** 可见的手动重试：优先重投失败过的回执，否则用当前快照回执；不做自动无限重试。 */
  function retryDraftRestore(): void {
    const receipts = failedReceiptsRef.current.length > 0
      ? failedReceiptsRef.current
      : stopReceipts
    if (receipts.length === 0) {
      // 没有可重投的回执：保留提示，等待快照带来回执后再重试。
      return
    }
    void applyStopReceipts(receipts, threadId, bindingEpochRef.current)
  }

  // 快照通道补偿：刷新、多标签或 Stop 响应丢失后，用该 Thread 自己的持久回执恢复草稿。
  // 与 Stop 响应共用同一次原子幂等合并，因此同一回执不会重复回填。
  const stopReceiptSignature = stopReceipts
    .map((receipt) => `${receipt.threadId}:${receipt.stopRequestId}`)
    .join('|')
  useEffect(() => {
    if (!threadId || stopReceiptSignature.length === 0) {
      return
    }
    if (attemptedReceiptSignatureRef.current === stopReceiptSignature) {
      return
    }
    // 每个回执集合只自动尝试一次：失败不自动重试，改为保留原回执并提示用户手动重试。
    attemptedReceiptSignatureRef.current = stopReceiptSignature
    void applyStopReceipts(stopReceipts, threadId, bindingEpochRef.current)
    // 只按回执身份补偿；applyStopReceipts 每次渲染都是新引用。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stopReceiptSignature, threadId])

  return {
    draft,
    draftRef,
    goalDraft,
    goalDraftRef,
    draftRestoreError,
    setDraft,
    setGoalDraft,
    clearSubmittedParts,
    restorePendingDraft,
    applyStopReceipts,
    retryDraftRestore,
  }
}
