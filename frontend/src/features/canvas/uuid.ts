import type { UUIDString } from '@/shared/api/contracts/studio'

/**
 * Canonical UUID 校验：8-4-4-4-12 的小写/大写十六进制 dash 形态。
 * 只校验 shape（不限定 UUID version/variant 位）：客户端生成的实体 id
 * 来自 crypto.randomUUID()（v4），服务端生成的 id 可能使用其他版本。
 */
export const CANONICAL_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** shape 校验通过后把字符串收窄为 UUIDString（如 URL 参数、服务端 id）。 */
export function isCanonicalUuid(value: string): value is UUIDString {
  return CANONICAL_UUID_PATTERN.test(value)
}
