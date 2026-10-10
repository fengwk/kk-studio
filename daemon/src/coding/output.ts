import { lstat, open, readdir, rename, rm } from 'node:fs/promises'
import { join, resolve, sep } from 'node:path'
import { randomUUID } from 'node:crypto'
import type { CapabilityResult, ResultContent } from '../contracts.js'

/**
 * 有界输出捕获与受控临时产物：内联小输出，本地落盘大文本，从不因为输出体积终止进程。
 *
 * <p>与 Java 参考实现（{@code OutputSpool}/{@code TextOutputStore}/{@code TemporaryResourceStore}）对齐：内联阈值
 * ≤50 KiB 且 ≤2000 行；跨阈值后只在受控临时根建立 owner-only 中转文件并流式写入，终态原子发布为 durable 全文；达到捕获预算（默认 1 GiB）后停止文件捕获
 * 但继续统计，绝不杀进程。in-use lease 保证定时清扫不删除在途读取/写入的 workspace。
 */

/** 内联输出字节上限。 */
export const INLINE_MAX_BYTES = 50 * 1024
/** 内联输出行数上限。 */
export const INLINE_MAX_LINES = 2000
/** 预览 head 字节上限。 */
export const PREVIEW_HEAD_MAX_BYTES = 4 * 1024
/** 预览 tail 字节上限。 */
export const PREVIEW_TAIL_MAX_BYTES = 8 * 1024
/** 默认单次调用的全文捕获预算：1 GiB。 */
export const DEFAULT_CAPTURE_BUDGET_BYTES = 1024 * 1024 * 1024

/** 固定容量的字节尾部缓冲：只保留最近写入的 {@code capacity} 字节。 */
export class ByteTailBuffer {
  private readonly ring: Uint8Array
  private written = 0

  constructor(capacity: number) {
    if (capacity <= 0) {
      throw new Error('capacity must be positive')
    }
    this.ring = new Uint8Array(capacity)
  }

  append(source: Uint8Array): void {
    for (const value of source) {
      this.ring[this.written % this.ring.length] = value
      this.written++
    }
  }

  size(): number {
    return Math.min(this.written, this.ring.length)
  }

  toBytes(): Uint8Array {
    const length = this.size()
    const first = this.written - length
    const result = new Uint8Array(length)
    for (let index = 0; index < length; index++) {
      result[index] = this.ring[(first + index) % this.ring.length] as number
    }
    return result
  }
}

/** 固定容量的头部缓冲：只保留最先写入的 {@code capacity} 字节。 */
class FirstBytesBuffer {
  private readonly capacity: number
  private chunks: Uint8Array[] = []
  private length = 0

  constructor(capacity: number) {
    this.capacity = capacity
  }

  append(source: Uint8Array): void {
    if (this.length >= this.capacity) {
      return
    }
    const take = Math.min(source.length, this.capacity - this.length)
    this.chunks.push(Uint8Array.from(source.subarray(0, take)))
    this.length += take
  }

  size(): number {
    return this.length
  }

  toBytes(): Uint8Array {
    return Uint8Array.from(Buffer.concat(this.chunks.map((chunk) => Buffer.from(chunk))))
  }
}

/** 把长度收缩到不切断 UTF-8 多字节字符的位置。 */
export function alignToCharacterBoundary(bytes: Uint8Array, limit: number): number {
  return limit - incompleteSuffixLength(bytes, limit)
}

/** 返回首个完整字符的起始下标；缓冲从字符内部开始时跳过前导续字节。 */
export function leadingCharacterBoundary(bytes: Uint8Array): number {
  let index = 0
  while (index < bytes.length && isContinuation(bytes[index] as number)) {
    index++
  }
  return index
}

function isContinuation(value: number): boolean {
  return (value & 0xc0) === 0x80
}

