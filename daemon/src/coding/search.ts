import { closeSync, constants, openSync, readSync } from 'node:fs'
import { access, lstat, readFile } from 'node:fs/promises'
import { dirname, join, relative, resolve } from 'node:path'
import type { CapabilityExecutor, CapabilityResult } from '../contracts.js'
import { errorResult, textResult } from '../contracts.js'
import {
  ToolInputRejectedError,
  ToolRunFailureError,
  isAbsolute,
  optionalBoolean,
  optionalPositiveInt,
  optionalString,
  requiredString,
  resolveExistingPath,
  toErrorResult,
  toPosix,
} from './support.js'
import { OutputSpool, textOutputStoreFor } from './output.js'
import { PROBE_BYTES, detectEncoding, looksBinary, probe } from './text.js'
import {
  defaultRipgrepCommand,
  runRipgrep,
  type RipgrepCommand,
  type RipgrepResult,
} from './ripgrep.js'

/**
 * 检索能力（fs.find / fs.grep）的 ripgrep 适配。
 *
 * <p>find 用 `rg --files` 枚举允许路径与 glob 命中的路径后取交集；grep 用 `rg --json` 流式解析匹配。忽略规则、隐藏文件、符号链接、
 * 二进制文件与 Unicode 字符类都由显式参数固定为现有语义；超时/取消会 SIGKILL rg 并等待 close，结果上限达到时主动停止进程并保留
 * partial 结果。rg 退出码 2 不会被当成“无匹配”：可解析的路径错误进入 skipped，正则/glob 错误成为输入拒绝，其余成为运行失败。
 */

export const MAX_DISPLAY_LINE_CHARS = 500
export const MAX_SKIP_DETAIL = 10
const MAX_MULTILINE_FILE_BYTES = 64 * 1024 * 1024

export type SearchStopReason = 'TIMED_OUT' | 'CANCELLED'

export interface SkippedEntry {
  path: string
  reason: string
}

export interface SearchOutcome {
  lines: string[]
  limitReached: boolean
  skipped: SkippedEntry[]
  stopReason: SearchStopReason | null
}

interface GrepOptions {
  multiline: boolean
  literal: boolean
  ignoreCase: boolean
}

/** rg 的工作目录与显式 ignore 文件：glob 与 exclude 规则都相对仓库根解释。 */
interface SearchRoot {
  root: string
  cwd: string
  globPrefix: string
  excludeFile: string | null
}

interface OutputState {
  spoolClosed: boolean
}

/**
 * 构造检索能力；生产装配使用默认的 `@vscode/ripgrep` rgPath，测试可注入 Node 脚本作为确定性 rg 替身。
 */
