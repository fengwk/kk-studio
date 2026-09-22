/**
 * RFC4122 v4 UUID：优先使用 `crypto.randomUUID`；不可用时用
 * `crypto.getRandomValues` 生成同样规范的 UUID（版本 4、变体 10xx）。
 * 所有本地生成的 part/command/stop/decision id 都必须是规范 UUID。
 */
export function createUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 规范 UUID 形状（含版本/变位校验）。 */
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

/**
 * Canonical UUID 校验：8-4-4-4-12 的小写/大写十六进制 dash 形态。
 * 只校验 shape（不限定 UUID version/variant 位）：客户端生成的实体 id
 * 来自 crypto.randomUUID()（v4），服务端生成的 id 可能使用其他版本。
 */
export const CANONICAL_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export type CanonicalUuidString = `${string}-${string}-${string}-${string}-${string}`

/** shape 校验通过后把字符串收窄为规范 UUID 字符串类型。 */
export function isCanonicalUuid<T extends string = CanonicalUuidString>(value: string): value is T {
  return CANONICAL_UUID_PATTERN.test(value)
}
