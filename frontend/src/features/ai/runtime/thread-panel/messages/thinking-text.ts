/**
 * 规范化思考文本：仅去除末尾多余的空白与换行符，
 * 保留前导缩进与内部段落格式。
 */
export function normalizeThinkingText(raw: string): string {
  return raw.replace(/[\r\n\s]+$/, '')
}
