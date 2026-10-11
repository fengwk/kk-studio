import { constants } from 'node:fs'
import { access, lstat, readFile, readdir } from 'node:fs/promises'
import { basename, dirname, join, relative, resolve } from 'node:path'
import ignore from 'ignore'
import picomatch from 'picomatch'
import { RE2 } from 're2-wasm'
import {
  CancelledError,
  ToolInputRejectedError,
  ToolRunFailureError,
  toPosix,
} from './support.js'
import { decodeText, detectEncoding, forEachLine, looksBinary, probe, readAllBytes } from './text.js'

/**
 * 搜索核心（fs.find / fs.grep 的可移植实现）。
 *
 * <p>与 Java 参考实现对齐：忽略规则从检索起点自身的祖先链解析分层 {@code .gitignore}（仓库根及其后代，last-match-wins）；glob 用 picomatch，正则用
 * RE2（线性、无回溯）；超时/取消与读取失败绝不报告为“无匹配”，未搜索路径显式列出。单行模式流式扫描（内存与文件大小无关），多行模式保留 64 MiB 显式上界。
 *
 * <p>本模块不含能力层与输出落盘：它由 Worker 线程独占执行（真实搜索、可被硬终止），结果以可结构化克隆的纯数据返回，输出 spool 只在主线程共享一份。
 */

export const MAX_DISPLAY_LINE_CHARS = 500
export const MAX_SKIP_DETAIL = 10
export const MAX_MULTILINE_FILE_BYTES = 64 * 1024 * 1024

/** 未搜索路径及原因。 */
export interface SkippedEntry {
  path: string
  reason: string
}

/** fs.find 的纯结果。 */
export interface FindOutcome {
  matches: string[]
  limitReached: boolean
  skipped: SkippedEntry[]
}

/** fs.grep 的纯结果。 */
export interface GrepOutcome {
  lines: string[]
  moreMatches: boolean
  skipped: SkippedEntry[]
}

export interface GrepOptions {
  multiline: boolean
  literal: boolean
  ignoreCase: boolean
  include: string | null
}

/** 主线程下发、Worker 消费的搜索作业（结构化克隆友好）。 */
export interface FindJob {
  kind: 'find'
  pattern: string
  path: string
  limit: number
  timeoutMillis: number
}

export interface GrepJob {
  kind: 'grep'
  pattern: string
  path: string
  limit: number
  multiline: boolean
  literal: boolean
  ignoreCase: boolean
  include: string | null
  timeoutMillis: number
}

export type SearchJob = FindJob | GrepJob

/** Worker 回传的稳定结果；失败按类别保留可重建的稳定错误语义。 */
export type SearchReply =
  | ({ ok: true; kind: 'find' } & FindOutcome)
  | ({ ok: true; kind: 'grep' } & GrepOutcome)
  | { ok: false; error: 'input' | 'run' | 'cancelled'; message: string; code: string }

/** 统一检查 deadline；Worker 无 AbortSignal，取消由主线程终止 Worker 实现。 */
export class SearchControl {
  private readonly started = Date.now()

  constructor(
    private readonly timeoutMillis: number,
    private readonly capabilityName: string,
  ) {}

  check(): void {
    if (this.timeoutMillis > 0 && Date.now() - this.started >= this.timeoutMillis) {
      throw new ToolRunFailureError(
        `${this.capabilityName} timed out after ${this.timeoutMillis} milliseconds`,
        'TIMEOUT',
      )
    }
  }
}

// ---------------------------------------------------------------------------
// glob / 正则 / include
// ---------------------------------------------------------------------------

function compileGlob(glob: string): (test: string) => boolean {
  return picomatch(glob, { dot: true })
}

interface IncludePattern {
  matches(path: string, base: string): boolean
}

function compileInclude(source: string | null): IncludePattern {
  if (source === null) {
    return { matches: () => true }
  }
  const pathPattern = source.includes('/')
  const matcher = compileGlob(source)
  return { matches: (path, base) => matcher(pathPattern ? path : base) }
}

