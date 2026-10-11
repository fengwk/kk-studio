import { lstat, realpath, stat } from 'node:fs/promises'
import { dirname, join, relative } from 'node:path'
import type { CapabilityResult } from '../contracts.js'
import { errorResult } from '../contracts.js'

/**
 * 文件/搜索/进程能力共享的失败分类与参数校验。
 *
 * <p>与 Java 参考实现对齐：派发前的确定性输入拒绝（{@link ToolInputRejectedError}）可以声明“未执行”；进入执行后的受控诊断
 * （{@link ToolRunFailureError}/{@link ToolServiceFailureError}）只声明结果不可确认。两者都带稳定的 {@code UPPER_SNAKE}
 * 错误码，便于跨进程判定；普通异常一律用固定安全文案，绝不回显原始 message。
 */

export class ToolInputRejectedError extends Error {
  readonly code: string

  constructor(message: string, code = 'INVALID_ARGUMENT') {
    super(message)
    this.name = 'ToolInputRejectedError'
    this.code = code
  }
}

export class ToolRunFailureError extends Error {
  readonly code: string

  constructor(message: string, code = 'FAILED') {
    super(message)
    this.name = 'ToolRunFailureError'
    this.code = code
  }
}

export class ToolServiceFailureError extends Error {
  readonly code: string

  constructor(message: string, code = 'SERVICE_FAILURE') {
    super(message)
    this.name = 'ToolServiceFailureError'
    this.code = code
  }
}

/** 执行取消的内部信号；调用方把它映射为 cancelled 结果而非普通失败。 */
export class CancelledError extends Error {
  constructor() {
    super('Operation cancelled')
    this.name = 'CancelledError'
  }
}

export type ExecutionFact = 'NOT_EXECUTED' | 'FAILED' | 'UNCERTAIN'

const FACT_TEXT: Record<ExecutionFact, string> = {
  NOT_EXECUTED: 'The tool was not executed.',
  FAILED: 'The tool ran but failed; side effects may have occurred.',
  UNCERTAIN: 'Whether the tool took effect cannot be confirmed.',
}

const VERIFY_EFFECT_NEXT_ACTION =
  'Verify whether the intended effect already took place before deciding what to do next'

/** 组装三段式英文文案：发生了什么 + 执行事实 + 下一步。 */
export function guide(whatFailed: string, fact: ExecutionFact, nextAction: string): string {
  return `${sentence(whatFailed)} ${FACT_TEXT[fact]} ${sentence(nextAction)}`
}

function sentence(text: string): string {
  const stripped = text.trim()
  if (stripped.length === 0) {
    return stripped
  }
  const last = stripped[stripped.length - 1]
  return last === '.' || last === '!' || last === '?' ? stripped : `${stripped}.`
}

/** 把执行体抛出的异常映射为稳定的错误结果；普通异常不回显原始 message。 */
export function toErrorResult(callId: string, error: unknown): CapabilityResult {
  if (error instanceof ToolInputRejectedError) {
    return errorResult(
      callId,
      guide(
        error.message,
        'NOT_EXECUTED',
        'Correct the arguments to match the tool schema, then call the tool again',
      ),
      error.code,
    )
  }
  if (error instanceof ToolRunFailureError || error instanceof ToolServiceFailureError) {
    return errorResult(callId, guide(error.message, 'UNCERTAIN', VERIFY_EFFECT_NEXT_ACTION), error.code)
  }
  if (error instanceof CancelledError) {
    return errorResult(
      callId,
      guide(
        'Operation cancelled',
        'UNCERTAIN',
        'Check whether the operation already took effect before deciding what to do next',
      ),
      'CANCELLED',
    )
  }
  return errorResult(
    callId,
    guide(
      'The capability failed while running and the underlying error is not repeated to avoid leaking details',
      'UNCERTAIN',
      VERIFY_EFFECT_NEXT_ACTION,
    ),
    'INTERNAL',
  )
}

/**
 * 把 Node 文件系统 errno 收敛为稳定错误码。
 *
 * <p>权限错误与“不存在”需要区分：前者是受控失败、后者是输入坐标问题；其余一律按 I/O 归类，message 不携带原始路径。
 */
export function classifyFileSystemError(error: unknown): { code: string; message: string } {
  const errno = error as NodeJS.ErrnoException
  switch (errno?.code) {
    case 'EACCES':
    case 'EPERM':
      return { code: 'PERMISSION_DENIED', message: 'permission denied while accessing the path' }
    case 'ENOENT':
      return { code: 'NOT_FOUND', message: 'path does not exist' }
    case 'EISDIR':
      return { code: 'INVALID_ARGUMENT', message: 'path is a directory' }
    case 'ENOTDIR':
      return { code: 'INVALID_ARGUMENT', message: 'path is not a directory' }
    case 'EEXIST':
      return { code: 'ALREADY_EXISTS', message: 'path already exists' }
    case 'ENOSPC':
      return { code: 'NO_SPACE', message: 'no space left on the device' }
    default:
      return { code: 'IO', message: 'file system operation failed' }
  }
}

/** 参数必须是字符串。 */
export function requiredString(args: Record<string, unknown>, name: string): string {
  const value = args[name]
  if (typeof value !== 'string') {
    throw new ToolInputRejectedError(`${name} is required and must be a string`)
  }
  return value
}

