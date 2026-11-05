/**
 * 规范小写 UUID 的唯一文本形式。
 *
 * <p>控制流字段、IDE 桥请求与受控临时目录名都由 Java UUID.toString() 或 Node randomUUID() 产生，
 * 只接受 8-4-4-4-12 的小写十六进制；大写、缺段或多余分隔符一律拒绝。
 */
export const CANONICAL_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** 是否为规范小写 UUID 文本；非字符串一律为 false。 */
export function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && CANONICAL_UUID_PATTERN.test(value)
}
