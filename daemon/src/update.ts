import { createHash, randomUUID } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { chmod, copyFile, cp, lstat, open, readdir, readFile, rm } from 'node:fs/promises'
import path from 'node:path'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { setTimeout as sleep } from 'node:timers/promises'
import type { DaemonConfig, ManagedProcess, ProcessSpec, ProcessSupervisor } from './contracts.js'
import { extractArchive as extractArchiveSafely, MAX_ARCHIVE_BYTES, MAX_CHECKSUM_BYTES, MAX_MANIFEST_BYTES } from './archive.js'
import { getOutboundNetwork } from './network.js'
import {
  acquireFileLock,
  ensurePrivateDirectory,
  verifyPrivateFile,
  writePrivateFile,
  type DirectoryLock,
} from './native/index.js'
import {
  PUBLISHED_PLATFORMS,
  artifactFileName,
  artifactUrl,
  checksumUrl,
  type ReleaseArch,
  type ReleaseOs,
} from './release.js'
import {
  HANDOFF_FILE,
  MANAGED_ENTRY,
  PERMIT_FILE,
  RESULT_FILE,
  RUNTIME_MANIFEST,
  TAKEOVER_FILE,
  UNIX_UPDATE_SCRIPT_NAME,
  UPDATE_DIRECTORY,
  UPDATE_LOCK_FILE,
  WINDOWS_UPDATE_SCRIPT_NAME,
} from './update-entry.js'
import { UNIX_UPDATE_SCRIPT, WINDOWS_UPDATE_SCRIPT } from './update-scripts.js'

export {
  HANDOFF_FILE,
  MANAGED_ENTRY,
  PERMIT_FILE,
  RESULT_FILE,
  RUNTIME_MANIFEST,
  STARTUP_ACK_FILE,
  STEPS_FILE,
  TAKEOVER_FILE,
  UNIX_UPDATE_SCRIPT_NAME,
  UPDATE_DIRECTORY,
  UPDATE_LOCK_FILE,
  WINDOWS_UPDATE_SCRIPT_NAME,
} from './update-entry.js'

/** 受管配置文件相对数据目录的固定名。 */
export const MANAGED_CONFIG_NAME = 'daemon.json'

/** 子进程每条流的保留上限：64 KiB。 */
export const STREAM_LIMIT_BYTES = 64 * 1024
/** 归档下载连续无有效数据的最长等待：300 秒；累计下载时长不设硬上限。 */
export const DOWNLOAD_IDLE_TIMEOUT_MILLIS = 300_000
/** 下载结果分类：调用方必须能区分无进展、外部取消与普通网络失败。 */
export const DOWNLOAD_NO_PROGRESS_MESSAGE = 'official artifact download timed out after 300 seconds without progress'
export const DOWNLOAD_CANCELLED_MESSAGE = 'official artifact download was cancelled'
export const DOWNLOAD_FAILED_MESSAGE = 'official artifact download failed'
export const DOWNLOAD_FINALIZE_MESSAGE = 'official artifact download could not be finalized'
/** 官方下载重定向上限：标准有界 20 跳，每跳按 URL 重新选择代理。 */
export const MAX_DOWNLOAD_REDIRECTS = 20

const REDIRECT_STATUS = new Set([301, 302, 303, 307, 308])

const CHECK_CONFIG_SUCCESS = 'Daemon configuration is valid'
const EXTRACT_DIRECTORY = 'extract'
const CHECK_CONFIG_TIMEOUT_MILLIS = 60_000
const LAUNCH_TIMEOUT_MILLIS = 15_000
const PERMIT_TAKEOVER_TIMEOUT_MILLIS = 30_000
const PERMIT_TAKEOVER_POLL_MILLIS = 50

const VERSION_PATTERN = /^[0-9A-Za-z._+-]{1,64}$/
const OPERATION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const SHA256_PATTERN = /^[0-9a-fA-F]{64}$/
const COMMAND_FIELDS = ['operationId', 'targetVersion', 'artifactUrl', 'artifactSha256'] as const

export interface UpdateOutcome {
  operationId: string
  phase: 'ACCEPTED' | 'PREPARED' | 'FAILED'
  message?: string
  targetVersion?: string
  /**
   * 已接受交接的许可释放步骤：只在 handoff 已冻结为 {@code ACCEPTED} 时提供，幂等且可安全重复调用。
   *
   * <p>交接目录与 handoff 是唯一权威，因此 releasePermit 由本模块提供；但**何时**释放由 Runtime 决定：回执入缓存
   * 并完成一次出站尝试之后，重放与重连重发都再调用一次，因此一次失败的释放可由后续重放自然重试。
   */
  releasePermit?: () => Promise<void>
}

/** 发行归档下载进度；只统计归档 body，校验文件不混入。 */
export interface UpdateProgress {
  downloadedBytes: number
  totalBytes: number | null
}

export type UpdateProgressReporter = (progress: UpdateProgress) => void

export interface RunResult {
  code: number | null
  stdout: string
  stderr: string
}

export interface RunOptions {
  signal?: AbortSignal
  timeoutMillis?: number
  cwd?: string
}

/** 可注入的副作用边界；生产实现走官方 HTTPS、受控归档解包与原生 supervisor，测试注入确定实现。 */
export interface UpdateDependencies {
  releaseOs: ReleaseOs
  releaseArch: ReleaseArch
  fetchTo(
    url: string,
    destination: string,
    signal: AbortSignal,
    maxBytes: number,
    reportProgress?: (downloadedBytes: number, totalBytes: number | null) => void,
  ): Promise<void>
  extractArchive(archive: string, destination: string, signal: AbortSignal): Promise<void>
  run(command: string, args: string[], options: RunOptions): Promise<RunResult>
  id(): string
}

/** 预期的期望失败；消息只承载去敏、有界说明，绝不携带输入值。 */
class UpdateFailure extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'UpdateFailure'
  }
}

interface ParsedCommand {
  operationId: string
  targetVersion: string
  artifactUrl: string
  artifactSha256: string
}

