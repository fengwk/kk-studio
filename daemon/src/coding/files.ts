import { createHash } from 'node:crypto'
import { readdir, stat } from 'node:fs/promises'
import type {
  CapabilityCall,
  CapabilityContext,
  CapabilityExecutor,
  CapabilityResult,
} from '../contracts.js'
import { textResult } from '../contracts.js'
import { MAX_DETAILS_JSON_UTF8_BYTES } from '../protocol.js'
import {
  CancelledError,
  ToolInputRejectedError,
  ToolRunFailureError,
  classifyFileSystemError,
  displayPath,
  isSymbolicLink,
  optionalBoolean,
  optionalPositiveInt,
  optionalPositiveIntOrNull,
  requiredString,
  resolveExistingPath,
  resolveWritablePath,
  toErrorResult,
  exists as pathExists,
} from './support.js'
import {
  DEFAULT_READ_LIMIT,
  MAX_READ_LIMIT,
  decodeText,
  detectEncoding,
  detectImageMediaType,
  encodeText,
  looksBinary,
  probe,
  readAllBytes,
  readTextWindow,
} from './text.js'
import { acquireFileLock, commitTextFile } from './commit.js'
import { textOutputStoreFor } from './output.js'

/**
 * 文件能力（fs.read / fs.write / fs.edit）。
 *
 * <p>与 Java 参考实现对齐：文件参数只接受绝对路径，没有隐式 cwd；read 返回精确有界窗口（正文最多 60000 码点），图片作为二进制内容返回；write/edit 在保留
 * 编码/BOM/行尾的前提下原子提交，无法无损表示时拒绝。目录、设备、FIFO 与符号链接在任何 I/O 之前拒绝。
 */

const MAX_DIRECTORY_RESPONSE_BYTES = 48 * 1024
const MAX_OFFSET = 2147483647

/** LSP 只读支持状态；由 LSP 切片提供，本片不复制其 discovery 逻辑。 */
export interface LspSupportInfo {
  language: string | null
  available: boolean
}

/**
 * Files 与 LSP 切片的跨片钩子入口。
 *
 * <p>Runtime 在装配时注入：{@code lspSupport} 用于 read 的 {@code lsp:} header，{@code fileChanged} 在 write/edit 成功提交后通知 LSP 刷新文档。
 * 未注入时不输出 header、不通知，也不通过手写扩展名 pattern 推定服务可用。
 */
export interface FileCapabilityHooks {
  lspSupport?: (file: string) => LspSupportInfo | null
  fileChanged?: (file: string) => void
}

let fileHooks: FileCapabilityHooks = {}

/** 注入跨片钩子；幂等，Runtime 热更时可重新调用。 */
export function setFileCapabilityHooks(hooks: FileCapabilityHooks): void {
  fileHooks = hooks
}

/** 读取有界文本窗口或确定性的目录清单。 */
const readExecutor: CapabilityExecutor = async (call, context) => {
  try {
    const args = call.arguments
    const rawPath = requiredString(args, 'path')
    // 窗口参数先于任何文件系统访问校验，畸形窗口不会以 ENOENT 之类的 I/O 结论掩盖参数错误。
    const offset = optionalPositiveInt(args, 'offset', 1, MAX_OFFSET)
    const limit = optionalPositiveInt(args, 'limit', DEFAULT_READ_LIMIT, MAX_READ_LIMIT)
    const columnOffset = optionalPositiveIntOrNull(args, 'column_offset')
    const path = await resolveExistingPath(rawPath)
    const display = displayPath(path, rawPath)
    // 读取受控临时产物期间持有 in-use lease，防止定时清扫删除在途读取的 workspace。
    const store = await textOutputStoreFor(context.config.dataDir)
    const lease = await store.acquire(path)
    try {
      return await readExisting(call, context, path, display, offset, limit, columnOffset)
    } finally {
      lease.close()
    }
  } catch (error) {
    return toErrorResult(call.invocationId, error)
  }
}

