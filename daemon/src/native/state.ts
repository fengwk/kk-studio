import { closeSync, existsSync, fsyncSync, mkdtempSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { ensurePrivateDirectory } from './private-fs.js'

/**
 * 父进程与 scope helper 之间唯一的握手面：调用私有状态目录里的这几个文件。
 *
 * <p>发布一律「先写同目录临时文件再原子改名」，读者要么看不到文件，要么看到完整内容。状态目录由父进程以 0700 独占创建，
 * 其中每个控制文件都是 0600；目录与文件都在调用收尾时删除。
 *
 * <p>本模块被父进程与 helper 都以**编译产物**静态引用，因此同目录模块一律用静态 `.js` import，不做源码态扩展名探测。
 */

/** helper 发布的范围渠道：POSIX 是会话 id，Windows 是首个进程 pid。 */
export const SCOPE_FILE = 'scope'

/** 父进程在确认范围之后放行用户命令的许可文件：没有它，helper 不会启动任何进程。 */
export const PERMIT_FILE = 'permit'

/** helper 发布的用户命令自然退出事实（JSON：{exitCode, signal}）。 */
export const EXIT_FILE = 'exit'

/** helper 发布的失败**代码**：固定阶段标识（如 `helper-launch-failure`），绝不携带命令路径或环境片段。 */
export const ERROR_FILE = 'error'

/** helper 发布的收敛事实：范围已确认没有活动成员。 */
export const CLEANUP_FILE = 'cleanup'

/** helper 自身启动规格（cwd 与 argv）；读取后立即删除，绝不进入命令行或诊断。 */
export const LAUNCH_FILE = 'launch'

/** helper 自身的诊断输出；绝不进入用户命令的输出流。 */
export const DIAGNOSTICS_FILE = 'diagnostics'

/** 状态目录前缀：定位残留时一眼可辨。 */
export const STATE_DIR_PREFIX = 'kk-studio-daemon-process-scope-'

/** 命令的启动规格；只承载启动配置，不含任何运行字节或屏幕内容。 */
export interface LaunchSpec {
  cwd: string
  args: string[]
}

/** 命令自身发布的自然退出事实。 */
export interface ExitReport {
  exitCode: number | null
  signal: string | null
}

/**
 * 独占创建一个**属主独占**的调用私有状态目录，并返回其路径。
 *
 * <p>仅 `mkdtemp` 只能在 POSIX 上得到 0700；Windows 的 `%TEMP%` 目录会带上继承来的宽松 DACL，所以这里创建之后立即用原生
 * `ensurePrivateDirectory` 收紧并读回验证。调用方（父进程）在写入任何启动规格之前已经拿到一个有证据的私有根，随后发布的
 * 状态文件都继承这个根的 owner-only 语义。
 *
 * <p>这是异步的：收紧与读回验证必须真的完成，不能因为「目录已经建好」就跳过。
 */
export async function createStateDir(): Promise<string> {
  const directory = mkdtempSync(join(tmpdir(), STATE_DIR_PREFIX))
  return ensurePrivateDirectory(directory)
}

/** 原子发布状态文件；失败必须让调用方显式失败，绝不留下半个文件。 */
export function publish(stateDir: string, name: string, content: string): void {
  const target = join(stateDir, name)
  const temporary = `${target}.tmp`
  const descriptor = openSync(temporary, 'w', 0o600)
  try {
    writeFileSync(descriptor, content)
    fsyncSync(descriptor)
  } finally {
    closeSync(descriptor)
  }
  renameSync(temporary, target)
}

/** 尽力发布：只用于失败报告本身，报告失败时只能放弃。 */
export function publishQuietly(stateDir: string, name: string, content: string): void {
  try {
    publish(stateDir, name, content)
  } catch {
    // 失败原因不可发布时不再尝试第二种发布方式。
  }
}

/** 读取状态文件；不存在或不可读都按「还没有发布」处理。 */
export function read(stateDir: string, name: string): string | null {
  try {
    return readFileSync(join(stateDir, name), 'utf8').trim()
  } catch {
    return null
  }
}

/** 发布 helper 启动规格：单一 JSON 文档，NUL 字符显式拒绝。 */
export function writeLaunch(stateDir: string, spec: LaunchSpec): void {
  rejectNul(spec.cwd)
  for (const argument of spec.args) {
    rejectNul(argument)
  }
  publish(stateDir, LAUNCH_FILE, JSON.stringify(spec))
}

/** 读取并立即删除启动规格；不存在时返回 null；不可解析时显式失败且不回显载荷。 */
export function readLaunch(stateDir: string): LaunchSpec | null {
  const file = join(stateDir, LAUNCH_FILE)
  if (!existsSync(file)) {
    return null
  }
  let payload: string
  try {
    payload = readFileSync(file, 'utf8')
  } catch {
    throw new Error('the process scope launch spec is unreadable')
  }
  try {
    rmSync(file, { force: true })
  } catch {
    // 删除失败不影响契约：文件内容已经被读走，目录最终仍会被整体删除。
  }
  let spec: unknown
  try {
    spec = JSON.parse(payload)
  } catch {
    throw new Error('the process scope launch spec is unreadable')
  }
  if (!isLaunchSpec(spec)) {
    throw new Error('the process scope launch spec is incomplete')
  }
  return spec
}

/** 解析命令的自然退出事实；不存在时返回 null，载荷损坏时显式失败。 */
export function readExit(stateDir: string): ExitReport | null {
  const value = read(stateDir, EXIT_FILE)
  if (value === null) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(value)
  } catch {
    throw new Error('the process scope helper published an unreadable exit code')
  }
  if (!isExitReport(parsed)) {
    throw new Error('the process scope helper published an unreadable exit code')
  }
  return parsed
}

/** 校验收敛事实；只在 helper 明确发布 "true" 时才算已经证明收敛。 */
export function readCleanup(stateDir: string): boolean | null {
  const value = read(stateDir, CLEANUP_FILE)
  if (value === null) {
    return null
  }
  if (value === 'true') {
    return true
  }
  if (value === 'false') {
    return false
  }
  return null
}

/** 删除调用私有的状态目录；清理失败不影响调用结果。 */
export function removeStateDir(stateDir: string): void {
  try {
    rmSync(stateDir, { recursive: true, force: true })
  } catch {
    // 状态目录只承载调用私有的握手文件，清理失败不改变调用结果。
  }
}

function rejectNul(value: string): void {
  if (value.includes('\0')) {
    throw new Error('the process scope launch spec must not contain a NUL character')
  }
}

function isLaunchSpec(value: unknown): value is LaunchSpec {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as { cwd?: unknown; args?: unknown }
  return typeof candidate.cwd === 'string' && Array.isArray(candidate.args) && candidate.args.every((item) => typeof item === 'string')
}

function isExitReport(value: unknown): value is ExitReport {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as { exitCode?: unknown; signal?: unknown }
  const codeOk = candidate.exitCode === null || typeof candidate.exitCode === 'number'
  const signalOk = candidate.signal === null || typeof candidate.signal === 'string'
  return codeOk && signalOk
}