/**
 * 严格解析更新命令：固定四个字段，拒绝未知字段、非文本与非法取值。
 *
 * operationId 必须是规范小写 UUID（用作暂存子目录名），targetVersion 必须落在安全版本字符集，
 * artifactSha256 必须是 64 位十六进制；artifactUrl 由上层按本机 os/arch 再校验。
 */
export function parseUpdateCommand(raw: Record<string, unknown>): ParsedCommand {
  if (typeof raw !== 'object' || raw === null) {
    throw new UpdateFailure('update command must be an object')
  }
  for (const field of Object.keys(raw)) {
    if (!(COMMAND_FIELDS as readonly string[]).includes(field)) {
      throw new UpdateFailure('update command has an unexpected field')
    }
  }
  const operationId = requiredText(raw, 'operationId')
  if (!OPERATION_ID_PATTERN.test(operationId)) {
    throw new UpdateFailure('update command.operationId must be a canonical lowercase UUID')
  }
  const targetVersion = requiredText(raw, 'targetVersion')
  if (!VERSION_PATTERN.test(targetVersion)) {
    throw new UpdateFailure('update command.targetVersion is not a safe version')
  }
  const artifact = requiredText(raw, 'artifactUrl')
  const artifactSha256 = requiredText(raw, 'artifactSha256')
  if (!SHA256_PATTERN.test(artifactSha256)) {
    throw new UpdateFailure('update command.artifactSha256 must be 64 hex characters')
  }
  return { operationId, targetVersion, artifactUrl: artifact, artifactSha256: artifactSha256 }
}

function requiredText(raw: Record<string, unknown>, field: string): string {
  const value = raw[field]
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new UpdateFailure(`update command.${field} must be non-blank text`)
  }
  return value
}

/**
 * 只把 ENOENT 视为「不存在」；权限、路径中段不是目录或其他 IO 故障必须显式失败，绝不伪装成缺失。
 */
async function lstatOrNull(target: string): Promise<Awaited<ReturnType<typeof lstat>> | null> {
  try {
    return await lstat(target)
  } catch (error) {
    if ((error as NodeJS.ErrnoException | null)?.code === 'ENOENT') {
      return null
    }
    throw new UpdateFailure('the managed installation could not be inspected')
  }
}

async function isRealDirectory(target: string): Promise<boolean> {
  const stats = await lstatOrNull(target)
  return stats !== null && stats.isDirectory() && !stats.isSymbolicLink()
}

async function isRealRegularFile(target: string): Promise<boolean> {
  const stats = await lstatOrNull(target)
  return stats !== null && stats.isFile() && !stats.isSymbolicLink()
}

async function sha256File(target: string): Promise<string> {
  const hash = createHash('sha256')
  const handle = await open(target, 'r')
  try {
    const buffer = Buffer.allocUnsafe(64 * 1024)
    for (;;) {
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, null)
      if (bytesRead === 0) {
        break
      }
      hash.update(buffer.subarray(0, bytesRead))
    }
  } finally {
    await handle.close()
  }
  return hash.digest('hex')
}

function readChecksumDigest(text: string): string {
  const line = text.trim()
  const match = /^([0-9a-fA-F]{64})(?:[ \t]+\*?[^\s]+)?$/.exec(line)
  if (match === null || match[1] === undefined) {
    throw new UpdateFailure('official checksum file is malformed')
  }
  return match[1].toLowerCase()
}

/**
 * 严格把宿主平台映射到官方发布 OS；未覆盖平台显式失败，绝不静默回退到 linux。
 */
export function releaseOsFor(platform: NodeJS.Platform): ReleaseOs {
  switch (platform) {
    case 'linux':
      return 'linux'
    case 'darwin':
      return 'macos'
    case 'win32':
      return 'windows'
    default:
      throw new UpdateFailure('the host operating system is not covered by the managed update')
  }
}

/**
 * 严格把宿主 (os, arch) 聚合到官方发布组合；未发布的组合（例如 windows-arm64）显式失败。
 *
 * <p>仅看架构无法判断制品是否存在（Windows 只有 amd64），因此组合校验必须发生在映射处，
 * 而不是等到下载一个并不存在的资产。
 */
export function releasePlatformFor(
  platform: NodeJS.Platform,
  arch: string,
): { os: ReleaseOs; arch: ReleaseArch } {
  const os = releaseOsFor(platform)
  const releaseArch = releaseArchFor(arch)
  if (!PUBLISHED_PLATFORMS.has(`${os}-${releaseArch}`)) {
    throw new UpdateFailure('the host platform is not covered by the managed update')
  }
  return { os, arch: releaseArch }
}

/**
 * 严格把宿主 CPU 架构映射到官方发布架构；未覆盖架构显式失败，绝不静默回退到 amd64。
 */
export function releaseArchFor(arch: string): ReleaseArch {
  switch (arch) {
    case 'x64':
      return 'amd64'
    case 'arm64':
      return 'arm64'
    default:
      throw new UpdateFailure('the host architecture is not covered by the managed update')
  }
}

/** 有界文本收集器：只保留前 {@code limit} 字节，但持续消费底层流避免背压。 */
class BoundedText {
  private readonly chunks: Buffer[] = []
  private size = 0

  constructor(private readonly limit: number) {}

  push(chunk: Buffer): void {
    if (this.size >= this.limit) {
      return
    }
    const remaining = this.limit - this.size
    const slice = chunk.length > remaining ? chunk.subarray(0, remaining) : chunk
    this.chunks.push(Buffer.from(slice))
    this.size += slice.length
  }

  text(): string {
    return Buffer.concat(this.chunks).toString('utf8')
  }
}

interface DrainOutcome {
  error: unknown | null
}

/** 终止导致的流中断（premature close / destroy / abort）是该流的预期结束；其余读写故障必须如实上报。 */
const TERMINATION_STREAM_CODES = new Set(['ERR_STREAM_PREMATURE_CLOSE', 'ERR_STREAM_DESTROYED', 'ABORT_ERR'])

function isTerminationNoise(error: unknown): boolean {
  const code = (error as NodeJS.ErrnoException | null)?.code
  return typeof code === 'string' && TERMINATION_STREAM_CODES.has(code)
}