function sequenceLength(value: number): number {
  if ((value & 0x80) === 0) {
    return 1
  }
  if ((value & 0xe0) === 0xc0) {
    return 2
  }
  if ((value & 0xf0) === 0xe0) {
    return 3
  }
  if ((value & 0xf8) === 0xf0) {
    return 4
  }
  return 1
}

function incompleteSuffixLength(bytes: Uint8Array, length: number): number {
  if (length === 0) {
    return 0
  }
  let index = length - 1
  let continuationCount = 0
  while (index >= 0 && isContinuation(bytes[index] as number)) {
    continuationCount++
    index--
  }
  if (index < 0) {
    return Math.min(length, 3)
  }
  const expected = sequenceLength(bytes[index] as number)
  if (expected <= 1) {
    return 0
  }
  const available = continuationCount + 1
  return available < expected ? available : 0
}

/** 简易异步互斥：串行化临时产物创建登记、in-use 获取与清扫的关键段。 */
class Mutex {
  private tail: Promise<void> = Promise.resolve()

  async runExclusive<T>(task: () => Promise<T>): Promise<T> {
    const previous = this.tail
    let release!: () => void
    this.tail = new Promise<void>((res) => {
      release = res
    })
    await previous
    try {
      return await task()
    } finally {
      release()
    }
  }
}

/** 受控临时产物的 in-use 租约。 */
export interface Lease {
  close(): void
}

const NONE_LEASE: Lease = { close() {} }

/**
 * 私有目录/文件端口：owner-only 语义由 native 切片承载（POSIX 0700/0600，Windows DACL），本片只消费不复制。
 *
 * <p>默认实现惰性加载 native 边界（{@code src/native/index.ts} 的 {@code ensurePrivateDirectory}/{@code writePrivateFile}）；注入点供测试
 * 提供行为等价的双替身，生产路径绝不退化为 POSIX chmod。
 */
export interface PrivateFs {
  ensurePrivateDirectory(path: string): Promise<string>
  writePrivateFile(path: string, data: string | Uint8Array): Promise<void>
}

// 保持 non-literal 类型，动态导入不触发本片对 native 切片的编译期依赖。
// TODO(main 集成)：native 切片合并后改为静态 `import { ensurePrivateDirectory, writePrivateFile } from '../native/index.js'`，
// 由编译器校验签名（native index 实际导出：ensurePrivateDirectory(path):Promise<string>、writePrivateFile(path,data):Promise<void>、verifyPrivateFile、createProcessSupervisor、acquireDirectoryLock）。
const NATIVE_MODULE: string = '../native/index.js'

const nativePrivateFs: PrivateFs = {
  async ensurePrivateDirectory(path: string): Promise<string> {
    const native = (await import(NATIVE_MODULE)) as {
      ensurePrivateDirectory(candidate: string): Promise<string>
    }
    return native.ensurePrivateDirectory(path)
  },
  async writePrivateFile(path: string, data: string | Uint8Array): Promise<void> {
    const native = (await import(NATIVE_MODULE)) as {
      writePrivateFile(candidate: string, content: string | Uint8Array): Promise<void>
    }
    await native.writePrivateFile(path, data)
  },
}

const defaultNow = (): number => Math.floor(Date.now() / 1000)

/** 受控临时 workspace 的创建登记、in-use 租约与清扫。 */
export class TemporaryResourceStore {
  private readonly rootDir: string
  private readonly now: () => number
  private readonly privateFs: PrivateFs
  private readonly leases = new Map<string, number>()
  private readonly mutex = new Mutex()

  private constructor(rootDir: string, now: () => number, privateFs: PrivateFs) {
    this.rootDir = rootDir
    this.now = now
    this.privateFs = privateFs
  }

  static async open(
    tmpRoot: string,
    now: () => number = defaultNow,
    privateFs: PrivateFs = nativePrivateFs,
  ): Promise<TemporaryResourceStore> {
    // 根目录惰性创建：读取在途产物只需 lease 计算，落盘大输出时才需要私有目录端口（native）。
    return new TemporaryResourceStore(join(resolve(tmpRoot), 'workspaces'), now, privateFs)
  }

