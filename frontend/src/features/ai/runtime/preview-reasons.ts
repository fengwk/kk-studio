import { ApiError } from '@/shared/api/client'

export const PREVIEW_ERROR_REASONS = [
  'PREVIEW_STALE_CURSOR',
  'PREVIEW_QUEUED_COMMANDS',
  'PREVIEW_THREAD_BUSY',
  'PREVIEW_COMPACTION_REQUIRED',
  'PREVIEW_ATTACHMENT_NOT_READY',
  'PREVIEW_PLANNING_FAILED',
  'PREVIEW_PROVIDER_UNAVAILABLE',
  'PREVIEW_UNSUPPORTED',
  'PREVIEW_ENCODING_FAILED',
] as const

export type PreviewErrorReason = (typeof PREVIEW_ERROR_REASONS)[number]

export function isKnownPreviewErrorReason(reason: unknown): reason is PreviewErrorReason {
  return typeof reason === 'string' && (PREVIEW_ERROR_REASONS as readonly string[]).includes(reason)
}

/**
 * 格式化预览失败错误消息。
 * 严格按照 9 种 reason 白名单输出本地化文本；
 * 未知 409 返回专用 fallback，严禁回显 errors.detail、raw provider error 或 URL。
 */
export function formatPreviewErrorMessage(error: unknown, t: (key: string) => string): string {
  if (error instanceof ApiError && error.status === 409) {
    const reason = error.errors?.reason
    if (isKnownPreviewErrorReason(reason)) {
      return t(`ai.runtime.debug.previewError.${reason}`)
    }
    return t('ai.runtime.debug.previewError.UNKNOWN_409')
  }
  return t('ai.runtime.debug.previewFailed')
}
