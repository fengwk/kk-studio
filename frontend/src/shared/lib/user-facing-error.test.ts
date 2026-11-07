import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { setLocale } from '@/shared/i18n'
import { errorDetailMessage, toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'

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

describe('toUserFacingErrorMessage', () => {
  // 测试意图：HTTP 失败按真实 ApiError 的 status 分类，两个 locale 都有固定文案，
  // 与后端标题文本无关。
  it.each([
    [
      new ApiError('Conflict', 409),
      '资源冲突，请刷新后重试',
      'Resource conflict. Refresh and try again.',
    ],
    [
      new ApiError('Unauthorized', 401),
      '没有权限执行此操作',
      'You do not have permission to perform this action.',
    ],
    [
      new ApiError('Internal Server Error', 500),
      '服务暂时异常，请稍后重试',
      'The service is temporarily unavailable. Please try again later.',
    ],
  ])('classifies %# by status in zh-CN and en-US', (error, zh, en) => {
    setLocale('zh-CN')
    expect(toUserFacingErrorMessage(error)).toBe(zh)
    setLocale('en-US')
    expect(toUserFacingErrorMessage(error)).toBe(en)
  })

  // 测试意图：字段校验失败没有状态码分类，改用后端去敏 detail 命中字段文案。
  it('uses the sanitized detail of a validation failure for field wording', () => {
    const validation = new ApiError(
      'Unprocessable Entity',
      422,
      'validation',
      undefined,
      undefined,
      'config.limit.context must be a positive integer',
    )
    setLocale('zh-CN')
    expect(toUserFacingErrorMessage(validation)).toBe('请填写有效的上下文窗口，必须为正整数')
    setLocale('en-US')
    expect(toUserFacingErrorMessage(validation)).toBe(
      'Enter a valid context window; it must be a positive integer',
    )
  })

  // 测试意图：传输层没有 status，用 client 生成的稳定 code 识别，标题文本不参与。
  it('classifies transport failures by code', () => {
    expect(
      toUserFacingErrorMessage(new ApiError('Failed to fetch', undefined, 'network_error')),
    ).toBe('网络异常，请稍后重试')
    expect(toUserFacingErrorMessage(new ApiError('Request timeout', undefined, 'timeout'))).toBe(
      '网络异常，请稍后重试',
    )
  })
})
