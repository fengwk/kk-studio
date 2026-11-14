import type { QueryClient } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import { interactionService } from '@/shared/api/interaction-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { interactionDraftStore } from './interaction-draft-store'
import type { HarnessToolInputResultDTO } from '@/shared/api/generated/contracts'

/**
 * 问卷提交：先冻结 submissionId（精确重试身份），结果只通过草稿 store 与 query 失效发布。
 *
 * 409 视为非可恢复冲突（保留草稿供查看，禁止过期提交）；其他错误保留同一
 * submissionId 供精确重试。
 */
export async function submitQuestionnaireAnswer({
  interactionId,
  threadId,
  submissionId,
  answers,
  declined,
  queryClient,
  conflictErrorText,
  fallbackErrorText,
  onSuccess,
}: {
  interactionId: string
  threadId: string
  submissionId: string
  answers?: string[][]
  declined?: boolean
  queryClient: QueryClient
  /** 409 冲突时展示的非可恢复文案；与普通错误文案不同。 */
  conflictErrorText: string
  fallbackErrorText: string
  onSuccess?: (result: HarnessToolInputResultDTO) => void
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
      queryClient.invalidateQueries({
        queryKey: queryKeys.threads.snapshot(threadId),
      }),
    ])
    onSuccess?.(res)
  } catch (err: unknown) {
    if (isConflictError(err)) {
      // 409 冲突：保留草稿作查看，禁止过期提交
      interactionDraftStore.setSubmissionError(
        interactionId,
        conflictErrorText,
        true,
      )
      return
    }
    // 普通网络/服务错误：允许使用同一 submissionId 精确重试
    const msg = err instanceof Error ? err.message : fallbackErrorText
    interactionDraftStore.setSubmissionError(interactionId, msg, false)
  }
}

/** 单选点击：再次点击已选标签则取消；单选清空自定义输入。 */
export function toggleSingleOrMultiOption(
  previous: { selectedLabels: string[]; customText: string },
  label: string,
  multiple: boolean,
): { selectedLabels: string[]; customText: string } {
  if (!multiple) {
    return {
      selectedLabels: previous.selectedLabels[0] === label ? [] : [label],
      customText: '',
    }
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