/**
 * 持续排空一条流到有界收集器：无论正常结束还是失败都返回结果而不抛出，保证两条流都能落定，
 * 绝不把未处理的 Promise 拒绝留在事件循环里。
 */
async function drainBounded(
  stream: NodeJS.ReadableStream,
  sink: BoundedText,
  terminationExpected: () => boolean,
): Promise<DrainOutcome> {
  try {
    for await (const chunk of stream) {
      sink.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)))
    }
    return { error: null }
  } catch (error) {
    if (terminationExpected() && isTerminationNoise(error)) {
      return { error: null }
    }
    return { error }
  }
}

function convergenceFailure(): UpdateFailure {
  return new UpdateFailure('the managed process could not be proven converged after termination')
}

/**
 * 通过原生 supervisor 运行一个受管子进程。
 *
 * <p>stdin 立即关闭（命令只以 argv 传参，绝不从 stdin 读取）；stdout/stderr 始终有界排空，各保留
 * {@code STREAM_LIMIT_BYTES} 但持续消费以避免背压。超时预算覆盖进程冷启动，取消在启动之前与启动 await
 * 之后都设围栏，信号随启动规格一起下传。终止只发起一次并缓存为同一个 Promise（发起时即挂拒绝处理，
 * 绝无未处理拒绝），主路径与 {@code finally} 都 await 同一 Promise，因此返回前执行范围一定已收敛。
 *
 * <p>收敛失败与真实 IO 故障都必须显式上报为安全的期望失败，绝不把「终止没有收敛」当成执行成功。
 */
export async function runManagedProcess(
  processes: ProcessSupervisor,
  spec: ProcessSpec,
  options: RunOptions,
): Promise<RunResult> {
  const signal = options.signal ?? spec.signal
  const stdout = new BoundedText(STREAM_LIMIT_BYTES)
  const stderr = new BoundedText(STREAM_LIMIT_BYTES)
  let managed: ManagedProcess | null = null
  let timer: NodeJS.Timeout | null = null
  let cancelled = false
  let timedOut = false
  let termination: Promise<void> | null = null
  let terminationFailure: unknown = null

  const requestTermination = (): Promise<void> => {
    if (termination !== null) {
      return termination
    }
    if (managed === null) {
      // 取消可能早于启动，此时没有可终止的范围：必须在启动 await 之后重新收敛。
      return Promise.resolve()
    }
    const target = managed
    termination = (async () => {
      try {
        await target.terminate()
      } catch (error) {
        terminationFailure = error
      }
    })()
    return termination
  }
  const terminationExpected = (): boolean => cancelled || timedOut || termination !== null
  const onAbort = (): void => {
    cancelled = true
    void requestTermination()
  }
  const closeStdin = (target: ManagedProcess): void => {
    if (!target.stdin.writableEnded) {
      target.stdin.end()
    }
  }
  const drain = (target: ManagedProcess, expected: () => boolean): Promise<[DrainOutcome, DrainOutcome]> =>
    Promise.all([
      drainBounded(target.stdout, stdout, expected),
      drainBounded(target.stderr, stderr, expected),
    ])

  try {
    if (signal?.aborted === true) {
      // 取消早于启动：不启动任何进程。
      cancelled = true
      return { code: null, stdout: '', stderr: '' }
    }
    if (options.timeoutMillis !== undefined && options.timeoutMillis > 0) {
      // deadline 覆盖进程冷启动；取消/超时在启动 await 之前也生效。
      timer = setTimeout(() => {
        timedOut = true
        void requestTermination()
      }, options.timeoutMillis)
    }
    signal?.addEventListener('abort', onAbort, { once: true })

    try {
      managed = await processes.start({ ...spec, signal })
    } catch {
      await requestTermination()
      if (terminationFailure !== null) {
        throw convergenceFailure()
      }
      if (cancelled || timedOut) {
        return { code: null, stdout: '', stderr: '' }
      }
      throw new UpdateFailure('the managed process could not be started')
    }
    const running = managed
    closeStdin(running)

    // 启动 await 之后重新设围栏：取消/超时窗口内绝不继续等待自然退出。
    if (cancelled || timedOut) {
      await requestTermination()
      await drain(running, () => true)
      if (terminationFailure !== null) {
        throw convergenceFailure()
      }
      return { code: null, stdout: stdout.text(), stderr: stderr.text() }
    }

    const drained = drain(running, terminationExpected)
    const exit = await running.exit
    // 自然退出时这里是幂等空操作；超时/取消时等到终止完全收敛。
    await requestTermination()
    const [out, err] = await drained
    if (terminationFailure !== null) {
      throw convergenceFailure()
    }
    if (out.error !== null || err.error !== null) {
      throw new UpdateFailure('the managed process output could not be read')
    }
    return { code: cancelled || timedOut ? null : exit.exitCode, stdout: stdout.text(), stderr: stderr.text() }
  } catch (error) {
    // 异常路径同样先收敛再决定终态；收敛失败绝不报告成功。
    await requestTermination()
    if (terminationFailure !== null) {
      throw convergenceFailure()
    }
    throw error
  } finally {
    if (timer !== null) {
      clearTimeout(timer)
    }
    signal?.removeEventListener('abort', onAbort)
    await requestTermination()
  }
}

function nodeExecutable(os: ReleaseOs): string {
  return os === 'windows' ? 'node.exe' : 'node'
}

/** PowerShell 单引号字面量：内部单引号写成两个单引号。 */
export function powerShellLiteral(value: string): string {
  return `'${value.replace(/'/g, "''")}'`
}

const UPDATER_DIRECTORY = 'updater'
const UPDATER_MARKER = 'updater.json'

interface StagedUpdater {
  node: string
  entry: string
}

function updaterNodeName(os: ReleaseOs): string {
  return os === 'windows' ? 'node.exe' : 'node'
}

/**
 * 把更新器运行时从已校验的暂存发行复制到 operation 私有目录。
 *
 * <p>更新器不能从 staged/installed 目录运行：Windows 不允许在可执行文件仍被映射时整体 rename 其目录。复制只取
 * {@code dist}、koffi 原生绑定和冻结 Node 可执行文件，并对 marker 做精确复用；复制完成前 marker 不存在，后续重放不会把
 * 半成品当成可用更新器。
 */
