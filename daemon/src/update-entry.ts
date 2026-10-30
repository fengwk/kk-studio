import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { realpathSync } from 'node:fs'
import { lstat, readdir, readFile, rename } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { DaemonConfig } from './contracts.js'
import { updateScriptOs } from './native/host.js'
import { acquireFileLock, ensurePrivateDirectory, writePrivateFile, type DirectoryLock } from './native/index.js'

/**
 * 独立更新器与新 Daemon 共享的本地交接入口。
 *
 * <p>更新器持有安装目录级内核互斥贯穿旧进程退出、备份、替换和新进程预检；每一步只以私有 handoff、步骤日志和真实目录
 * 布局为证据。新 Daemon 在启动时通过 {@link awaitStartupHandoff} 写入私有启动确认，并等到更新器写出 result 且释放互斥
 * 之后才返回；任何缺失结果、陈旧成功或超时都不会被解释为成功。
 *
 * <p>本入口只处理本机文件与服务生命周期，不读取凭据、不访问控制协议，也不重试下载。
 */

/** 受管程序入口（发行根下）。 */
export const MANAGED_ENTRY = 'main.js'
/** 暂存与 handoff 的进程私有子目录。 */
export const UPDATE_DIRECTORY = 'updates'
/** handoff 元数据文件名；独立更新器按它执行替换与重启。 */
export const HANDOFF_FILE = 'handoff.json'
/** 更新许可文件名；独立更新器必须等它出现才可停止旧受管范围。 */
export const PERMIT_FILE = 'permit'
/** 独立更新器写出的本机结果。 */
export const RESULT_FILE = 'update-result.json'
/** 结果已被当前启动消费的标记，避免同一失败在每次启动时重复上报。 */
export const RESULT_REPORTED_FILE = 'update-result.reported'
/** 更新器取得互斥后的本机接管确认。 */
export const TAKEOVER_FILE = 'update-takeover.json'
/** 替换步骤日志；与 daemon 写出的 handoff 分离，避免并发覆盖状态。 */
export const STEPS_FILE = 'update-steps.json'
/** 新 Daemon 完成本机预检后写出的私有启动确认。 */
export const STARTUP_ACK_FILE = 'startup-ack.json'
/** 安装目录级内核互斥文件；位于 updates 目录，不会随发行目录 rename。 */
export const UPDATE_LOCK_FILE = 'install.lock'
/** 冻结发行清单文件名（发行根下），承载 daemon 版本与平台运行时。 */
export const RUNTIME_MANIFEST = 'runtime-manifest.json'
/** Unix 更新脚本在暂存目录内的固定文件名。 */
export const UNIX_UPDATE_SCRIPT_NAME = 'kk-studio-daemon-update.sh'
/** Windows 更新脚本在暂存目录内的固定文件名。 */
export const WINDOWS_UPDATE_SCRIPT_NAME = 'kk-studio-daemon-update.ps1'

export const DEFAULT_PERMIT_WAIT_MILLIS = 120_000
export const DEFAULT_STARTUP_ACK_WAIT_MILLIS = 120_000
export const DEFAULT_HANDOFF_POLL_MILLIS = 250
const MAX_METADATA_BYTES = 1024 * 1024
const OPERATION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const TOKEN_PATTERN = OPERATION_ID_PATTERN

export type HandoffState = 'PREPARING' | 'ACCEPTED' | 'FAILED'
export type UpdatePhase = 'PREPARED' | 'FAILED'
export type UpdateStep =
  | 'TAKEOVER'
  | 'PERMIT'
  | 'STOPPING'
  | 'STOPPED'
  | 'BACKUP_PREPARED'
  | 'BACKED_UP'
  | 'REPLACED'
  | 'STARTING'
  | 'STARTED'
  | 'RESTORED'
  | 'COMPLETED'
export type ServiceAction = 'stop' | 'start'

export interface HandoffMetadata {
  state: HandoffState
  operationId: string
  targetVersion: string
  artifactUrl: string
  artifactSha256: string
  extractDir: string
  installedDir: string
  configFile: string
  updateScript: string
  permitFile: string
  startupToken: string
}

export interface UpdateResult {
  operationId: string
  phase: UpdatePhase
  message?: string
  targetVersion?: string
  daemonVersion?: string
  pid?: number
}

export interface UpdateJournal {
  operationId: string
  targetVersion: string
  backupDir?: string
  steps: UpdateStep[]
}

export interface StartupAck {
  operationId: string
  targetVersion: string
  token: string
  version: string
  pid: number
}

export type StartupOutcome =
  | { state: 'normal' }
  | { state: 'updated'; operationId: string; targetVersion: string }
  | { state: 'failed'; operationId: string; message: string }

export interface StartupHandoffOptions {
  signal?: AbortSignal
  timeoutMillis?: number
  pollIntervalMillis?: number
}

export interface UpdaterOptions {
  operationDir: string
  stagedDir: string
  installedDir: string
  updateScript: string
  permitWaitMillis?: number
  startupAckWaitMillis?: number
  pollIntervalMillis?: number
}

export interface UpdaterDependencies {
  /** 运行同一脚本的 stop/start 动作；注入实现用于故障注入测试。 */
  runService(action: ServiceAction, scriptPath: string, directories: UpdaterDirectories): Promise<void>
  rename?(from: string, to: string): Promise<void>
}

