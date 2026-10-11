import { open, readFile } from 'node:fs/promises'
import { ToolRunFailureError } from './support.js'

/**
 * 文本读取与编码共享边界：前缀探测、严格解码、逐行扫描与有界读取窗口。
 *
 * <p>与 Java 参考实现（{@code TextStreams}/{@code TextReadWindow}/{@code TextFileCodec}）逐条对齐：LF、CRLF 与孤立 CR
 * 都是行边界；正文最多 {@link MAX_BODY_CODE_POINTS} 个 Unicode 码点；单行长度不截断；非法 UTF-8、NUL 与孤立代理项一律以明确的
 * “看似二进制”失败，绝不静默替换成替换字符。
 */

/** 缺省返回行数上限。 */
export const DEFAULT_READ_LIMIT = 2000
/** 最大返回行数上限。 */
export const MAX_READ_LIMIT = 2000
/** 整个窗口保留的正文码点上限。 */
export const MAX_BODY_CODE_POINTS = 60000
/** 编码与二进制探测读取的前缀字节数。 */
export const PROBE_BYTES = 8192
/** 逐行扫描的单行保留上限（字符数）。 */
export const MAX_LINE_CHARS = 1024 * 1024

const BINARY_MESSAGE = 'file appears to be binary'
const READ_BUFFER_CHARS = 8 * 1024

export type CharsetName = 'UTF-8' | 'UTF-16LE' | 'UTF-16BE'

export interface Encoding {
  charset: CharsetName
  bomLength: number
  utf16: boolean
}

/** 按前缀 BOM 判定编码；无 BOM 时为 UTF-8。 */
export function detectEncoding(probe: Uint8Array): Encoding {
  if (probe.length >= 3 && probe[0] === 0xef && probe[1] === 0xbb && probe[2] === 0xbf) {
    return { charset: 'UTF-8', bomLength: 3, utf16: false }
  }
  if (probe.length >= 2 && probe[0] === 0xff && probe[1] === 0xfe) {
    return { charset: 'UTF-16LE', bomLength: 2, utf16: true }
  }
  if (probe.length >= 2 && probe[0] === 0xfe && probe[1] === 0xff) {
    return { charset: 'UTF-16BE', bomLength: 2, utf16: true }
  }
  return { charset: 'UTF-8', bomLength: 0, utf16: false }
}

/** 控制字符（NUL、除制表/换行/回车/ESC 外的控制码）视为二进制内容；UTF-16 前缀不按此判定。 */
export function looksBinary(probe: Uint8Array, encoding: Encoding): boolean {
  if (encoding.utf16) {
    return false
  }
  const scan = Math.min(probe.length, PROBE_BYTES)
  for (let index = 0; index < scan; index++) {
    const value = probe[index] as number
    if (value === 0 || value < 0x09 || (value > 0x0d && value < 0x20 && value !== 0x1b)) {
      return true
    }
  }
  return false
}

/** 独立的二进制字节判定（用于 write/edit 的整段解码）。 */
export function isBinaryBytes(bytes: Uint8Array): boolean {
  const scan = Math.min(bytes.length, PROBE_BYTES)
  for (let index = 0; index < scan; index++) {
    const value = bytes[index] as number
    if (value === 0 || value < 0x09 || (value > 0x0d && value < 0x20 && value !== 0x1b)) {
      return true
    }
  }
  return false
}

/** 流式解码器：分块推入字节，返回已能完整解码的字符串，未完成的尾部字节留待下一块。 */
export interface StreamingDecoder {
  push(bytes: Uint8Array): string
  finish(): string
}

function malformed(): never {
  throw new ToolRunFailureError(`${BINARY_MESSAGE}: malformed text encoding`, 'CODEC')
}

function invalidEncoding(): never {
  throw new ToolRunFailureError(`${BINARY_MESSAGE}: invalid text encoding`, 'CODEC')
}

