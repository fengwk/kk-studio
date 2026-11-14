import { useCallback } from 'react'
import { useI18n } from '@/shared/i18n'
import type { IntegerListValidationError } from '@/shared/lib/integer-list'

/**
 * 整数列表校验错误到可展示文案。
 *
 * <p>错误码连同 token 与边界一起进入文案，用户能从提示里直接看出哪个值为何被拒。
 */
export function useIntegerListErrorMessage() {
  const { t } = useI18n()
  return useCallback(
    (err: IntegerListValidationError): string => {
      switch (err.code) {
        case 'emptyToken':
          return t('controls.tagInput.emptyToken')
        case 'notInteger':
          return t('controls.tagInput.invalidToken', { token: String(err.token ?? '') })
        case 'outOfRange':
          return t('controls.tagInput.outOfRange', {
            token: String(err.token ?? ''),
            min: err.min != null ? err.min : '',
            max: err.max != null ? err.max : '',
          })
        case 'duplicate':
          return t('controls.tagInput.duplicate', { token: String(err.token ?? '') })
      }
    },
    [t],
  )
}