/** 可选字符串；缺失返回 null。 */
export function optionalString(args: Record<string, unknown>, name: string): string | null {
  const value = args[name]
  return value === undefined ? null : typeof value === 'string' ? value : null
}

/** 可选布尔；缺失或非布尔视为 false（与参考实现一致）。 */
export function optionalBoolean(args: Record<string, unknown>, name: string): boolean {
  return args[name] === true
}

/** 可选正整数，带默认值与上限；越界先于任何 I/O 拒绝。 */
export function optionalPositiveInt(
  args: Record<string, unknown>,
  name: string,
  defaultValue: number,
  maximum: number,
): number {
  const value = args[name]
  if (value === undefined) {
    return defaultValue
  }
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > maximum) {
    throw new ToolInputRejectedError(`${name} must be a positive integer <= ${maximum}`)
  }
  return value
}

/** 可选正整数（无上限），缺失返回 null。 */
export function optionalPositiveIntOrNull(
  args: Record<string, unknown>,
  name: string,
): number | null {
  const value = args[name]
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1) {
    throw new ToolInputRejectedError(`${name} must be a positive integer`)
  }
  return value
}

/** 必须的字符串且非空白，用于路径类参数。 */
export function requirePathValue(raw: string | null | undefined, name: string): string {
  if (raw === null || raw === undefined || raw.trim().length === 0) {
    throw new ToolInputRejectedError(`${name} is required`)
  }
  return raw
}

/** 展示路径：优先回显调用方给出的绝对路径（词法归一 + '/' 分隔），否则退回已解析目标。 */
export function displayPath(target: string | null, rawPath: string | null): string {
  if (rawPath !== null && rawPath.trim().length > 0) {
    if (isAbsolute(rawPath)) {
      return toPosix(normalize(rawPath))
    }
  }
  return target !== null ? toPosix(target) : rawPath === null ? '<missing>' : rawPath
}

/** 平台无关的绝对路径判定：POSIX 以 '/' 开头；Windows 以盘符或 UNC 开头。 */
export function isAbsolute(path: string): boolean {
  if (path.startsWith('/')) {
    return true
  }
  return /^[A-Za-z]:[\\/]/.test(path) || path.startsWith('\\\\')
}

/** 词法归一：折叠 '.' 与 '..' 段，不触碰文件系统。 */
export function normalize(path: string): string {
  const absolute = isAbsolute(path)
  const separator = path.includes('\\') && !path.startsWith('/') ? '\\' : '/'
  const parts = path.split(/[\\/]+/)
  const stack: string[] = []
  for (const part of parts) {
    if (part === '' || part === '.') {
      continue
    }
    if (part === '..' && stack.length > 0 && stack[stack.length - 1] !== '..') {
      stack.pop()
      continue
    }
    stack.push(part)
  }
  const joined = stack.join(separator)
  if (absolute) {
    return path.startsWith('\\\\') ? `\\\\${joined}` : separator + joined
  }
  return joined
}

/** 转成 '/' 分隔的展示路径。 */
export function toPosix(path: string): string {
  return path.replace(/\\/g, '/')
}

/** 解析已存在的文件或目录；{@code rawPath} 必须是绝对路径，返回其真实路径。 */
export async function resolveExistingPath(rawPath: string): Promise<string> {
  const candidate = requireAbsolute(rawPath, 'path')
  if (!(await exists(candidate))) {
    throw new ToolInputRejectedError(`path does not exist: ${displayPath(null, rawPath)}`, 'NOT_FOUND')
  }
  try {
    return await realpath(candidate)
  } catch {
    throw new ToolInputRejectedError(`path must exist: ${candidate}`, 'NOT_FOUND')
  }
}

/** 解析写目标：允许尚不存在的末段，把现存祖先解析为真实路径后再拼接剩余段。 */
export async function resolveWritablePath(rawPath: string): Promise<string> {
  const candidate = requireAbsolute(rawPath, 'path')
  if (await exists(candidate)) {
    try {
      return await realpath(candidate)
    } catch {
      throw new ToolInputRejectedError(`path must exist: ${candidate}`, 'NOT_FOUND')
    }
  }
  let ancestor = dirname(candidate)
  while (!(await exists(ancestor))) {
    const parent = dirname(ancestor)
    if (parent === ancestor) {
      throw new ToolInputRejectedError(
        `path has no existing ancestor: ${displayPath(null, rawPath)}`,
        'NOT_FOUND',
      )
    }
    ancestor = parent
  }
  let realAncestor: string
  try {
    realAncestor = await realpath(ancestor)
  } catch {
    throw new ToolInputRejectedError(`path ancestor must exist: ${ancestor}`, 'NOT_FOUND')
  }
  return normalize(join(realAncestor, relative(ancestor, candidate)))
}

/** 路径是否存在（跟随符号链接）。 */
export async function exists(path: string): Promise<boolean> {
  try {
    await stat(path)
    return true
  } catch {
    return false
  }
}

/** 最终分量是否为符号链接（不跟随）。 */
export async function isSymbolicLink(path: string): Promise<boolean> {
  try {
    return (await lstat(path)).isSymbolicLink()
  } catch {
    return false
  }
}

function requireAbsolute(raw: string, name: string): string {
  requirePathValue(raw, name)
  if (!isAbsolute(raw)) {
    throw new ToolInputRejectedError(
      `${name} must be an absolute path (a relative or non-absolute value was given)`,
    )
  }
  return normalize(raw)
}
