import type { ProcessSupervisor } from '../contracts.js'
import { PosixProcessSupervisor } from './posix-supervisor.js'
import { WindowsProcessSupervisor } from './process-supervisor-windows.js'

/**
 * 按平台创建进程 supervisor。
 *
 * <p>所有用户子进程都必须经由这里启动：Linux 用新 session + 子进程收养（POSIX）收尾，Windows 用创建期归属的 Job 收尾。
 * 其它平台一律显式失败，绝不静默回退到「直接 spawn / 只 kill 根进程」。
 *
 * <p>冻结契约要求无法提供**严格收敛保证**的平台必须 fail-closed。macOS 没有子进程收养者，未登记且故意 `setsid` 的后代会被
 * 重新挂到 launchd，native 层无法证明其归属，因此这里拒绝创建带副作用的 supervisor，而不是提供一个覆盖不全的假保证。
 */
export function createProcessSupervisor(): ProcessSupervisor {
  if (process.platform === 'win32') {
    return new WindowsProcessSupervisor()
  }
  if (process.platform === 'linux') {
    return new PosixProcessSupervisor()
  }
  throw new UnsupportedPlatformError(unsupportedPlatformDiagnostic(process.platform))
}

/**
 * 平台无法提供严格收敛保证时的启动错误。
 *
 * <p>{@link message} 是固定安全诊断（只由平台名构造），不含路径、凭证或原始异常，可直接作为单行启动诊断输出。
 */
export class UnsupportedPlatformError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UnsupportedPlatformError'
  }
}

/** 未支持平台的安全固定诊断：darwin 说明严格收敛不可用，其它平台只报告不支持，绝不回退。 */
export function unsupportedPlatformDiagnostic(platform: NodeJS.Platform): string {
  if (platform === 'darwin') {
    return 'strict native process confinement is unavailable on darwin: macOS has no child subreaper, so an unregistered descendant that calls setsid is reparented to launchd and cannot be proven converged'
  }
  return `the native process supervisor does not support ${platform}`
}