  root(): string {
    return this.rootDir
  }

  /** 登记并返回一个新的 workspace，同时持有其 in-use lease，直到 {@link Workspace.close()}。 */
  async create(): Promise<Workspace> {
    const id = randomUUID()
    const directory = join(this.rootDir, id)
    return this.mutex.runExclusive(async () => {
      await this.privateFs.ensurePrivateDirectory(this.rootDir)
      await this.privateFs.ensurePrivateDirectory(directory)
      try {
        await this.privateFs.writePrivateFile(join(directory, 'created-at'), String(this.now()))
      } catch (error) {
        await deleteTreeQuietly(directory)
        throw error
      }
      this.leases.set(id, 1)
      return new Workspace(id, directory, () => this.release(id))
    })
  }

  /** 在已登记的受控 workspace 内创建一个空的 owner-only 文件。 */
  async createPrivateFile(path: string): Promise<void> {
    await this.privateFs.writePrivateFile(path, new Uint8Array())
  }

  /** 若 {@code candidate} 位于某个已登记受控 workspace 内，则获取其 in-use lease 并返回；否则返回 no-op。 */
  async acquire(candidate: string): Promise<Lease> {
    const normalized = resolve(candidate)
    if (!isWithin(this.rootDir, normalized)) {
      return NONE_LEASE
    }
    const relative = normalized.slice(this.rootDir.length).replace(/^[\\/]+/, '')
    const first = relative.split(/[\\/]/)[0] as string
    const workspace = join(this.rootDir, first)
    if (!isUuid(first)) {
      return NONE_LEASE
    }
    return this.mutex.runExclusive(async () => {
      if (!(await this.isControlledWorkspace(workspace))) {
        return NONE_LEASE
      }
      this.leases.set(first, (this.leases.get(first) ?? 0) + 1)
      let released = false
      return {
        close: () => {
          if (!released) {
            released = true
            this.release(first)
          }
        },
      }
    })
  }

  /** 清扫超过 ttl 且未租用的已登记 workspace，返回删除数量。 */
  async sweep(ttlSeconds: number): Promise<number> {
    if (ttlSeconds <= 0) {
      throw new Error('ttlSeconds must be positive')
    }
    const cutoff = this.now() - ttlSeconds
    return this.mutex.runExclusive(async () => {
      // 尚未落盘过任何输出时根目录不存在：没有可清扫的对象，直接返回 0。
      if (!(await pathExists(this.rootDir))) {
        return 0
      }
      await this.requireControlledRoot()
      let removed = 0
      for (const entry of await readdir(this.rootDir)) {
        const workspace = join(this.rootDir, entry)
        if (!(await this.isControlledWorkspace(workspace))) {
          continue
        }
        if (this.leases.has(entry)) {
          continue
        }
        const createdAt = await readCreatedAt(workspace)
        if (createdAt === null || createdAt > cutoff) {
          continue
        }
        if (await deleteTree(workspace)) {
          removed++
        }
      }
      return removed
    })
  }

  /** 删除一个已不再需要的中转 workspace（含创建登记）。 */
  async deleteWorkspace(directory: string): Promise<void> {
    await deleteTreeQuietly(directory)
  }

  hasActiveLease(id: string): boolean {
    return this.leases.has(id)
  }

  private release(id: string): void {
    const count = (this.leases.get(id) ?? 0) - 1
    if (count <= 0) {
      this.leases.delete(id)
    } else {
      this.leases.set(id, count)
    }
  }

  private async requireControlledRoot(): Promise<void> {
    const info = await lstat(this.rootDir)
    if (!info.isDirectory() || info.isSymbolicLink()) {
      throw new Error(`temporary workspace root is not a real directory: ${this.rootDir}`)
    }
  }

