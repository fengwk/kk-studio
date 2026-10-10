import { createHash, randomUUID } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { lstat, open, readFile, rm } from 'node:fs/promises'
import path from 'node:path'
import { Readable, Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import type { DaemonConfig, ProcessSpec, ProcessSupervisor } from './contracts.js'
import { extractArchive as extractArchiveSafely, MAX_ARCHIVE_BYTES, MAX_CHECKSUM_BYTES, MAX_MANIFEST_BYTES } from './archive.js'
import { ensurePrivateDirectory, verifyPrivateFile, writePrivateFile } from './native/index.js'
import { UNIX_UPDATE_SCRIPT, WINDOWS_UPDATE_SCRIPT } from './update-scripts.js'

/** 官方 Release 下载基址；只允许该仓库。 */
export const RELEASE_BASE = 'https://github.com/fengwk/kk-studio/releases/download'
/** 受管程序入口（发行根下）。 */
export const MANAGED_ENTRY = 'main.js'
/** 受管配置文件相对数据目录的固定名。 */
export const MANAGED_CONFIG_NAME = 'daemon.json'
/** 暂存与 handoff 的进程私有子目录。 */
export const UPDATE_DIRECTORY = 'updates'
/** handoff 元数据文件名；独立 OS 更新器按它执行替换与重启。 */
export const HANDOFF_FILE = 'handoff.json'
/** 更新许可文件名；独立 OS 更新器必须等它出现才可替换发行目录。 */
export const PERMIT_FILE = 'permit'
/** 独立更新器读取的最小证据文件名（阶段与去敏说明）。 */
export const RESULT_FILE = 'update-result.json'
/** 冻结发行清单文件名（发行根下），承载 daemon 版本与平台运行时。 */
export const RUNTIME_MANIFEST = 'runtime-manifest.json'
/** Unix 更新脚本在暂存目录内的固定文件名。 */
export const UNIX_UPDATE_SCRIPT_NAME = 'kk-studio-daemon-update.sh'
/** Windows 更新脚本在暂存目录内的固定文件名。 */
export const WINDOWS_UPDATE_SCRIPT_NAME = 'kk-studio-daemon-update.ps1'

/** 子进程每条流的保留上限：64 KiB。 */
export const STREAM_LIMIT_BYTES = 64 * 1024
/** 归档下载固定超时：300 秒。 */
export const DOWNLOAD_TIMEOUT_MILLIS = 300_000

const CHECK_CONFIG_SUCCESS = 'Daemon configuration is valid'
const EXTRACT_DIRECTORY = 'extract'
const CHECK_CONFIG_TIMEOUT_MILLIS = 60_000
const LAUNCH_TIMEOUT_MILLIS = 15_000

const VERSION_PATTERN = /^[0-9A-Za-z._+-]{1,64}$/
const OPERATION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const SHA256_PATTERN = /^[0-9a-fA-F]{64}$/
const COMMAND_FIELDS = ['operationId', 'targetVersion', 'artifactUrl', 'artifactSha256'] as const

export type ReleaseOs = 'linux' | 'macos' | 'windows'
export type ReleaseArch = 'amd64' | 'arm64'

export interface UpdateOutcome {
  operationId: string
  phase: 'ACCEPTED' | 'PREPARED' | 'FAILED'
  message?: string
  targetVersion?: string
}

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
  fetchTo(url: string, destination: string, signal: AbortSignal, maxBytes: number): Promise<void>
  extractArchive(archive: string, destination: string, signal: AbortSignal): Promise<void>
  run(command: string, args: string[], options: RunOptions): Promise<RunResult>
  id(): string
}

/** 预期的期望失败；消息只承载去敏、有界说明，绝不携带输入值。 */
class UpdateFailure extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UpdateFailure'
  }
}

/** 制品文件名：{@code kk-studio-daemon-v<version>-<os>-<arch>.tar.gz}。 */
export function artifactFileName(version: string, os: ReleaseOs, arch: ReleaseArch): string {
  return `kk-studio-daemon-v${version}-${os}-${arch}.tar.gz`
}

/** 制品下载 URL。 */
export function artifactUrl(version: string, os: ReleaseOs, arch: ReleaseArch): string {
  return `${RELEASE_BASE}/v${version}/${artifactFileName(version, os, arch)}`
}

