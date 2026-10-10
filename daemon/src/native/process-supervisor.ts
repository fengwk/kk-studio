import type { ProcessSupervisor } from '../contracts.js'
import { PosixProcessSupervisor } from './posix-supervisor.js'
import { WindowsProcessSupervisor } from './process-supervisor-windows.js'

/**
 * 按平台创建进程 supervisor。
 *
 * <p>所有用户子进程都必须经由这里启动：POSIX 用新 session 与真实内核成员枚举收尾，Windows 用创建期归属的 Job 收尾。
 * 不支持的平台显式失败，绝不静默回退到「直接 spawn / 只 kill 根进程」。
 */
export function createProcessSupervisor(): ProcessSupervisor {
  if (process.platform === 'win32') {
    return new WindowsProcessSupervisor()
  }
  if (process.platform === 'linux' || process.platform === 'darwin') {
    return new PosixProcessSupervisor()
  }
  throw new Error(`the native process supervisor does not support ${process.platform}`)
}