function escapeLiteral(source: string): string {
  return source.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function compileRegex(
  source: string,
  literal: boolean,
  ignoreCase: boolean,
  multiline: boolean,
): RE2 {
  const flags = `u${ignoreCase ? 'i' : ''}${multiline ? 'm' : ''}`
  try {
    return new RE2(literal ? escapeLiteral(source) : source, flags)
  } catch (error) {
    const message = error instanceof Error ? error.message : 'invalid pattern'
    throw new ToolInputRejectedError(
      `Invalid regex: ${message}. Use literal=true for exact text, or escape regex metacharacters.`,
    )
  }
}

// ---------------------------------------------------------------------------
// 分层 .gitignore
// ---------------------------------------------------------------------------

interface RuleSet {
  base: string
  ig: ReturnType<typeof ignore>
}

class GitIgnoreRules {
  constructor(private readonly rules: RuleSet[]) {}

  static async prepare(
    searchDirectory: string,
    control: SearchControl,
  ): Promise<{ rules: GitIgnoreRules; searchDirectoryIgnored: boolean }> {
    const target = resolve(searchDirectory)
    const chain = ancestorChain(target)
    const repositoryIndex = await repositoryRootIndex(chain)
    const start = repositoryIndex >= 0 ? repositoryIndex : 0

    let context = new GitIgnoreRules([])
    if (repositoryIndex >= 0) {
      context = await context.withExcludeRules(chain[repositoryIndex] as string, control)
    }
    for (let index = start; index < chain.length; index++) {
      control.check()
      const directory = chain[index] as string
      if (index > start && (await context.isIgnored(directory, true, control))) {
        return { rules: context, searchDirectoryIgnored: true }
      }
      context = await context.withDirectoryRules(directory, control)
    }
    return { rules: context, searchDirectoryIgnored: false }
  }

  withDirectoryRules(directory: string, control: SearchControl): Promise<GitIgnoreRules> {
    return this.appendRules(join(directory, '.gitignore'), directory, control)
  }

  async withExcludeRules(repositoryRoot: string, control: SearchControl): Promise<GitIgnoreRules> {
    const excludeFile = await resolveExcludeFile(repositoryRoot)
    return excludeFile === null ? this : this.appendRules(excludeFile, repositoryRoot, control)
  }

  async isIgnored(path: string, directory: boolean, control: SearchControl): Promise<boolean> {
    if (isGitMetadata(path)) {
      return true
    }
    let ignored = false
    for (const rule of this.rules) {
      control.check()
      const rel = toPosix(relative(rule.base, path))
      if (rel === '' || rel.startsWith('..')) {
        continue
      }
      const result = rule.ig.test(directory ? `${rel}/` : rel)
      if (result.ignored) {
        ignored = true
      } else if (result.unignored) {
        ignored = false
      }
    }
    return ignored
  }

  private async appendRules(
    ruleFile: string,
    baseDirectory: string,
    control: SearchControl,
  ): Promise<GitIgnoreRules> {
    let content: string
    try {
      const info = await lstat(ruleFile)
      if (!info.isFile()) {
        return this
      }
      await access(ruleFile, constants.R_OK)
      content = await readFile(ruleFile, 'utf8')
    } catch {
      return this
    }
    const ig = ignore()
    let added = 0
    for (const rawLine of content.split(/\r\n|\n|\r/)) {
      control.check()
      const line = rawLine
      if (line.length === 0 || line.startsWith('#')) {
        continue
      }
      try {
        ig.add(line)
        added++
      } catch {
        // 空行、comment 或无效 pattern：git 语义下不产生任何规则。
      }
    }
    if (added === 0) {
      return this
    }
    return new GitIgnoreRules([...this.rules, { base: baseDirectory, ig }])
  }
}

function ancestorChain(target: string): string[] {
  const chain: string[] = []
  let current = resolve(target)
  for (;;) {
    chain.push(current)
    const parent = dirname(current)
    if (parent === current) {
      break
    }
    current = parent
  }
  chain.reverse()
  return chain
}

async function repositoryRootIndex(chain: string[]): Promise<number> {
  for (let index = chain.length - 1; index >= 0; index--) {
    if (await pathExistsNoFollow(join(chain[index] as string, '.git'))) {
      return index
    }
  }
  return -1
}

async function pathExistsNoFollow(path: string): Promise<boolean> {
  try {
    await lstat(path)
    return true
  } catch {
    return false
  }
}

async function resolveExcludeFile(repositoryRoot: string): Promise<string | null> {
  const dotGit = join(repositoryRoot, '.git')
  let gitDir: string | null
  let info
  try {
    info = await lstat(dotGit)
  } catch {
    return null
  }
  if (info.isDirectory()) {
    gitDir = dotGit
  } else if (info.isFile()) {
    gitDir = await readGitDirPointer(dotGit, repositoryRoot)
    if (gitDir === null) {
      return null
    }
  } else {
    return null
  }
  const commonDir = await readCommonDir(gitDir)
  const commonExclude = await excludeFile(commonDir)
  if (commonExclude !== null) {
    return commonExclude
  }
  return excludeFile(gitDir)
}

async function excludeFile(gitDir: string | null): Promise<string | null> {
  if (gitDir === null) {
    return null
  }
  const exclude = join(gitDir, 'info', 'exclude')
  return (await pathExistsNoFollow(exclude)) ? exclude : null
}

async function readGitDirPointer(dotGitFile: string, repositoryRoot: string): Promise<string | null> {
  try {
    const content = await readFile(dotGitFile, 'utf8')
    const line = content.split(/\r\n|\n|\r/)[0] ?? ''
    if (!line.startsWith('gitdir:')) {
      return null
    }
    const value = line.slice('gitdir:'.length).trim()
    if (value.length === 0) {
      return null
    }
    return resolve(repositoryRoot, value)
  } catch {
    return null
  }
}

async function readCommonDir(gitDir: string): Promise<string | null> {
  const commonFile = join(gitDir, 'commondir')
  if (!(await pathExistsNoFollow(commonFile))) {
    return null
  }
  try {
    const content = await readFile(commonFile, 'utf8')
    const line = (content.split(/\r\n|\n|\r/)[0] ?? '').trim()
    if (line.length === 0) {
      return null
    }
    return resolve(gitDir, line)
  } catch {
    return null
  }
}

function isGitMetadata(path: string): boolean {
  return path.split(/[\\/]/).some((segment) => segment === '.git')
}

// ---------------------------------------------------------------------------
// 无符号链接遍历
// ---------------------------------------------------------------------------

type FileConsumer = (file: string) => Promise<boolean>

async function walk(
  searchDirectory: string,
  control: SearchControl,
  consumer: FileConsumer,
): Promise<{ unreadable: string[] }> {
  await requireReadableDirectory(searchDirectory)
  const target = resolve(searchDirectory)
  const prepared = await GitIgnoreRules.prepare(target, control)
  if (prepared.searchDirectoryIgnored) {
    return { unreadable: [] }
  }
  const unreadable: string[] = []
  await walkDirectory(target, prepared.rules, control, consumer, unreadable)
  return { unreadable }
}

async function walkDirectory(
  directory: string,
  rules: GitIgnoreRules,
  control: SearchControl,
  consumer: FileConsumer,
  unreadable: string[],
): Promise<boolean> {
  control.check()
  let entries
  try {
    entries = await readdir(directory, { withFileTypes: true })
  } catch {
    unreadable.push(directory)
    return true
  }
  entries.sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0))
  for (const entry of entries) {
    control.check()
    const full = join(directory, entry.name)
    if (entry.isSymbolicLink()) {
      continue
    }
    if (entry.isDirectory()) {
      if (!(await rules.isIgnored(full, true, control))) {
        const nextRules = await rules.withDirectoryRules(full, control)
        const continueWalk = await walkDirectory(full, nextRules, control, consumer, unreadable)
        if (!continueWalk) {
          return false
        }
      }
    } else if (entry.isFile()) {
      if (!(await isReadable(full))) {
        unreadable.push(full)
        continue
      }
      if (!(await rules.isIgnored(full, false, control))) {
        const continueWalk = await consumer(full)
        if (!continueWalk) {
          return false
        }
      }
    }
  }
  return true
}