export function createSearchCapabilities(
  command: RipgrepCommand = defaultRipgrepCommand,
): Record<string, CapabilityExecutor> {
  const findExecutor: CapabilityExecutor = async (call, context) => {
    const processes = new RipgrepProcesses(command)
    const output: OutputState = { spoolClosed: true }
    try {
      const args = call.arguments
      const pattern = argumentText('pattern', requiredString(args, 'pattern'))
      const path = await resolveExistingPath(requiredString(args, 'path'))
      const limit = optionalPositiveInt(args, 'limit', 1000, 100000)
      if (context.signal.aborted) {
        return emitSearchResult(
          call,
          context,
          {
            lines: [],
            limitReached: false,
            skipped: [],
            stopReason: stopReasonFromSignal(context.signal),
          },
          limit,
          'No files found matching pattern',
          output,
        )
      }
      await requireDirectory(path)
      if (isGitMetadata(path)) {
        return textResult(call.invocationId, 'No files found matching pattern')
      }
      if (pattern.length === 0) {
        return textResult(call.invocationId, 'No files found matching pattern')
      }
      const searchRoot = await prepareSearchRoot(path)

      const allowed = new Set<string>()
      const allowedResult = await listFiles(
        processes,
        searchRoot,
        null,
        context.signal,
        (entry) => {
          allowed.add(entry)
          return true
        },
      )
      const skipped = new Map<string, string>()
      addSkipped(skipped, allowedResult.skipped)
      if (allowedResult.stopReason !== null) {
        return emitSearchResult(
          call,
          context,
          {
            lines: [],
            limitReached: false,
            skipped: skippedList(skipped),
            stopReason: allowedResult.stopReason,
          },
          limit,
          'No files found matching pattern',
          output,
        )
      }
      if (context.signal.aborted) {
        return emitSearchResult(
          call,
          context,
          {
            lines: [],
            limitReached: false,
            skipped: skippedList(skipped),
            stopReason: stopReasonFromSignal(context.signal),
          },
          limit,
          'No files found matching pattern',
          output,
        )
      }

      const matches: string[] = []
      let limitReached = false
      // 即使 allowed 为空也要执行 pattern 阶段：非法 glob 必须在空目录下同样被拒绝，而不是报告无匹配。
      const patternResult = await listFiles(
        processes,
        searchRoot,
        pattern,
        context.signal,
        (entry) => {
          if (!allowed.has(entry)) {
            return true
          }
          matches.push(entry)
          if (matches.length > limit) {
            limitReached = true
            return false
          }
          return true
        },
      )
      addSkipped(skipped, patternResult.skipped)
      return emitSearchResult(
        call,
        context,
        {
          lines: matches,
          limitReached,
          skipped: skippedList(skipped),
          stopReason: patternResult.stopReason,
        },
        limit,
        'No files found matching pattern',
        output,
      )
    } catch (error) {
      return searchFailure(call.invocationId, error, context.signal)
    } finally {
      if ((!processes.started || processes.exited) && output.spoolClosed) {
        context.confirmExecutionExit()
      }
    }
  }

  const grepExecutor: CapabilityExecutor = async (call, context) => {
    const processes = new RipgrepProcesses(command)
    const output: OutputState = { spoolClosed: true }
    try {
      const args = call.arguments
      const pattern = argumentText('pattern', requiredString(args, 'pattern'))
      const path = await resolveExistingPath(requiredString(args, 'path'))
      const limit = optionalPositiveInt(args, 'limit', 100, 100000)
      const multiline = optionalBoolean(args, 'multiline')
      const literal = optionalBoolean(args, 'literal')
      const ignoreCase = optionalBoolean(args, 'ignore_case')
      const includeValue = optionalString(args, 'include')
      const include = includeValue === null ? null : argumentText('include', includeValue)
      const options: GrepOptions = { multiline, literal, ignoreCase }
      if (context.signal.aborted) {
        return emitSearchResult(
          call,
          context,
          {
            lines: [],
            limitReached: false,
            skipped: [],
            stopReason: stopReasonFromSignal(context.signal),
          },
          limit,
          'No matches found',
          output,
        )
      }

      const info = await lstat(path)
      if (info.isFile()) {
        await requireReadable(path)
        if (isGitMetadata(path)) {
          return textResult(call.invocationId, 'No matches found')
        }
        if (multiline) {
          await requireMultilineFileSize(path)
        }
        await rejectBinaryFile(path)
        if (context.signal.aborted) {
          return emitSearchResult(
            call,
            context,
            {
              lines: [],
              limitReached: false,
              skipped: [],
              stopReason: stopReasonFromSignal(context.signal),
            },
            limit,
            'No matches found',
            output,
          )
        }
        const outcome = await grepFiles(
          processes,
          fileSearchRoot(path),
          pattern,
          options,
          limit,
          context.signal,
          null,
        )
        return emitSearchResult(call, context, outcome, limit, 'No matches found', output)
      }
      if (!info.isDirectory()) {
        throw new ToolInputRejectedError('path must be a regular file or directory')
      }
      await requireReadable(path)
      if (isGitMetadata(path)) {
        return textResult(call.invocationId, 'No matches found')
      }
      if (context.signal.aborted) {
        return emitSearchResult(
          call,
          context,
          {
            lines: [],
            limitReached: false,
            skipped: [],
            stopReason: stopReasonFromSignal(context.signal),
          },
          limit,
          'No matches found',
          output,
        )
      }

      const skipped = new Map<string, string>()
      if (include !== null && include.length === 0) {
        return textResult(call.invocationId, 'No matches found')
      }
      const searchRoot = await prepareSearchRoot(path)
      let includeSet: Set<string> | null = null
      if (include !== null) {
        const matched = new Set<string>()
        const listResult = await listFiles(
          processes,
          searchRoot,
          include,
          context.signal,
          (entry) => {
            matched.add(entry)
            return true
          },
        )
        addSkipped(skipped, listResult.skipped)
        if (listResult.stopReason !== null) {
          return emitSearchResult(
            call,
            context,
            {
              lines: [],
              limitReached: false,
              skipped: skippedList(skipped),
              stopReason: listResult.stopReason,
            },
            limit,
            'No matches found',
            output,
          )
        }
        if (matched.size === 0) {
          return emitSearchResult(
            call,
            context,
            { lines: [], limitReached: false, skipped: skippedList(skipped), stopReason: null },
            limit,
            'No matches found',
            output,
          )
        }
        includeSet = matched
      }

      // include 只做结果过滤：grep 自身不带 glob，才能保留 .gitignore/exclude 对匹配文件的约束。
      const outcome = await grepFiles(
        processes,
        searchRoot,
        pattern,
        options,
        limit,
        context.signal,
        includeSet,
      )
      addSkipped(skipped, outcome.skipped)
      return emitSearchResult(
        call,
        context,
        { ...outcome, skipped: skippedList(skipped) },
        limit,
        'No matches found',
        output,
      )
    } catch (error) {
      return searchFailure(call.invocationId, error, context.signal)
    } finally {
      if ((!processes.started || processes.exited) && output.spoolClosed) {
        context.confirmExecutionExit()
      }
    }
  }

  return {
    'fs.find': findExecutor,
    'fs.grep': grepExecutor,
  }
}

