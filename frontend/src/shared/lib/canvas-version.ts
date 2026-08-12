import type { CanvasVersion } from '@/shared/api/contracts/base'

/** canonical 非负十进制字符串：'0' 或非零开头，无前导零、无符号、无空白。 */
const CANONICAL_DECIMAL = /^(0|[1-9][0-9]*)$/

export function isCanvasVersion(value: unknown): value is CanvasVersion {
  return typeof value === 'string' && CANONICAL_DECIMAL.test(value)
}

/**
 * 比较两个 canonical 非负十进制字符串版本：先比长度、同长再按字典序，
 * 完全避免 JS number 精度问题（支持任意长度，含超过 Number.MAX_SAFE_INTEGER）。
 * 调用方必须保证输入是 canonical 字符串（API/SSE 边界已严格校验）。
 */
export function compareCanvasVersions(left: CanvasVersion, right: CanvasVersion): number {
  if (left.length !== right.length) {
    return left.length < right.length ? -1 : 1
  }
  if (left === right) {
    return 0
  }
  return left < right ? -1 : 1
}
