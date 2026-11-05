import {
  scanStrictJson,
  utf8ByteLength as strictUtf8ByteLength,
  type StrictJsonViolation,
} from './protocol/strict-json.js'

/** 不符合 Daemon wire 协议或配置的消息。 */
export class ProtocolError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ProtocolError'
  }
}

export interface StrictLimits {
  maxBytes: number
  maxNestingDepth: number
  maxNumberLength: number
}

/** 精确计算 UTF-8 字节数；拒绝未配对代理项，避免静默替换后突破协议边界。 */
export function utf8ByteLength(value: string, name: string): number {
  return strictUtf8ByteLength(value, name, (message) => new ProtocolError(message))
}

/**
 * 严格解析一段 JSON 文本为值。
 *
 * <p>JSON.parse 保证语法正确且无尾随内容；共享扫描额外检测重复对象键并施加嵌套深度、数字长度与整体字节上限，
 * 弥补 JSON.parse 默认吞重复键的缺口。
 */
export function parseStrictJson(text: unknown, name: string, limits: StrictLimits): unknown {
  if (typeof text !== 'string') {
    throw new ProtocolError(`${name} must be a string`)
  }
  if (utf8ByteLength(text, name) > limits.maxBytes) {
    throw new ProtocolError(`${name} exceeds ${limits.maxBytes} UTF-8 bytes`)
  }
  const violation = scanStrictJson(text, limits)
  if (violation !== null) {
    throw new ProtocolError(`${name} ${violationMessage(violation)}`)
  }
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new ProtocolError(`${name} must be valid JSON`)
  }
  return value
}

function violationMessage(violation: StrictJsonViolation): string {
  switch (violation.kind) {
    case 'duplicate':
      return `duplicate key "${violation.key}"`
    case 'nesting':
      return `exceeds maximum nesting depth ${violation.limit}`
    case 'numberLength':
      return `number exceeds ${violation.limit} characters`
    case 'syntax':
      return 'must be valid JSON'
  }
}
