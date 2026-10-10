/**
 * 原生进程范围的**能力自述**：把「这套实现在当前平台能证明什么」显式暴露给调用方，而不是用同一套 API 假装所有平台等价。
 *
 * <p>三件事分别描述收敛的三条证据：POSIX 的会话收敛（真实枚举会话成员并按启动身份核验）、Windows 的 Job 创建期归属（进程在
 * 内核里创建即入 Job，不存在「已创建但未归属」的窗口）、以及 Linux 的子进程收养（未登记且故意 `setsid` 的后代在父进程退出后被
 * 内核重新挂回本人名下，因此仍可核实并收敛）。
 *
 * <p>`unverifiedLimits` 记录当前无法证明或尚未在真实平台验收的限制；调用方据此实现能力或降级，绝不能把「未验收」当成「已支持」。
 */
export interface ProcessScopeCapabilities {
  platform: NodeJS.Platform
  /** POSIX：会话级收敛，真实枚举会话成员并按启动身份核验。 */
  sessionConvergence: boolean
  /** Windows：Job 成员在进程创建期由内核直接归属，收敛证据是 `ActiveProcesses == 0`。 */
  jobMembership: boolean
  /** Linux：内核子进程收养（`PR_SET_CHILD_SUBREAPER`），覆盖未登记且故意 `setsid` 的后代。 */
  orphanAdoption: boolean
  /** 尚未证明或尚未在真实平台验收的限制。 */
  unverifiedLimits: string[]
}

/** 当前平台的原生进程范围能力。 */
export function processScopeCapabilities(): ProcessScopeCapabilities {
  if (process.platform === 'linux') {
    return { platform: 'linux', sessionConvergence: true, jobMembership: false, orphanAdoption: true, unverifiedLimits: [] }
  }
  if (process.platform === 'darwin') {
    return {
      platform: 'darwin',
      sessionConvergence: true,
      jobMembership: false,
      orphanAdoption: false,
      unverifiedLimits: [
        'macOS has no child subreaper: an unregistered descendant that calls setsid is reparented to launchd and cannot be proven converged by the native layer',
        'the macOS (libproc) implementation is not verified on the Linux development host',
      ],
    }
  }
  if (process.platform === 'win32') {
    return {
      platform: 'win32',
      sessionConvergence: false,
      jobMembership: true,
      orphanAdoption: false,
      unverifiedLimits: ['the Windows (Job object) implementation is not verified on the Linux development host'],
    }
  }
  return {
    platform: process.platform,
    sessionConvergence: false,
    jobMembership: false,
    orphanAdoption: false,
    unverifiedLimits: [`native process scopes are not supported on ${process.platform}`],
  }
}