  private async isControlledWorkspace(entry: string): Promise<boolean> {
    let info
    try {
      info = await lstat(entry)
    } catch {
      return false
    }
    if (!info.isDirectory() || info.isSymbolicLink()) {
      return false
    }
    if (!isUuid(entry.slice(entry.lastIndexOf(sep) + 1))) {
      return false
    }
    return (await readCreatedAt(entry)) !== null
  }
}

/** 活动 workspace 句柄：持有 in-use lease，{@link Workspace.close()} 释放且保证恰好一次。 */
export class Workspace {
  constructor(
    readonly id: string,
    readonly directory: string,
    private readonly releaseLease: () => void,
  ) {}

  close(): void {
    this.releaseLease()
  }
}

async function readCreatedAt(directory: string): Promise<number | null> {
  try {
    const handle = await open(join(directory, 'created-at'), 'r')
    try {
      const buffer = Buffer.allocUnsafe(64)
      const { bytesRead } = await handle.read(buffer, 0, 64, 0)
      const parsed = Number.parseInt(buffer.subarray(0, bytesRead).toString('ascii').trim(), 10)
      return Number.isNaN(parsed) ? null : parsed
    } finally {
      await handle.close()
    }
  } catch {
    return null
  }
}

async function pathExists(path: string): Promise<boolean> {
  try {
    await lstat(path)
    return true
  } catch {
    return false
  }
}

async function deleteTree(directory: string): Promise<boolean> {
  try {
    await rm(directory, { recursive: true, force: true })
    return true
  } catch {
    return false
  }
}

async function deleteTreeQuietly(directory: string): Promise<void> {
  await deleteTree(directory)
}

function isUuid(value: string): boolean {
  return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(
    value,
  )
}

function isWithin(parent: string, candidate: string): boolean {
  const normalizedParent = parent.endsWith(sep) ? parent : parent + sep
  return candidate.startsWith(normalizedParent)
}

/** 本地大文本输出的受控临时边界。 */
export class TextOutputStore {
  private readonly temporaryResources: TemporaryResourceStore
  private readonly captureBudget: number
  private readonly openWorkspaces = new Map<string, Workspace>()

  private constructor(temporaryResources: TemporaryResourceStore, captureBudgetBytes: number) {
    this.temporaryResources = temporaryResources
    this.captureBudget = captureBudgetBytes
  }

  static async open(
    tmpRoot: string,
    captureBudgetBytes: number = DEFAULT_CAPTURE_BUDGET_BYTES,
    now?: () => number,
    privateFs?: PrivateFs,
  ): Promise<TextOutputStore> {
    if (captureBudgetBytes <= 0) {
      throw new Error('captureBudgetBytes must be positive')
    }
    const temporaryResources =
      privateFs === undefined
        ? await TemporaryResourceStore.open(tmpRoot, now)
        : await TemporaryResourceStore.open(tmpRoot, now, privateFs)
    return new TextOutputStore(temporaryResources, captureBudgetBytes)
  }

  root(): string {
    return this.temporaryResources.root()
  }

  captureBudgetBytes(): number {
    return this.captureBudget
  }

  sweep(ttlSeconds: number): Promise<number> {
    return this.temporaryResources.sweep(ttlSeconds)
  }

  acquire(candidate: string): Promise<Lease> {
    return this.temporaryResources.acquire(candidate)
  }

  /** 登记新 workspace 并在其中创建 owner-only 中转文件。 */
  async createStagingFile(callId: string): Promise<string> {
    const workspace = await this.temporaryResources.create()
    let reusable = false
    try {
      const name = `${uniqueName(callId)}.part`
      const stagingFile = join(workspace.directory, name)
      await this.temporaryResources.createPrivateFile(stagingFile)
      this.openWorkspaces.set(stagingFile, workspace)
      reusable = true
      return stagingFile
    } finally {
      if (!reusable) {
        workspace.close()
        await this.temporaryResources.deleteWorkspace(workspace.directory)
      }
    }
  }

