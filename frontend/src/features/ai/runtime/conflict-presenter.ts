import { ApiError, isConflictError } from '@/shared/api/client'
import { translate } from '@/shared/i18n'

export interface ConflictPresentation {
  reason: string
  detail: string
}

export function presentConflict(error: unknown): ConflictPresentation | null {
  if (!isConflictError(error)) {
    return null
  }
  const reason = error instanceof ApiError && typeof error.errors?.reason === 'string'
    ? error.errors.reason
    : 'CONFLICT'
  const detail = error instanceof ApiError && typeof error.errors?.detail === 'string'
    ? error.errors.detail
    : error instanceof Error ? error.message : translate('shared.requestFailed')
  return { reason, detail }
}