async function readExisting(
  call: CapabilityCall,
  context: CapabilityContext,
  path: string,
  display: string,
  offset: number,
  limit: number,
  columnOffset: number | null,
): Promise<CapabilityResult> {
  const info = await statOrNull(path)
  if (info !== null && info.isDirectory()) {
    return directoryResponse(call.invocationId, path, display, offset, limit)
  }
  if (info === null || !info.isFile()) {
    // 字符设备、FIFO、socket、块设备等非普通节点必须在任何 I/O 之前拒绝。
    throw new ToolRunFailureError(`not a regular file: ${display}`, 'INVALID_ARGUMENT')
  }
  const probeBytes = await probe(path)
  const imageMime = detectImageMediaType(probeBytes)
  if (imageMime !== null) {
    const bytes = await readAllBytes(path)
    return {
      callId: call.invocationId,
      error: false,
      details: {},
      contents: [{ type: 'binary', bytes, mediaType: imageMime }],
    }
  }
  const encoding = detectEncoding(probeBytes)
  if (looksBinary(probeBytes, encoding)) {
    throw new ToolRunFailureError('file appears to be binary', 'CODEC')
  }
  const checkpoint = (): void => {
    if (context.signal.aborted) {
      throw new CancelledError()
    }
  }
  const text = await readTextWindow(
    path,
    encoding,
    offset,
    limit,
    columnOffset,
    display,
    lspStatus(path),
    checkpoint,
  )
  return textResult(call.invocationId, text)
}

async function directoryResponse(
  callId: string,
  path: string,
  display: string,
  offset: number,
  limit: number,
): Promise<CapabilityResult> {
  const entries = await readdir(path, { withFileTypes: true })
  const names = entries
    .map((entry) => `${entry.name}${entry.isDirectory() ? '/' : ''}`)
    .sort((left, right) => (left < right ? -1 : left > right ? 1 : 0))
  const totalEntries = names.length
  const start = Math.min(offset, totalEntries + 1)
  const end = Math.min(totalEntries, start + limit - 1)

  const headers = [`path: ${display}`, 'kind: directory', '']
  if (offset > totalEntries) {
    const output = [...headers, `[Showing 0 entries of ${totalEntries}.]`]
    return textResponse(callId, output.join('\n'))
  }

  let bytes = responseUtf8Bytes(headers)
  const entriesList: string[] = []
  let actualEnd = start - 1
  for (let index = start; index <= end; index++) {
    const entryName = names[index - 1] as string
    const entryBytes = utf8Bytes(entryName)
    let candidateBytes = bytes + 1 + entryBytes
    if (index < totalEntries) {
      candidateBytes += 2 + utf8Bytes(directoryTailLine(start, index, totalEntries))
    }
    if (candidateBytes > MAX_DIRECTORY_RESPONSE_BYTES) {
      if (index === start) {
        throw new Error(
          `directory read response exceeds ${MAX_DIRECTORY_RESPONSE_BYTES} bytes on first entry: ${candidateBytes} bytes`,
        )
      }
      break
    }
    entriesList.push(entryName)
    bytes += 1 + entryBytes
    actualEnd = index
  }

  const output = [...headers, ...entriesList]
  if (actualEnd < totalEntries && start <= totalEntries) {
    output.push('')
    output.push(directoryTailLine(start, actualEnd, totalEntries))
  }
  return textResponse(callId, output.join('\n'))
}

function directoryTailLine(start: number, end: number, totalEntries: number): string {
  return `[Showing entries ${start}-${end} of ${totalEntries}. Re-run read with offset=${end + 1} to continue.]`
}

function utf8Bytes(value: string): number {
  return Buffer.byteLength(value, 'utf8')
}

function responseUtf8Bytes(lines: string[]): number {
  if (lines.length === 0) {
    return 0
  }
  let bytes = lines.length - 1
  for (const line of lines) {
    bytes += utf8Bytes(line)
  }
  return bytes
}

function textResponse(callId: string, text: string): CapabilityResult {
  const bytes = utf8Bytes(text)
  if (bytes > MAX_DIRECTORY_RESPONSE_BYTES) {
    throw new Error(
      `read response exceeds ${MAX_DIRECTORY_RESPONSE_BYTES} bytes invariant: ${bytes} bytes`,
    )
  }
  return textResult(callId, text)
}

