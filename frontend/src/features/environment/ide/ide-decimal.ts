/** wire 上的十进制整数：`revision` 允许 0，`bindingEpoch` 必须为正。 */
export const CANONICAL_NON_NEGATIVE_DECIMAL = /^(0|[1-9][0-9]*)$/
export const CANONICAL_POSITIVE_DECIMAL = /^[1-9][0-9]*$/

/** 任意十进制字符串比较；revision 允许 gap，不要求 +1。 */
export function compareDecimal(left: string, right: string): number {
  if (left.length !== right.length) {
    return left.length < right.length ? -1 : 1
  }
  return left < right ? -1 : left > right ? 1 : 0
}