/** 严格 UTF-8 解码：非法序列、代理项编码与过短序列立即失败，绝不替换成 U+FFFD。 */
export function createUtf8Decoder(): StreamingDecoder {
  let pending: number[] = []
  return {
    push(bytes: Uint8Array): string {
      const data = pending.length === 0 ? Array.from(bytes) : pending.concat(Array.from(bytes))
      pending = []
      let index = 0
      let out = ''
      while (index < data.length) {
        const b0 = data[index] as number
        if (b0 < 0x80) {
          out += String.fromCharCode(b0)
          index += 1
          continue
        }
        let need: number
        let codePoint: number
        if (b0 >= 0xc2 && b0 <= 0xdf) {
          need = 1
          codePoint = b0 & 0x1f
        } else if (b0 >= 0xe0 && b0 <= 0xef) {
          need = 2
          codePoint = b0 & 0x0f
        } else if (b0 >= 0xf0 && b0 <= 0xf4) {
          need = 3
          codePoint = b0 & 0x07
        } else {
          invalidEncoding()
        }
        const available = data.length - index - 1
        for (let offset = 1; offset <= Math.min(need, available); offset++) {
          const continuation = data[index + offset] as number
          if ((continuation & 0xc0) !== 0x80) {
            malformed()
          }
        }
        if (available < need) {
          pending = data.slice(index)
          break
        }
        for (let offset = 1; offset <= need; offset++) {
          const continuation = data[index + offset] as number
          codePoint = (codePoint << 6) | (continuation & 0x3f)
        }
        if (!isValidCodePoint(codePoint)) {
          malformed()
        }
        out += String.fromCodePoint(codePoint)
        index += need + 1
      }
      return out
    },
    finish(): string {
      if (pending.length > 0) {
        invalidEncoding()
      }
      return ''
    },
  }
}

function isValidCodePoint(codePoint: number): boolean {
  if (codePoint > 0x10ffff) {
    return false
  }
  if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
    return false
  }
  if (codePoint < 0x80) {
    return true
  }
  if (codePoint < 0x800) {
    return codePoint >= 0x80
  }
  if (codePoint < 0x10000) {
    return codePoint >= 0x800
  }
  return codePoint >= 0x10000
}

/** 严格 UTF-16 解码：孤立代理项与奇数尾部字节立即失败。 */
export function createUtf16Decoder(littleEndian: boolean): StreamingDecoder {
  let pendingByte: number | null = null
  let pendingHigh: number | null = null
  return {
    push(bytes: Uint8Array): string {
      let index = 0
      let out = ''
      if (pendingByte !== null && bytes.length > 0) {
        const low = bytes[0] as number
        const unit = littleEndian ? (low << 8) | pendingByte : (pendingByte << 8) | low
        pendingByte = null
        index = 1
        out += emit(unit)
      }
      while (index + 1 < bytes.length) {
        const first = bytes[index] as number
        const second = bytes[index + 1] as number
        const unit = littleEndian ? (second << 8) | first : (first << 8) | second
        index += 2
        out += emit(unit)
      }
      if (index < bytes.length) {
        pendingByte = bytes[index] as number
      }
      return out

      function emit(unit: number): string {
        if (pendingHigh !== null) {
          if (unit >= 0xdc00 && unit <= 0xdfff) {
            const high = pendingHigh
            pendingHigh = null
            return String.fromCodePoint(((high - 0xd800) << 10) + (unit - 0xdc00) + 0x10000)
          }
          pendingHigh = null
          malformed()
        }
        if (unit >= 0xd800 && unit <= 0xdbff) {
          pendingHigh = unit
          return ''
        }
        if (unit >= 0xdc00 && unit <= 0xdfff) {
          malformed()
        }
        return String.fromCharCode(unit)
      }
    },
    finish(): string {
      if (pendingByte !== null || pendingHigh !== null) {
        invalidEncoding()
      }
      return ''
    },
  }
}

export function createDecoder(encoding: Encoding): StreamingDecoder {
  if (encoding.charset === 'UTF-16LE') {
    return createUtf16Decoder(true)
  }
  if (encoding.charset === 'UTF-16BE') {
    return createUtf16Decoder(false)
  }
  return createUtf8Decoder()
}

/** 读取文件前缀；文件短于前缀时返回其全部内容。 */
export async function probe(path: string): Promise<Uint8Array> {
  const handle = await open(path, 'r')
  try {
    const buffer = Buffer.allocUnsafe(PROBE_BYTES)
    const { bytesRead } = await handle.read(buffer, 0, PROBE_BYTES, 0)
    return buffer.subarray(0, bytesRead)
  } finally {
    await handle.close()
  }
}