export const searchCapabilities: Record<string, CapabilityExecutor> = createSearchCapabilities()

class RipgrepProcesses {
  started = false
  exited = false

  constructor(private readonly command: RipgrepCommand) {}

  async run(
    cwd: string,
    args: readonly string[],
    signal: AbortSignal,
    onChunk: (chunk: Buffer) => boolean | void,
  ): Promise<RipgrepResult> {
    const result = await runRipgrep(this.command, args, cwd, signal, onChunk)
    if (result.launched) {
      this.started = true
      this.exited = true
    }
    if (result.launchError !== null || result.spawnError !== null) {
      throw new ToolRunFailureError('ripgrep could not be started', 'INTERNAL')
    }
    if (result.consumerError !== null) {
      throw result.consumerError
    }
    return result
  }
}

/** 检索根的 rg 工作目录、相对 glob 前缀与显式 exclude 文件。 */
async function prepareSearchRoot(root: string): Promise<SearchRoot> {
  const repository = await repositoryRoot(root)
  if (repository === null) {
    return { root, cwd: root, globPrefix: '', excludeFile: null }
  }
  return {
    root,
    cwd: repository,
    globPrefix: toPosix(relative(repository, root)),
    excludeFile: await resolveExcludeFile(repository),
  }
}

/** 直接文件检索不使用 glob 或忽略规则；cwd 只用于解释 rg 的相对错误路径。 */
function fileSearchRoot(path: string): SearchRoot {
  const directory = dirname(path)
  return { root: path, cwd: directory, globPrefix: '', excludeFile: null }
}

async function repositoryRoot(path: string): Promise<string | null> {
  let current = path
  for (;;) {
    try {
      await lstat(join(current, '.git'))
      return current
    } catch {
      // 继续向上寻找最近的仓库根。
    }
    const parent = dirname(current)
    if (parent === current) {
      return null
    }
    current = parent
  }
}

/** 解析 `.git/info/exclude`，linked worktree 经 gitdir 指针与 commondir 落到共享仓库。 */
async function resolveExcludeFile(repository: string): Promise<string | null> {
  const dotGit = join(repository, '.git')
  let info
  try {
    info = await lstat(dotGit)
  } catch {
    return null
  }
  let gitDir: string
  if (info.isDirectory()) {
    gitDir = dotGit
  } else if (info.isFile()) {
    const pointer = await readGitdirPointer(dotGit, repository)
    if (pointer === null) {
      return null
    }
    gitDir = pointer
  } else {
    return null
  }
  const commonDir = await readCommondir(gitDir)
  const candidates = commonDir === null ? [gitDir] : [commonDir, gitDir]
  for (const directory of candidates) {
    const exclude = await existingFile(join(directory, 'info', 'exclude'))
    if (exclude !== null) {
      return exclude
    }
  }
  return null
}

async function readGitdirPointer(dotGitFile: string, repository: string): Promise<string | null> {
  const line = await firstLine(dotGitFile)
  if (line === null || !line.startsWith('gitdir:')) {
    return null
  }
  const value = line.slice('gitdir:'.length).trim()
  return value.length === 0 ? null : resolve(repository, value)
}

