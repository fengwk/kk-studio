import { createReadStream } from 'node:fs'
import { pipeline } from 'node:stream/promises'
import { extract, list, type ReadEntry } from 'tar'

/**
 * 受控发行归档处理：先流式列举并逐条校验，再在严格模式下解包。
 *
 * <p>安全边界（全部 fail closed，绝不静默继续）：
 *
 * <ul>
 *   <li>拒绝绝对路径、盘符、`..` 逃逸、空段与 NUL；归档根 `./` 只表示根目录。
 *   <li>拒绝重复覆盖（非目录条目同路径出现两次）。
 *   <li>只接受普通文件、目录、符号链接与硬链接；设备、FIFO 与其他类型一律拒绝。
 *   <li>链接目标必须是归档内的相对路径且不得逃出解包根；硬链接只能指向已声明的普通文件。
 *   <li>逐条累计字节预算：单文件 ≤ 1 GiB、解包总量 ≤ 4 GiB。
 *   <li>解包使用 `strict` + `preservePaths:false`，不 chown（`preserveOwner:false`）、不按归档强制 chmod。
 *   <li>取消/超时会结束实际的读取与解包流，不留悬挂 IO。
 * </ul>
 */

/** 下载归档字节上限：1 GiB。 */
export const MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024
/** 官方校验文件字节上限：4 KiB。 */
export const MAX_CHECKSUM_BYTES = 4 * 1024
/** 冻结运行时清单字节上限：16 KiB。 */
export const MAX_MANIFEST_BYTES = 16 * 1024
/** 解包总量上限：4 GiB。 */
export const MAX_EXTRACTED_TOTAL_BYTES = 4 * 1024 * 1024 * 1024
/** 单个解包文件上限：1 GiB。 */
export const MAX_EXTRACTED_FILE_BYTES = 1024 * 1024 * 1024
/** 归档处理固定超时：300 秒。 */
export const ARCHIVE_TIMEOUT_MILLIS = 300_000

/** 归档处理的期望失败；消息只承载去敏、有界说明。 */
export class ArchiveError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArchiveError'
  }
}

const FILE_TYPES = new Set(['File', 'OldFile', 'ContiguousFile'])
const DIRECTORY_TYPES = new Set(['Directory', 'GNUDumpDir'])
const LINK_TYPES = new Set(['SymbolicLink', 'Link'])

interface ArchiveScan {
  seen: Set<string>
  files: Set<string>
  total: number
}

function newScan(): ArchiveScan {
  return { seen: new Set(), files: new Set(), total: 0 }
}

/**
 * 规范化归档条目路径：去掉尾部斜杠与前导 `./`，返回相对路径；归档根本身返回空串。
 *
 * <p>任何绝对路径、盘符、`..`、中间空段或 NUL 都返回 null，表示该条目不可接受。
 */
export function normalizeArchivePath(raw: string): string | null {
  if (raw.includes('\0')) {
    return null
  }
  let value = raw
  while (value.endsWith('/')) {
    value = value.slice(0, -1)
  }
  while (value.startsWith('./')) {
    value = value.slice(2)
  }
  if (value === '.' || value === '') {
    return ''
  }
  if (value.startsWith('/') || /^[A-Za-z]:[\\/]/.test(value) || value.includes('\\')) {
    return null
  }
  const segments = value.split('/')
  for (const segment of segments) {
    if (segment === '' || segment === '.' || segment === '..') {
      return null
    }
  }
  return value
}

function resolvesInsideRoot(entryPath: string, linkTarget: string): boolean {
  if (linkTarget.includes('\0') || linkTarget.includes('\\') || linkTarget.startsWith('/') || /^[A-Za-z]:[\\/]/.test(linkTarget)) {
    return false
  }
  const base = entryPath.includes('/') ? entryPath.slice(0, entryPath.lastIndexOf('/')) : ''
  const combined = base === '' ? linkTarget : `${base}/${linkTarget}`
  let depth = 0
  for (const segment of combined.split('/')) {
    if (segment === '' || segment === '.') {
      continue
    }
    if (segment === '..') {
      depth -= 1
      if (depth < 0) {
        return false
      }
      continue
    }
    depth += 1
  }
  return true
}

