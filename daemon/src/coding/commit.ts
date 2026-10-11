import { constants } from 'node:fs'
import { chmod, lstat, mkdir, open, rename, rm, stat } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { ToolRunFailureError } from './support.js'

/**
 * 文本文件原子提交与变更串行化。
 *
 * <p>权限语义与 Java 参考实现一致：目标已存在且平台支持 POSIX 权限时，staging 先以 0600 建立、写入内容、内容落盘后才收敛为目标的精确权限，最后原子替换；
 * 目标不存在时使用平台默认创建语义。目标不是普通文件（目录、设备、FIFO、符号链接）时在任何 I/O 之前拒绝。
 */

const STAGING_PREFIX = '.kk-mutation-'
const STRIPES = 64

const stripeLocks: Array<Promise<void>> = Array.from({ length: STRIPES }, () =>
  Promise.resolve(),
)

/** 按路径 stripe 串行化变更；返回释放函数。 */
export async function acquireFileLock(path: string): Promise<() => void> {
  const index = stripeIndex(path)
  const previous = stripeLocks[index] as Promise<void>
  let release!: () => void
  const current = new Promise<void>((resolve) => {
    release = resolve
  })
  stripeLocks[index] = previous.then(() => current)
  await previous
  return release
}

function stripeIndex(path: string): number {
  let hash = 0
  const normalized = path.replace(/\\/g, '/')
  for (let index = 0; index < normalized.length; index++) {
    hash = (Math.imul(hash, 31) + normalized.charCodeAt(index)) | 0
  }
  return Math.abs(hash) % STRIPES
}

/** 以 {@code bytes} 原子替换 {@code target}；目标不存在时创建，不改变调用方给出的内容。 */
export async function commitTextFile(target: string, bytes: Uint8Array): Promise<void> {
  const parent = dirname(target)
  await mkdir(parent, { recursive: true })
  const targetPermissions = await existingPermissions(target)
  const staging = await createStagingFile(parent, targetPermissions !== null)
  try {
    const handle = await open(staging, 'r+')
    try {
      // 真正写满：FileHandle.writeFile 内部循环补齐短写，避免 durable 正文尾部丢失。
      await handle.writeFile(bytes)
      await handle.sync()
    } finally {
      await handle.close()
    }
    if (targetPermissions !== null) {
      await chmod(staging, targetPermissions)
    }
    await rename(staging, target)
  } finally {
    await rm(staging, { force: true })
  }
}

/** 目标已存在时返回其精确 POSIX 权限；不存在或平台不支持 POSIX 权限视图时返回 null。 */
async function existingPermissions(target: string): Promise<number | null> {
  let info
  try {
    info = await lstat(target)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return null
    }
    throw error
  }
  if (info.isSymbolicLink()) {
    throw new ToolRunFailureError(`target is a symbolic link: ${target}`, 'INVALID_ARGUMENT')
  }
  if (!info.isFile()) {
    throw new ToolRunFailureError(`target is not a regular file: ${target}`, 'INVALID_ARGUMENT')
  }
  if (process.platform === 'win32') {
    return null
  }
  return info.mode & 0o7777
}

async function createStagingFile(parent: string, ownerOnly: boolean): Promise<string> {
  const candidate = join(parent, `${STAGING_PREFIX}${randomUUID()}.tmp`)
  const mode = ownerOnly ? 0o600 : 0o666
  const handle = await open(candidate, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, mode)
  await handle.close()
  return candidate
}

/** 目标是否存在于文件系统（不跟随符号链接的最终分量）。 */
export async function pathExists(path: string): Promise<boolean> {
  try {
    await stat(path)
    return true
  } catch {
    return false
  }
}
