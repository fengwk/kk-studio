import { ApiError, isConflictError } from '@/shared/api/client'
import { translate } from '@/shared/i18n'

export interface ConflictPresentation {
  reason: string
  detail: string
}

/** All HTTP 409 callers use the same reason/detail precedence and presentation contract. */
export function presentConflict(error: unknown): ConflictPresentation | null {
  if (!isConflictError(error)) {
    return null
  }
  const reason =
    error instanceof ApiError && typeof error.reason === 'string' && error.reason.length > 0
      ? error.reason
      : error instanceof ApiError && typeof error.code === 'string'
        ? error.code
        : 'CONFLICT'
  const detail =
    (error instanceof ApiError && typeof error.detail === 'string' && error.detail.length > 0
      ? error.detail
      : null)
    ?? (error instanceof Error ? error.message : translate('shared.requestFailed'))
  return { reason, detail }
}