/** 有界读取窗口的扫描状态机：单遍投影为窗口元数据与有界正文。 */
export class TextWindowScanner {
  private readonly offset: number
  private readonly limit: number
  private readonly columnStart: number
  private readonly displayPath: string
  private readonly lspStatus: string | null
  private readonly body: Array<{ number: number; content: string }> = []
  private current = ''
  private line = 1
  private columns = 0
  private capturedInLine = 0
  private capturedCodePoints = 0
  private budgetExhausted = false
  private lastLineCut = false
  private endedWithNewline = false
  private pendingHigh: number | null = null
  private skipLf = false

  constructor(
    offset: number,
    limit: number,
    columnOffset: number | null,
    displayPath: string,
    lspStatus: string | null,
  ) {
    this.offset = offset
    this.limit = limit
    this.columnStart = columnOffset === null ? 1 : columnOffset
    this.displayPath = displayPath
    this.lspStatus = lspStatus
  }

  push(chunk: string): void {
    for (let index = 0; index < chunk.length; index++) {
      const value = chunk.charCodeAt(index)
      if (this.pendingHigh !== null) {
        if (value < 0xdc00 || value > 0xdfff) {
          malformed()
        }
        const codePoint = ((this.pendingHigh - 0xd800) << 10) + (value - 0xdc00) + 0x10000
        this.pendingHigh = null
        this.skipLf = false
        this.onCodePoint(codePoint)
        continue
      }
      if (value === 0x0d) {
        this.onSeparator()
        this.skipLf = true
        continue
      }
      if (value === 0x0a) {
        if (!this.skipLf) {
          this.onSeparator()
        }
        this.skipLf = false
        continue
      }
      this.skipLf = false
      if (value >= 0xd800 && value <= 0xdbff) {
        if (index + 1 < chunk.length) {
          const low = chunk.charCodeAt(index + 1)
          if (low < 0xdc00 || low > 0xdfff) {
            malformed()
          }
          index++
          this.onCodePoint(((value - 0xd800) << 10) + (low - 0xdc00) + 0x10000)
        } else {
          this.pendingHigh = value
        }
        continue
      }
      if (value >= 0xdc00 && value <= 0xdfff) {
        malformed()
      }
      this.onCodePoint(value)
    }
  }

  finish(): string {
    if (this.pendingHigh !== null) {
      malformed()
    }
    return this.format(this.complete())
  }

  private onCodePoint(codePoint: number): void {
    if (codePoint === 0) {
      throw new ToolRunFailureError(`${BINARY_MESSAGE}: contains NUL character`, 'CODEC')
    }
    this.columns++
    this.endedWithNewline = false
    if (this.line < this.offset || this.body.length >= this.limit) {
      return
    }
    if (this.line === this.offset && this.columns < this.columnStart) {
      return
    }
    if (this.budgetExhausted) {
      return
    }
    this.current += String.fromCodePoint(codePoint)
    this.capturedInLine++
    this.capturedCodePoints++
    if (this.capturedCodePoints >= MAX_BODY_CODE_POINTS) {
      this.budgetExhausted = true
    }
  }

  private onSeparator(): void {
    this.finishLine()
    this.line++
    this.columns = 0
    this.endedWithNewline = true
  }

  private finishLine(): void {
    if (this.line === this.offset && this.columnStart > Math.max(this.columns, 1)) {
      throw new ToolRunFailureError(
        `column_offset ${this.columnStart} is out of range: line ${this.line} has ${this.columns} columns`,
        'INVALID_ARGUMENT',
      )
    }
    if (
      this.line >= this.offset &&
      this.body.length < this.limit &&
      (!this.budgetExhausted || this.capturedInLine > 0)
    ) {
      const startColumn = this.line === this.offset ? this.columnStart : 1
      this.body.push({ number: this.line, content: this.current })
      this.lastLineCut = this.capturedInLine < Math.max(0, this.columns - startColumn + 1)
    }
    this.current = ''
    this.capturedInLine = 0
  }