/** 校验文件下载 URL。 */
export function checksumUrl(version: string, os: ReleaseOs, arch: ReleaseArch): string {
  return `${artifactUrl(version, os, arch)}.sha256`
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

async function lstatOrNull(target: string): Promise<Awaited<ReturnType<typeof lstat>> | null> {
  try {
    return await lstat(target)
  } catch {
    return null
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

async function drain(stream: NodeJS.ReadableStream, sink: BoundedText): Promise<void> {
  for await (const chunk of stream) {
    sink.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)))
  }
}

/**
 * 通过原生 supervisor 运行一个受管子进程：始终有界消费 stdout/stderr，超时或取消都会终止整个
 * ManagedProcess 并等待其真正收敛，随后才返回结果。
 */
export async function runManagedProcess(
  processes: ProcessSupervisor,
  spec: ProcessSpec,
  options: RunOptions,
): Promise<RunResult> {
  const managed = await processes.start(spec)
  const stdout = new BoundedText(STREAM_LIMIT_BYTES)
  const stderr = new BoundedText(STREAM_LIMIT_BYTES)
  const outDone = drain(managed.stdout, stdout).catch(() => undefined)
  const errDone = drain(managed.stderr, stderr).catch(() => undefined)
  let timedOut = false
  let terminating = false
  const terminate = (): void => {
    if (terminating) {
      return
    }
    terminating = true
    void managed.terminate().catch(() => undefined)
  }
  const timer =
    options.timeoutMillis === undefined
      ? undefined
      : setTimeout(() => {
          timedOut = true
          terminate()
        }, options.timeoutMillis)
  const onAbort = (): void => {
    terminate()
  }
  if (options.signal !== undefined) {
    if (options.signal.aborted) {
      terminate()
    } else {
      options.signal.addEventListener('abort', onAbort, { once: true })
    }
  }
  try {
    const exit = await managed.exit
    await Promise.all([outDone, errDone])
    return { code: timedOut ? null : exit.exitCode, stdout: stdout.text(), stderr: stderr.text() }
  } finally {
    if (timer !== undefined) {
      clearTimeout(timer)
    }
    options.signal?.removeEventListener('abort', onAbort)
  }
}

function nodeExecutable(os: ReleaseOs): string {
  return os === 'windows' ? 'node.exe' : 'node'
}

/** PowerShell 单引号字面量：内部单引号写成两个单引号。 */
export function powerShellLiteral(value: string): string {
  return `'${value.replace(/'/g, "''")}'`
}

/**
 * 分离更新器命令：独立于旧 Daemon 生命周期的短生命周期 OS 作业。
 *
 * 三种平台都把 `<operation-dir> <staged-dir> <installed-dir>` 三个参数原样交给更新脚本；Windows 的
 * 注册/启动脚本以 UTF-16LE Base64 的 `-EncodedCommand` 传递，避免任何 shell 引号注入。
 */