async function readCommondir(gitDir: string): Promise<string | null> {
  const line = await firstLine(join(gitDir, 'commondir'))
  return line === null || line.length === 0 ? null : resolve(gitDir, line)
}

async function firstLine(file: string): Promise<string | null> {
  try {
    const content = await readFile(file, 'utf8')
    return content.split(/\r\n|\n|\r/)[0] ?? ''
  } catch {
    return null
  }
}

async function existingFile(path: string): Promise<string | null> {
  try {
    return (await lstat(path)).isFile() ? path : null
  } catch {
    return null
  }
}

/** 固定忽略与隐藏文件语义；`--no-config` 防止宿主 RIPGREP_CONFIG_PATH 改变结果。 */
function baseArgs(root: SearchRoot): string[] {
  const args = [
    '--no-config',
    '--no-ignore-dot',
    '--no-ignore-global',
    '--no-ignore-exclude',
    '--no-require-git',
    '--hidden',
  ]
  if (root.excludeFile !== null) {
    args.push('--ignore-file', root.excludeFile)
  }
  return args
}

/** `.git` 目录与指向 gitdir 的 `.git` 文件都不进入检索。 */
const GIT_EXCLUDE = ['--glob', '!.git'] as const

/** 含斜杠的 glob 相对检索根解释；rg 默认相对 cwd，因此补上仓库根到检索根的相对前缀。 */
function searchGlob(root: SearchRoot, pattern: string): string {
  if (root.globPrefix === '') {
    return pattern
  }
  const negated = pattern.startsWith('!')
  const body = negated ? pattern.slice(1) : pattern
  const adjusted = body.includes('/') ? `${root.globPrefix}/${body}` : body
  return negated ? `!${adjusted}` : adjusted
}

function fileArgs(root: SearchRoot, pattern: string | null): string[] {
  const args = ['--files', ...baseArgs(root), '--sort', 'path', '--null']
  if (pattern !== null) {
    args.push('--glob', searchGlob(root, pattern))
  }
  args.push(...GIT_EXCLUDE, root.root)
  return args
}

function grepArgs(root: SearchRoot, pattern: string, options: GrepOptions): string[] {
  const args = ['--json', ...baseArgs(root), '--sort', 'path']
  args.push(...GIT_EXCLUDE, '--no-unicode', '--crlf')
  if (options.literal) {
    args.push('--fixed-strings')
  }
  if (options.ignoreCase) {
    args.push('--ignore-case')
  }
  if (options.multiline) {
    args.push('--multiline')
  }
  args.push('-e', pattern, root.root)
  return args
}

interface ListResult {
  stopReason: SearchStopReason | null
  skipped: SkippedEntry[]
}

async function listFiles(
  processes: RipgrepProcesses,
  root: SearchRoot,
  pattern: string | null,
  signal: AbortSignal,
  onPath: (path: string) => boolean,
): Promise<ListResult> {
  if (signal.aborted) {
    return { stopReason: stopReasonFromSignal(signal), skipped: [] }
  }
  const splitter = new DelimitedBuffer(0, (value) => {
    const path = toPosix(value)
    return path.length === 0 ? true : onPath(path)
  })
  const result = await processes.run(root.cwd, fileArgs(root, pattern), signal, (chunk) =>
    splitter.push(chunk),
  )
  if (result.stopped !== null) {
    return {
      stopReason: result.stopped === 'abort' ? stopReasonFromSignal(signal) : null,
      skipped: [],
    }
  }
  splitter.finish()
  const skipped = failureSkipped(result, root.cwd)
  return { stopReason: null, skipped }
}