/** 校验单条归档条目并累计预算；返回失败原因，接受时返回 null。 */
export function inspectArchiveEntry(entry: ReadEntry, scan: ArchiveScan): string | null {
  if (entry.meta === true) {
    return null
  }
  if (entry.unsupported === true || entry.invalid === true) {
    return 'distribution archive contains an unsupported entry'
  }
  const normalized = normalizeArchivePath(entry.path)
  if (normalized === null) {
    return 'distribution archive contains an unsafe path'
  }
  if (normalized === '') {
    return null
  }
  if (entry.type === 'SymbolicLink' || entry.type === 'Link') {
    const target = entry.linkpath
    if (typeof target !== 'string' || !resolvesInsideRoot(normalized, target)) {
      return 'distribution archive contains a link that escapes the extraction root'
    }
    if (entry.type === 'Link' && !scan.files.has(target.replace(/^\.\//, ''))) {
      return 'distribution archive contains a hard link to an undeclared file'
    }
    if (scan.seen.has(normalized)) {
      return 'distribution archive contains a duplicate entry'
    }
    scan.seen.add(normalized)
    return null
  }
  if (DIRECTORY_TYPES.has(entry.type)) {
    scan.seen.add(normalized)
    return null
  }
  if (!FILE_TYPES.has(entry.type)) {
    return 'distribution archive contains a device, fifo or unsupported entry'
  }
  if (scan.seen.has(normalized)) {
    return 'distribution archive contains a duplicate entry'
  }
  if (!Number.isSafeInteger(entry.size) || entry.size < 0 || entry.size > MAX_EXTRACTED_FILE_BYTES) {
    return 'distribution archive contains an entry beyond the size budget'
  }
  scan.total += entry.size
  if (scan.total > MAX_EXTRACTED_TOTAL_BYTES) {
    return 'distribution archive exceeds the total size budget'
  }
  scan.seen.add(normalized)
  scan.files.add(normalized)
  return null
}

function combineSignals(signal: AbortSignal, timeoutMillis: number): AbortSignal {
  return AbortSignal.any([signal, AbortSignal.timeout(timeoutMillis)])
}

type TarParser = NodeJS.WritableStream & { abort?: () => void }

async function runPass(
  createParser: () => TarParser,
  archive: string,
  signal: AbortSignal,
  timeoutMillis: number,
): Promise<void> {
  const effective = combineSignals(signal, timeoutMillis)
  const source = createReadStream(archive)
  // 保留一个错误监听，避免取消时 source.destroy(reason) 的错误在 pipeline 之外成为未处理异常。
  source.on('error', () => undefined)
  const parser = createParser()
  const abort = (): void => {
    const reason = new Error('distribution archive processing was aborted')
    try {
      parser.abort?.()
    } catch {
      // tar 会抛自己的 abort 错误；下面 source 的错误才是确定的失败信号。
    }
    source.destroy(reason)
  }
  if (effective.aborted) {
    abort()
  } else {
    effective.addEventListener('abort', abort, { once: true })
  }
  try {
    await pipeline(source, parser)
  } catch (error) {
    if (effective.aborted) {
      throw new ArchiveError('distribution archive processing was aborted')
    }
    throw new ArchiveError('distribution archive could not be processed')
  } finally {
    effective.removeEventListener('abort', abort)
  }
}

/** 先流式列举校验归档全部条目；任何违规都以期望失败终止，不进入解包。 */
export async function validateArchive(
  archive: string,
  signal: AbortSignal,
  timeoutMillis: number = ARCHIVE_TIMEOUT_MILLIS,
): Promise<void> {
  const scan = newScan()
  let violation: string | null = null
  const parser = list({
    strict: true,
    onReadEntry: entry => {
      if (violation === null) {
        violation = inspectArchiveEntry(entry, scan)
      }
    },
  })
  await runPass(() => parser as unknown as TarParser, archive, signal, timeoutMillis)
  if (violation !== null) {
    throw new ArchiveError(violation)
  }
}

/** 在严格模式下解包已验证归档；不 chown、不按归档强制 chmod、不跟随逃逸链接。 */
export async function extractArchive(
  archive: string,
  destination: string,
  signal: AbortSignal,
  timeoutMillis: number = ARCHIVE_TIMEOUT_MILLIS,
): Promise<void> {
  await validateArchive(archive, signal, timeoutMillis)
  let skipped = 0
  const parser = extract({
    cwd: destination,
    strict: true,
    preservePaths: false,
    preserveOwner: false,
    chmod: false,
    unlink: true,
    filter: entryPath => {
      if (normalizeArchivePath(entryPath) === null) {
        skipped += 1
        return false
      }
      return true
    },
  })
  await runPass(() => parser as unknown as TarParser, archive, signal, timeoutMillis)
  if (skipped > 0) {
    throw new ArchiveError('distribution archive contains an unsafe path')
  }
}
