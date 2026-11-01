import { visit as jsoncVisit, type JSONPath } from 'jsonc-parser'

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
  maxStringBytes: number
}

/** 精确计算 UTF-8 字节数；拒绝未配对代理项，避免静默替换后突破协议边界。 */
export function utf8ByteLength(value: string, name: string): number {
  let bytes = 0
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code < 0x80) {
      bytes += 1
    } else if (code < 0x800) {
      bytes += 2
    } else if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1)
      if (!(next >= 0xdc00 && next <= 0xdfff)) {
        throw new ProtocolError(`${name} must be valid Unicode`)
      }
      bytes += 4
      index++
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw new ProtocolError(`${name} must be valid Unicode`)
    } else {
      bytes += 3
    }
  }
  return bytes
}

/**
 * 严格解析一段 JSON 文本为值。
 *
 * <p>JSON.parse 保证语法正确且无尾随内容；jsonc-parser visit 额外检测重复对象键并施加嵌套深度、数字长度、
 * 字符串字节与整体字节上限，弥补 JSON.parse 默认吞重复键的缺口。
 */
export function parseStrictJson(text: unknown, name: string, limits: StrictLimits): unknown {
  if (typeof text !== 'string') {
    throw new ProtocolError(`${name} must be a string`)
  }
  if (utf8ByteLength(text, name) > limits.maxBytes) {
    throw new ProtocolError(`${name} exceeds ${limits.maxBytes} UTF-8 bytes`)
  }
  const duplicate = detectViolations(text, limits)
  if (duplicate !== null) {
    throw new ProtocolError(`${name} ${duplicate}`)
  }
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new ProtocolError(`${name} must be valid JSON`)
  }
  return value
}

function detectViolations(text: string, limits: StrictLimits): string | null {
  const seen = new Map<string, Set<string>>()
  let violation: string | null = null
  const pathKey = (path: JSONPath): string => JSON.stringify(path)
  jsoncVisit(
    text,
    {
      onObjectBegin: (_offset, _length, _line, _character, pathSupplier) => {
        if (violation === null && pathSupplier().length + 1 > limits.maxNestingDepth) {
          violation = `exceeds maximum nesting depth ${limits.maxNestingDepth}`
        }
      },
      onArrayBegin: (_offset, _length, _line, _character, pathSupplier) => {
        if (violation === null && pathSupplier().length + 1 > limits.maxNestingDepth) {
          violation = `exceeds maximum nesting depth ${limits.maxNestingDepth}`
        }
      },
      onObjectProperty: (property, _offset, _length, _line, _character, pathSupplier) => {
        if (violation !== null) {
          return
        }
        const currentPath = pathSupplier()
        const key = pathKey(currentPath)
        let properties = seen.get(key)
        if (properties === undefined) {
          properties = new Set<string>()
          seen.set(key, properties)
        }
        if (properties.has(property)) {
          violation = `duplicate key "${property}"`
          return
        }
        properties.add(property)
      },
      onLiteralValue: (value, _offset, length) => {
        if (violation !== null) {
          return
        }
        if (typeof value === 'number' && length > limits.maxNumberLength) {
          violation = `number exceeds ${limits.maxNumberLength} characters`
        }
      },
    },
    { allowEmptyContent: false },
  )
  return violation
}