async function grepFiles(
  processes: RipgrepProcesses,
  root: SearchRoot,
  pattern: string,
  options: GrepOptions,
  limit: number,
  signal: AbortSignal,
  includeSet: Set<string> | null,
): Promise<SearchOutcome> {
  if (signal.aborted) {
    return {
      lines: [],
      limitReached: false,
      skipped: [],
      stopReason: stopReasonFromSignal(signal),
    }
  }
  const binaryPaths = new Map<string, boolean>()
  const lines: string[] = []
  let matches = 0
  let moreMatches = false
  let emittedThroughLine = -1
  let currentPath = ''
  const splitter = new DelimitedBuffer(0x0a, (raw) => {
    if (raw.length === 0) {
      return true
    }
    const event = JSON.parse(raw) as RipgrepEvent
    if (event.type !== 'match' || event.data === undefined) {
      return true
    }
    const data = event.data
    const path = eventPath(data)
    if (path === null) {
      return true
    }
    const display = toPosix(path)
    if (display !== currentPath) {
      currentPath = display
      emittedThroughLine = -1
    }
    if (includeSet !== null && !includeSet.has(display)) {
      return true
    }
    // rg 可能先输出了 NUL 之前的匹配；旧契约按前 8KiB 探针把整个文件视为二进制并静默跳过。
    let binary = binaryPaths.get(path)
    if (binary === undefined) {
      binary = probeLooksBinary(path)
      binaryPaths.set(path, binary)
    }
    if (binary) {
      return true
    }
    const text = eventLines(data)
    if (text === null) {
      return true
    }
    const block = blockLines(text)
    const firstLine = data.line_number ?? 1
    const baseLine = firstLine - 1
    const submatches = data.submatches ?? []
    if (options.multiline) {
      if (submatches.length === 0) {
        if (matches >= limit) {
          moreMatches = true
          return false
        }
        lines.push(`${display}:${firstLine}:${matchCenteredExcerpt(block[0]?.content ?? '', 0, 0)}`)
        matches++
        return true
      }
      for (const submatch of submatches) {
        if (matches >= limit) {
          moreMatches = true
          return false
        }
        const startChar = charIndexAtByte(text, submatch.start ?? 0)
        const endChar = charIndexAtByte(text, submatch.end ?? submatch.start ?? 0)
        const startLine = baseLine + lineIndexAt(block, startChar)
        const coveredEnd = endChar > startChar ? endChar - 1 : startChar
        const endLine = baseLine + lineIndexAt(block, coveredEnd)
        for (let index = Math.max(startLine, emittedThroughLine + 1); index <= endLine; index++) {
          const line = block[index - baseLine] as BlockLine
          const excerptStart = Math.max(0, startChar - line.startChar)
          const excerptEnd = Math.min(line.content.length, endChar - line.startChar)
          lines.push(
            `${display}:${index + 1}:${matchCenteredExcerpt(
              line.content,
              excerptStart,
              Math.max(excerptStart, excerptEnd),
            )}`,
          )
          emittedThroughLine = index
        }
        matches++
      }
      return true
    }

    if (matches >= limit) {
      moreMatches = true
      return false
    }
    const line = block[0] as BlockLine
    const submatch = submatches[0]
    const startChar = charIndexAtByte(text, submatch?.start ?? 0)
    const endChar = charIndexAtByte(text, submatch?.end ?? submatch?.start ?? 0)
    const excerptStart = Math.max(0, startChar - line.startChar)
    const excerptEnd = Math.min(line.content.length, endChar - line.startChar)
    lines.push(
      `${display}:${firstLine}:${matchCenteredExcerpt(
        line.content,
        excerptStart,
        Math.max(excerptStart, excerptEnd),
      )}`,
    )
    matches++
    return true
  })

  const result = await processes.run(
    root.cwd,
    grepArgs(root, pattern, options),
    signal,
    (chunk) => splitter.push(chunk),
  )
  if (result.stopped !== null) {
    return {
      lines,
      limitReached: moreMatches,
      skipped: [],
      stopReason: result.stopped === 'abort' ? stopReasonFromSignal(signal) : null,
    }
  }
  splitter.finish()
  return {
    lines,
    limitReached: moreMatches,
    skipped: failureSkipped(result, root.cwd),
    stopReason: null,
  }
}

/** 与旧检索一致：前 8KiB 含 NUL/控制字符（UTF-16 BOM 除外）即按二进制静默跳过。 */
function probeLooksBinary(path: string): boolean {
  let fd: number | null = null
  try {
    fd = openSync(path, 'r')
    const buffer = Buffer.allocUnsafe(PROBE_BYTES)
    const bytesRead = readSync(fd, buffer, 0, PROBE_BYTES, 0)
    const bytes = buffer.subarray(0, bytesRead)
    return looksBinary(bytes, detectEncoding(bytes))
  } catch {
    return false
  } finally {
    if (fd !== null) {
      closeSync(fd)
    }
  }
}