  /** 把中转文件原子发布为 durable 全文，返回其绝对路径。 */
  async publish(stagingFile: string): Promise<string> {
    if (!stagingFile.endsWith('.part')) {
      throw new Error('staging file must end with .part')
    }
    const target = `${stagingFile.slice(0, stagingFile.length - '.part'.length)}.log`
    await rename(stagingFile, target)
    this.releaseWorkspace(stagingFile)
    return target
  }

  /** 清理未发布的中转文件并释放其 workspace；已发布全文不受影响。 */
  async deleteStagingQuietly(stagingFile: string | null): Promise<void> {
    if (stagingFile === null) {
      return
    }
    await rm(stagingFile, { force: true })
    const workspace = this.openWorkspaces.get(stagingFile)
    if (workspace !== undefined) {
      this.openWorkspaces.delete(stagingFile)
      workspace.close()
      await this.temporaryResources.deleteWorkspace(workspace.directory)
    }
  }

  /** 所有已发布全文；仅供测试与诊断。 */
  async publishedFiles(): Promise<string[]> {
    return this.listFilesWithSuffix('.log')
  }

  /** 所有未发布中转文件；仅供测试与诊断。 */
  async partialFiles(): Promise<string[]> {
    return this.listFilesWithSuffix('.part')
  }

  private releaseWorkspace(stagingFile: string): void {
    const workspace = this.openWorkspaces.get(stagingFile)
    if (workspace !== undefined) {
      this.openWorkspaces.delete(stagingFile)
      workspace.close()
    }
  }

  private async listFilesWithSuffix(suffix: string): Promise<string[]> {
    const result: string[] = []
    let entries: string[]
    try {
      entries = await readdir(this.root())
    } catch {
      // 尚未落盘过任何输出时根目录不存在：没有可列举的产物。
      return result
    }
    for (const entry of entries) {
      const workspace = join(this.root(), entry)
      let info
      try {
        info = await lstat(workspace)
      } catch {
        continue
      }
      if (!info.isDirectory() || info.isSymbolicLink()) {
        continue
      }
      for (const file of await readdir(workspace)) {
        if (file.endsWith(suffix)) {
          result.push(join(workspace, file))
        }
      }
    }
    return result
  }
}

function uniqueName(callId: string): string {
  return `${sanitize(callId)}-${randomUUID().slice(0, 8)}`
}

function sanitize(callId: string): string {
  const value = callId ?? ''
  let builder = ''
  for (let index = 0; index < value.length && builder.length < 64; index++) {
    const character = value[index] as string
    builder += /[a-zA-Z0-9\-_.]/.test(character) ? character : '_'
  }
  return builder.length === 0 ? 'output' : builder
}

/** 生产者端有界输出捕获器。 */
export class OutputSpool {
  private readonly store: TextOutputStore
  private readonly callId: string
  private readonly inlineMaxBytes: number
  private readonly inlineMaxLines: number
  private readonly captureBudgetBytes: number
  private readonly memoryChunks: Uint8Array[] = []
  private readonly headBuffer = new FirstBytesBuffer(PREVIEW_HEAD_MAX_BYTES)
  private readonly tailBuffer = new ByteTailBuffer(PREVIEW_TAIL_MAX_BYTES)

  private totalByteCount = 0
  private lineBreaks = 0
  private lastByte = 0
  private hasBytes = false
  private spilled = false
  private captureTruncated = false
  private captureFailed = false
  private stagingFile: string | null = null
  private capturedByteCount = 0
  private fileHandle: Awaited<ReturnType<typeof open>> | null = null

  constructor(
    store: TextOutputStore,
    callId: string,
    inlineMaxBytes: number = INLINE_MAX_BYTES,
    inlineMaxLines: number = INLINE_MAX_LINES,
    captureBudgetBytes: number = store.captureBudgetBytes(),
  ) {
    if (inlineMaxBytes <= 0 || inlineMaxLines <= 0 || captureBudgetBytes <= 0) {
      throw new Error('limits must be positive')
    }
    this.store = store
    this.callId = callId
    this.inlineMaxBytes = inlineMaxBytes
    this.inlineMaxLines = inlineMaxLines
    this.captureBudgetBytes = captureBudgetBytes
  }