export function detachedLaunchCommand(
  os: ReleaseOs,
  operationId: string,
  script: string,
  stagedDir: string,
  installedDir: string,
): string[] {
  const unit = `kk-studio-daemon-update-${operationId}`
  const operationDir = path.dirname(stagedDir)
  if (os === 'macos') {
    return ['launchctl', 'submit', '-l', unit, '--', 'bash', script, operationDir, stagedDir, installedDir]
  }
  if (os === 'windows') {
    const action =
      `-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "${script}" ` +
      `-OperationDir "${operationDir}" -StagedDir "${stagedDir}" -InstalledDir "${installedDir}"`
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
  return ['systemd-run', '--user', '--collect', `--unit=${unit}`, 'bash', script, operationDir, stagedDir, installedDir]
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
): (command: Record<string, unknown>, config: DaemonConfig, signal: AbortSignal) => Promise<UpdateOutcome> {
  async function update(
    command: Record<string, unknown>,
    config: DaemonConfig,
    signal: AbortSignal,
  ): Promise<UpdateOutcome> {
    let operationId: string | undefined
    try {
      const parsed = parseUpdateCommand(command)
      operationId = parsed.operationId
      return await prepare(parsed, config, signal)
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
        await writePrivateFile(scriptPath, scriptContent(deps.releaseOs))
        return { operationId: parsed.operationId, phase: 'PREPARED', targetVersion: parsed.targetVersion }
      }
      throw new UpdateFailure('the update operation is not in a reusable state')
    }
    if (state === 'PREPARING' || state === 'FAILED') {
      throw new UpdateFailure('the update operation is not in a reusable state')
    }

    const archive = path.join(updateRoot, artifactFileName(parsed.targetVersion, deps.releaseOs, deps.releaseArch))
    const checksum = `${archive}.sha256`
    try {
      await deps.fetchTo(parsed.artifactUrl, archive, signal, MAX_ARCHIVE_BYTES)
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
      await writePrivateFile(scriptPath, scriptContent(deps.releaseOs))
    } catch (error) {
      await cleanupDownload(archive, checksum)
      throw error
    }

    // handoff 必须先于 OS 作业持久化（PREPARING），作业只有等到许可文件才可替换发行目录。
    await writeHandoff(handoffPath, 'PREPARING', parsed, extractDir, lib, configFile, scriptPath, permitPath)
    try {
      await launch(deps, scriptPath, extractDir, lib, dataDir)
    } catch (error) {
      await writeHandoff(handoffPath, 'FAILED', parsed, extractDir, lib, configFile, scriptPath, permitPath)
      throw error
    }
    // 作业已接受：切换到 ACCEPTED 并释放许可，表示旧 Daemon 已把阶段回执交给 Runtime。
    await writeHandoff(handoffPath, 'ACCEPTED', parsed, extractDir, lib, configFile, scriptPath, permitPath)
    await writePrivateFile(permitPath, `${parsed.operationId}\n`)
    return { operationId: parsed.operationId, phase: 'PREPARED', targetVersion: parsed.targetVersion }
  }

  return update
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
  }
  await writePrivateFile(handoffPath, `${JSON.stringify(handoff, null, 2)}\n`)
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
  if (!Object.prototype.hasOwnProperty.call(codeServer, `${os}-${arch}`)) {
    throw new UpdateFailure('staged distribution does not contain this platform runtime')
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
): Promise<void> {
  const operationId = path.basename(path.dirname(scriptPath))
  const command = detachedLaunchCommand(deps.releaseOs, operationId, scriptPath, stagedDir, installedDir)
  const executable = command[0]
  if (executable === undefined) {
    throw new UpdateFailure('update launcher command is empty')
  }
  const result = await deps.run(executable, command.slice(1), { timeoutMillis: LAUNCH_TIMEOUT_MILLIS, cwd })
  if (result.code !== 0) {
    throw new UpdateFailure('detached update launcher did not start')
  }
}

/** 生产依赖：官方 HTTPS 下载、受控归档解包、原生 supervisor 与 UUID。 */
export function productionDependencies(processes: ProcessSupervisor): UpdateDependencies {
  return {
    releaseOs: releaseOsFor(process.platform),
    releaseArch: releaseArchFor(process.arch),
    id: () => randomUUID(),
    async fetchTo(url, destination, signal, maxBytes) {
      const effective = AbortSignal.any([signal, AbortSignal.timeout(DOWNLOAD_TIMEOUT_MILLIS)])
      const response = await fetch(url, { signal: effective, redirect: 'follow' })
      if (!response.ok || response.body === null) {
        throw new UpdateFailure('official artifact download failed')
      }
      let received = 0
      const counter = new Transform({
        transform(chunk: Buffer, _encoding, callback) {
          received += chunk.length
          if (received > maxBytes) {
            callback(new UpdateFailure('official artifact download exceeds the size budget'))
            return
          }
          callback(null, chunk)
        },
      })
      try {
        await pipeline(
          Readable.fromWeb(response.body as Parameters<typeof Readable.fromWeb>[0]),
          counter,
          createWriteStream(destination, { mode: 0o600 }),
        )
      } catch (error) {
        await rm(destination, { force: true }).catch(() => undefined)
        if (effective.aborted) {
          throw new UpdateFailure('official artifact download was aborted')
        }
        throw error
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
 * 受管更新的生产入口：验官方制品、SHA256、发行清单版本与磁盘配置预检，写入 handoff 并启动独立更新器。
 *
 * Runtime 以此包装 UPDATE_RESULT；同一 operationId 的重复命令复用暂存制品，绝不第二次下载。宿主平台必须落在
 * 官方发布矩阵内，否则以去敏 FAILED 收尾而不是静默回退。
 */
export async function handleUpdate(
  command: Record<string, unknown>,
  config: DaemonConfig,
  signal: AbortSignal,
  processes: ProcessSupervisor,
): Promise<UpdateOutcome> {
  try {
    return await createUpdateHandler(productionDependencies(processes))(command, config, signal)
  } catch (error) {
    if (error instanceof UpdateFailure) {
      return failure(safeOperationId(command), boundedMessage(error))
    }
    throw error
  }
}