  private complete(): Window {
    if (this.columns > 0) {
      this.finishLine()
    }
    const totalLines = this.columns > 0 ? this.line : this.line - 1
    if (this.offset > totalLines) {
      return {
        endsWithNewline: this.endedWithNewline,
        emptyRange: true,
        startLine: 0,
        startColumn: 0,
        endLine: 0,
        endColumn: 0,
        truncationReason: null,
        nextLine: 0,
        nextColumn: 0,
        width: 1,
        body: [],
      }
    }
    const last = this.body[this.body.length - 1] as { number: number; content: string }
    const lastStartColumn = last.number === this.offset ? this.columnStart : 1
    const codePoints = Array.from(last.content).length
    const endColumn = Math.max(1, lastStartColumn - 1 + codePoints)
    const truncated = this.lastLineCut || totalLines > last.number
    let reason: string | null = null
    let nextLine = 0
    let nextColumn = 0
    if (truncated) {
      reason = this.budgetExhausted ? 'character_limit' : 'line_limit'
      if (this.lastLineCut) {
        nextLine = last.number
        nextColumn = endColumn + 1
      } else {
        nextLine = last.number + 1
        nextColumn = 1
      }
    }
    return {
      endsWithNewline: this.endedWithNewline,
      emptyRange: false,
      startLine: this.offset,
      startColumn: this.columnStart,
      endLine: last.number,
      endColumn: requireProtocolPosition(endColumn, 'column'),
      truncationReason: reason,
      nextLine: requireProtocolPosition(nextLine, 'line'),
      nextColumn: requireProtocolPosition(nextColumn, 'column'),
      width: Math.max(1, String(Math.max(1, totalLines)).length),
      body: this.body,
    }
  }

  private format(window: Window): string {
    const output: string[] = []
    output.push(`path: ${this.displayPath}`)
    output.push(`ends_with_newline: ${window.endsWithNewline ? 'yes' : 'no'}`)
    output.push(
      window.emptyRange
        ? 'range: empty'
        : `range: ${window.startLine}:${window.startColumn}-${window.endLine}:${window.endColumn}`,
    )
    if (window.truncationReason !== null) {
      output.push('truncated: yes')
      output.push(`truncation_reason: ${window.truncationReason}`)
      output.push(`next: ${window.nextLine}:${window.nextColumn}`)
    }
    if (this.lspStatus !== null && this.lspStatus !== 'unsupported') {
      output.push(`lsp: ${this.lspStatus}`)
    }
    if (window.body.length > 0) {
      output.push('')
      for (const line of window.body) {
        output.push(`${pad(line.number, window.width)}|${line.content}`)
      }
    }
    if (window.truncationReason !== null) {
      if (window.body.length > 0) {
        output.push('')
      }
      output.push(
        `[TRUNCATED: More file content remains. Next position: line ${window.nextLine}, column ${window.nextColumn}.]`,
      )
    }
    return output.join('\n')
  }
}

interface Window {
  endsWithNewline: boolean
  emptyRange: boolean
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
  truncationReason: string | null
  nextLine: number
  nextColumn: number
  width: number
  body: Array<{ number: number; content: string }>
}

function pad(value: number, width: number): string {
  const text = String(value)
  return text.length >= width ? text : ' '.repeat(width - text.length) + text
}

function requireProtocolPosition(value: number, field: string): number {
  if (value > Number.MAX_SAFE_INTEGER) {
    throw new ToolRunFailureError(
      `text read position exceeds the supported integer range: ${field}=${value}`,
      'INVALID_ARGUMENT',
    )
  }
  return value
}

/** 在已解码字符块上投影窗口；块边界不切断代理对。 */
export function readWindowFromChunks(
  chunks: Iterable<string>,
  offset: number,
  limit: number,
  columnOffset: number | null,
  displayPath: string,
  lspStatus: string | null,
): string {
  const scanner = new TextWindowScanner(offset, limit, columnOffset, displayPath, lspStatus)
  for (const chunk of chunks) {
    scanner.push(chunk)
  }
  return scanner.finish()
}

/**
 * 读取并格式化文本窗口：按既有编码严格解码后交给窗口核心。
 *
 * <p>BOM 已在字节层跳过，绝不在此按字符再剥一次，否则会吞掉正文里真正的 {@code \uFEFF}。
 */