async function stageUpdater(
  extractDir: string,
  operationDir: string,
  os: ReleaseOs,
  operationId: string,
  targetVersion: string,
): Promise<StagedUpdater> {
  const sourceNodeModules = path.join(extractDir, 'node_modules')
  const sourceKoffi = path.join(sourceNodeModules, 'koffi')
  const sourceKoffiPlatforms = path.join(sourceNodeModules, '@koromix')
  const sourceDist = path.join(extractDir, 'dist')
  const sourceNode = path.join(extractDir, 'runtime', 'code-server', 'lib', updaterNodeName(os))
  if (
    !(await isRealDirectory(sourceDist)) ||
    !(await isRealRegularFile(path.join(sourceDist, 'update-entry.js'))) ||
    !(await isRealDirectory(sourceKoffi)) ||
    !(await isRealDirectory(sourceKoffiPlatforms)) ||
    !(await isRealRegularFile(sourceNode))
  ) {
    throw new UpdateFailure('staged distribution is missing the updater runtime')
  }
  const platformPackages = await readdirKoffiPlatforms(sourceKoffiPlatforms)
  if (platformPackages.length === 0) {
    throw new UpdateFailure('staged distribution is missing the updater native binding')
  }
  const updaterRoot = path.join(operationDir, UPDATER_DIRECTORY)
  const node = path.join(updaterRoot, updaterNodeName(os))
  const entry = path.join(updaterRoot, 'dist', 'update-entry.js')
  const markerPath = path.join(updaterRoot, UPDATER_MARKER)
  if (
    (await isRealDirectory(updaterRoot)) &&
    (await isRealRegularFile(markerPath)) &&
    (await isRealRegularFile(node)) &&
    (await isRealRegularFile(entry))
  ) {
    const marker = await readBoundedJsonObject(markerPath, MAX_MANIFEST_BYTES).catch(() => null)
    if (marker?.operationId === operationId && marker?.targetVersion === targetVersion) {
      return { node, entry }
    }
  }
  await rm(updaterRoot, { recursive: true, force: true })
  await ensurePrivateDirectory(updaterRoot)
  const destinationDist = path.join(updaterRoot, 'dist')
  const destinationKoffi = path.join(updaterRoot, 'node_modules', 'koffi')
  const destinationPlatforms = path.join(updaterRoot, 'node_modules', '@koromix')
  await ensurePrivateDirectory(path.join(updaterRoot, 'node_modules'))
  await cp(sourceDist, destinationDist, { recursive: true, force: true, dereference: false })
  await cp(sourceKoffi, destinationKoffi, { recursive: true, force: true, dereference: false })
  for (const name of platformPackages) {
    await cp(path.join(sourceKoffiPlatforms, name), path.join(destinationPlatforms, name), {
      recursive: true,
      force: true,
      dereference: false,
    })
  }
  await copyFile(sourceNode, node)
  if (os !== 'windows') {
    await chmod(node, 0o700)
  }
  await writePrivateFile(path.join(updaterRoot, 'package.json'), '{"type":"module","private":true}\n')
  await writePrivateFile(markerPath, `${JSON.stringify({ operationId, targetVersion }, null, 2)}\n`)
  return { node, entry }
}

async function readdirKoffiPlatforms(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true })
  return entries
    .filter(entry => entry.isDirectory() && /^koffi-[a-z0-9-]+$/.test(entry.name))
    .map(entry => entry.name)
}

/**
 * 分离更新器命令：独立于旧 Daemon 生命周期的短生命周期 OS 作业。
 *
 * <p>三种平台都以 {@code run <operation-dir> <staged-dir> <installed-dir> <updater-node> <updater-entry>}
 * 调用同一脚本；脚本的 run 动作只 exec operation 私有目录中的更新器，不自行替换发行。Windows 的注册/启动脚本以
 * UTF-16LE Base64 的 `-EncodedCommand` 传递，避免任何 shell 引号注入。
 */
export function detachedLaunchCommand(
  os: ReleaseOs,
  operationId: string,
  script: string,
  stagedDir: string,
  installedDir: string,
  updaterNode: string,
  updaterEntry: string,
): string[] {
  const unit = `kk-studio-daemon-update-${operationId}`
  const operationDir = path.dirname(stagedDir)
  if (os === 'macos') {
    return [
      'launchctl',
      'submit',
      '-l',
      unit,
      '--',
      'bash',
      script,
      'run',
      operationDir,
      stagedDir,
      installedDir,
      updaterNode,
      updaterEntry,
    ]
  }
  if (os === 'windows') {
    const action =
      `-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "${script}" ` +
      `-Action run -OperationDir "${operationDir}" -StagedDir "${stagedDir}" -InstalledDir "${installedDir}" ` +
      `-UpdaterNode "${updaterNode}" -UpdaterEntry "${updaterEntry}"`
    const inner = [
      `$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ${powerShellLiteral(action)}`,
      `$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(10)`,
      `$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType Interactive -RunLevel Limited`,
      `Register-ScheduledTask -TaskName ${powerShellLiteral(unit)} -Action $action -Trigger $trigger -Principal $principal -Force | Out-Null`,
      `Start-ScheduledTask -TaskName ${powerShellLiteral(unit)}`,
    ].join('; ')
    return ['powershell', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(inner, 'utf16le').toString('base64')]
  }
  // The staged directory lives at <operation>/extract, so <operation> is its parent.
  return [
    'systemd-run',
    '--user',
    '--collect',
    `--unit=${unit}`,
    'bash',
    script,
    'run',
    operationDir,
    stagedDir,
    installedDir,
    updaterNode,
    updaterEntry,
  ]
}

function boundedMessage(error: unknown): string {
  const raw = error instanceof Error ? error.message : 'update preparation failed'
  const cleaned = [...raw]
    .filter(char => {
      const code = char.codePointAt(0) ?? 0
      return code >= 0x20 && code !== 0x7f
    })
    .join('')
  return cleaned.length > 500 ? cleaned.slice(0, 500) : cleaned
}

