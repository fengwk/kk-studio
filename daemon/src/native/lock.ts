import { closeSync, constants, openSync } from 'node:fs'
import { join } from 'node:path'
import koffi from 'koffi'
import { delay } from '../delay.js'
import { DirectoryLockBusyError, DirectoryLockTimeoutError } from './lock-error.js'
import { ensurePrivateDirectory, verifyPrivateFile } from './private-fs.js'

/**
 * 目录级内核锁：同一目录同一锁文件只允许一个持有者。
 *
 * <p>锁文件所在目录先按属主独占私有目录（0700）确认，锁文件本身以 0600 创建/打开并读回验证。目录与锁文件都走
 * `private-fs` 的创建即收紧 + 读回验证路径，因此已有目录被放宽权限、锁文件被换成符号链接或其它扩权文件时，这里显式失败而
 * 不是继续持有一把「名义上的锁」。
 *
 * <p>POSIX 用一个内核 `flock` 建议锁（独占且非阻塞）承载：锁随打开文件描述符存活，进程崩溃时由内核自动释放，因此不会像
 * `O_EXCL` 创建那样留下需要人工清理的陈旧锁。Windows 用 `LockFileEx`（独占、立即失败）表达同一语义，见 `win32.ts`。
 *
 * <p>每个受管目录有两把用途不同的锁：Daemon 运行锁 {@link DAEMON_LOCK_FILE_NAME} 与安装操作锁
 * {@link INSTALL_LOCK_FILE_NAME}。两者都复用同一内核原语，安装器因此不需要 `flock` 命令、PID 存活猜测或陈旧锁目录。
 *
 * <p>抢锁失败、以及显式释放时 `flock(LOCK_UN)` 失败，都必须显式抛出：绝不静默降级为「无锁继续」或「已经释放」。竞争失败用
 * {@link DirectoryLockBusyError} 与权限/IO 错误区分，调用方才能正确报告 busy 而不是误报损坏。
 */

/** 打开的文件描述符上承载的内核锁。 */
export interface DirectoryLock {
  close(): Promise<void>
}

/** Daemon 运行锁文件名：同一数据目录只允许一个运行实例。 */
export const DAEMON_LOCK_FILE_NAME = 'daemon.lock'

/** 安装操作锁文件名：同一环境目录的安装/更新/卸载互斥。 */
export const INSTALL_LOCK_FILE_NAME = 'install.lock'

/** 目录锁的默认轮询间隔：等待运行锁释放时使用。 */
const DEFAULT_POLL_MILLISECONDS = 100

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
export async function acquireDirectoryLock(
  directory: string,
  lockFileName: string = DAEMON_LOCK_FILE_NAME,
): Promise<DirectoryLock> {
  if (process.platform === 'win32') {
    const win = await import('./win32.js')
    return win.lockDirectory(directory, lockFileName)
  }
  if (process.platform !== 'linux' && process.platform !== 'darwin') {
    throw new Error(`the directory lock is not supported on ${process.platform}`)
  }
  // 目录语义：先按属主独占锁文件所在目录确认，再在其中落 0600 锁文件。
  await ensurePrivateDirectory(directory)
  const lockPath = join(directory, lockFileName)
  const descriptor = openSync(lockPath, constants.O_RDWR | constants.O_CREAT | constants.O_NOFOLLOW, 0o600)
  try {
    // 已有锁文件必须是属主独占的普通文件：被换成链接或放宽权限时这是最后一道闸。
    await verifyPrivateFile(lockPath)
    if (flock(descriptor, LOCK_EX | LOCK_NB) !== 0) {
      throw new DirectoryLockBusyError(`the directory is already locked by another process: ${directory}`)
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
        throw new Error(`cannot release the directory lock: ${directory}`)
      }
      released = true
      closeSync(descriptor)
    },
  }
}

/**
 * 有界等待一个目录锁变为可获取：拿到后立即释放，证明持锁者已经退出。
 *
 * <p>这是「等运行锁释放」的唯一实现：轮询真实内核锁，而不是猜测 PID 存活、删除锁文件或读取年龄。等待期间只有竞争失败会被
 * 重试；任何权限/IO/非私有路径错误立即上抛。期限到点仍被持有则抛 {@link DirectoryLockTimeoutError}。
 */
export async function waitForDirectoryLock(
  directory: string,
  lockFileName: string = DAEMON_LOCK_FILE_NAME,
  timeoutMilliseconds = 30_000,
  pollMilliseconds = DEFAULT_POLL_MILLISECONDS,
): Promise<void> {
  if (!Number.isSafeInteger(timeoutMilliseconds) || timeoutMilliseconds < 0) {
    throw new Error('the lock wait timeout must be a non-negative integer')
  }
  const deadline = Date.now() + timeoutMilliseconds
  for (;;) {
    try {
      const lock = await acquireDirectoryLock(directory, lockFileName)
      await lock.close()
      return
    } catch (error) {
      if (!(error instanceof DirectoryLockBusyError)) {
        throw error
      }
    }
    const remaining = deadline - Date.now()
    if (remaining <= 0) {
      throw new DirectoryLockTimeoutError(`the directory lock was not released in time: ${directory}`)
    }
    await delay(Math.min(pollMilliseconds, remaining))
  }
}
