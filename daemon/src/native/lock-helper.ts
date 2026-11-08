import { realpathSync } from 'node:fs'
import { isAbsolute } from 'node:path'
import { pathToFileURL } from 'node:url'

import {
  DAEMON_LOCK_FILE_NAME,
  INSTALL_LOCK_FILE_NAME,
  acquireDirectoryLock,
  waitForDirectoryLock,
  type DirectoryLock,
} from './lock.js'
import { DirectoryLockBusyError, DirectoryLockTimeoutError } from './lock-error.js'

/**
 * 安装操作锁 helper：脚本从独立临时管理副本运行它，只负责用内核锁原语表达「安装操作互斥」与「等运行锁释放」。
 *
 * <p>`hold <directory>` 取得 `<directory>/install.lock` 后向 stdout 写一行 `locked`，并一直持有到 stdin 关闭。安装脚本在
 * 收到这一行之后才停止服务、清理暂存和发布程序，并在全部工作完成后关闭 stdin；父进程异常退出时管道同样关闭，helper 随
 * 即释放锁。锁由内核在进程退出时释放，因此不存在需要猜测 PID 或清理的陈旧锁目录。
 *
 * <p>`wait <directory> <seconds>` 轮询真实 `daemon.lock` 内核锁，拿到后立即释放，作为「目标服务已释放运行锁」的唯一证据。
 *
 * <p>退出码是脚本可判定的稳定契约：2 用法错误、75 安装锁 busy、76 等运行锁超时、77 无法取得/检查锁。所有错误只输出固定
 * 文本，绝不包含路径之外的宿主诊断或凭据。
 */

/** 已取得安装操作锁并开始守护。 */
export const HOLD_READY_LINE = 'locked'

/** 脚本可判定的 helper 退出码。 */
export const LOCK_HELPER_EXIT = {
  usage: 2,
  busy: 75,
  timeout: 76,
  failed: 77,
} as const

const USAGE = 'usage: lock-helper hold <directory> | lock-helper wait <directory> <timeout-seconds>\n'
const MAXIMUM_WAIT_SECONDS = 3600

function writeError(message: string): void {
  process.stderr.write(`${message}\n`)
}

function isAbsolutePath(value: string): boolean {
  return isAbsolute(value) && !/[\u0000-\u001f\u007f]/.test(value)
}

/** 等待 stdin 关闭：父进程用关闭管道表达「受管发布阶段已经结束」。 */
function waitForParent(): Promise<void> {
  return new Promise((resolve) => {
    const finish = (): void => {
      process.stdin.off('end', finish)
      process.stdin.off('close', finish)
      process.stdin.off('error', finish)
      resolve()
    }
    process.stdin.on('end', finish)
    process.stdin.on('close', finish)
    process.stdin.on('error', finish)
    process.stdin.resume()
  })
}

async function hold(directory: string): Promise<number> {
  let lock: DirectoryLock
  try {
    lock = await acquireDirectoryLock(directory, INSTALL_LOCK_FILE_NAME)
  } catch (error) {
    if (error instanceof DirectoryLockBusyError) {
      writeError('install operation lock is busy')
      return LOCK_HELPER_EXIT.busy
    }
    writeError('cannot acquire the install operation lock')
    return LOCK_HELPER_EXIT.failed
  }
  let released = true
  try {
    process.stdout.write(`${HOLD_READY_LINE}\n`)
    await waitForParent()
  } finally {
    try {
      await lock.close()
    } catch {
      released = false
    }
  }
  if (!released) {
    writeError('cannot release the install operation lock')
    return LOCK_HELPER_EXIT.failed
  }
  return 0
}

async function wait(directory: string, seconds: string): Promise<number> {
  if (!/^[0-9]{1,4}$/.test(seconds) || Number(seconds) > MAXIMUM_WAIT_SECONDS) {
    writeError(USAGE.trimEnd())
    return LOCK_HELPER_EXIT.usage
  }
  try {
    await waitForDirectoryLock(directory, DAEMON_LOCK_FILE_NAME, Number(seconds) * 1000)
    return 0
  } catch (error) {
    if (error instanceof DirectoryLockTimeoutError) {
      writeError('daemon runtime lock wait timed out')
      return LOCK_HELPER_EXIT.timeout
    }
    writeError('cannot inspect the daemon runtime lock')
    return LOCK_HELPER_EXIT.failed
  }
}

/** 解析 argv 并执行一个 helper 子命令；返回值即进程退出码。 */
export async function runLockHelper(argv: string[]): Promise<number> {
  const [command, directory, ...rest] = argv
  if ((command !== 'hold' && command !== 'wait') || directory === undefined || !isAbsolutePath(directory)) {
    writeError(USAGE.trimEnd())
    return LOCK_HELPER_EXIT.usage
  }
  if (command === 'hold') {
    if (rest.length !== 0) {
      writeError(USAGE.trimEnd())
      return LOCK_HELPER_EXIT.usage
    }
    return hold(directory)
  }
  if (rest.length !== 1) {
    writeError(USAGE.trimEnd())
    return LOCK_HELPER_EXIT.usage
  }
  return wait(directory, rest[0]!)
}

function isMain(): boolean {
  const entry = process.argv[1]
  if (entry === undefined) {
    return false
  }
  try {
    return import.meta.url === pathToFileURL(realpathSync(entry)).href
  } catch {
    return false
  }
}

if (isMain()) {
  runLockHelper(process.argv.slice(2)).then(
    (code) => process.exit(code),
    () => {
      writeError('cannot run the install operation lock helper')
      process.exit(LOCK_HELPER_EXIT.failed)
    },
  )
}
