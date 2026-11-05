/**
 * 原生基座对外边界：进程 supervisor、属主独占私有文件与内核目录锁。
 *
 * <p>能力/运行时/IDE 切片从这里取 supervisor、私有文件与锁，不自己 `spawn` 或自行放宽权限。
 */
export { createProcessSupervisor, UnsupportedPlatformError } from './process-supervisor.js'
export { ensurePrivateDirectory, writePrivateFile, verifyPrivateFile } from './private-fs.js'
export { isRealDirectory, isRealFile } from './fs-kind.js'
export { acquireDirectoryLock, acquireFileLock, type DirectoryLock } from './lock.js'
