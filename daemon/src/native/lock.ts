import { closeSync, constants, openSync } from 'node:fs'
import { dirname, join } from 'node:path'
import koffi from 'koffi'
import { ensurePrivateDirectory, verifyPrivateFile } from './private-fs.js'

/**
 * 文件级内核锁：同一锁文件只允许一个持有者，目录锁是它在固定文件名上的特化。
 *
 * <p>锁文件所在目录先按属主独占私有目录（0700）确认，锁文件本身以 0600 创建/打开并读回验证。目录与锁文件都走
 * `private-fs` 的创建即收紧 + 读回验证路径，因此已有目录被放宽权限、锁文件被换成符号链接或其它扩权文件时，这里显式失败而
 * 不是继续持有一把「名义上的锁」。
 *
 * <p>POSIX 用一个内核 `flock` 建议锁（独占且非阻塞）承载：锁随打开文件描述符存活，进程崩溃时由内核自动释放，因此不会像
 * `O_EXCL` 创建那样留下需要人工清理的陈旧锁。Windows 用 `LockFileEx`（独占、立即失败）表达同一语义，见 `win32.ts`。
 *
 * <p>抢锁失败、以及显式释放时 `flock(LOCK_UN)` 失败，都必须显式抛出：绝不静默降级为「无锁继续」或「已经释放」。
 */

/** 打开的文件描述符上承载的内核锁。 */
export interface DirectoryLock {
  close(): Promise<void>
}

/** 锁文件名：固定名称使所有实例对同一目录竞争同一把内核锁。 */
const LOCK_FILE_NAME = 'daemon.lock'

/** POSIX `flock` 操作码。 */
const LOCK_EX = 2
const LOCK_NB = 4
const LOCK_UN = 8

let flockFunction: ((descriptor: number, operation: number) => number) | null = null

function flock(descriptor: number, operation: number): number {
  if (flockFunction === null) {
    const library = koffi.load(null)
    flockFunction = library.func('int flock(int, int)')
  }
  return flockFunction(descriptor, operation)
}

/** 获取一个**目录**的内核建议锁；已被其它进程持有时显式失败。 */
export async function acquireDirectoryLock(directory: string): Promise<DirectoryLock> {
  return acquireFileLock(join(directory, LOCK_FILE_NAME), directory)
}

/**
 * 获取一个**锁文件**的内核建议锁；已被其它进程持有时显式失败。
 *
 * <p>{@code label} 只用于诊断；默认使用锁文件路径。目录锁把目录作为诊断标识，保持既有错误文案。
 */
export async function acquireFileLock(lockPath: string, label = lockPath): Promise<DirectoryLock> {
  if (process.platform === 'win32') {
    const win = await import('./win32.js')
    return win.lockFile(lockPath)
  }
  if (process.platform !== 'linux' && process.platform !== 'darwin') {
    throw new Error(`the file lock is not supported on ${process.platform}`)
  }
  // 目录语义：先按属主独占锁文件所在目录确认，再在其中落 0600 锁文件。
  await ensurePrivateDirectory(dirname(lockPath))
  const descriptor = openSync(lockPath, constants.O_RDWR | constants.O_CREAT | constants.O_NOFOLLOW, 0o600)
  try {
    // 已有锁文件必须是属主独占的普通文件：被换成链接或放宽权限时这是最后一道闸。
    await verifyPrivateFile(lockPath)
    if (flock(descriptor, LOCK_EX | LOCK_NB) !== 0) {
      throw new Error(`the directory is already locked by another process: ${label}`)
    }
  } catch (error) {
    closeSync(descriptor)
    throw error
  }
  let released = false
  return {
    async close(): Promise<void> {
      if (released) {
        return
      }
      // 释放也核对返回值：`LOCK_UN` 失败时不能对外宣称「已经释放」。
      if (flock(descriptor, LOCK_UN) !== 0) {
        throw new Error(`cannot release the directory lock: ${label}`)
      }
      released = true
      closeSync(descriptor)
    },
  }
}
