import type { CanvasRevision } from '@/shared/api/contracts/base'

/**
 * 比较两个 canonical 非负十进制字符串版本/修订号：先比长度、同长再按字典序，
 * 完全避免 JS number 精度问题（支持任意长度，含超过 Number.MAX_SAFE_INTEGER）。
 * 调用方必须保证输入是 canonical 字符串：HTTP 响应由生成契约约束，应用事件由协议解码保证。
 */
export function compareCanvasRevisions(left: CanvasRevision, right: CanvasRevision): number {
  if (left.length !== right.length) {
    return left.length < right.length ? -1 : 1
  }
  if (left === right) {
    return 0
  }
  return left < right ? -1 : 1
}