/** 在本次调用声明的绝对路径下按意图创建或完整覆盖单个文本文件。 */
const writeExecutor: CapabilityExecutor = async (call, context) => {
  try {
    const args = call.arguments
    const rawPath = requiredString(args, 'path')
    const content = requiredString(args, 'content')
    const path = await resolveWritablePath(rawPath)
    const display = displayPath(path, rawPath)

    const release = await acquireFileLock(path)
    try {
      requireNotCancelled(context)
      const existed = await pathExists(path)
      let charset: 'UTF-8' | 'UTF-16LE' | 'UTF-16BE' = 'UTF-8'
      let bomLength = 0
      if (existed) {
        const info = await statOrNull(path)
        if (info !== null && info.isDirectory()) {
          throw new ToolInputRejectedError(`path is a directory: ${display}`)
        }
        if (info === null || !info.isFile()) {
          throw new ToolInputRejectedError(`path is not a regular file: ${display}`)
        }
        const decoded = decodeText(await readAllBytes(path))
        charset = decoded.charset
        bomLength = decoded.bomLength
      } else if (await isSymbolicLink(path)) {
        // 悬空符号链接不是待创建的新文件：替换它会静默丢掉链接本身。
        throw new ToolInputRejectedError(`path is a symbolic link: ${display}`)
      }

      const encoded = encodeText(content, charset, bomLength)
      requireNotCancelled(context)
      await commitTextFile(path, encoded)
      fileHooks.fileChanged?.(path)
      return textResult(
        call.invocationId,
        `${existed ? 'Overwrote ' : 'Created '}${display} successfully.`,
      )
    } finally {
      release()
    }
  } catch (error) {
    return toErrorResult(call.invocationId, error)
  }
}

/** Preview and commit share the same file read, matching and encoding computation. */
const editExecutor: CapabilityExecutor = (call, context) => editFile(call, context, false)
const editPreviewExecutor: CapabilityExecutor = (call, context) => editFile(call, context, true)

async function editFile(
  call: CapabilityCall,
  context: CapabilityContext,
  preview: boolean,
): Promise<CapabilityResult> {
  try {
    const args = call.arguments
    const rawPath = requiredString(args, 'path')
    const oldText = requiredString(args, 'old_string')
    const newText = requiredString(args, 'new_string')

    if (oldText.length === 0) {
      throw new ToolInputRejectedError('old_string must not be empty')
    }
    const normalizedOld = normalizeToLf(oldText)
    const normalizedNew = normalizeToLf(newText)
    if (normalizedOld === normalizedNew) {
      throw new ToolInputRejectedError(
        'No changes to apply: old_string and new_string must differ after line-ending normalization',
      )
    }

    const path = await resolveExistingPath(rawPath)
    const display = displayPath(path, rawPath)
    const info = await statOrNull(path)
    if (info !== null && info.isDirectory()) {
      throw new ToolInputRejectedError(`path must be a file: ${display}`)
    }
    if (info === null || !info.isFile()) {
      throw new ToolInputRejectedError(`path is not a regular file: ${display}`)
    }

    const release = await acquireFileLock(path)
    try {
      requireNotCancelled(context)
      const originalBytes = await readAllBytes(path)
      const sha256 = createHash('sha256').update(originalBytes).digest('hex')
      if (
        call.expectedFile !== undefined
        && (call.expectedFile.path !== path || call.expectedFile.sha256 !== sha256)
      ) {
        throw new ToolInputRejectedError(
          'File changed since edit review; nothing was written. Review a new edit before approving it.',
        )
      }
      const decoded = decodeText(originalBytes)
      const source = decoded.text
      const lineEndingStyle = detectLineEnding(source)
      const document = parseLineEndingDocument(source)
      const occurrences = buildOccurrences(document, normalizedOld)

      if (occurrences.length === 0) {
        throw new ToolInputRejectedError(`Could not find old_string in ${display}`)
      }
      const replaceAll = optionalBoolean(args, 'replace_all')
      if (!replaceAll && occurrences.length > 1) {
        throw new ToolInputRejectedError(
          `Found ${occurrences.length} exact matches; use replace_all to change every match or provide more context`,
        )
      }
      if (replaceAll && hasOverlappingOccurrences(occurrences)) {
        throw new ToolInputRejectedError(
          `Found overlapping exact matches for old_string in ${display}; replace_all cannot safely apply overlapping replacements`,
        )
      }

      let nextDocument = document
      const pending = replaceAll ? [...occurrences] : [occurrences[0] as Occurrence]
      for (let index = pending.length - 1; index >= 0; index--) {
        nextDocument = applyOccurrence(
          nextDocument,
          pending[index] as Occurrence,
          normalizedNew,
          lineEndingStyle,
        )
      }

      const encoded = encodeText(
        serializeLineEndingDocument(nextDocument),
        decoded.charset,
        decoded.bomLength,
      )
      if (bytesEqual(encoded, originalBytes)) {
        throw new ToolInputRejectedError('No changes to apply: edit would not change the file')
      }

      const editReview = { path, diff: buildDiff(document, nextDocument), sha256 }
      const completed = textResult(
        call.invocationId,
        `Edited successfully.\nReplacements: ${pending.length}`,
        { editReview },
      )
      // Reserve the complete result, not just the diff, inside the existing 1 MiB JSON
      // budget before preview/commit. Changed lines are never silently truncated.
      const persistedResult = {
        toolCallId: completed.callId,
        contents: completed.contents,
        error: completed.error,
        details: completed.details,
      }
      if (Buffer.byteLength(JSON.stringify(persistedResult), 'utf8') > MAX_DETAILS_JSON_UTF8_BYTES) {
        throw new ToolInputRejectedError(
          'Complete edit diff exceeds the review transport limit; split this edit into smaller changes.',
        )
      }
      requireNotCancelled(context)
      if (preview) {
        return {
          callId: call.invocationId,
          error: false,
          details: {},
          contents: [{ type: 'json', json: editReview }],
        }
      }
      await commitTextFile(path, encoded)
      fileHooks.fileChanged?.(path)

      return completed
    } finally {
      release()
    }
  } catch (error) {
    return toErrorResult(call.invocationId, error)
  }
}