export interface UpdaterDirectories {
  operationDir: string
  stagedDir: string
  installedDir: string
}

interface ActiveHandoff {
  operationDir: string
  handoff: HandoffMetadata
  journal: UpdateJournal
  result: UpdateResult | null
  reported: boolean
}

class HandoffFailure extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'HandoffFailure'
  }
}

function failure(message: string, result: Partial<UpdateResult> = {}): UpdateResult {
  return { ...result, operationId: result.operationId ?? '', phase: 'FAILED', message }
}

function safeText(error: unknown, fallback: string): string {
  if (error instanceof HandoffFailure) {
    return error.message
  }
  return fallback
}

function requireText(value: unknown, field: string, pattern?: RegExp): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > 4096) {
    throw new HandoffFailure(`update metadata.${field} is missing`)
  }
  if (pattern !== undefined && !pattern.test(value)) {
    throw new HandoffFailure(`update metadata.${field} is invalid`)
  }
  return value
}

function optionalText(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 && value.length <= 500 ? value : undefined
}

/** 只把 ENOENT 视为不存在；其它检查故障必须显式失败。 */
async function lstatOrNull(target: string): Promise<Awaited<ReturnType<typeof lstat>> | null> {
  try {
    return await lstat(target)
  } catch (error) {
    if ((error as NodeJS.ErrnoException | null)?.code === 'ENOENT') {
      return null
    }
    throw new HandoffFailure('local update metadata could not be inspected')
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

async function readBoundedJson(target: string): Promise<Record<string, unknown>> {
  const stats = await lstatOrNull(target)
  if (stats === null || !stats.isFile() || stats.isSymbolicLink() || stats.size > MAX_METADATA_BYTES) {
    throw new HandoffFailure('local update metadata is missing or unsafe')
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(await readFile(target, 'utf8'))
  } catch {
    throw new HandoffFailure('local update metadata is malformed')
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new HandoffFailure('local update metadata is malformed')
  }
  return parsed as Record<string, unknown>
}

/** 严格读取 handoff；缺字段或未知状态一律 fail closed。 */
export function parseHandoff(raw: Record<string, unknown>): HandoffMetadata {
  const state = raw.state
  if (state !== 'PREPARING' && state !== 'ACCEPTED' && state !== 'FAILED') {
    throw new HandoffFailure('update handoff state is invalid')
  }
  return {
    state,
    operationId: requireText(raw.operationId, 'operationId', OPERATION_ID_PATTERN),
    targetVersion: requireText(raw.targetVersion, 'targetVersion'),
    artifactUrl: requireText(raw.artifactUrl, 'artifactUrl'),
    artifactSha256: requireText(raw.artifactSha256, 'artifactSha256'),
    extractDir: requireText(raw.extractDir, 'extractDir'),
    installedDir: requireText(raw.installedDir, 'installedDir'),
    configFile: requireText(raw.configFile, 'configFile'),
    updateScript: requireText(raw.updateScript, 'updateScript'),
    permitFile: requireText(raw.permitFile, 'permitFile'),
    startupToken: requireText(raw.startupToken, 'startupToken', TOKEN_PATTERN),
  }
}

export async function readHandoff(operationDir: string): Promise<HandoffMetadata | null> {
  const handoffPath = path.join(operationDir, HANDOFF_FILE)
  if (!(await isRealRegularFile(handoffPath))) {
    return null
  }
  const handoff = parseHandoff(await readBoundedJson(handoffPath))
  if (handoff.operationId !== path.basename(operationDir)) {
    throw new HandoffFailure('update handoff does not match its operation directory')
  }
  return handoff
}

/** 读取更新器写出的结果；损坏的结果绝不被当成“没有结果”。 */
export async function readResult(operationDir: string): Promise<UpdateResult | null> {
  const resultPath = path.join(operationDir, RESULT_FILE)
  if (!(await isRealRegularFile(resultPath))) {
    return null
  }
  const raw = await readBoundedJson(resultPath)
  if (raw.phase !== 'PREPARED' && raw.phase !== 'FAILED') {
    throw new HandoffFailure('update result phase is invalid')
  }
  const operationId = requireText(raw.operationId, 'operationId', OPERATION_ID_PATTERN)
  if (operationId !== path.basename(operationDir)) {
    throw new HandoffFailure('update result does not match its operation directory')
  }
  return {
    operationId,
    phase: raw.phase,
    message: optionalText(raw.message),
    targetVersion: optionalText(raw.targetVersion),
    daemonVersion: optionalText(raw.daemonVersion),
    pid: typeof raw.pid === 'number' && Number.isSafeInteger(raw.pid) && raw.pid > 0 ? raw.pid : undefined,
  }
}

async function writeResult(operationDir: string, result: UpdateResult): Promise<void> {
  await ensurePrivateDirectory(operationDir)
  await writePrivateFile(path.join(operationDir, RESULT_FILE), `${JSON.stringify(result, null, 2)}\n`)
}

export async function readJournal(operationDir: string): Promise<UpdateJournal> {
  const journalPath = path.join(operationDir, STEPS_FILE)
  if (!(await isRealRegularFile(journalPath))) {
    return {
      operationId: path.basename(operationDir),
      targetVersion: '',
      steps: [],
    }
  }
  const raw = await readBoundedJson(journalPath)
  const operationId = requireText(raw.operationId, 'operationId', OPERATION_ID_PATTERN)
  if (operationId !== path.basename(operationDir)) {
    throw new HandoffFailure('update step journal does not match its operation directory')
  }
  const steps = raw.steps
  if (!Array.isArray(steps) || steps.some(step => typeof step !== 'string')) {
    throw new HandoffFailure('update step journal is malformed')
  }
  const allowed = new Set<string>([
    'TAKEOVER',
    'PERMIT',
    'STOPPING',
    'STOPPED',
    'BACKUP_PREPARED',
    'BACKED_UP',
    'REPLACED',
    'STARTING',
    'STARTED',
    'RESTORED',
    'COMPLETED',
  ])
  if (steps.some(step => !allowed.has(step as string))) {
    throw new HandoffFailure('update step journal contains an unknown step')
  }
  return {
    operationId,
    targetVersion: optionalText(raw.targetVersion) ?? '',
    backupDir: optionalText(raw.backupDir),
    steps: steps as UpdateStep[],
  }
}

async function writeJournal(operationDir: string, journal: UpdateJournal): Promise<void> {
  await ensurePrivateDirectory(operationDir)
  await writePrivateFile(path.join(operationDir, STEPS_FILE), `${JSON.stringify(journal, null, 2)}\n`)
}

async function appendStep(operationDir: string, journal: UpdateJournal, step: UpdateStep): Promise<void> {
  journal.steps.push(step)
  await writeJournal(operationDir, journal)
}

/** 读取安装在发行目录里的 daemon 版本；缺失或损坏时返回 null，由调用方 fail closed。 */
export async function installedDaemonVersion(installedDir: string): Promise<string | null> {
  const manifestPath = path.join(installedDir, RUNTIME_MANIFEST)
  if (!(await isRealRegularFile(manifestPath))) {
    return null
  }
  try {
    const manifest = await readBoundedJson(manifestPath)
    return typeof manifest.daemonVersion === 'string' ? manifest.daemonVersion : null
  } catch {
    return null
  }
}

/** 验证一个发行目录存在可执行入口和匹配的目标版本。 */
async function verifyInstalledTarget(installedDir: string, targetVersion: string): Promise<void> {
  if (!(await isRealDirectory(installedDir))) {
    throw new HandoffFailure('the installed distribution is missing')
  }
  if (!(await isRealRegularFile(path.join(installedDir, MANAGED_ENTRY)))) {
    throw new HandoffFailure('the installed distribution has no entry point')
  }
  if ((await installedDaemonVersion(installedDir)) !== targetVersion) {
    throw new HandoffFailure('the installed distribution does not match the update target')
  }
}

async function verifyStagedDistribution(stagedDir: string): Promise<void> {
  if (!(await isRealDirectory(stagedDir)) || !(await isRealRegularFile(path.join(stagedDir, MANAGED_ENTRY)))) {
    throw new HandoffFailure('the staged distribution is missing')
  }
}

function assertConsistentHandoff(
  handoff: HandoffMetadata,
  operationDir: string,
  stagedDir: string,
  installedDir: string,
  updateScript: string,
): void {
  if (handoff.operationId !== path.basename(operationDir)) {
    throw new HandoffFailure('update handoff does not match the operation')
  }
  if (path.resolve(handoff.extractDir) !== path.resolve(stagedDir)) {
    throw new HandoffFailure('update handoff does not match the staged directory')
  }
  if (path.resolve(handoff.installedDir) !== path.resolve(installedDir)) {
    throw new HandoffFailure('update handoff does not match the installed directory')
  }
  if (path.resolve(handoff.updateScript) !== path.resolve(updateScript)) {
    throw new HandoffFailure('update handoff does not match the update script')
  }
}

async function writeTakeover(operationDir: string, handoff: HandoffMetadata): Promise<void> {
  await writePrivateFile(
    path.join(operationDir, TAKEOVER_FILE),
    `${JSON.stringify(
      {
        operationId: handoff.operationId,
        targetVersion: handoff.targetVersion,
        token: handoff.startupToken,
        pid: process.pid,
      },
      null,
      2,
    )}\n`,
  )
}

async function readTakeover(operationDir: string): Promise<Record<string, unknown> | null> {
  if (!(await isRealRegularFile(path.join(operationDir, TAKEOVER_FILE)))) {
    return null
  }
  return readBoundedJson(path.join(operationDir, TAKEOVER_FILE))
}

function sleep(millis: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted === true) {
      reject(new HandoffFailure('local update handoff was cancelled'))
      return
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, millis)
    const onAbort = (): void => {
      clearTimeout(timer)
      reject(new HandoffFailure('local update handoff was cancelled'))
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

async function waitForPermit(
  handoff: HandoffMetadata,
  operationDir: string,
  timeoutMillis: number,
  pollIntervalMillis: number,
): Promise<void> {
  const deadline = Date.now() + timeoutMillis
  for (;;) {
    const permitPath = path.join(operationDir, PERMIT_FILE)
    if (await isRealRegularFile(permitPath)) {
      const permit = (await readFile(permitPath, 'utf8')).trim()
      if (permit !== handoff.operationId) {
        throw new HandoffFailure('the update permit does not match the operation')
      }
      const current = await readHandoff(operationDir)
      if (current === null || current.state !== 'ACCEPTED') {
        throw new HandoffFailure('the update handoff was not accepted')
      }
      return
    }
    if (Date.now() >= deadline) {
      throw new HandoffFailure('the daemon did not release the update permit in time')
    }
    await sleep(pollIntervalMillis)
  }
}

async function waitForStartupAck(
  handoff: HandoffMetadata,
  operationDir: string,
  timeoutMillis: number,
  pollIntervalMillis: number,
): Promise<StartupAck> {
  const deadline = Date.now() + timeoutMillis
  const ackPath = path.join(operationDir, STARTUP_ACK_FILE)
  for (;;) {
    if (await isRealRegularFile(ackPath)) {
      const raw = await readBoundedJson(ackPath)
      const ack: StartupAck = {
        operationId: requireText(raw.operationId, 'operationId', OPERATION_ID_PATTERN),
        targetVersion: requireText(raw.targetVersion, 'targetVersion'),
        token: requireText(raw.token, 'token', TOKEN_PATTERN),
        version: requireText(raw.version, 'version'),
        pid: typeof raw.pid === 'number' && Number.isSafeInteger(raw.pid) && raw.pid > 0 ? raw.pid : 0,
      }
      if (ack.operationId !== handoff.operationId || ack.token !== handoff.startupToken) {
        throw new HandoffFailure('the startup acknowledgement does not match the operation')
      }
      if (ack.targetVersion !== handoff.targetVersion || ack.version !== handoff.targetVersion) {
        throw new HandoffFailure('the startup acknowledgement reports the wrong version')
      }
      if (ack.pid <= 0) {
        throw new HandoffFailure('the startup acknowledgement has no process identity')
      }
      return ack
    }
    if (Date.now() >= deadline) {
      throw new HandoffFailure('the new daemon did not confirm startup in time')
    }
    await sleep(pollIntervalMillis)
  }
}

/**
 * 完成一次本机交接。函数返回前互斥一定已释放；返回 FAILED 表示已持久记录确定失败，调用方不得再猜测成功。
 *
 * <p>步骤恢复只依据 handoff、步骤日志和目录布局：缺少 installed 且备份存在时才继续替换，已安装目标版本且 staging
 * 已消失时才跳过替换。这里不读取 mtime、不按等待时长推断成功。
 */
export async function runUpdater(options: UpdaterOptions, dependencies?: Partial<UpdaterDependencies>): Promise<UpdateResult> {
  const operationId = path.basename(path.resolve(options.operationDir))
  if (!OPERATION_ID_PATTERN.test(operationId)) {
    throw new HandoffFailure('the update operation directory is invalid')
  }
  const operationDir = path.resolve(options.operationDir)
  const stagedDir = path.resolve(options.stagedDir)
  const installedDir = path.resolve(options.installedDir)
  const updateScript = path.resolve(options.updateScript)
  const permitWaitMillis = options.permitWaitMillis ?? DEFAULT_PERMIT_WAIT_MILLIS
  const startupAckWaitMillis = options.startupAckWaitMillis ?? DEFAULT_STARTUP_ACK_WAIT_MILLIS
  const pollIntervalMillis = options.pollIntervalMillis ?? DEFAULT_HANDOFF_POLL_MILLIS
  const deps = dependencies ?? {}
  const doRename = deps.rename ?? rename
  const runService =
    deps.runService ??
    ((action, scriptPath, directories) =>
      runUpdateScript(updateScriptOs(), action, scriptPath, directories))

  const existing = await readResult(operationDir)
  if (existing !== null) {
    if (existing.phase === 'PREPARED') {
      try {
        await verifyInstalledTarget(installedDir, (await readHandoff(operationDir))?.targetVersion ?? '')
      } catch {
        const result = failure('the installed distribution does not match the recorded update', { operationId })
        await writeResult(operationDir, result)
        return result
      }
    }
    return existing
  }

  let lock: DirectoryLock
  try {
    lock = await acquireFileLock(path.join(path.dirname(operationDir), UPDATE_LOCK_FILE))
  } catch (error) {
    if (error instanceof Error && error.message.includes('already locked')) {
      throw new HandoffFailure('another updater already owns the installation lock')
    }
    throw new HandoffFailure('the installation lock could not be acquired')
  }

  let stage = 'preflight'
  let journal: UpdateJournal | null = null
  let result: UpdateResult
  try {
    const handoff = await readHandoff(operationDir)
    if (handoff === null) {
      throw new HandoffFailure('the update handoff is missing')
    }
    assertConsistentHandoff(handoff, operationDir, stagedDir, installedDir, updateScript)
    if (handoff.state === 'FAILED') {
      result = failure('the update handoff is already marked failed', {
        operationId,
        targetVersion: handoff.targetVersion,
      })
      await writeResult(operationDir, result)
      return result
    }

    stage = 'takeover'
    const takeover = await readTakeover(operationDir)
    if (
      takeover === null ||
      takeover.operationId !== handoff.operationId ||
      takeover.token !== handoff.startupToken
    ) {
      await writeTakeover(operationDir, handoff)
    }

    stage = 'permit'
    await waitForPermit(handoff, operationDir, permitWaitMillis, pollIntervalMillis)
    journal = await readJournal(operationDir)
    if (journal.targetVersion === '') {
      journal.targetVersion = handoff.targetVersion
      await writeJournal(operationDir, journal)
    }
    if (journal.targetVersion !== handoff.targetVersion) {
      throw new HandoffFailure('the update step journal does not match the target version')
    }
    if (!journal.steps.includes('TAKEOVER')) {
      await appendStep(operationDir, journal, 'TAKEOVER')
    }
    if (!journal.steps.includes('PERMIT')) {
      await appendStep(operationDir, journal, 'PERMIT')
    }

    const installedVersion = await installedDaemonVersion(installedDir)
    const stagedExists = await isRealDirectory(stagedDir)
    if (installedVersion !== handoff.targetVersion || stagedExists) {
      await verifyStagedDistribution(stagedDir)
      stage = 'stop'
      await appendStep(operationDir, journal, 'STOPPING')
      await runService('stop', updateScript, { operationDir, stagedDir, installedDir })
      await appendStep(operationDir, journal, 'STOPPED')

      stage = 'backup'
      const backupPrevious = await ensureBackup(operationDir, installedDir, journal, doRename)
      if (backupPrevious === null) {
        throw new HandoffFailure('the installed distribution could not be backed up')
      }

      stage = 'replace'
      await doRename(stagedDir, installedDir)
      await appendStep(operationDir, journal, 'REPLACED')
    } else if (!journal.steps.includes('REPLACED')) {
      // 只有“目标版本已安装且 staging 已消失”这一真实布局才能跳过替换；仍显式记录恢复事实。
      await appendStep(operationDir, journal, 'REPLACED')
    }

    await verifyInstalledTarget(installedDir, handoff.targetVersion)
    stage = 'start'
    await appendStep(operationDir, journal, 'STARTING')
    await runService('start', updateScript, { operationDir, stagedDir, installedDir })
    await appendStep(operationDir, journal, 'STARTED')

    stage = 'startup-ack'
    const ack = await waitForStartupAck(handoff, operationDir, startupAckWaitMillis, pollIntervalMillis)
    await verifyInstalledTarget(installedDir, handoff.targetVersion)
    result = {
      operationId,
      phase: 'PREPARED',
      targetVersion: handoff.targetVersion,
      daemonVersion: ack.version,
      pid: ack.pid,
    }
    // 先持久记录成功步骤，再写唯一成功的 PREPARED result；任何日志失败都必须落在 FAILED，不能被后续新进程误读为成功。
    await appendStep(operationDir, journal, 'COMPLETED')
    await writeResult(operationDir, result)
    return result
  } catch (error) {
    const message = safeText(error, `update failed during ${stage}`)
    result = failure(message, { operationId })
    if (journal !== null) {
      await restorePrevious(operationDir, installedDir, journal, doRename)
    }
    await writeResult(operationDir, result)
    return result
  } finally {
    await lock.close()
  }
}

/**
 * 创建唯一备份目录并把当前发行移入 {@code previous}。
 *
 * <p>备份目录路径在 rename 之前持久写入步骤日志；已经由日志和布局确认的备份不会被第二次覆盖。
 */
async function ensureBackup(
  operationDir: string,
  installedDir: string,
  journal: UpdateJournal,
  doRename: (from: string, to: string) => Promise<void>,
): Promise<string | null> {
  if (await isRealDirectory(installedDir)) {
    const backupDir = journal.backupDir ?? path.join(operationDir, `backup-${randomUUID()}`)
    if (journal.backupDir === undefined) {
      journal.backupDir = backupDir
      await writeJournal(operationDir, journal)
      await appendStep(operationDir, journal, 'BACKUP_PREPARED')
    }
    await ensurePrivateDirectory(backupDir)
    const previous = path.join(backupDir, 'previous')
    if (await isRealDirectory(previous)) {
      throw new HandoffFailure('the unique backup directory already contains a distribution')
    }
    await doRename(installedDir, previous)
    await appendStep(operationDir, journal, 'BACKED_UP')
    return previous
  }
  if (journal.backupDir !== undefined) {
    const previous = path.join(journal.backupDir, 'previous')
    if (await isRealDirectory(previous)) {
      if (!journal.steps.includes('BACKED_UP')) {
        await appendStep(operationDir, journal, 'BACKED_UP')
      }
      return previous
    }
  }
  return null
}

/** 替换失败且 installed 缺失时，尽力把唯一备份还原为可运行发行；绝不覆盖已存在的 installed。 */
async function restorePrevious(
  operationDir: string,
  installedDir: string,
  journal: UpdateJournal,
  doRename: (from: string, to: string) => Promise<void>,
): Promise<void> {
  if (journal.backupDir === undefined || journal.steps.includes('REPLACED')) {
    return
  }
  if ((await isRealDirectory(installedDir)) || !(await isRealDirectory(journal.backupDir))) {
    return
  }
  const previous = path.join(journal.backupDir, 'previous')
  if (!(await isRealDirectory(previous))) {
    return
  }
  try {
    await doRename(previous, installedDir)
    if (!journal.steps.includes('RESTORED')) {
      await appendStep(operationDir, journal, 'RESTORED')
    }
  } catch {
    // 还原失败不覆盖原始失败结果；备份仍在原处，供人工恢复。
  }
}

export function updateScriptActionCommand(
  os: 'linux' | 'macos' | 'windows',
  action: ServiceAction | 'run',
  scriptPath: string,
  directories: UpdaterDirectories,
  updater?: { node: string; entry: string },
): string[] {
  if (os === 'windows') {
    const args = [
      '-NoProfile',
      '-NonInteractive',
      '-ExecutionPolicy',
      'Bypass',
      '-File',
      scriptPath,
      '-Action',
      action,
      '-OperationDir',
      directories.operationDir,
      '-StagedDir',
      directories.stagedDir,
      '-InstalledDir',
      directories.installedDir,
    ]
    if (updater !== undefined) {
      args.push('-UpdaterNode', updater.node, '-UpdaterEntry', updater.entry)
    }
    return ['powershell', ...args]
  }
  const args = ['bash', scriptPath, action, directories.operationDir, directories.stagedDir, directories.installedDir]
  if (updater !== undefined) {
    args.push(updater.node, updater.entry)
  }
  return args
}

/** 运行同一脚本的 stop/start 动作；只保留有界、去敏的失败原因。 */
async function runUpdateScript(
  os: 'linux' | 'macos' | 'windows',
  action: ServiceAction,
  scriptPath: string,
  directories: UpdaterDirectories,
): Promise<void> {
  const command = updateScriptActionCommand(os, action, scriptPath, directories)
  const executable = command[0]
  if (executable === undefined) {
    throw new HandoffFailure('the update script command is empty')
  }
  await new Promise<void>((resolve, reject) => {
    const child = spawn(executable, command.slice(1), {
      stdio: ['ignore', 'ignore', 'pipe'],
      windowsHide: true,
    })
    let stderr = ''
    child.stderr?.on('data', (chunk: Buffer) => {
      if (stderr.length < 4096) {
        stderr += chunk.toString('utf8').slice(0, 4096 - stderr.length)
      }
    })
    let settled = false
    const finish = (error: HandoffFailure | null): void => {
      if (settled) {
        return
      }
      settled = true
      clearTimeout(timer)
      if (error === null) {
        resolve()
      } else {
        reject(error)
      }
    }
    const timer = setTimeout(() => {
      child.kill('SIGKILL')
      finish(new HandoffFailure(`the managed service did not ${action} in time`))
    }, 90_000)
    child.once('error', () => finish(new HandoffFailure(`the managed service could not ${action}`)))
    child.once('close', code => {
      if (code === 0) {
        finish(null)
      } else {
        finish(new HandoffFailure(`the managed service did not ${action}`))
      }
    })
  })
}

function updatesDirectory(dataDir: string): string {
  return path.join(dataDir, UPDATE_DIRECTORY)
}

function resultReportedPath(operationDir: string): string {
  return path.join(operationDir, RESULT_REPORTED_FILE)
}

async function isResultReported(operationDir: string): Promise<boolean> {
  if (!(await isRealRegularFile(resultReportedPath(operationDir)))) {
    return false
  }
  try {
    const raw = await readBoundedJson(resultReportedPath(operationDir))
    return raw.operationId === path.basename(operationDir)
  } catch {
    return false
  }
}

async function markResultReported(operationDir: string): Promise<void> {
  await writePrivateFile(
    resultReportedPath(operationDir),
    `${JSON.stringify({ operationId: path.basename(operationDir) })}\n`,
  )
}

async function readHandoffs(updatesDir: string): Promise<ActiveHandoff[]> {
  if (!(await isRealDirectory(updatesDir))) {
    return []
  }
  const entries = await readdir(updatesDir, { withFileTypes: true })
  const records: ActiveHandoff[] = []
  for (const entry of entries) {
    if (!entry.isDirectory() || !OPERATION_ID_PATTERN.test(entry.name)) {
      continue
    }
    const operationDir = path.join(updatesDir, entry.name)
    const handoff = await readHandoff(operationDir)
    if (handoff === null) {
      continue
    }
    records.push({
      operationDir,
      handoff,
      journal: await readJournal(operationDir),
      result: await readResult(operationDir),
      reported: await isResultReported(operationDir),
    })
  }
  return records
}

/** 选择一个尚未完成的交接；多个未完成交接是明确冲突，绝不任选一个猜测。 */
function selectIncomplete(records: ActiveHandoff[]): ActiveHandoff | undefined {
  const incomplete = records.filter(record => record.result === null && record.handoff.state !== 'FAILED')
  if (incomplete.length > 1) {
    throw new HandoffFailure('more than one update handoff is active')
  }
  return incomplete[0]
}

/** 没有未完成交接时，最多消费一条尚未上报的终态结果，避免每次启动重复上报。 */
async function consumeCompletedResult(
  records: ActiveHandoff[],
  installedDir: string,
): Promise<StartupOutcome | null> {
  const unreported = records.filter(record => record.result !== null && !record.reported)
  if (unreported.length === 0) {
    return null
  }
  const chosen = unreported.find(record => record.result?.phase === 'FAILED') ?? unreported[0]
  if (chosen?.result === null || chosen?.result === undefined) {
    return null
  }
  if (chosen.result.phase === 'PREPARED') {
    await verifyInstalledTarget(installedDir, chosen.handoff.targetVersion)
  }
  // 多个历史结果只上报一条有界事件；其余标记已消费，避免重复提示。
  for (const record of unreported) {
    await markResultReported(record.operationDir)
  }
  if (chosen.result.phase === 'PREPARED') {
    return {
      state: 'updated',
      operationId: chosen.handoff.operationId,
      targetVersion: chosen.handoff.targetVersion,
    }
  }
  return {
    state: 'failed',
    operationId: chosen.handoff.operationId,
    message: chosen.result.message ?? 'the update failed',
  }
}

async function writeFailureResult(operationDir: string, operationId: string, message: string): Promise<UpdateResult> {
  const result = failure(message, { operationId })
  await writeResult(operationDir, result)
  await markResultReported(operationDir)
  return result
}

async function recoverWithoutUpdater(active: ActiveHandoff, installedDir: string): Promise<StartupOutcome> {
  const operationId = active.handoff.operationId
  if (!(await isRealDirectory(installedDir))) {
    throw new HandoffFailure('the installation is missing after an interrupted update')
  }
  const version = await installedDaemonVersion(installedDir)
  if (version === active.handoff.targetVersion) {
    await writeFailureResult(
      active.operationDir,
      operationId,
      'the installation was replaced but the updater did not confirm startup',
    )
    return {
      state: 'failed',
      operationId,
      message: 'the installation was replaced but the updater did not confirm startup',
    }
  }
  if (!(await isRealRegularFile(path.join(installedDir, MANAGED_ENTRY)))) {
    throw new HandoffFailure('the installation is not runnable after an interrupted update')
  }
  const result = await writeFailureResult(
    active.operationDir,
    operationId,
    'the update was interrupted before the installation was replaced',
  )
  return { state: 'failed', operationId, message: result.message ?? 'the update was interrupted' }
}

async function writeStartupAck(
  config: Pick<DaemonConfig, 'dataDir' | 'distributionDir' | 'configPath'>,
  active: ActiveHandoff,
): Promise<void> {
  const { handoff } = active
  if (handoff.state !== 'ACCEPTED') {
    throw new HandoffFailure('the update handoff is not ready for the new daemon')
  }
  if (path.resolve(handoff.installedDir) !== path.resolve(config.distributionDir)) {
    throw new HandoffFailure('the update handoff targets another installation')
  }
  if (path.resolve(handoff.configFile) !== path.resolve(config.configPath)) {
    throw new HandoffFailure('the update handoff targets another configuration')
  }
  await verifyInstalledTarget(config.distributionDir, handoff.targetVersion)
  if (!(await isRealRegularFile(config.configPath))) {
    throw new HandoffFailure('the daemon configuration is missing')
  }
  const ackPath = path.join(active.operationDir, STARTUP_ACK_FILE)
  if (await isRealRegularFile(ackPath)) {
    const raw = await readBoundedJson(ackPath)
    if (raw.operationId === handoff.operationId && raw.token === handoff.startupToken) {
      return
    }
    throw new HandoffFailure('a different startup acknowledgement already exists')
  }
  const ack: StartupAck = {
    operationId: handoff.operationId,
    targetVersion: handoff.targetVersion,
    token: handoff.startupToken,
    version: handoff.targetVersion,
    pid: process.pid,
  }
  await writePrivateFile(ackPath, `${JSON.stringify(ack, null, 2)}\n`)
}

async function waitForResultAndRelease(
  active: ActiveHandoff,
  lockPath: string,
  options: Required<Pick<StartupHandoffOptions, 'timeoutMillis' | 'pollIntervalMillis'>> & {
    signal?: AbortSignal
  },
  installedDir: string,
): Promise<StartupOutcome> {
  const deadline = Date.now() + options.timeoutMillis
  for (;;) {
    if (options.signal?.aborted === true) {
      throw new HandoffFailure('local update handoff was cancelled')
    }
    const observed = await readResult(active.operationDir)
    let released: DirectoryLock
    try {
      released = await acquireFileLock(lockPath)
    } catch {
      if (Date.now() >= deadline) {
        throw new HandoffFailure(
          observed === null
            ? 'the update handoff did not complete in time'
            : 'the update lock was not released in time',
        )
      }
      await sleep(options.pollIntervalMillis, options.signal)
      continue
    }
    try {
      // 取得安装锁意味着更新器已结束；再读一次结果，避免在写 result 与释放锁之间误判。
      const result = observed ?? (await readResult(active.operationDir))
      if (result === null) {
        const failed = await writeFailureResult(
          active.operationDir,
          active.handoff.operationId,
          'the updater disappeared before confirming the new daemon',
        )
        return { state: 'failed', operationId: active.handoff.operationId, message: failed.message ?? 'the update failed' }
      }
      if (result.phase === 'PREPARED') {
        await verifyInstalledTarget(installedDir, active.handoff.targetVersion)
        if (!(await isResultReported(active.operationDir))) {
          await markResultReported(active.operationDir)
        }
        return { state: 'updated', operationId: active.handoff.operationId, targetVersion: active.handoff.targetVersion }
      }
      if (!(await isResultReported(active.operationDir))) {
        await markResultReported(active.operationDir)
      }
      return {
        state: 'failed',
        operationId: active.handoff.operationId,
        message: result.message ?? 'the update failed',
      }
    } finally {
      await released.close()
    }
  }
}

function selectUnreportedResult(records: ActiveHandoff[]): ActiveHandoff | undefined {
  const unreported = records.filter(record => record.result !== null && !record.reported)
  if (unreported.length > 1) {
    return unreported.find(record => record.result?.phase === 'FAILED') ?? unreported[0]
  }
  return unreported[0]
}

async function waitWhileLocked(
  config: Pick<DaemonConfig, 'dataDir' | 'distributionDir' | 'configPath'>,
  updatesDir: string,
  lockPath: string,
  options: Required<Pick<StartupHandoffOptions, 'timeoutMillis' | 'pollIntervalMillis'>> & {
    signal?: AbortSignal
  },
): Promise<StartupOutcome> {
  const deadline = Date.now() + options.timeoutMillis
  for (;;) {
    const records = await readHandoffs(updatesDir)
    const active = selectIncomplete(records) ?? selectUnreportedResult(records)
    if (active !== undefined) {
      const installedVersion = await installedDaemonVersion(config.distributionDir)
      if (installedVersion === active.handoff.targetVersion) {
        await writeStartupAck(config, active)
      }
      return waitForResultAndRelease(active, lockPath, options, config.distributionDir)
    }
    // 互斥可能刚释放而 handoff 尚未出现；只在预算内重读，绝不据此进入正常业务。
    try {
      const released = await acquireFileLock(lockPath)
      await released.close()
      return { state: 'normal' }
    } catch {
      // 仍然被持有：继续等待 handoff 出现。
    }
    if (Date.now() >= deadline) {
      throw new HandoffFailure('the installation is locked without an update handoff')
    }
    await sleep(options.pollIntervalMillis, options.signal)
  }
}

/**
 * 新 Daemon 在启动 Runtime 之前调用的交接门。
 *
 * <p>没有更新时立即返回 {@code normal}；作为更新目标启动时，先完成本机版本/配置/安装布局预检并写私有启动确认，再等待
 * updater 写出 result 并释放安装目录互斥。返回 {@code failed} 表示交接被拒绝或中断，调用方应将其作为有界失败事件上报，
 * 但不得在任何 result 缺失或互斥未释放的情况下继续正常 READY。
 */
export async function awaitStartupHandoff(
  config: Pick<DaemonConfig, 'dataDir' | 'distributionDir' | 'configPath'>,
  options: StartupHandoffOptions = {},
): Promise<StartupOutcome> {
  const updatesDir = updatesDirectory(config.dataDir)
  if (!(await isRealDirectory(updatesDir))) {
    return { state: 'normal' }
  }
  const lockPath = path.join(updatesDir, UPDATE_LOCK_FILE)
  const effectiveOptions = {
    timeoutMillis: options.timeoutMillis ?? DEFAULT_STARTUP_ACK_WAIT_MILLIS,
    pollIntervalMillis: options.pollIntervalMillis ?? DEFAULT_HANDOFF_POLL_MILLIS,
    signal: options.signal,
  }
  let lock: DirectoryLock
  try {
    lock = await acquireFileLock(lockPath)
  } catch {
    return waitWhileLocked(config, updatesDir, lockPath, effectiveOptions)
  }
  try {
    const records = await readHandoffs(updatesDir)
    const incomplete = selectIncomplete(records)
    if (incomplete !== undefined) {
      return recoverWithoutUpdater(incomplete, config.distributionDir)
    }
    return (await consumeCompletedResult(records, config.distributionDir)) ?? { state: 'normal' }
  } finally {
    await lock.close()
  }
}

/**
 * 更新器可执行入口。
 *
 * <p>参数固定为四个绝对路径；成功只在 result=PREPARED 后返回 0。cli 失败时只输出固定安全诊断。
 */
export async function runUpdaterCli(argv: string[]): Promise<number> {
  const values = new Map<string, string>()
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index]
    const value = argv[index + 1]
    if (
      flag === undefined ||
      value === undefined ||
      !['--operation-dir', '--staged-dir', '--installed-dir', '--update-script'].includes(flag) ||
      values.has(flag)
    ) {
      process.stderr.write('update arguments are invalid\n')
      return 2
    }
    values.set(flag, value)
  }
  const operationDir = values.get('--operation-dir')
  const stagedDir = values.get('--staged-dir')
  const installedDir = values.get('--installed-dir')
  const updateScript = values.get('--update-script')
  if (
    operationDir === undefined ||
    stagedDir === undefined ||
    installedDir === undefined ||
    updateScript === undefined ||
    !path.isAbsolute(operationDir) ||
    !path.isAbsolute(stagedDir) ||
    !path.isAbsolute(installedDir) ||
    !path.isAbsolute(updateScript)
  ) {
    process.stderr.write('update arguments are invalid\n')
    return 2
  }
  try {
    const result = await runUpdater({ operationDir, stagedDir, installedDir, updateScript })
    if (result.phase === 'PREPARED') {
      process.stdout.write('update applied\n')
      return 0
    }
    process.stderr.write(`update failed: ${result.message ?? 'the update failed'}\n`)
    return 1
  } catch (error) {
    process.stderr.write(`update failed: ${safeText(error, 'the update could not be completed')}\n`)
    return 1
  }
}

/** 仅在直接执行本模块时进入 CLI；被测试或新 Daemon 导入时不产生副作用。 */
function isDirectEntry(): boolean {
  const entry = process.argv[1]
  if (entry === undefined) {
    return false
  }
  return path.resolve(entry) === realpathSync(fileURLToPath(import.meta.url))
}

if (isDirectEntry()) {
  runUpdaterCli(process.argv.slice(2)).then(
    code => {
      process.exitCode = code
    },
    error => {
      process.stderr.write(`update failed: ${safeText(error, 'the update could not be completed')}\n`)
      process.exitCode = 1
    },
  )
}
