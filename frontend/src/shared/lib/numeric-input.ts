/** 规范化整数输入：仅保留数字。 */
export function sanitizeIntegerInput(raw: string): string {
  return raw.replace(/[^\d]/g, '')
}

/**
 * 规范化小数输入：数字 + 至多一个小数点。
 * 允许中间态如 `1.`，便于连续输入。
 */
export function sanitizeDecimalInput(raw: string): string {
  const cleaned = raw.replace(/[^\d.]/g, '')
  const dot = cleaned.indexOf('.')
  if (dot < 0) {
    return cleaned
  }
  return `${cleaned.slice(0, dot + 1)}${cleaned.slice(dot + 1).replace(/\./g, '')}`
}