async function readBoundedJsonObject(target: string, limit: number): Promise<Record<string, unknown>> {
  const stats = await lstat(target)
  if (stats.size > limit) {
    throw new UpdateFailure('update metadata exceeds its size budget')
  }
  const text = await readFile(target, 'utf8')
  const parsed: unknown = JSON.parse(text)
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new UpdateFailure('update metadata is malformed')
  }
  return parsed as Record<string, unknown>
}

type HandoffState = 'ABSENT' | 'PREPARING' | 'ACCEPTED' | 'FAILED'

/** 构建更新处理器；生产 `handleUpdate` 使用真实依赖，测试注入确定依赖。 */
export function createUpdateHandler(
  deps: UpdateDependencies,
): (
  command: Record<string, unknown>,
  config: DaemonConfig,
  signal: AbortSignal,
  reportProgress?: UpdateProgressReporter,
) => Promise<UpdateOutcome> {
  async function update(
    command: Record<string, unknown>,
    config: DaemonConfig,
    signal: AbortSignal,
    reportProgress?: UpdateProgressReporter,
  ): Promise<UpdateOutcome> {
    let operationId: string | undefined
    try {
      const parsed = parseUpdateCommand(command)
      operationId = parsed.operationId
      return await prepare(parsed, config, signal, reportProgress)
    } catch (error) {
      if (error instanceof UpdateFailure) {
        const echoed = operationId ?? safeOperationId(command)
        return failure(echoed, boundedMessage(error))
      }
      throw error
    }
  }

  async function prepare(
    parsed: ParsedCommand,
    config: DaemonConfig,
    signal: AbortSignal,
    reportProgress?: UpdateProgressReporter,
  ): Promise<UpdateOutcome> {
    const dataDir = path.resolve(config.dataDir)
    if (!(await isRealDirectory(dataDir))) {
      throw new UpdateFailure('managed installation root is not a real directory')
    }
    const lib = path.join(dataDir, 'lib')
    const installedEntry = path.join(lib, MANAGED_ENTRY)
    const configFile = path.join(dataDir, MANAGED_CONFIG_NAME)
    if (!(await isRealDirectory(lib)) || !(await isRealRegularFile(installedEntry)) || !(await isRealRegularFile(configFile))) {
      throw new UpdateFailure('managed installation layout is not present')
    }
    // 目标 URL 必须与本机 os/arch 对应的官方发布形态一致，防止伪造命令把 Daemon 指向别处。
    const expectedUrl = artifactUrl(parsed.targetVersion, deps.releaseOs, deps.releaseArch)
    if (parsed.artifactUrl !== expectedUrl) {
      throw new UpdateFailure('update artifact URL is not the official release target')
    }
    const updates = path.join(dataDir, UPDATE_DIRECTORY)
    if ((await lstatOrNull(updates)) !== null && !(await isRealDirectory(updates))) {
      throw new UpdateFailure('update directory is not a real directory')
    }
    await ensurePrivateDirectory(updates)
    const updateRoot = path.join(updates, parsed.operationId)
    if (!updateRoot.startsWith(dataDir + path.sep)) {
      throw new UpdateFailure('update target escapes the data directory')
    }
    if ((await lstatOrNull(updateRoot)) !== null && !(await isRealDirectory(updateRoot))) {
      throw new UpdateFailure('update target is not a real directory')
    }
    await ensurePrivateDirectory(updateRoot)
    const extractDir = path.join(updateRoot, EXTRACT_DIRECTORY)
    const scriptName = deps.releaseOs === 'windows' ? WINDOWS_UPDATE_SCRIPT_NAME : UNIX_UPDATE_SCRIPT_NAME
    const scriptPath = path.join(updateRoot, scriptName)
    const handoffPath = path.join(updateRoot, HANDOFF_FILE)
    const permitPath = path.join(updateRoot, PERMIT_FILE)

    // 幂等：只有已成功启动的 ACCEPTED handoff 才复用；PREPARING / FAILED 一律 fail closed，绝不重放。
    const state = await readHandoffState(handoffPath, parsed)
    if (state === 'ACCEPTED') {
      if (await isRealRegularFile(path.join(extractDir, MANAGED_ENTRY))) {
        const startupToken = await readHandoffStartupToken(handoffPath)
        await stageUpdater(extractDir, updateRoot, deps.releaseOs, parsed.operationId, parsed.targetVersion)
        await writePrivateFile(scriptPath, scriptContent(deps.releaseOs))
        return {
          operationId: parsed.operationId,
          phase: 'PREPARED',
          targetVersion: parsed.targetVersion,
          releasePermit: () =>
            releasePermit(permitPath, parsed.operationId, startupToken, parsed.targetVersion),
        }
      }
      throw new UpdateFailure('the update operation is not in a reusable state')
    }
    if (state === 'PREPARING' || state === 'FAILED') {
      throw new UpdateFailure('the update operation is not in a reusable state')
    }

    const archive = path.join(updateRoot, artifactFileName(parsed.targetVersion, deps.releaseOs, deps.releaseArch))
    const checksum = `${archive}.sha256`
    try {
      await deps.fetchTo(parsed.artifactUrl, archive, signal, MAX_ARCHIVE_BYTES, (downloadedBytes, totalBytes) => {
        reportProgress?.({ downloadedBytes, totalBytes })
      })
      await deps.fetchTo(checksumUrl(parsed.targetVersion, deps.releaseOs, deps.releaseArch), checksum, signal, MAX_CHECKSUM_BYTES)
      if (!(await isRealRegularFile(archive)) || !(await isRealRegularFile(checksum))) {
        throw new UpdateFailure('downloaded artifact is not a real file')
      }
      await verifyPrivateFile(archive)
      await verifyPrivateFile(checksum)
      const actualSha = await sha256File(archive)
      if (actualSha !== parsed.artifactSha256.toLowerCase()) {
        throw new UpdateFailure('downloaded artifact SHA256 does not match the command')
      }
      if (readChecksumDigest(await readFile(checksum, 'utf8')) !== actualSha) {
        throw new UpdateFailure('official checksum file does not match the artifact')
      }

      await rm(extractDir, { recursive: true, force: true })
      await ensurePrivateDirectory(extractDir)
      await deps.extractArchive(archive, extractDir, signal)
      if (!(await isRealRegularFile(path.join(extractDir, MANAGED_ENTRY)))) {
        throw new UpdateFailure('staged distribution is missing its entry point')
      }
      await verifyStagedManifest(extractDir, deps.releaseOs, deps.releaseArch, parsed.targetVersion)
      await verifyStagedConfiguration(extractDir, configFile, deps, signal)
      const updater = await stageUpdater(extractDir, updateRoot, deps.releaseOs, parsed.operationId, parsed.targetVersion)
      await writePrivateFile(scriptPath, scriptContent(deps.releaseOs))
      return await accept(parsed, extractDir, lib, configFile, scriptPath, permitPath, handoffPath, dataDir, updater)
    } catch (error) {
      await cleanupDownload(archive, checksum)
      throw error
    }
  }

  async function accept(
    parsed: ParsedCommand,
    extractDir: string,
    lib: string,
    configFile: string,
    scriptPath: string,
    permitPath: string,
    handoffPath: string,
    dataDir: string,
    updater: StagedUpdater,
  ): Promise<UpdateOutcome> {
    const startupToken = randomUUID()
    // handoff 必须先于 OS 作业持久化（PREPARING），作业只有等到许可文件才可替换发行目录。
    await writeHandoff(handoffPath, 'PREPARING', parsed, extractDir, lib, configFile, scriptPath, permitPath, startupToken)
    try {
      await launch(deps, scriptPath, extractDir, lib, dataDir, updater.node, updater.entry)
    } catch (error) {
      await writeHandoff(handoffPath, 'FAILED', parsed, extractDir, lib, configFile, scriptPath, permitPath, startupToken)
      throw error
    }
    // 作业已接受：切换 handoff 为 ACCEPTED，把 PREPARED 回执连同许可释放步骤交给 Runtime。
    // 许可绝不在本模块内释放：Runtime 必须在回执入缓存并完成一次出站尝试后才释放它，重放与重连重发据此重试。
    await writeHandoff(handoffPath, 'ACCEPTED', parsed, extractDir, lib, configFile, scriptPath, permitPath, startupToken)
    return {
      operationId: parsed.operationId,
      phase: 'PREPARED',
      targetVersion: parsed.targetVersion,
      releasePermit: () =>
        releasePermit(permitPath, parsed.operationId, startupToken, parsed.targetVersion),
    }
  }

  return update
}