/** 把 rg 退出码 2 的 stderr 映射为输入拒绝、路径 skipped 或运行失败，绝不返回“无匹配”。 */
function failureSkipped(result: RipgrepResult, root: string): SkippedEntry[] {
  if (result.code !== 2) {
    if (result.code !== 0 && result.code !== 1) {
      throw new ToolRunFailureError('ripgrep exited unexpectedly', 'INTERNAL')
    }
    return []
  }
  const failure = classifyFailure(result.stderr, root)
  if (failure.input !== null) {
    throw new ToolInputRejectedError(failure.input)
  }
  if (failure.fatal) {
    throw new ToolRunFailureError('ripgrep failed while searching; results are incomplete', 'FAILED')
  }
  return failure.skipped
}

interface FailureClassification {
  input: string | null
  skipped: SkippedEntry[]
  fatal: boolean
}

function classifyFailure(stderr: string, root: string): FailureClassification {
  const output = stderr.trim()
  if (output.length === 0) {
    return { input: null, skipped: [], fatal: true }
  }
  if (/regex parse error/i.test(output)) {
    return {
      input: `Invalid regex: ${firstDiagnostic(output)}. Use literal=true for exact text, or escape regex metacharacters.`,
      skipped: [],
      fatal: false,
    }
  }
  if (/error parsing glob/i.test(output)) {
    return { input: `Invalid glob: ${firstDiagnostic(output)}`, skipped: [], fatal: false }
  }
  const skipped: SkippedEntry[] = []
  let fatal = false
  for (const line of output.split(/\r?\n/)) {
    if (!line.startsWith('rg: ')) {
      fatal = true
      continue
    }
    const separator = line.indexOf(': ', 'rg: '.length)
    if (separator < 0) {
      fatal = true
      continue
    }
    const rawPath = line.slice('rg: '.length, separator)
    const absolute = isAbsolute(rawPath) ? rawPath : resolve(root, rawPath)
    const path = toPosix(absolute)
    if (!skipped.some((entry) => entry.path === path)) {
      skipped.push({ path, reason: 'could not be read' })
    }
  }
  return { input: null, skipped, fatal }
}

function firstDiagnostic(stderr: string): string {
  const lines = stderr
    .split(/\r?\n/)
    .map((value) => value.trim())
    .filter((value) => value.length > 0)
  const errorLine = lines.find((value) => value.startsWith('error: '))
  if (errorLine !== undefined) {
    return errorLine.slice('error: '.length)
  }
  return (lines[0] ?? 'invalid pattern').replace(/^rg: /, '')
}

interface BlockLine {
  startChar: number
  content: string
}

/** rg 的 `lines.text` 是完整行块；按 LF 切分并去掉 CRLF 的 CR，保留空行。 */
function blockLines(text: string): BlockLine[] {
  const lines: BlockLine[] = []
  let start = 0
  for (;;) {
    const newline = text.indexOf('\n', start)
    const end = newline < 0 ? text.length : newline
    const contentEnd = end > start && text.charCodeAt(end - 1) === 0x0d ? end - 1 : end
    lines.push({ startChar: start, content: text.slice(start, contentEnd) })
    if (newline < 0) {
      break
    }
    start = newline + 1
  }
  if (text.endsWith('\n') && lines.length > 1) {
    lines.pop()
  }
  return lines.length === 0 ? [{ startChar: 0, content: '' }] : lines
}

function lineIndexAt(lines: BlockLine[], offset: number): number {
  let low = 0
  let high = lines.length - 1
  while (low < high) {
    const middle = (low + high + 1) >>> 1
    if ((lines[middle] as BlockLine).startChar <= offset) {
      low = middle
    } else {
      high = middle - 1
    }
  }
  return low
}

/** rg JSON 的 submatch 偏移是 UTF-8 字节；展示截取需要 JS 字符下标。 */
function charIndexAtByte(text: string, byteOffset: number): number {
  if (byteOffset <= 0) {
    return 0
  }
  const bytes = Buffer.from(text, 'utf8')
  if (byteOffset >= bytes.length) {
    return text.length
  }
  return bytes.subarray(0, byteOffset).toString('utf8').length
}

interface RipgrepEvent {
  type?: string
  data?: {
    path?: { text?: string; bytes?: string }
    lines?: { text?: string; bytes?: string }
    line_number?: number
    submatches?: Array<{ start?: number; end?: number }>
  }
}

function eventPath(data: NonNullable<RipgrepEvent['data']>): string | null {
  return decodedValue(data.path)
}

function eventLines(data: NonNullable<RipgrepEvent['data']>): string | null {
  return decodedValue(data.lines)
}