export async function readTextWindow(
  path: string,
  encoding: Encoding,
  offset: number,
  limit: number,
  columnOffset: number | null,
  displayPath: string,
  lspStatus: string | null,
  checkpoint: () => void,
): Promise<string> {
  const handle = await open(path, 'r')
  try {
    const decoder = createDecoder(encoding)
    const scanner = new TextWindowScanner(offset, limit, columnOffset, displayPath, lspStatus)
    const buffer = Buffer.allocUnsafe(READ_BUFFER_CHARS)
    let position = encoding.bomLength
    for (;;) {
      checkpoint()
      const { bytesRead } = await handle.read(buffer, 0, READ_BUFFER_CHARS, position)
      if (bytesRead === 0) {
        break
      }
      position += bytesRead
      checkpoint()
      scanner.push(decoder.push(buffer.subarray(0, bytesRead)))
    }
    scanner.push(decoder.finish())
    return scanner.finish()
  } finally {
    await handle.close()
  }
}

/** 逐行扫描结果：文件总行数与是否以换行符结尾。 */
export interface LineScanOutcome {
  totalLines: number
  endsWithNewline: boolean
}

/** 逐行消费者：返回 false 表示提前终止扫描。 */
export type LineConsumer = (
  lineNumber: number,
  line: string,
  truncated: boolean,
) => boolean | Promise<boolean>

/**
 * 以严格解码逐行扫描文件；单行超过 {@link MAX_LINE_CHARS} 时以保留前缀加 {@code truncated=true} 交付。
 *
 * <p>调用方必须处理 {@code truncated}：grep 视为“无法完整搜索”的显式失败，绝不把它当成该行无匹配。
 */
export async function forEachLine(
  path: string,
  encoding: Encoding,
  consumer: LineConsumer,
): Promise<LineScanOutcome> {
  const handle = await open(path, 'r')
  try {
    const decoder = createDecoder(encoding)
    const buffer = Buffer.allocUnsafe(8 * 1024)
    let line = ''
    let lineNumber = 0
    let truncated = false
    let sawCr = false
    let endedWithNewline = false
    let position = encoding.bomLength
    for (;;) {
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, position)
      if (bytesRead === 0) {
        break
      }
      position += bytesRead
      const chunk = decoder.push(buffer.subarray(0, bytesRead))
      for (let index = 0; index < chunk.length; index++) {
        const value = chunk.charCodeAt(index)
        if (value === 0) {
          throw new ToolRunFailureError(
            `${BINARY_MESSAGE}: contains NUL character`,
            'CODEC',
          )
        }
        if (sawCr) {
          sawCr = false
          lineNumber++
          endedWithNewline = true
          if (!(await consumer(lineNumber, line, truncated))) {
            return { totalLines: lineNumber, endsWithNewline: true }
          }
          line = ''
          truncated = false
          if (value === 0x0a) {
            continue
          }
          endedWithNewline = false
        }
        if (value === 0x0d) {
          sawCr = true
          continue
        }
        if (value === 0x0a) {
          lineNumber++
          endedWithNewline = true
          if (!(await consumer(lineNumber, line, truncated))) {
            return { totalLines: lineNumber, endsWithNewline: true }
          }
          line = ''
          truncated = false
          continue
        }
        endedWithNewline = false
        if (line.length < MAX_LINE_CHARS) {
          line += chunk[index]
        } else {
          truncated = true
        }
      }
    }
    const tail = decoder.finish()
    if (tail.length > 0) {
      // finish() 只在无未完成字节时返回空串；这里保守地把余量并入当前行。
      line += tail
    }
    if (sawCr) {
      lineNumber++
      await consumer(lineNumber, line, truncated)
      endedWithNewline = true
    } else if (line.length > 0 || truncated) {
      lineNumber++
      await consumer(lineNumber, line, truncated)
      endedWithNewline = false
    }
    return { totalLines: lineNumber, endsWithNewline: endedWithNewline && lineNumber > 0 }
  } finally {
    await handle.close()
  }
}

