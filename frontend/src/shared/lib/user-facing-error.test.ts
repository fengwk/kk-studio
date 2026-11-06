import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { errorDetailMessage } from '@/shared/lib/user-facing-error'

describe('errorDetailMessage', () => {
  // 测试意图：后端去敏 detail 是最可信的失败原因，优先于通用 message。
  it('prefers the sanitized api detail over the generic message', () => {
    expect(
      errorDetailMessage(
        new ApiError('Request failed', 400, 'validation', undefined, undefined, 'No such file: a.txt'),
      ),
    ).toBe('No such file: a.txt')
  })

  // 测试意图：没有 detail 时依次退回 ApiError/Error 的 message、字符串错误，最后才是 null。
  it('falls back to error messages, strings and finally null', () => {
    expect(errorDetailMessage(new ApiError('Unavailable', 409))).toBe('Unavailable')
    expect(errorDetailMessage(new Error('network down'))).toBe('network down')
    expect(errorDetailMessage('  plain text  ')).toBe('  plain text  ')
    expect(errorDetailMessage(new Error('   '))).toBeNull()
    expect(errorDetailMessage('   ')).toBeNull()
    expect(errorDetailMessage(null)).toBeNull()
    expect(errorDetailMessage(undefined)).toBeNull()
    expect(errorDetailMessage(42)).toBeNull()
  })
})