async function statOrNull(path: string): Promise<Awaited<ReturnType<typeof stat>> | null> {
  try {
    return await stat(path)
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    if (code === 'ENOENT') {
      return null
    }
    const classified = classifyFileSystemError(error)
    throw new ToolRunFailureError(classified.message, classified.code)
  }
}

function requireNotCancelled(context: CapabilityContext): void {
  if (context.signal.aborted) {
    throw new CancelledError()
  }
}

function lspStatus(path: string): string | null {
  const support = fileHooks.lspSupport?.(path)
  if (support === undefined || support === null || !support.available || support.language === null) {
    return null
  }
  return `supported (${support.language})`
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) {
    return false
  }
  for (let index = 0; index < left.length; index++) {
    if (left[index] !== right[index]) {
      return false
    }
  }
  return true
}

// ---------------------------------------------------------------------------
// 精确文本替换的行尾/编码保留与行级 diff
// ---------------------------------------------------------------------------

const DIFF_CONTEXT_LINES = 4
const DIFF_MAX_CELLS = 2_000_000

interface LineEndingDocument {
  lines: string[]
  eolAfter: Array<string | null>
}

interface Cursor {
  lineIndex: number
  column: number
}

interface Occurrence {
  startIndex: number
  endIndex: number
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
  consumedEndings: string[]
  trailingEnding: string | null
}