/** 严格解码整段字节，保留编码与 BOM 长度。 */
export interface DecodedText {
  text: string
  charset: CharsetName
  bomLength: number
}

/** 严格解码：非法 UTF-8/UTF-16 或 NUL 一律按“看似二进制”失败。 */
export function decodeText(bytes: Uint8Array): DecodedText {
  const encoding = detectEncoding(bytes)
  if (encoding.bomLength === 0 && isBinaryBytes(bytes)) {
    throw new ToolRunFailureError(BINARY_MESSAGE, 'CODEC')
  }
  const decoder = createDecoder(encoding)
  const text = decoder.push(bytes.subarray(encoding.bomLength)) + decoder.finish()
  if (text.includes('\u0000')) {
    throw new ToolRunFailureError(`${BINARY_MESSAGE}: contains NUL character`, 'CODEC')
  }
  return { text, charset: encoding.charset, bomLength: encoding.bomLength }
}

/** 严格编码文本，按既有编码与 BOM 长度还原字节；无法无损表示时拒绝。 */
export function encodeText(text: string, charset: CharsetName, bomLength: number): Uint8Array {
  for (let index = 0; index < text.length; index++) {
    const value = text.charCodeAt(index)
    if (value >= 0xd800 && value <= 0xdbff) {
      const next = index + 1 < text.length ? text.charCodeAt(index + 1) : 0
      if (next < 0xdc00 || next > 0xdfff) {
        throw new ToolRunFailureError(
          `text cannot be losslessly encoded in charset ${charset}`,
          'CODEC',
        )
      }
      index++
    } else if (value >= 0xdc00 && value <= 0xdfff) {
      throw new ToolRunFailureError(
        `text cannot be losslessly encoded in charset ${charset}`,
        'CODEC',
      )
    }
  }
  let content: Uint8Array
  if (charset === 'UTF-16LE') {
    content = new Uint8Array(text.length * 2)
    for (let index = 0; index < text.length; index++) {
      const unit = text.charCodeAt(index)
      content[index * 2] = unit & 0xff
      content[index * 2 + 1] = unit >> 8
    }
  } else if (charset === 'UTF-16BE') {
    content = new Uint8Array(text.length * 2)
    for (let index = 0; index < text.length; index++) {
      const unit = text.charCodeAt(index)
      content[index * 2] = unit >> 8
      content[index * 2 + 1] = unit & 0xff
    }
  } else {
    content = new Uint8Array(Buffer.from(text, 'utf8'))
  }
  if (bomLength === 0) {
    return content
  }
  const bom =
    charset === 'UTF-8'
      ? [0xef, 0xbb, 0xbf]
      : charset === 'UTF-16LE'
        ? [0xff, 0xfe]
        : [0xfe, 0xff]
  const result = new Uint8Array(bom.length + content.length)
  result.set(bom, 0)
  result.set(content, bom.length)
  return result
}

/** 读取整段文件字节。 */
export async function readAllBytes(path: string): Promise<Uint8Array> {
  return new Uint8Array(await readFile(path))
}

/** 受支持图片的媒体类型探测；非图片返回 null。 */
export function detectImageMediaType(bytes: Uint8Array): string | null {
  if (bytes.length < 3) {
    return null
  }
  if (
    bytes.length >= 8 &&
    bytes[0] === 0x89 &&
    bytes[1] === 0x50 &&
    bytes[2] === 0x4e &&
    bytes[3] === 0x47 &&
    bytes[4] === 0x0d &&
    bytes[5] === 0x0a &&
    bytes[6] === 0x1a &&
    bytes[7] === 0x0a
  ) {
    return 'image/png'
  }
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return 'image/jpeg'
  }
  if (
    bytes.length >= 6 &&
    bytes[0] === 0x47 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x38 &&
    (bytes[4] === 0x37 || bytes[4] === 0x39) &&
    bytes[5] === 0x61
  ) {
    return 'image/gif'
  }
  if (
    bytes.length >= 12 &&
    bytes[0] === 0x52 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x46 &&
    bytes[8] === 0x57 &&
    bytes[9] === 0x45 &&
    bytes[10] === 0x42 &&
    bytes[11] === 0x50
  ) {
    return 'image/webp'
  }
  return null
}