/**
 * 幂等释放匹配 operation 的许可：独立 OS 更新器只在看到它之后才可替换发行目录。
 *
 * <p>释放前必须在有界时间内观察到同一 operation 的 takeover，并确认安装目录内核互斥已被持有：takeover 由更新器在拿到
 * 互斥后写入，二者同时成立才证明更新器已经接管，旧进程不会把许可释放给一个尚未启动或已经退出的旁观者。释放动作本身
 * 幂等（重复写入同一内容），因此同 operation 的重放与重连重发都能安全地重试一次失败的释放。
 */
async function releasePermit(
  permitPath: string,
  operationId: string,
  startupToken: string,
  targetVersion: string,
): Promise<void> {
  const operationDir = path.dirname(permitPath)
  const takeoverPath = path.join(operationDir, TAKEOVER_FILE)
  const lockPath = path.join(path.dirname(operationDir), UPDATE_LOCK_FILE)
  const deadline = Date.now() + PERMIT_TAKEOVER_TIMEOUT_MILLIS
  for (;;) {
    if (await isRealRegularFile(takeoverPath)) {
      let takeover: Record<string, unknown>
      try {
        takeover = await readBoundedJsonObject(takeoverPath, MAX_MANIFEST_BYTES)
      } catch {
        throw new UpdateFailure('the update takeover record is malformed')
      }
      if (
        takeover.operationId !== operationId ||
        takeover.token !== startupToken ||
        takeover.targetVersion !== targetVersion
      ) {
        throw new UpdateFailure('the update takeover record does not match the operation')
      }
      let lock: DirectoryLock
      try {
        lock = await acquireFileLock(lockPath)
      } catch (error) {
        if (error instanceof Error && error.message.includes('already locked')) {
          await writePrivateFile(permitPath, `${operationId}\n`)
          return
        }
        throw error
      }
      // takeover 存在但互斥已空闲：更新器尚未接管或已经退出，绝不能把许可释放给未知状态。
      await lock.close()
    }
    if (Date.now() >= deadline) {
      throw new UpdateFailure('the updater did not acquire the installation lock in time')
    }
    await sleep(PERMIT_TAKEOVER_POLL_MILLIS)
  }
}

function scriptContent(os: ReleaseOs): string {
  return os === 'windows' ? WINDOWS_UPDATE_SCRIPT : UNIX_UPDATE_SCRIPT
}

function safeOperationId(command: Record<string, unknown>): string {
  const value = command.operationId
  return typeof value === 'string' && OPERATION_ID_PATTERN.test(value) ? value : ''
}

function failure(operationId: string, message: string): UpdateOutcome {
  return { operationId, phase: 'FAILED', message }
}

async function cleanupDownload(archive: string, checksum: string): Promise<void> {
  await rm(archive, { force: true }).catch(() => undefined)
  await rm(checksum, { force: true }).catch(() => undefined)
}

async function readHandoffState(handoffPath: string, parsed: ParsedCommand): Promise<HandoffState> {
  if (!(await isRealRegularFile(handoffPath))) {
    return 'ABSENT'
  }
  let metadata: Record<string, unknown>
  try {
    metadata = await readBoundedJsonObject(handoffPath, MAX_MANIFEST_BYTES)
  } catch {
    return 'ABSENT'
  }
  if (metadata.operationId !== parsed.operationId) {
    throw new UpdateFailure('the operation directory already holds a different update')
  }
  if (
    metadata.targetVersion !== parsed.targetVersion ||
    metadata.artifactUrl !== parsed.artifactUrl ||
    typeof metadata.artifactSha256 !== 'string' ||
    metadata.artifactSha256.toLowerCase() !== parsed.artifactSha256.toLowerCase()
  ) {
    throw new UpdateFailure('the operation directory already holds a different update')
  }
  if (metadata.state === 'ACCEPTED') {
    return 'ACCEPTED'
  }
  if (metadata.state === 'PREPARING') {
    return 'PREPARING'
  }
  return 'FAILED'
}