export function normalizeToLf(value: string): string {
  return value.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

export function detectLineEnding(text: string): string {
  const hasCrlf = text.includes('\r\n')
  const withoutCrlf = text.replace(/\r\n/g, '')
  const hasCr = withoutCrlf.includes('\r')
  const hasLf = withoutCrlf.includes('\n')
  const styles = (hasCrlf ? 1 : 0) + (hasCr ? 1 : 0) + (hasLf ? 1 : 0)
  if (styles > 1) {
    return 'mixed'
  }
  if (hasCrlf) {
    return '\r\n'
  }
  if (hasCr) {
    return '\r'
  }
  return '\n'
}

export function parseLineEndingDocument(text: string): LineEndingDocument {
  const lines: string[] = []
  const eolAfter: Array<string | null> = []
  let current = ''
  for (let index = 0; index < text.length; index++) {
    const ch = text[index] as string
    if (ch === '\r') {
      if (index + 1 < text.length && text[index + 1] === '\n') {
        lines.push(current)
        eolAfter.push('\r\n')
        current = ''
        index++
        continue
      }
      lines.push(current)
      eolAfter.push('\r')
      current = ''
      continue
    }
    if (ch === '\n') {
      lines.push(current)
      eolAfter.push('\n')
      current = ''
      continue
    }
    current += ch
  }
  lines.push(current)
  eolAfter.push(null)
  return { lines, eolAfter }
}

export function serializeLineEndingDocument(document: LineEndingDocument): string {
  let builder = ''
  for (let index = 0; index < document.lines.length; index++) {
    builder += document.lines[index] as string
    const ending = document.eolAfter[index]
    if (ending !== null && ending !== undefined) {
      builder += ending
    }
  }
  return builder
}

function serializeNormalizedDocument(document: LineEndingDocument): string {
  let builder = ''
  for (let index = 0; index < document.lines.length; index++) {
    builder += document.lines[index] as string
    if (document.eolAfter[index] !== null && document.eolAfter[index] !== undefined) {
      builder += '\n'
    }
  }
  return builder
}

function cursorFromNormalizedIndex(document: LineEndingDocument, index: number): Cursor {
  let offset = 0
  for (let lineIndex = 0; lineIndex < document.lines.length; lineIndex++) {
    const line = document.lines[lineIndex] as string
    if (index <= offset + line.length) {
      return { lineIndex, column: index - offset }
    }
    offset += line.length
    if (document.eolAfter[lineIndex] !== null && document.eolAfter[lineIndex] !== undefined) {
      if (index === offset + 1) {
        return { lineIndex: Math.min(lineIndex + 1, document.lines.length - 1), column: 0 }
      }
      offset += 1
    }
  }
  const lastLineIndex = Math.max(0, document.lines.length - 1)
  const lastLine = document.lines[lastLineIndex] as string
  if (index === offset) {
    return { lineIndex: lastLineIndex, column: lastLine.length }
  }
  throw new ToolInputRejectedError('line ending document index is out of range')
}

function occurrenceStarts(text: string, search: string): number[] {
  const starts: number[] = []
  let offset = 0
  for (;;) {
    const found = text.indexOf(search, offset)
    if (found < 0) {
      break
    }
    starts.push(found)
    offset = found + 1
  }
  return starts
}

function buildOccurrences(document: LineEndingDocument, search: string): Occurrence[] {
  const normalized = serializeNormalizedDocument(document)
  const result: Occurrence[] = []
  for (const startIndex of occurrenceStarts(normalized, search)) {
    const endIndex = startIndex + search.length
    const start = cursorFromNormalizedIndex(document, startIndex)
    const end = cursorFromNormalizedIndex(document, endIndex)
    const consumedEndings: string[] = []
    for (let line = start.lineIndex; line < end.lineIndex; line++) {
      const ending = document.eolAfter[line]
      if (ending !== null && ending !== undefined) {
        consumedEndings.push(ending)
      }
    }
    result.push({
      startIndex,
      endIndex,
      startLine: start.lineIndex,
      startColumn: start.column,
      endLine: end.lineIndex,
      endColumn: end.column,
      consumedEndings,
      trailingEnding: document.eolAfter[end.lineIndex] ?? null,
    })
  }
  return result
}

function hasOverlappingOccurrences(occurrences: Occurrence[]): boolean {
  for (let index = 1; index < occurrences.length; index++) {
    if (
      (occurrences[index] as Occurrence).startIndex <
      (occurrences[index - 1] as Occurrence).endIndex
    ) {
      return true
    }
  }
  return false
}

function resolveInsertedEnding(
  style: string,
  consumedEndings: string[],
  replacementEndingIndex: number,
): string {
  if (style !== 'mixed') {
    return style
  }
  return replacementEndingIndex < consumedEndings.length
    ? (consumedEndings[replacementEndingIndex] as string)
    : '\n'
}

function applyOccurrence(
  document: LineEndingDocument,
  occurrence: Occurrence,
  replacement: string,
  style: string,
): LineEndingDocument {
  const prefix = (document.lines[occurrence.startLine] as string).slice(0, occurrence.startColumn)
  const endLine = document.lines[occurrence.endLine] as string
  const suffix = endLine.slice(Math.min(occurrence.endColumn, endLine.length))
  const replacementDocument = parseLineEndingDocument(replacement)
  const replacementLines = [...replacementDocument.lines]
  const replacementEolAfter = [...replacementDocument.eolAfter]
  const lastReplacementIndex = replacementLines.length - 1
  replacementLines[0] = prefix + (replacementLines[0] as string)
  replacementLines[lastReplacementIndex] =
    (replacementLines[lastReplacementIndex] as string) + suffix
  for (let index = 0; index < replacementEolAfter.length; index++) {
    if (index === replacementEolAfter.length - 1) {
      replacementEolAfter[index] = occurrence.trailingEnding
    } else {
      replacementEolAfter[index] = resolveInsertedEnding(
        style,
        occurrence.consumedEndings,
        index,
      )
    }
  }
  const lines = [
    ...document.lines.slice(0, occurrence.startLine),
    ...replacementLines,
    ...document.lines.slice(occurrence.endLine + 1),
  ]
  const eolAfter = [
    ...document.eolAfter.slice(0, occurrence.startLine),
    ...replacementEolAfter,
    ...document.eolAfter.slice(occurrence.endLine + 1),
  ]
  return { lines, eolAfter }
}

type DiffKind = 'CONTEXT' | 'REMOVED' | 'ADDED'
type ContextPosition = 'LEADING' | 'INTER_HUNK' | 'TRAILING'

interface DiffPart {
  kind: DiffKind
  lines: string[]
}

export function buildDiff(originalDoc: LineEndingDocument, nextDoc: LineEndingDocument): string {
  const originalLines = diffLines(originalDoc)
  const nextLines = diffLines(nextDoc)
  return renderDiff(originalLines, nextLines, diffParts(originalLines, nextLines))
}

function diffLines(document: LineEndingDocument): string[] {
  const tokens: string[] = []
  for (let index = 0; index < document.lines.length; index++) {
    const ending = document.eolAfter[index]
    if (ending !== null && ending !== undefined) {
      tokens.push(`${document.lines[index] as string}\n`)
      continue
    }
    if ((document.lines[index] as string).length !== 0) {
      tokens.push(document.lines[index] as string)
    }
  }
  return tokens
}

function displayLine(token: string): string {
  return token.endsWith('\n') ? token.slice(0, -1) : token
}

function diffParts(original: string[], next: string[]): DiffPart[] {
  let prefix = 0
  while (
    prefix < original.length &&
    prefix < next.length &&
    original[prefix] === next[prefix]
  ) {
    prefix++
  }
  let suffix = 0
  while (
    suffix < original.length - prefix &&
    suffix < next.length - prefix &&
    original[original.length - 1 - suffix] === next[next.length - 1 - suffix]
  ) {
    suffix++
  }
  const parts: DiffPart[] = []
  if (prefix > 0) {
    parts.push({ kind: 'CONTEXT', lines: original.slice(0, prefix) })
  }
  parts.push(
    ...alignMiddle(
      original.slice(prefix, original.length - suffix),
      next.slice(prefix, next.length - suffix),
    ),
  )
  if (suffix > 0) {
    parts.push({ kind: 'CONTEXT', lines: original.slice(original.length - suffix) })
  }
  return parts
}

function alignMiddle(original: string[], next: string[]): DiffPart[] {
  if (original.length === 0 && next.length === 0) {
    return []
  }
  if (original.length * next.length > DIFF_MAX_CELLS) {
    return [{ kind: 'REMOVED', lines: [...original] }, { kind: 'ADDED', lines: [...next] }]
  }

  const width = next.length + 1
  const lcs = new Int32Array((original.length + 1) * width)
  for (let originalIndex = original.length - 1; originalIndex >= 0; originalIndex--) {
    for (let nextIndex = next.length - 1; nextIndex >= 0; nextIndex--) {
      lcs[originalIndex * width + nextIndex] =
        original[originalIndex] === next[nextIndex]
          ? (lcs[(originalIndex + 1) * width + nextIndex + 1] as number) + 1
          : Math.max(
              lcs[(originalIndex + 1) * width + nextIndex] as number,
              lcs[originalIndex * width + nextIndex + 1] as number,
            )
    }
  }

  const parts: DiffPart[] = []
  let removed: string[] = []
  let added: string[] = []
  let context: string[] = []
  let originalIndex = 0
  let nextIndex = 0
  while (originalIndex < original.length && nextIndex < next.length) {
    if (original[originalIndex] === next[nextIndex]) {
      flushChanged(parts, removed, added)
      removed = []
      added = []
      context.push(original[originalIndex] as string)
      originalIndex++
      nextIndex++
      continue
    }
    flushContext(parts, context)
    context = []
    if (
      (lcs[(originalIndex + 1) * width + nextIndex] as number) >=
      (lcs[originalIndex * width + nextIndex + 1] as number)
    ) {
      removed.push(original[originalIndex] as string)
      originalIndex++
    } else {
      added.push(next[nextIndex] as string)
      nextIndex++
    }
  }
  flushContext(parts, context)
  while (originalIndex < original.length) {
    removed.push(original[originalIndex] as string)
    originalIndex++
  }
  while (nextIndex < next.length) {
    added.push(next[nextIndex] as string)
    nextIndex++
  }
  flushChanged(parts, removed, added)
  return parts
}

function flushContext(parts: DiffPart[], context: string[]): void {
  if (context.length > 0) {
    parts.push({ kind: 'CONTEXT', lines: [...context] })
  }
}

function flushChanged(parts: DiffPart[], removed: string[], added: string[]): void {
  if (removed.length > 0) {
    parts.push({ kind: 'REMOVED', lines: [...removed] })
  }
  if (added.length > 0) {
    parts.push({ kind: 'ADDED', lines: [...added] })
  }
}

function renderDiff(original: string[], next: string[], parts: DiffPart[]): string {
  const width = Math.max(2, String(Math.max(1, Math.max(original.length, next.length))).length)
  const cursor = new DiffCursor(width)
  let diff = ''
  for (let index = 0; index < parts.length; index++) {
    const part = parts[index] as DiffPart
    if (part.kind !== 'CONTEXT') {
      for (const line of part.lines) {
        if (part.kind === 'ADDED') {
          diff += cursor.appendAdded(displayLine(line))
        } else {
          diff += cursor.appendRemoved(displayLine(line))
        }
      }
      continue
    }
    const previousChanged = index > 0 && (parts[index - 1] as DiffPart).kind !== 'CONTEXT'
    const nextChanged =
      index + 1 < parts.length && (parts[index + 1] as DiffPart).kind !== 'CONTEXT'
    if (!previousChanged && !nextChanged) {
      cursor.skip(part.lines.length)
      continue
    }
    const position: ContextPosition =
      previousChanged && nextChanged
        ? 'INTER_HUNK'
        : previousChanged
          ? 'TRAILING'
          : 'LEADING'
    diff += appendContextBlock(part.lines, cursor, position)
  }
  if (diff.length > 0 && diff.endsWith('\n')) {
    diff = diff.slice(0, -1)
  }
  return diff
}

function appendContextBlock(
  block: string[],
  cursor: DiffCursor,
  position: ContextPosition,
): string {
  let diff = ''
  if (position === 'TRAILING') {
    diff += appendContextLines(block, 0, Math.min(block.length, DIFF_CONTEXT_LINES), cursor)
    if (block.length > DIFF_CONTEXT_LINES) {
      diff += '...\n'
      cursor.skip(block.length - DIFF_CONTEXT_LINES)
    }
    return diff
  }
  if (position === 'LEADING') {
    if (block.length <= DIFF_CONTEXT_LINES) {
      return diff + appendContextLines(block, 0, block.length, cursor)
    }
    cursor.skip(block.length - DIFF_CONTEXT_LINES)
    diff += '...\n'
    return diff + appendContextLines(block, block.length - DIFF_CONTEXT_LINES, block.length, cursor)
  }
  if (block.length <= 2 * DIFF_CONTEXT_LINES) {
    return diff + appendContextLines(block, 0, block.length, cursor)
  }
  diff += appendContextLines(block, 0, DIFF_CONTEXT_LINES, cursor)
  diff += '...\n'
  cursor.skip(block.length - 2 * DIFF_CONTEXT_LINES)
  return diff + appendContextLines(block, block.length - DIFF_CONTEXT_LINES, block.length, cursor)
}

function appendContextLines(
  block: string[],
  from: number,
  to: number,
  cursor: DiffCursor,
): string {
  let diff = ''
  for (let index = from; index < to; index++) {
    diff += cursor.appendContext(displayLine(block[index] as string))
  }
  return diff
}

/** diff 渲染游标：分开跟踪原文与结果文行号，并把编号右对齐到固定宽度。 */
class DiffCursor {
  private oldLine = 1
  private newLine = 1

  constructor(private readonly width: number) {}

  appendRemoved(line: string): string {
    return this.append('-', this.oldLine++, line)
  }

  appendAdded(line: string): string {
    return this.append('+', this.newLine++, line)
  }

  appendContext(line: string): string {
    const rendered = this.append(' ', this.oldLine, line)
    this.oldLine++
    this.newLine++
    return rendered
  }

  skip(lines: number): void {
    this.oldLine += lines
    this.newLine += lines
  }

  private append(marker: string, lineNumber: number, line: string): string {
    return `${marker}${String(lineNumber).padStart(this.width, ' ')}|${line}\n`
  }
}

export const fileCapabilities: Record<string, CapabilityExecutor> = {
  'fs.read': readExecutor,
  'fs.write': writeExecutor,
  'fs.edit': editExecutor,
  'fs.edit.preview': editPreviewExecutor,
}
