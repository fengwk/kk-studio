/**
 * 分支（Thread）名称的客户端规范化与校验，与后端 `Names.normalize` 对齐：
 * 把任意 Unicode 空白折叠为单空格、去掉首尾空格，结果非空且至多 256 个码点。
 *
 * 名称是创建请求的事实之一（进入 creationRequestHash），因此前端只提交规范化后的
 * 唯一形态；无法规范化时返回 null，由调用方给出字段级错误，绝不静默截断或兜底。
 */
export const THREAD_NAME_MAX_CODE_POINTS = 256

export function normalizeThreadName(raw: unknown): string | null {
  if (typeof raw !== 'string') {
    return null
  }
  const collapsed = raw.replace(/\p{White_Space}+/gu, ' ').replace(/^ +| +$/g, '')
  if (collapsed.length === 0) {
    return null
  }
  if (Array.from(collapsed).length > THREAD_NAME_MAX_CODE_POINTS) {
    return null
  }
  return collapsed
}

/** 持久化/创建 target 中的名称必须是已规范化形态。 */
export function isCanonicalThreadName(value: unknown): value is string {
  return typeof value === 'string' && normalizeThreadName(value) === value
}
