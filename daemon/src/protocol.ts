import { parseStrictJson as strictParseJson, utf8ByteLength as strictUtf8ByteLength } from './protocol/strict-json.js'

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

/** 严格解析 Daemon 配置文本：字节、嵌套深度与数字长度上限由共享扫描统一判定。 */
export function parseStrictJson(text: unknown, name: string, limits: StrictLimits): unknown {
  return strictParseJson(text, name, {
    ...limits,
    makeError: (message) => new ProtocolError(message),
  })
}