  async write(bytes: Uint8Array): Promise<void> {
    if (bytes.length === 0) {
      return
    }
    for (const value of bytes) {
      if (value === 0x0d) {
        this.lineBreaks++
      } else if (value === 0x0a && this.lastByte !== 0x0d) {
        this.lineBreaks++
      }
      this.lastByte = value
    }
    this.hasBytes = true
    this.totalByteCount += bytes.length

    this.captureHead(bytes)
    this.tailBuffer.append(bytes)

    if (this.captureTruncated || this.captureFailed) {
      return
    }
    if (!this.spilled) {
      if (this.totalByteCount <= this.inlineMaxBytes && this.totalLines() <= this.inlineMaxLines) {
        this.memoryChunks.push(Uint8Array.from(bytes))
        return
      }
      if (!(await this.spillToStagingFile())) {
        return
      }
    }
    await this.captureBytes(bytes)
  }

  totalBytes(): number {
    return this.totalByteCount
  }

  totalLines(): number {
    if (!this.hasBytes) {
      return 0
    }
    const endsWithTerminator = this.lastByte === 0x0a || this.lastByte === 0x0d
    return this.lineBreaks + (endsWithTerminator ? 0 : 1)
  }

  isSpilled(): boolean {
    return this.spilled
  }

  isCaptureTruncated(): boolean {
    return this.captureTruncated
  }

  isCaptureFailed(): boolean {
    return this.captureFailed
  }

  capturedBytes(): number {
    return this.capturedByteCount
  }

  /** 构造终态结果。 */
  async finish(error: boolean, terminalNote = ''): Promise<CapabilityResult> {
    const note = terminalNote ?? ''
    if (!this.spilled) {
      let text = Buffer.concat(this.memoryChunks.map((chunk) => Buffer.from(chunk))).toString('utf8')
      if (note.length > 0) {
        text = text.length === 0 || text.endsWith('\n') ? text + note : `${text}\n${note}`
      }
      return { callId: this.callId, error, details: {}, contents: [{ type: 'text', text }] }
    }
    const published = await this.completeAndPublish()
    let preview = this.buildPreviewText(published)
    if (note.length > 0) {
      preview += `\n${note}`
    }
    return {
      callId: this.callId,
      error,
      details: this.buildDetails(published),
      contents: [{ type: 'text', text: preview }],
    }
  }

  captureTruncatedTailPreview(): string {
    const tail = this.tailBuffer.toBytes()
    const begin = leadingCharacterBoundary(tail)
    const end = alignToCharacterBoundary(tail, tail.length)
    return Buffer.from(tail.subarray(begin, Math.max(begin, end))).toString('utf8')
  }

  async close(): Promise<void> {
    await this.closeFileQuietly()
    if (this.stagingFile !== null) {
      await this.store.deleteStagingQuietly(this.stagingFile)
      this.stagingFile = null
    }
  }

  private captureHead(bytes: Uint8Array): void {
    this.headBuffer.append(bytes)
  }

  private async spillToStagingFile(): Promise<boolean> {
    this.spilled = true
    try {
      this.stagingFile = await this.store.createStagingFile(this.callId)
    } catch {
      this.captureFailed = true
      this.stagingFile = null
      return false
    }
    try {
      this.fileHandle = await open(this.stagingFile, 'r+')
      if (this.memoryChunks.length > 0) {
        const buffered = Uint8Array.from(Buffer.concat(this.memoryChunks.map((c) => Buffer.from(c))))
        this.memoryChunks.length = 0
        await this.captureBytes(buffered)
      }
      return true
    } catch {
      await this.failCapture(this.stagingFile)
      return false
    }
  }