async function writeHandoff(
  handoffPath: string,
  state: HandoffState,
  parsed: ParsedCommand,
  extractDir: string,
  installedDir: string,
  configFile: string,
  scriptPath: string,
  permitPath: string,
  startupToken: string,
): Promise<void> {
  const handoff = {
    state,
    operationId: parsed.operationId,
    targetVersion: parsed.targetVersion,
    artifactUrl: parsed.artifactUrl,
    artifactSha256: parsed.artifactSha256.toLowerCase(),
    extractDir,
    installedDir,
    configFile,
    updateScript: scriptPath,
    permitFile: permitPath,
    startupToken,
  }
  await writePrivateFile(handoffPath, `${JSON.stringify(handoff, null, 2)}\n`)
}

async function readHandoffStartupToken(handoffPath: string): Promise<string> {
  const metadata = await readBoundedJsonObject(handoffPath, MAX_MANIFEST_BYTES)
  const token = metadata.startupToken
  if (typeof token !== 'string' || !OPERATION_ID_PATTERN.test(token)) {
    throw new UpdateFailure('the accepted handoff has no startup token')
  }
  return token
}

async function verifyStagedManifest(
  extractDir: string,
  os: ReleaseOs,
  arch: ReleaseArch,
  targetVersion: string,
): Promise<void> {
  const manifestPath = path.join(extractDir, RUNTIME_MANIFEST)
  if (!(await isRealRegularFile(manifestPath))) {
    throw new UpdateFailure('staged distribution has no runtime manifest')
  }
  let manifest: Record<string, unknown>
  try {
    manifest = await readBoundedJsonObject(manifestPath, MAX_MANIFEST_BYTES)
  } catch {
    throw new UpdateFailure('staged distribution manifest is malformed')
  }
  const version = manifest.daemonVersion
  if (typeof version !== 'string' || version.length === 0 || version !== targetVersion) {
    throw new UpdateFailure('staged distribution manifest version does not match target')
  }
  const codeServer = manifest.codeServer
  if (typeof codeServer !== 'object' || codeServer === null || Array.isArray(codeServer)) {
    throw new UpdateFailure('staged distribution manifest has no platform runtime')
  }
  const platformKey = `${os}-${arch}`
  if (!Object.prototype.hasOwnProperty.call(codeServer, platformKey)) {
    throw new UpdateFailure('staged distribution does not contain this platform runtime')
  }
  const asset = (codeServer as Record<string, unknown>)[platformKey]
  if (typeof asset !== 'object' || asset === null || Array.isArray(asset)) {
    throw new UpdateFailure('staged distribution platform runtime is malformed')
  }
  const record = asset as Record<string, unknown>
  const runtimeVersion = manifest.codeServerVersion
  const url = record.url
  const versionText = typeof runtimeVersion === 'string' ? runtimeVersion : ''
  const expectedName = `code-server-${versionText}-${platformKey}.tar.gz`
  const expectedPrefix = `https://github.com/fengwk/code-server/releases/download/v${versionText}/`
  const frozenFork =
    /^[0-9A-Za-z._+-]+$/.test(versionText) &&
    typeof url === 'string' &&
    url === `${expectedPrefix}${expectedName}`
  if (!frozenFork) {
    throw new UpdateFailure('staged distribution runtime is not the frozen fork release')
  }
  if (typeof record.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(record.sha256)) {
    throw new UpdateFailure('staged distribution runtime checksum is not frozen')
  }
  const nodeName = nodeExecutable(os)
  const node = path.join(extractDir, 'runtime', 'code-server', 'lib', nodeName)
  const entry = path.join(extractDir, 'runtime', 'code-server', 'out', 'node', 'entry.js')
  if (!(await isRealRegularFile(node)) || !(await isRealRegularFile(entry))) {
    throw new UpdateFailure('staged distribution is missing the frozen code-server runtime')
  }
}

async function verifyStagedConfiguration(
  extractDir: string,
  configFile: string,
  deps: UpdateDependencies,
  signal: AbortSignal,
): Promise<void> {
  const node = path.join(extractDir, 'runtime', 'code-server', 'lib', nodeExecutable(deps.releaseOs))
  const entry = path.join(extractDir, MANAGED_ENTRY)
  if (!(await isRealRegularFile(configFile))) {
    throw new UpdateFailure('existing daemon configuration is not present')
  }
  // 只把配置路径作为 argv 传入；token 始终只留在文件里，绝不进入命令行。
  const result = await deps.run(node, [entry, '--check-config', configFile], {
    signal,
    timeoutMillis: CHECK_CONFIG_TIMEOUT_MILLIS,
    cwd: extractDir,
  })
  if (result.code !== 0 || result.stdout.trim() !== CHECK_CONFIG_SUCCESS) {
    throw new UpdateFailure('staged distribution rejected the existing daemon configuration')
  }
}

async function launch(
  deps: UpdateDependencies,
  scriptPath: string,
  stagedDir: string,
  installedDir: string,
  cwd: string,
  updaterNode: string,
  updaterEntry: string,
): Promise<void> {
  const operationId = path.basename(path.dirname(scriptPath))
  const command = detachedLaunchCommand(deps.releaseOs, operationId, scriptPath, stagedDir, installedDir, updaterNode, updaterEntry)
  const executable = command[0]
  if (executable === undefined) {
    throw new UpdateFailure('update launcher command is empty')
  }
  const result = await deps.run(executable, command.slice(1), { timeoutMillis: LAUNCH_TIMEOUT_MILLIS, cwd })
  if (result.code !== 0) {
    throw new UpdateFailure('detached update launcher did not start')
  }
}

