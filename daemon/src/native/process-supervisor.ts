import type { ProcessSupervisor } from '../contracts.js'
import { PosixProcessSupervisor } from './posix-supervisor.js'
import { WindowsProcessSupervisor } from './process-supervisor-windows.js'

/**
 * 按平台创建进程 supervisor。
 *
 * <p>所有用户子进程都必须经由这里启动：Linux 与 macOS 用同一套 POSIX supervisor（新 session、会话成员枚举、TERM→宽限→KILL、
 * 启动身份核验），Windows 用创建期归属的 Job。其它平台一律显式失败，绝不静默回退到「直接 spawn / 只 kill 根进程」。
 *
 * <p>macOS 没有 Linux 的子进程收养者。未登记且故意 `setsid` 的后代会被重新挂到 launchd，收尾无法再证明它仍属于本次调用；
 * 这是会话生命周期的边界，由 {@link processScopeCapabilities} 如实暴露，不是拒绝启动普通进程的理由，也不是安全沙箱。
 */
export function createProcessSupervisor(): ProcessSupervisor {
  if (process.platform === 'win32') {
    return new WindowsProcessSupervisor()
  }
  if (process.platform === 'linux' || process.platform === 'darwin') {
    return new PosixProcessSupervisor()
  }
  throw new UnsupportedPlatformError(process.platform)
}

/**
 * 平台没有可用进程 supervisor 时的启动错误。
 *
 * <p>构造参数只接受平台名，{@link message} 由内部生成，是固定安全诊断：不含路径、凭证或原始异常，可直接作为单行启动诊断输出。
 */
export class UnsupportedPlatformError extends Error {
  constructor(platform: NodeJS.Platform) {
    super(`the native process supervisor does not support ${platform}`)
    this.name = 'UnsupportedPlatformError'
  }
}
