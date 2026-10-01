import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { setLocale, translate } from '@/shared/i18n'
import {
  formatPreviewErrorMessage,
  isKnownPreviewErrorReason,
  PREVIEW_ERROR_REASONS,
} from './preview-reasons'

describe('safe preview error messages', () => {
  // 测试意图：中英文都只输出九种稳定 reason 的本地文案，忽略服务端自由文本。
  for (const locale of ['zh-CN', 'en-US'] as const) {
    it.each(PREVIEW_ERROR_REASONS)(`${locale}: localizes %s without exposing details`, (reason) => {
      setLocale(locale)
      const error = new ApiError('untrusted message', 409, 'CONFLICT', {
        reason,
        detail: 'https://untrusted.invalid/private',
      })
      const text = formatPreviewErrorMessage(error, translate)
      expect(text).toBe(translate(`ai.runtime.debug.previewError.${reason}`))
      expect(text).not.toContain('ai.runtime.')
      expect(text).not.toMatch(/untrusted|https?:\/\//)
      expect(isKnownPreviewErrorReason(reason)).toBe(true)
    })

    it.each([undefined, null, 1, {}, 'UNKNOWN', 'PREVIEW_THREAD_BUSY '])(
      `${locale}: rejects unknown reason %j`,
      (reason) => {
        setLocale(locale)
        const error = new ApiError('untrusted message', 409, undefined, {
          reason,
          detail: 'https://untrusted.invalid/private',
        })
        expect(isKnownPreviewErrorReason(reason)).toBe(false)
        const text = formatPreviewErrorMessage(error, translate)
        expect(text).toBe(translate('ai.runtime.debug.previewError.UNKNOWN_409'))
        expect(text).not.toMatch(/untrusted|https?:\/\//)
      },
    )

    it(`${locale}: never exposes other transport or JavaScript errors`, () => {
      setLocale(locale)
      for (const error of [
        new ApiError('untrusted message', 409),
        new ApiError('untrusted message', 500),
        new Error('https://untrusted.invalid/private'),
        'untrusted message',
        null,
      ]) {
        const expected = error instanceof ApiError && error.status === 409
          ? 'ai.runtime.debug.previewError.UNKNOWN_409'
          : 'ai.runtime.debug.previewFailed'
        expect(formatPreviewErrorMessage(error, translate)).toBe(translate(expected))
      }
    })
  }
})