function decodedValue(value: { text?: string; bytes?: string } | undefined): string | null {
  if (value === undefined) {
    return null
  }
  if (typeof value.text === 'string') {
    return value.text
  }
  return typeof value.bytes === 'string' ? Buffer.from(value.bytes, 'base64').toString('utf8') : null
}

/** 以单字节分隔符切分流式输出；分隔符不会出现在 UTF-8 多字节序列内部，因此按字节切分是安全的。 */
class DelimitedBuffer {
  private pending = Buffer.alloc(0)

  constructor(
    private readonly delimiter: number,
    private readonly onValue: (value: string) => boolean,
  ) {}

  push(chunk: Buffer): boolean {
    const data = this.pending.length === 0 ? chunk : Buffer.concat([this.pending, chunk])
    let start = 0
    for (;;) {
      const index = data.indexOf(this.delimiter, start)
      if (index < 0) {
        break
      }
      const value = data.subarray(start, index).toString('utf8')
      start = index + 1
      if (!this.onValue(value)) {
        this.pending = Buffer.alloc(0)
        return false
      }
    }
    this.pending = start === 0 ? Buffer.from(data) : Buffer.from(data.subarray(start))
    return true
  }

  finish(): void {
    if (this.pending.length === 0) {
      return
    }
    const value = this.pending.toString('utf8')
    this.pending = Buffer.alloc(0)
    this.onValue(value)
  }
}

async function emitSearchResult(
  call: { invocationId: string },
  context: Parameters<CapabilityExecutor>[1],
  outcome: SearchOutcome,
  limit: number,
  emptyText: string,
  output: OutputState,
): Promise<CapabilityResult> {
  const { lines, limitReached, skipped, stopReason } = outcome
  if (stopReason === null && lines.length === 0) {
    if (skipped.length > 0) {
      return errorResult(
        call.invocationId,
        `${emptyText}, but ${skipped.length} path(s) could not be searched: [${describeSkipped(skipped)}]`,
      )
    }
    return textResult(call.invocationId, emptyText)
  }

  const store = await textOutputStoreFor(context.config.dataDir)
  const spool = new OutputSpool(store, call.invocationId)
  output.spoolClosed = false
  try {
    const displayCount = Math.min(limit, lines.length)
    for (let index = 0; index < displayCount; index++) {
      if (index > 0) {
        await spool.write(utf8('\n'))
      }
      await spool.write(utf8(lines[index] as string))
    }
    if (limitReached) {
      const prefix = displayCount > 0 ? '\n\n' : ''
      await spool.write(
        utf8(`${prefix}[${limit} results limit reached. Refine the pattern or raise limit.]`),
      )
    }
    if (skipped.length > 0) {
      const prefix = displayCount > 0 || limitReached ? '\n\n' : ''
      await spool.write(
        utf8(
          `${prefix}[${skipped.length} path(s) could not be searched: ${describeSkipped(skipped)}]`,
        ),
      )
    }
    if (stopReason !== null) {
      const hasPrecedingText = displayCount > 0 || limitReached || skipped.length > 0
      const prefix = hasPrecedingText ? '\n\n' : ''
      await spool.write(utf8(`${prefix}${stopNoticeFor(stopReason)}`))
    }
    const result = await spool.finish(stopReason !== null)
    if (stopReason !== null) {
      applyStoppedDetails(result, stopReason)
    }
    return result
  } finally {
    await spool.close()
    output.spoolClosed = true
  }
}

function searchFailure(callId: string, error: unknown, signal: AbortSignal): CapabilityResult {
  const result = toErrorResult(callId, error)
  if (signal.aborted) {
    const reason = signal.reason === 'TIMED_OUT' ? 'TIMED_OUT' : 'CANCELLED'
    result.details = { ...result.details, search: { outcome: reason, incomplete: true } }
    result.contents.push({ type: 'text', text: stopNoticeFor(reason) })
  }
  return result
}

function applyStoppedDetails(result: CapabilityResult, reason: SearchStopReason): void {
  result.details = {
    ...result.details,
    code: reason === 'TIMED_OUT' ? 'TIMEOUT' : 'CANCELLED',
    search: { outcome: reason, incomplete: true },
  }
}

function stopNoticeFor(reason: SearchStopReason): string {
  return reason === 'TIMED_OUT'
    ? '[Search timed out; results are incomplete.]'
    : '[Search cancelled; results are incomplete.]'
}

