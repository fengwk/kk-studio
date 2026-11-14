import { ApiError } from '@/shared/api/client'

/** PUT 的字段级校验错误：只取字符串值，其余形状忽略。 */
export function apiFieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError) || error.errors == null) {
    return {}
  }
  return Object.fromEntries(
    Object.entries(error.errors).filter((entry): entry is [string, string] => typeof entry[1] === 'string'),
  )
}

/** 保存失败提示优先用错误自身文案，缺失时回落到本地化通用文案。 */
export function toSaveErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}
