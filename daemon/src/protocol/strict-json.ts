import { visit, type JSONPath } from 'jsonc-parser'

/** 可选的结构上限；未给出的维度不收紧。 */
export interface StrictJsonLimits {
  maxNestingDepth?: number
  maxNumberLength?: number
}

/** 严格解析选项：整体字节上限、空文本文案与错误类型由调用方决定。 */
export interface StrictJsonParseOptions extends StrictJsonLimits {
  maxBytes: number
  /** 空或纯空白文本报 “is required”，而不是交给 JSON.parse 报语法错误。 */
  requireContent?: boolean
  /** 调用方的错误类型；默认普通 Error。 */
  makeError?: (message: string) => Error
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

/**
 * 严格解析一段 JSON 文本。
 *
 * <p>JSON.parse 保证语法正确；共享扫描额外检测重复对象键并施加嵌套深度、数字长度与整体字节上限，弥补 JSON.parse 默认吞重复键的
 * 缺口。控制流与 Daemon 配置共用这一份实现，只由调用方决定错误类型与空文本文案。
 */
export function parseStrictJson(
  text: unknown,
  name: string,
  options: StrictJsonParseOptions,
): unknown {
  const makeError = options.makeError ?? ((message: string) => new Error(message))
  if (typeof text !== 'string') {
    throw makeError(`${name} must be a string`)
  }
  if (utf8ByteLength(text, name, makeError) > options.maxBytes) {
    throw makeError(`${name} exceeds ${options.maxBytes} UTF-8 bytes`)
  }
  if (options.requireContent === true && text.trim() === '') {
    throw makeError(`${name} is required`)
  }
  const violation = scanStrictJson(text, options)
  if (violation !== null) {
    throw makeError(`${name} ${strictJsonViolationMessage(violation)}`)
  }
  try {
    return JSON.parse(text)
  } catch {
    throw makeError(`${name} must be valid JSON`)
  }
}

/** 结构违规的统一文案；控制流把它直接追加在字段名之后对外报告。 */
export function strictJsonViolationMessage(violation: StrictJsonViolation): string {
  switch (violation.kind) {
    case 'duplicate':
      return `contains duplicate field: ${violation.key}`
    case 'nesting':
      return `exceeds maximum nesting depth ${violation.limit}`
    case 'numberLength':
      return `number exceeds ${violation.limit} characters`
    case 'syntax':
      return 'must be valid JSON'
  }
}