  private async completeAndPublish(): Promise<string | null> {
    const staging = this.stagingFile
    if (staging === null) {
      return null
    }
    try {
      await this.closeFile()
    } catch {
      await this.abandonStaging(staging)
      return null
    }
    try {
      const target = await this.store.publish(staging)
      this.stagingFile = null
      return target
    } catch {
      await this.abandonStaging(staging)
      return null
    }
  }

  private async abandonStaging(staging: string): Promise<void> {
    this.captureFailed = true
    await this.closeFileQuietly()
    await this.store.deleteStagingQuietly(staging)
    this.stagingFile = null
  }

  private buildPreviewText(published: string | null): string {
    const headAll = this.headBuffer.toBytes()
    const tailAll = this.tailBuffer.toBytes()
    const headLength = alignToCharacterBoundary(headAll, headAll.length)
    let tailBegin = leadingCharacterBoundary(tailAll)
    let tailEnd = alignToCharacterBoundary(tailAll, tailAll.length)

    // 总量不超过缓冲容量之和时 head 与 tail 覆盖同一段内容：扣除重叠，避免重复展示同一批字节。
    const tailAbsStart = this.totalByteCount - tailAll.length + tailBegin
    const skip = Math.max(0, Math.min(tailEnd - tailBegin, headLength - tailAbsStart))
    if (skip >= tailEnd - tailBegin) {
      tailBegin = 0
      tailEnd = 0
    } else {
      tailBegin += skip
    }
    const omitted = tailAbsStart + skip - headLength
    const head = Buffer.from(headAll.subarray(0, headLength)).toString('utf8')
    const tail = Buffer.from(tailAll.subarray(tailBegin, tailEnd)).toString('utf8')

    let preview = head
    if (omitted > 0) {
      if (head.length > 0 && !head.endsWith('\n')) {
        preview += '\n'
      }
      preview += `[... ${omitted} bytes omitted here; the middle of the output is not shown ...]\n`
    }
    preview += tail
    if (preview.length > 0 && !preview.endsWith('\n')) {
      preview += '\n'
    }
    preview += `\n${this.buildFooter(published)}`
    return preview
  }

  private buildFooter(published: string | null): string {
    if (published === null) {
      let footer = `[Full output (${this.totalByteCount} bytes, ${this.totalLines()} lines) could not be saved to local storage; only this bounded preview is available.`
      if (this.captureTruncated) {
        footer += ` ${this.captureTruncationNotice()}`
      }
      return `${footer}]`
    }
    let footer = `[Full output: ${this.totalByteCount} bytes, ${this.totalLines()} lines. Saved to: ${published}\nUse read with offset/limit to page through the file, or grep to search it.]`
    if (this.captureTruncated) {
      footer += ` ${this.captureTruncationNotice()}`
    }
    return footer
  }

  private captureTruncationNotice(): string {
    return `Capture stopped at the local ${this.captureBudgetBytes}-byte daemon budget; the command itself ran to completion and its exit code is meaningful.`
  }

  private buildDetails(published: string | null): Record<string, unknown> {
    const textOutput: Record<string, unknown> = {
      totalBytes: this.totalByteCount,
      totalLines: this.totalLines(),
      capturedBytes: this.capturedByteCount,
      captureTruncated: this.captureTruncated,
      captureFailed: published === null,
    }
    if (published !== null) {
      textOutput.path = published
      textOutput.readHint = 'read pages the file with offset/limit; grep searches it'
    }
    return { textOutput }
  }

  private async captureBytes(bytes: Uint8Array): Promise<void> {
    const remaining = this.captureBudgetBytes - this.capturedByteCount
    if (remaining <= 0) {
      this.captureTruncated = true
      await this.closeFileQuietly()
      return
    }
    const accepted = Math.min(bytes.length, remaining)
    try {
      if (this.fileHandle === null) {
        throw new Error('missing file handle')
      }
      await this.fileHandle.write(bytes.subarray(0, accepted))
      this.capturedByteCount += accepted
      if (accepted < bytes.length) {
        this.captureTruncated = true
        await this.closeFileQuietly()
      }
    } catch {
      this.captureFailed = true
      await this.closeFileQuietly()
    }
  }