async function requireReadableDirectory(directory: string): Promise<void> {
  let info
  try {
    info = await lstat(directory)
  } catch {
    throw new ToolInputRejectedError('path must be a directory')
  }
  if (!info.isDirectory()) {
    throw new ToolInputRejectedError('path must be a directory')
  }
  if (!(await isReadable(directory))) {
    throw new ToolInputRejectedError(`path is not readable: ${directory}`)
  }
}

async function isReadable(path: string): Promise<boolean> {
  try {
    await access(path, constants.R_OK)
    return true
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// 纯作业入口
// ---------------------------------------------------------------------------

/** 执行一次 fs.find；不做能力层错误包装与输出落盘。 */
export async function findFiles(
  sourcePattern: string,
  path: string,
  limit: number,
  control: SearchControl,
): Promise<FindOutcome> {
  const pathPattern = sourcePattern.includes('/')
  control.check()
  const matcher = compileGlob(sourcePattern)
  control.check()

  const matched: string[] = []
  const skipped = new Map<string, string>()
  const report = await walk(path, control, async (file) => {
    control.check()
    const searchRelative = toPosix(relative(path, file))
    if (matcher(pathPattern ? searchRelative : basename(file))) {
      matched.push(toPosix(file))
      if (matched.length > limit) {
        return false
      }
    }
    return true
  })
  for (const unreadable of report.unreadable) {
    if (!skipped.has(toPosix(unreadable))) {
      skipped.set(toPosix(unreadable), 'could not be read')
    }
  }
  return {
    matches: matched,
    limitReached: matched.length > limit,
    skipped: [...skipped].map(([skippedPath, reason]) => ({ path: skippedPath, reason })),
  }
}

/** 执行一次 fs.grep；不做能力层错误包装与输出落盘。 */
export async function grepFiles(
  sourcePattern: string,
  path: string,
  limit: number,
  options: GrepOptions,
  control: SearchControl,
): Promise<GrepOutcome> {
  control.check()
  const pattern = compileRegex(sourcePattern, options.literal, options.ignoreCase, options.multiline)
  const include = compileInclude(options.include)
  control.check()

  const directFile = await isRegularFileNoFollow(path)
  const results = new SearchResults(limit)
  if (directFile) {
    if (!(await isReadable(path))) {
      throw new ToolInputRejectedError(`path is not readable: ${toPosix(path)}`)
    }
    if (!isGitMetadata(path)) {
      await searchInFile(path, dirname(path), true, include, pattern, options.multiline, control, results)
    }
  } else {
    if (!(await isDirectoryNoFollow(path))) {
      throw new ToolInputRejectedError('path must be a regular file or directory')
    }
    const report = await walk(path, control, async (file) => {
      control.check()
      await searchInFile(file, path, false, include, pattern, options.multiline, control, results)
      return !results.limitReached()
    })
    for (const unreadable of report.unreadable) {
      results.skip(toPosix(unreadable), 'could not be read')
    }
  }
  return {
    lines: results.lineList(),
    moreMatches: results.limitReached(),
    skipped: results.skippedList(),
  }
}

/** Worker 入口：把作业映射为可结构化克隆的稳定结果。 */
export async function runSearchJob(job: SearchJob): Promise<SearchReply> {
  const control = new SearchControl(job.timeoutMillis, job.kind)
  try {
    if (job.kind === 'find') {
      const outcome = await findFiles(job.pattern, job.path, job.limit, control)
      return { ok: true, kind: 'find', ...outcome }
    }
    const outcome = await grepFiles(
      job.pattern,
      job.path,
      job.limit,
      {
        multiline: job.multiline,
        literal: job.literal,
        ignoreCase: job.ignoreCase,
        include: job.include,
      },
      control,
    )
    return { ok: true, kind: 'grep', ...outcome }
  } catch (error) {
    return toReplyError(error)
  }
}

function toReplyError(error: unknown): SearchReply {
  if (error instanceof ToolInputRejectedError) {
    return { ok: false, error: 'input', message: error.message, code: error.code }
  }
  if (error instanceof CancelledError) {
    return { ok: false, error: 'cancelled', message: error.message, code: 'CANCELLED' }
  }
  if (error instanceof ToolRunFailureError) {
    return { ok: false, error: 'run', message: error.message, code: error.code }
  }
  return {
    ok: false,
    error: 'run',
    message: 'the search failed while running and the underlying error is not repeated to avoid leaking details',
    code: 'INTERNAL',
  }
}

// ---------------------------------------------------------------------------
// 逐文件搜索
// ---------------------------------------------------------------------------

class SearchFailureError extends ToolRunFailureError {
  constructor(
    message: string,
    readonly skipReason: string | null,
  ) {
    super(message)
    this.name = 'SearchFailureError'
  }
}

async function searchInFile(
  file: string,
  includeRoot: string | null,
  directFile: boolean,
  include: IncludePattern,
  pattern: RE2,
  multiline: boolean,
  control: SearchControl,
  results: SearchResults,
): Promise<void> {
  if (includeRoot !== null) {
    const includePath = toPosix(relative(includeRoot, file))
    if (!include.matches(includePath, basename(file))) {
      return
    }
  }
  const display = toPosix(file)
  try {
    if (multiline) {
      await searchMultiline(file, display, pattern, control, results)
    } else {
      await searchSingleLines(file, display, pattern, control, results)
    }
  } catch (error) {
    if (error instanceof SearchFailureError) {
      if (directFile) {
        throw error
      }
      if (error.skipReason !== null) {
        results.skip(display, error.skipReason)
      }
      return
    }
    throw error
  }
}

async function searchSingleLines(
  file: string,
  display: string,
  pattern: RE2,
  control: SearchControl,
  results: SearchResults,
): Promise<void> {
  let probeBytes: Uint8Array
  let encoding
  try {
    probeBytes = await probe(file)
    encoding = detectEncoding(probeBytes)
  } catch {
    throw new SearchFailureError(`path is not readable: ${display}`, 'could not be read')
  }
  if (looksBinary(probeBytes, encoding)) {
    throw new SearchFailureError(`file appears to be binary: ${display}`, null)
  }
  try {
    await forEachLine(file, encoding, (lineNumber, line, truncated) => {
      control.check()
      if (results.limitReached()) {
        return false
      }
      if (truncated) {
        throw new SearchFailureError(
          `file has a line longer than 1048576 characters and cannot be searched completely: ${display}`,
          'contains a line longer than 1048576 characters',
        )
      }
      const match = pattern.exec(line)
      if (match !== null) {
        if (!results.acceptMoreMatches()) {
          return false
        }
        const start = match.index
        const end = start + (match[0]?.length ?? 0)
        results.addLine(`${display}:${lineNumber}:${matchCenteredExcerpt(line, start, end)}`)
        results.endMatch()
      }
      return true
    })
  } catch (error) {
    if (error instanceof ToolRunFailureError && error.code === 'CODEC') {
      throw new SearchFailureError(`file appears to be binary: ${display}`, null)
    }
    throw error
  }
}

async function searchMultiline(
  file: string,
  display: string,
  pattern: RE2,
  control: SearchControl,
  results: SearchResults,
): Promise<void> {
  let size: number
  try {
    size = (await lstat(file)).size
  } catch {
    throw new SearchFailureError(`path is not readable: ${display}`, 'could not be read')
  }
  if (size > MAX_MULTILINE_FILE_BYTES) {
    throw new SearchFailureError(
      `multiline search requires loading the whole file; file exceeds ${MAX_MULTILINE_FILE_BYTES / (1024 * 1024)} MiB maximum for multiline: ${display}`,
      `exceeds ${MAX_MULTILINE_FILE_BYTES / (1024 * 1024)} MiB multiline limit`,
    )
  }
  let bytes: Uint8Array
  try {
    bytes = await readAllBytes(file)
  } catch {
    throw new SearchFailureError(`path is not readable: ${display}`, 'could not be read')
  }
  let text: string
  try {
    text = decodeText(bytes).text
  } catch {
    throw new SearchFailureError(`file appears to be binary: ${display}`, null)
  }
  matchingMultiline(pattern, TextLines.from(text), display, control, results)
}

function matchingMultiline(
  pattern: RE2,
  text: TextLines,
  display: string,
  control: SearchControl,
  results: SearchResults,
): void {
  if (text.lines.length === 0) {
    return
  }
  const global = new RE2(pattern.source, `${pattern.flags}g`)
  let emittedThroughLine = -1
  let match = global.exec(text.text)
  while (match !== null) {
    control.check()
    if (!results.acceptMoreMatches()) {
      return
    }
    const start = match.index
    const end = start + (match[0]?.length ?? 0)
    const startLine = lineIndex(text.lines, start)
    const coveredEnd = end > start ? end - 1 : start
    const endLine = lineIndex(text.lines, coveredEnd)
    for (let index = Math.max(startLine, emittedThroughLine + 1); index <= endLine; index++) {
      const line = text.lines[index] as Line
      const matchStart = Math.max(0, start - line.startOffset)
      const matchEnd = Math.min(line.content.length, end - line.startOffset)
      results.addLine(
        `${display}:${index + 1}:${matchCenteredExcerpt(line.content, matchStart, matchEnd)}`,
      )
      emittedThroughLine = index
    }
    results.endMatch()
    if ((match[0]?.length ?? 0) === 0 && global.lastIndex === start) {
      global.lastIndex = start + 1
    }
    match = global.exec(text.text)
  }
}

function lineIndex(lines: Line[], offset: number): number {
  const bounded = Math.max(0, offset)
  let low = 0
  let high = lines.length - 1
  while (low < high) {
    const middle = (low + high + 1) >>> 1
    if ((lines[middle] as Line).startOffset <= bounded) {
      low = middle
    } else {
      high = middle - 1
    }
  }
  return low
}

/** 超长行只在“展示”时按字符预算居中截取；匹配本身覆盖整行。 */
export function matchCenteredExcerpt(line: string, matchStartChar: number, matchEndChar: number): string {
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

interface Line {
  startOffset: number
  content: string
}

interface TextLines {
  text: string
  lines: Line[]
}

const TextLines = {
  from(source: string): TextLines {
    const normalized = source.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    if (normalized.length === 0) {
      return { text: normalized, lines: [] }
    }
    const contents = normalized.split('\n')
    const count = normalized.endsWith('\n') ? contents.length - 1 : contents.length
    const lines: Line[] = []
    let offset = 0
    for (let index = 0; index < count; index++) {
      const content = contents[index] as string
      lines.push({ startOffset: offset, content })
      offset += content.length + 1
    }
    return { text: normalized, lines }
  },
}

/** 逐文件匹配结果收集器；只在 Worker 内使用，结果以纯数组回传主线程。 */
class SearchResults {
  private readonly lines: string[] = []
  private readonly skipped = new Map<string, string>()
  private matches = 0
  private moreMatches = false

  constructor(private readonly limit: number) {}

  limitReached(): boolean {
    return this.moreMatches
  }

  acceptMoreMatches(): boolean {
    if (this.matches < this.limit) {
      return true
    }
    this.moreMatches = true
    return false
  }

  addLine(line: string): void {
    this.lines.push(line)
  }

  endMatch(): void {
    this.matches++
  }

  skip(path: string, reason: string): void {
    if (!this.skipped.has(path)) {
      this.skipped.set(path, reason)
    }
  }

  lineList(): string[] {
    return this.lines
  }

  skippedList(): SkippedEntry[] {
    return [...this.skipped].map(([path, reason]) => ({ path, reason }))
  }
}

async function isRegularFileNoFollow(path: string): Promise<boolean> {
  try {
    return (await lstat(path)).isFile()
  } catch {
    return false
  }
}

async function isDirectoryNoFollow(path: string): Promise<boolean> {
  try {
    return (await lstat(path)).isDirectory()
  } catch {
    return false
  }
}
