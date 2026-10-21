import {
  MAX_PLUGIN_VALUES_UTF8_BYTES,
  parseStrictJsonContainer,
} from '@/features/extensions/plugin-config-json'

/** 外部 JSON 文件失败的封闭原因：超限、非严格 UTF-8、非法 JSON 语法、读取本身失败。 */
export type JsonFileReadFailure = 'too-large' | 'not-utf8' | 'invalid-json' | 'read-failed'

export type JsonFileReadResult =
  | { ok: true; text: string }
  | { ok: false; reason: JsonFileReadFailure; detail?: string }

/** 只需要字节大小与原始字节；浏览器 {@link File} 天然满足。 */
export interface JsonFileSource {
  readonly size: number
  arrayBuffer: () => Promise<ArrayBuffer>
}

/**
 * 读取本地 JSON 文件：先按普通配置的 UTF-8 字节预算拒绝超限文件，再用 fatal UTF-8 解码拒绝非法字节序列，
 * 最后按严格 JSON 校验语法（拒绝注释、尾随逗号与重复键）。
 *
 * <p>任何失败都不返回文件内容，调用方因此只会在完全合格时改写配置；错误文本只说明规则与位置，不回显文件内容。
 */
export async function readStrictJsonFile(file: JsonFileSource): Promise<JsonFileReadResult> {
  if (file.size > MAX_PLUGIN_VALUES_UTF8_BYTES) {
    return { ok: false, reason: 'too-large' }
  }

  let bytes: Uint8Array
  try {
    bytes = new Uint8Array(await file.arrayBuffer())
  } catch {
    return { ok: false, reason: 'read-failed' }
  }
  // 大小声明不可信：解码前后都以同一预算为门禁。
  if (bytes.byteLength > MAX_PLUGIN_VALUES_UTF8_BYTES) {
    return { ok: false, reason: 'too-large' }
  }

  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return { ok: false, reason: 'not-utf8' }
  }

  const strict = parseStrictJsonContainer(text)
  if (!strict.ok) {
    return { ok: false, reason: 'invalid-json', detail: strict.error }
  }

  return { ok: true, text }
}
