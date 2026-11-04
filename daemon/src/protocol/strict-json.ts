import { visit, type JSONPath } from 'jsonc-parser'

/** 可选的结构上限；未给出的维度不收紧。 */
export interface StrictJsonLimits {
  maxNestingDepth?: number
  maxNumberLength?: number
}

/** 扫描到的第一个结构违规；调用方决定错误类型与文案。 */
export type StrictJsonViolation =
  | { kind: 'duplicate'; key: string }
  | { kind: 'nesting'; limit: number }
  | { kind: 'numberLength'; limit: number }
  | { kind: 'syntax' }

/** 精确计算 UTF-8 字节数；拒绝未配对代理项，避免静默替换后突破协议边界。 */
export function utf8ByteLength(
  value: string,
  name: string,
  makeError: (message: string) => Error = (message) => new Error(message),
): number {
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
        throw makeError(`${name} must be valid Unicode`)
      }
      bytes += 4
      index++
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw makeError(`${name} must be valid Unicode`)
    } else {
      bytes += 3
    }
  }
  return bytes
}

/**
 * 用 jsonc-parser 扫描 JSON 文本的重复键、嵌套深度、数字长度与语法错误。
 *
 * <p>JSON.parse 仍负责最终值解析；这里只补齐 JSON.parse 默认吞重复键且不限制结构规模的缺口。
 */
export function scanStrictJson(text: string, limits: StrictJsonLimits = {}): StrictJsonViolation | null {
  const seen = new Map<string, Set<string>>()
  let violation: StrictJsonViolation | null = null
  const pathKey = (path: JSONPath): string => JSON.stringify(path)
  const checkDepth = (pathSupplier: () => JSONPath): void => {
    if (
      violation === null &&
      limits.maxNestingDepth !== undefined &&
      pathSupplier().length + 1 > limits.maxNestingDepth
    ) {
      violation = { kind: 'nesting', limit: limits.maxNestingDepth }
    }
  }
  visit(
    text,
    {
      onObjectBegin: (_offset, _length, _line, _character, pathSupplier) => {
        checkDepth(pathSupplier)
      },
      onArrayBegin: (_offset, _length, _line, _character, pathSupplier) => {
        checkDepth(pathSupplier)
      },
      onObjectProperty: (property, _offset, _length, _line, _character, pathSupplier) => {
        if (violation !== null) {
          return
        }
        const currentPath = pathKey(pathSupplier())
        let properties = seen.get(currentPath)
        if (properties === undefined) {
          properties = new Set<string>()
          seen.set(currentPath, properties)
        }
        if (properties.has(property)) {
          violation = { kind: 'duplicate', key: property }
          return
        }
        properties.add(property)
      },
      onLiteralValue: (value, _offset, length) => {
        if (
          violation === null &&
          typeof value === 'number' &&
          limits.maxNumberLength !== undefined &&
          length > limits.maxNumberLength
        ) {
          violation = { kind: 'numberLength', limit: limits.maxNumberLength }
        }
      },
      onError: () => {
        violation ??= { kind: 'syntax' }
      },
    },
    { disallowComments: true, allowTrailingComma: false, allowEmptyContent: false },
  )
  return violation
}
