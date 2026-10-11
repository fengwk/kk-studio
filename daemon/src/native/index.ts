/**
 * 原生基座对外边界：进程 supervisor、属主独占私有文件、内核目录锁，以及操作系统代理解析。
 *
 * <p>这是跨切片唯一允许直接引用 Node 原生能力的地方：能力/运行时/IDE/代理切片只从这里取 supervisor、私有文件、锁与
 * 操作系统代理解析器，不自己 `spawn`、自行放宽权限或自行调用原生代理库。具体平台实现分别在同目录的 POSIX / Windows
 * 模块内。
 */
export { createProcessSupervisor, UnsupportedPlatformError } from './process-supervisor.js'
export { createSystemProxyResolver } from './system-proxy.js'
export { ensurePrivateDirectory, writePrivateFile, verifyPrivateFile } from './private-fs.js'
export { acquireDirectoryLock, type DirectoryLock } from './lock.js'
export { processScopeCapabilities, type ProcessScopeCapabilities } from './capabilities.js'