/** 失败时移除部分文件；清理失败不能覆盖固定的取消/超时/网络说明，只作为 cause 保留。 */
async function removePartialDownload(destination: string, failure: UpdateFailure): Promise<never> {
  let cleanupError: unknown
  try {
    await rm(destination, { force: true })
  } catch (error) {
    cleanupError = error
  }
  if (cleanupError !== undefined) {
    throw new UpdateFailure(failure.message, {
      cause: new AggregateError([failure, cleanupError], 'official artifact download and cleanup failed'),
    })
  }
  throw failure
}

/**
 * 把下载阶段异常收敛为三个可区分、去敏的类别；原始网络/清理错误只保留在 cause 中，不进入对外消息。
 *
 * <p>body 已经完整交付时，后续只剩 dispatcher 清理，失败必须报告为 finalize 失败，不能被误判成下载停滞。
 */
function downloadFailure(error: unknown, signal: AbortSignal, idle: AbortSignal, bodyComplete: boolean): UpdateFailure {
  if (signal.aborted) {
    return new UpdateFailure(DOWNLOAD_CANCELLED_MESSAGE, { cause: error })
  }
  if (idle.aborted) {
    return new UpdateFailure(DOWNLOAD_NO_PROGRESS_MESSAGE, { cause: error })
  }
  if (error instanceof UpdateFailure) {
    return error
  }
  return new UpdateFailure(bodyComplete ? DOWNLOAD_FINALIZE_MESSAGE : DOWNLOAD_FAILED_MESSAGE, { cause: error })
}

/** 只接受可安全表示为非负整数的 Content-Length；其它值让 totalBytes 保持 unknown。 */
function declaredContentLength(headers: { 'content-length'?: string | string[] }): number | null {
  const raw = headers['content-length']
  const text = Array.isArray(raw) ? raw[0] : raw
  if (typeof text !== 'string' || !/^[0-9]+$/.test(text)) {
    return null
  }
  const value = Number(text)
  return Number.isSafeInteger(value) ? value : null
}

/** 生产依赖：官方 HTTPS 下载、受控归档解包、原生 supervisor 与 UUID。 */
export function productionDependencies(processes: ProcessSupervisor): UpdateDependencies {
  const platform = releasePlatformFor(process.platform, process.arch)
  return {
    releaseOs: platform.os,
    releaseArch: platform.arch,
    id: () => randomUUID(),
    async fetchTo(url, destination, signal, maxBytes, reportProgress) {
      const idle = new AbortController()
      const watchdog = setTimeout(() => idle.abort(), DOWNLOAD_IDLE_TIMEOUT_MILLIS)
      const effective = AbortSignal.any([signal, idle.signal])
      let bodyComplete = false
      try {
        const network = getOutboundNetwork()
        let current = url
        for (let hop = 0; ; hop += 1) {
          const next = await network.withResponse(
            current,
            { method: 'GET', redirect: 'manual' },
            effective,
            async response => {
              let completed = false
              try {
                if (REDIRECT_STATUS.has(response.statusCode)) {
                  const location = response.headers.location
                  if (typeof location !== 'string' || hop >= MAX_DOWNLOAD_REDIRECTS) {
                    throw new UpdateFailure(DOWNLOAD_FAILED_MESSAGE)
                  }
                  // 逐跳按新 URL 重新选择代理；GET 不携带任何凭证或 body 到重定向目标。
                  // headers 与 redirect 都不刷新 watchdog，避免把停滞伪装成进展。
                  return new URL(location, current).toString()
                }
                if (response.statusCode < 200 || response.statusCode >= 300) {
                  throw new UpdateFailure(DOWNLOAD_FAILED_MESSAGE)
                }
                const totalBytes = declaredContentLength(response.headers)
                let received = 0
                const counter = new Transform({
                  transform(chunk: Buffer, _encoding, callback) {
                    if (chunk.length > 0) {
                      watchdog.refresh()
                    }
                    received += chunk.length
                    if (received > maxBytes) {
                      callback(new UpdateFailure('official artifact download exceeds the size budget'))
                      return
                    }
                    if (chunk.length > 0) {
                      // 只有非空 body chunk 才算进度；headers/redirect/空 chunk 不刷新也不上报。
                      reportProgress?.(received, totalBytes !== null && totalBytes >= received ? totalBytes : null)
                    }
                    callback(null, chunk)
                  },
                })
                await pipeline(response.body, counter, createWriteStream(destination, { mode: 0o600 }), {
                  signal: effective,
                })
                // body 已完整交付：先停止 watchdog，再让 withResponse 关闭 dispatcher。
                completed = true
                clearTimeout(watchdog)
                return undefined
              } finally {
                bodyComplete = completed
              }
            },
          )
          if (next === undefined) {
            return
          }
          current = next
        }
      } catch (error) {
        clearTimeout(watchdog)
        throw await removePartialDownload(destination, downloadFailure(error, signal, idle.signal, bodyComplete))
      } finally {
        clearTimeout(watchdog)
      }
    },
    async extractArchive(archive, destination, signal) {
      await extractArchiveSafely(archive, destination, signal)
    },
    run: (command, args, options) =>
      runManagedProcess(processes, { executable: command, args, cwd: options.cwd ?? process.cwd(), signal: options.signal }, options),
  }
}

/**
 * 受管更新的生产入口：验官方制品、SHA256、发行清单版本与磁盘配置预检，冻结 ACCEPTED handoff 并启动独立更新器。
 *
 * Runtime 以此包装 UPDATE_RESULT；同一 operationId 的重复命令复用暂存制品，绝不第二次下载，并把
 * {@link UpdateOutcome.releasePermit} 当作可重试的交接许可释放步骤。宿主平台必须落在官方发布矩阵内，
 * 否则以去敏 FAILED 收尾而不是静默回退。
 */
export async function handleUpdate(
  command: Record<string, unknown>,
  config: DaemonConfig,
  signal: AbortSignal,
  processes: ProcessSupervisor,
  reportProgress?: UpdateProgressReporter,
): Promise<UpdateOutcome> {
  try {
    return await createUpdateHandler(productionDependencies(processes))(command, config, signal, reportProgress)
  } catch (error) {
    if (error instanceof UpdateFailure) {
      return failure(safeOperationId(command), boundedMessage(error))
    }
    throw error
  }
}
