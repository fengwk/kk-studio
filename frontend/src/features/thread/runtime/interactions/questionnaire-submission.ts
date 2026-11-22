import type { QueryClient } from '@tanstack/react-query'
import type { ApplicationEventManager } from '@/shared/app-events/manager'
import { isConflictError } from '@/shared/api/client'
import { interactionService } from '@/shared/api/interaction-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { requestThreadSnapshotRefresh } from '@/features/thread/runtime/state/thread-snapshot-cache'
import { interactionDraftStore } from './interaction-draft-store'
import { reconcileInteractionConflict } from './interaction-conflict'
import type { HarnessToolInputResultDTO } from '@/shared/api/generated/contracts'

/**
 * 问卷提交：先冻结 submissionId（精确重试身份），结果只通过草稿 store 与 query 失效发布。
 *
 * 409 先回读最新待处理事实：事项已不在待处理列表就清理草稿并交给宿主移除；仍在待处理就解除
 * 锁定并作废被拒的提交身份，等用户重新确认；只有回读无法判断时才保留只读围栏。其他错误
 * 保留同一 submissionId 供精确重试。
 */
export async function submitQuestionnaireAnswer({
  interactionId,
  threadId,
  submissionId,
  answers,
  declined,
  queryClient,
  applicationEvents,
  conflictErrorText,
  conflictStillPendingText,
  fallbackErrorText,
  onSuccess,
}: {
  interactionId: string
  threadId: string
  submissionId: string
  answers?: string[][]
  declined?: boolean
  queryClient: QueryClient
  applicationEvents: ApplicationEventManager
  /** 回读无法判断（409 且没有新待处理事实）时的只读围栏文案。 */
  conflictErrorText: string
  /** 回读证明事项仍待处理时的重新确认文案。 */
  conflictStillPendingText: string
  fallbackErrorText: string
  onSuccess?: (result?: HarnessToolInputResultDTO) => void
}): Promise<void> {
  interactionDraftStore.setSubmitting(interactionId, true)
  try {
    const res = await interactionService.submitToolInput(
      threadId,
      interactionId,
      declined
        ? { type: 'DECLINE', submissionId }
        : { type: 'ANSWER', submissionId, answers: answers ?? [] },
    )
    // 提交成功，清理草稿并刷新 queries
    interactionDraftStore.clearDraft(interactionId)
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all }),
      requestThreadSnapshotRefresh(queryClient, applicationEvents, threadId),
    ])
    onSuccess?.(res)
  } catch (err: unknown) {
    if (isConflictError(err)) {
      const outcome = await reconcileInteractionConflict({
        queryClient,
        applicationEvents,
        threadId,
        toolInvocationId: interactionId,
      })
      if (outcome === 'resolved') {
        // 回读确认已由其他端处理：清理草稿并让宿主从待处理列表移除。
        interactionDraftStore.clearDraft(interactionId)
        onSuccess?.()
        return
      }
      if (outcome === 'pending') {
        interactionDraftStore.markConflictStillPending(interactionId, conflictStillPendingText)
        return
      }
      // 回读无法判断：保留草稿作查看，禁止过期提交。
      interactionDraftStore.setSubmissionError(interactionId, conflictErrorText, true)
      return
    }
    // 普通网络/服务错误：允许使用同一 submissionId 精确重试
    const msg = err instanceof Error ? err.message : fallbackErrorText
    interactionDraftStore.setSubmissionError(interactionId, msg, false)
  }
}

/** 单选点击：只保留本次标签，再次点击已选项不会取消；单选清空自定义输入。 */
export function toggleSingleOrMultiOption(
  previous: { selectedLabels: string[]; customText: string },
  label: string,
  multiple: boolean,
): { selectedLabels: string[]; customText: string } {
  if (!multiple) {
    return { selectedLabels: [label], customText: '' }
  }
  const exists = previous.selectedLabels.includes(label)
  const nextLabels = exists
    ? previous.selectedLabels.filter((item) => item !== label)
    : [...previous.selectedLabels, label]
  return { ...previous, selectedLabels: nextLabels }
}

/** 自定义输入：单选输入时清空预置选项选择；多选只更新文本。 */
export function applyCustomText(
  previous: { selectedLabels: string[]; customText: string },
  text: string,
  multiple: boolean,
): { selectedLabels: string[]; customText: string } {
  if (!multiple) {
    return { selectedLabels: [], customText: text }
  }
  return { ...previous, customText: text }
}
