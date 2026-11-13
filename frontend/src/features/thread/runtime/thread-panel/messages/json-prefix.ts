/**
 * 宽容的 JSON 前缀解析：模型流式生成工具参数时，文本会停在任意位置，
 * 需要把「已经表达出来的部分」交给界面，而不是等参数完整。
 *
 * 完整 JSON 直接交给 `JSON.parse`；只有它拒绝时才按前缀递归解析：
 * - 未闭合的字符串返回已到达的文本，转义按 JSON 规则解码；末尾不完整的转义
 *   （含 `\uXXXX`）整体丢弃，避免把半个序列当正文；
 * - 未闭合的对象与数组返回已经解析出的部分，缺少值的键被省略；
 * - 一直延伸到文本末尾的数字与字面量还可能继续增长，一律省略。
 *
 * 这里不做业务字段判断：任何工具的参数都能用同一份解析。
 */

interface JsonPrefix {
  /** 文本本身是完整合法的 JSON。 */
  complete: boolean
  /** 前缀已经表达的值；顶层值不完整时为 undefined。 */
  value: unknown
}

/** 值在当前文本里还没有形成，且可能随后续字节变化。 */
const INCOMPLETE = Symbol('incomplete-json-prefix')

type PrefixValue = unknown | typeof INCOMPLETE

interface ParseState {
  text: string
  position: number
}

const LITERALS: ReadonlyArray<readonly [string, unknown]> = [
  ['true', true],
  ['false', false],
  ['null', null],
]

export function parseJsonPrefix(text: string): JsonPrefix {
  try {
    return { complete: true, value: JSON.parse(text) as unknown }
  } catch {
    // 流式文本尚未闭合或停在非法位置：退化为前缀解析。
  }
  const value = parseValue({ text, position: 0 })
  return { complete: false, value: value === INCOMPLETE ? undefined : value }
}

function parseValue(state: ParseState): PrefixValue {
  skipWhitespace(state)
  if (state.position >= state.text.length) {
    return INCOMPLETE
  }
  const char = state.text[state.position]
  if (char === '{') {
    return parseObject(state)
  }
  if (char === '[') {
    return parseArray(state)
  }
  if (char === '"') {
    return parseString(state).text
  }
  if (char === '-' || (char >= '0' && char <= '9')) {
    return parseNumber(state)
  }
  if (char === 't' || char === 'f' || char === 'n') {
    return parseLiteral(state)
  }
  return INCOMPLETE
}

function parseObject(state: ParseState): Record<string, unknown> {
  const text = state.text
  const value: Record<string, unknown> = {}
  state.position += 1
  let separatorRequired = false
  while (true) {
    skipWhitespace(state)
    if (state.position >= text.length) {
      return value
    }
    const char = text[state.position]
    if (char === '}') {
      state.position += 1
      return value
    }
    if (separatorRequired) {
      if (char !== ',') {
        return value
      }
      state.position += 1
      skipWhitespace(state)
      if (state.position >= text.length) {
        return value
      }
    }
    if (text[state.position] !== '"') {
      return value
    }
    const key = parseString(state)
    if (!key.closed) {
      return value
    }
    skipWhitespace(state)
    if (text[state.position] !== ':') {
      return value
    }
    state.position += 1
    const member = parseValue(state)
    if (member === INCOMPLETE) {
      return value
    }
    value[key.text] = member
    separatorRequired = true
  }
}

function parseArray(state: ParseState): unknown[] {
  const text = state.text
  const value: unknown[] = []
  state.position += 1
  let separatorRequired = false
  while (true) {
    skipWhitespace(state)
    if (state.position >= text.length) {
      return value
    }
    if (text[state.position] === ']') {
      state.position += 1
      return value
    }
    if (separatorRequired) {
      if (text[state.position] !== ',') {
        return value
      }
      state.position += 1
      skipWhitespace(state)
      if (state.position >= text.length) {
        return value
      }
    }
    const element = parseValue(state)
    if (element === INCOMPLETE) {
      return value
    }
    value.push(element)
    separatorRequired = true
  }
}

function parseString(state: ParseState): { text: string; closed: boolean } {
  const text = state.text
  let value = ''
  state.position += 1
  while (state.position < text.length) {
    const char = text[state.position]
    if (char === '"') {
      state.position += 1
      return { text: value, closed: true }
    }
    if (char !== '\\') {
      value += char
      state.position += 1
      continue
    }
    const escape = decodeEscape(text, state.position)
    if (escape == null) {
      // 文本在转义中途结束：丢弃这半个转义并停止读取。
      state.position = text.length
      return { text: value, closed: false }
    }
    value += escape.text
    state.position += escape.length
  }
  return { text: value, closed: false }
}

function decodeEscape(
  text: string,
  position: number,
): { text: string; length: number } | null {
  const escaped = text.charAt(position + 1)
  switch (escaped) {
    case '"':
      return { text: '"', length: 2 }
    case '\\':
      return { text: '\\', length: 2 }
    case '/':
      return { text: '/', length: 2 }
    case 'b':
      return { text: '\b', length: 2 }
    case 'f':
      return { text: '\f', length: 2 }
    case 'n':
      return { text: '\n', length: 2 }
    case 'r':
      return { text: '\r', length: 2 }
    case 't':
      return { text: '\t', length: 2 }
    case 'u': {
      const hex = text.slice(position + 2, position + 6)
      if (!/^[0-9a-fA-F]{4}$/.test(hex)) {
        return null
      }
      return { text: String.fromCharCode(Number.parseInt(hex, 16)), length: 6 }
    }
    case '':
      return null
    default:
      // 非法转义按原样保留：宁可显示多余字符，也不悄悄吞掉正文。
      return { text: escaped, length: 2 }
  }
}

function parseNumber(state: ParseState): PrefixValue {
  const text = state.text
  const start = state.position
  while (state.position < text.length && isNumberChar(text[state.position])) {
    state.position += 1
  }
  if (state.position === start || state.position >= text.length) {
    // 数字一直延伸到文本末尾，后续字节还可能把它改写成一个更长的数字。
    return INCOMPLETE
  }
  const value = Number(text.slice(start, state.position))
  return Number.isFinite(value) ? value : INCOMPLETE
}

function parseLiteral(state: ParseState): PrefixValue {
  const rest = state.text.slice(state.position)
  for (const [word, value] of LITERALS) {
    if (!rest.startsWith(word)) {
      continue
    }
    state.position += word.length
    // 字面量正好停在文本末尾时，无法确认它就是最终取值。
    return state.position >= state.text.length ? INCOMPLETE : value
  }
  return INCOMPLETE
}

function isNumberChar(char: string): boolean {
  return char === '-' || char === '+' || char === '.' || char === 'e' || char === 'E'
    || (char >= '0' && char <= '9')
}

function skipWhitespace(state: ParseState): void {
  const text = state.text
  while (state.position < text.length) {
    const char = text[state.position]
    if (char !== ' ' && char !== '\t' && char !== '\n' && char !== '\r') {
      return
    }
    state.position += 1
  }
}
