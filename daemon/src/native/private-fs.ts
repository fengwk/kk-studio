import { constants, closeSync, fsyncSync, lstatSync, mkdirSync, openSync, renameSync, rmSync, writeSync, chmodSync, type Stats } from 'node:fs'
import { randomUUID } from 'node:crypto'

/**
 * 属主独占的私有文件系统原语：目录 0700、文件 0600，且创建后必须读回验证。
 *
 * <p>权限属于安全边界，因此「创建」与「读回验证」是同一条路径：创建即收紧，随后 `verifyPrivateFile` / 目录读回必须成立，
 * 否则显式失败，绝不静默继续。符号链接与 reparse 点一律拒绝（`O_NOFOLLOW` + `lstat` 复核），避免把敏感文件写到链接目标。
 *
 * <p>写入是**替换式**的：先在同目录以 0600 独占创建临时文件、写满并 `fsync`，再原子改名覆盖目标，最后读回验证。这样既不会
 * 把一个已存在的宽松权限文件先写进去再收紧（那会有一个泄漏窗口），也不会只写一半就当成成功（短写必须补齐）。
 *
 * <p>Windows 的 owner-only 语义由 DACL 承载，实现在 `win32.ts`；这里对非 POSIX 平台分发过去。
 */

/** 私有目录权限：只有属主可读写执行。 */
const DIRECTORY_MODE = 0o700

/** 私有文件权限：只有属主可读写。 */
const FILE_MODE = 0o600

/** 独占创建或确认一个只有属主可访问的私有目录，并返回该路径。 */
export async function ensurePrivateDirectory(path: string): Promise<string> {
  rejectSymlink(path)
  if (process.platform === 'win32') {
    const win = await import('./win32.js')
    return win.ensurePrivateDirectory(path)
  }
  // 已有路径必须先是「当前用户拥有的真实目录」，然后才收紧权限：绝不在属于别人的目录上 chmod。
  const existing = lstatOrNull(path)
  if (existing !== null) {
    if (!existing.isDirectory() || existing.isSymbolicLink()) {
      throw new Error(`the private directory path is not a real directory: ${path}`)
    }
    assertOwnedByCurrentUser(path, existing.uid, 'directory')
  }
  mkdirSync(path, { recursive: true, mode: DIRECTORY_MODE })
  chmodSync(path, DIRECTORY_MODE)
  const stats = lstatSync(path)
  if (!stats.isDirectory() || stats.isSymbolicLink()) {
    throw new Error(`the private directory path is not a real directory: ${path}`)
  }
  assertOwnerAndMode(path, stats.mode, DIRECTORY_MODE, stats.uid, 'directory')
  return path
}

/** 以 0600 原子替换写入一个敏感文件，并在写入后读回验证。 */
export async function writePrivateFile(path: string, data: string | Uint8Array): Promise<void> {
  rejectSymlink(path)
  if (process.platform === 'win32') {
    const win = await import('./win32.js')
    await win.writePrivateFile(path, data)
    return
  }
  // 已有目标若是链接或他人文件，直接拒绝：替换式写入不能成为「覆盖陌生人文件」的通道。
  const existing = lstatOrNull(path)
  if (existing !== null) {
    if (!existing.isFile() || existing.isSymbolicLink()) {
      throw new Error(`the private file path is not a real file: ${path}`)
    }
    assertOwnedByCurrentUser(path, existing.uid, 'file')
  }
  const bytes = typeof data === 'string' ? Buffer.from(data, 'utf8') : Buffer.from(data)
  const temporary = `${path}.${process.pid}.${randomUUID()}.tmp`
  const flags = constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW
  const descriptor = openSync(temporary, flags, FILE_MODE)
  try {
    writeAll(descriptor, bytes)
    fsyncSync(descriptor)
  } catch (error) {
    closeSync(descriptor)
    rmSync(temporary, { force: true })
    throw error
  }
  closeSync(descriptor)
  chmodSync(temporary, FILE_MODE)
  renameSync(temporary, path)
  await verifyPrivateFile(path)
}

/** 校验一个敏感文件的权限与属主；不是 0600 的普通文件就显式失败。 */
export async function verifyPrivateFile(path: string): Promise<void> {
  if (process.platform === 'win32') {
    const win = await import('./win32.js')
    await win.verifyPrivateFile(path)
    return
  }
  const stats = lstatSync(path)
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error(`the private file path is not a real file: ${path}`)
  }
  assertOwnerAndMode(path, stats.mode, FILE_MODE, stats.uid, 'file')
}

/** 写满整个缓冲区：`writeSync` 允许短写，循环补齐前绝不认为已经写完。 */
function writeAll(descriptor: number, bytes: Buffer): void {
  let offset = 0
  while (offset < bytes.length) {
    const written = writeSync(descriptor, bytes, offset, bytes.length - offset)
    if (written <= 0) {
      throw new Error('the private file could not be written completely')
    }
    offset += written
  }
}

function assertOwnerAndMode(path: string, mode: number, expectedMode: number, uid: number, kind: string): void {
  assertOwnedByCurrentUser(path, uid, kind)
  if ((mode & 0o777) !== expectedMode) {
    throw new Error(`the private ${kind} permissions are not owner-only: ${path} has ${(mode & 0o777).toString(8)}`)
  }
}

function assertOwnedByCurrentUser(path: string, uid: number, kind: string): void {
  const currentUid = process.getuid?.()
  if (currentUid !== undefined && uid !== currentUid) {
    throw new Error(`the private ${kind} is not owned by the current user: ${path}`)
  }
}

function lstatOrNull(path: string): Stats | null {
  try {
    return lstatSync(path)
  } catch (error) {
    if (error instanceof Error && 'code' in error && (error as NodeJS.ErrnoException).code === 'ENOENT') {
      return null
    }
    throw error
  }
}

/** 拒绝最终组件是符号链接或 reparse 点；路径不存在时交给后续创建步骤处理。 */
function rejectSymlink(path: string): void {
  const stats = lstatOrNull(path)
  if (stats !== null && stats.isSymbolicLink()) {
    throw new Error(`the private path must not be a symbolic link: ${path}`)
  }
}