function stopReasonFromSignal(signal: AbortSignal): SearchStopReason {
  return signal.reason === 'TIMED_OUT' ? 'TIMED_OUT' : 'CANCELLED'
}

function describeSkipped(skipped: SkippedEntry[]): string {
  const parts: string[] = []
  let shown = 0
  for (const entry of skipped) {
    if (shown === MAX_SKIP_DETAIL) {
      parts.push('...')
      break
    }
    parts.push(`${entry.path} (${entry.reason})`)
    shown++
  }
  return parts.join(', ')
}

function addSkipped(target: Map<string, string>, entries: SkippedEntry[]): void {
  for (const entry of entries) {
    if (!target.has(entry.path)) {
      target.set(entry.path, entry.reason)
    }
  }
}

function skippedList(skipped: Map<string, string>): SkippedEntry[] {
  return [...skipped].map(([path, reason]) => ({ path, reason }))
}

async function requireDirectory(path: string): Promise<void> {
  let info
  try {
    info = await lstat(path)
  } catch {
    throw new ToolInputRejectedError('path must be a directory')
  }
  if (!info.isDirectory()) {
    throw new ToolInputRejectedError('path must be a directory')
  }
  await requireReadable(path)
}

async function requireReadable(path: string): Promise<void> {
  try {
    await access(path, constants.R_OK)
  } catch {
    throw new ToolInputRejectedError(`path is not readable: ${toPosix(path)}`)
  }
}

async function rejectBinaryFile(path: string): Promise<void> {
  let bytes: Uint8Array
  try {
    bytes = await probe(path)
  } catch {
    throw new ToolRunFailureError(`path is not readable: ${toPosix(path)}`, 'FAILED')
  }
  if (looksBinary(bytes, detectEncoding(bytes))) {
    throw new ToolRunFailureError(`file appears to be binary: ${toPosix(path)}`)
  }
}

/** 多行检索保留单文件 64 MiB 上界；目录扫描由 rg 流式处理，不逐个预读。 */
async function requireMultilineFileSize(path: string): Promise<void> {
  let size: number
  try {
    size = (await lstat(path)).size
  } catch {
    throw new ToolRunFailureError(`path is not readable: ${toPosix(path)}`, 'FAILED')
  }
  if (size > MAX_MULTILINE_FILE_BYTES) {
    throw new ToolRunFailureError(
      `multiline search is limited to ${MAX_MULTILINE_FILE_BYTES / (1024 * 1024)} MiB per file: ${toPosix(path)}`,
    )
  }
}

function isGitMetadata(path: string): boolean {
  return path.split(/[\\/]/).some((segment) => segment === '.git')
}

function argumentText(name: string, value: string): string {
  if (value.includes('\0')) {
    throw new ToolInputRejectedError(`${name} must not contain NUL`)
  }
  return value
}

/** 超长行只在“展示”时按字符预算居中截取；匹配本身覆盖整行。 */
export function matchCenteredExcerpt(
  line: string,
  matchStartChar: number,
  matchEndChar: number,
): string {
  const codePoints = Array.from(line)
  const totalCp = codePoints.length
  if (totalCp <= MAX_DISPLAY_LINE_CHARS) {
    return line
  }
  const matchStartCp = codePointIndexAt(line, Math.min(line.length, Math.max(0, matchStartChar)))
  const matchEndCp = codePointIndexAt(
    line,
    Math.min(line.length, Math.max(matchStartChar, matchEndChar)),
  )
  const matchCenter = Math.floor((matchStartCp + matchEndCp) / 2)
  const budget = 440
  let startCp = Math.max(0, matchCenter - Math.floor(budget / 2))
  const endCp = Math.min(totalCp, startCp + budget)
  if (endCp === totalCp) {
    startCp = Math.max(0, endCp - budget)
  }
  const excerpt = codePoints.slice(startCp, endCp).join('')
  let result = ''
  if (startCp > 0) {
    result += '... '
  }
  result += excerpt
  if (endCp < totalCp) {
    result += ' ... (line truncated to 500 chars)'
  }
  return result
}

function codePointIndexAt(value: string, charIndex: number): number {
  return Array.from(value.slice(0, charIndex)).length
}

function utf8(value: string): Uint8Array {
  return new Uint8Array(Buffer.from(value, 'utf8'))
}