  private async closeFile(): Promise<void> {
    if (this.fileHandle !== null) {
      await this.fileHandle.sync()
      await this.fileHandle.close()
      this.fileHandle = null
    }
  }

  private async closeFileQuietly(): Promise<void> {
    try {
      await this.closeFile()
    } catch {
      // 已捕获部分仍可发布；关闭失败只影响后续写入，而后续写入已经被禁止。
    }
  }

  private async failCapture(staging: string | null): Promise<void> {
    this.captureFailed = true
    await this.closeFileQuietly()
    await this.store.deleteStagingQuietly(staging)
    this.stagingFile = null
  }
}

/** 结果级大文本外化：把单个超过内联阈值的文本结果落到本地 durable 全文。 */
export async function spoolLargeTextResult(
  store: TextOutputStore,
  result: CapabilityResult,
): Promise<CapabilityResult> {
  const first = result.contents[0] as ResultContent | undefined
  if (
    result.contents.length !== 1 ||
    first === undefined ||
    first.type !== 'text' ||
    Object.prototype.hasOwnProperty.call(result.details, 'textOutput')
  ) {
    return result
  }
  const spool = new OutputSpool(store, result.callId)
  try {
    await spool.write(new Uint8Array(Buffer.from(first.text, 'utf8')))
    if (!spool.isSpilled()) {
      return result
    }
    const spooled = await spool.finish(result.error)
    const textOutput = (spooled.details as { textOutput?: unknown }).textOutput
    return {
      callId: result.callId,
      error: result.error,
      details: { ...result.details, textOutput },
      contents: spooled.contents,
    }
  } finally {
    await spool.close()
  }
}

const storesByDataDir = new Map<string, Promise<TextOutputStore>>()

/** Runtime 装配时可覆盖的存储选项：私有文件端口（默认惰性加载 native）与默认捕获预算。 */
export interface TextOutputStoreOptions {
  privateFs?: PrivateFs
  captureBudgetBytes?: number
}

let storeOptions: TextOutputStoreOptions = {}

/** 覆盖能力层惰性打开的存储选项；不影响已打开的实例。 */
export function configureTextOutputStores(options: TextOutputStoreOptions): void {
  storeOptions = options
}

/**
 * 按数据目录惰性获取受控临时输出存储。
 *
 * <p>契约中的 {@code DaemonConfig} 不携带存储实例，因此能力在执行时按 {@code <dataDir>/tmp} 惰性打开并复用同一实例；Runtime 侧应通过本入口取得同一实例来按
 * WELCOME 下发的 TTL 定时 {@link TextOutputStore#sweep}，保证清扫与能力使用的是同一份 lease 状态。
 */
export function textOutputStoreFor(dataDir: string): Promise<TextOutputStore> {
  let store = storesByDataDir.get(dataDir)
  if (store === undefined) {
    store = TextOutputStore.open(
      join(dataDir, 'tmp'),
      storeOptions.captureBudgetBytes,
      undefined,
      storeOptions.privateFs,
    )
    storesByDataDir.set(dataDir, store)
  }
  return store
}

/** 对当前所有数据目录的受控临时存储执行一次 TTL 清扫，返回各目录删除的 workspace 数量。 */
export async function sweepTextOutputStores(ttlSeconds: number): Promise<number[]> {
  const removed: number[] = []
  for (const store of storesByDataDir.values()) {
    removed.push(await (await store).sweep(ttlSeconds))
  }
  return removed
}

/**
 * 热更/关闭时释放内存中的存储缓存，但不删除任何 durable 全文或中转文件。
 *
 * <p>活跃续租由持有旧实例的调用方维持；重新获取会基于磁盘上的 {@code created-at} 重建 lease，不会删除在途输出。
 */
export function releaseTextOutputStore(dataDir: string): void {
  storesByDataDir.delete(dataDir)
}
