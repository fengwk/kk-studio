/**
 * 原生进程范围的**能力自述**：把「这套实现在当前平台能证明什么」显式暴露给调用方，而不是用同一套 API 假装所有平台等价。
 *
 * <p>Linux 同时提供会话生命周期（真实枚举会话成员并按启动身份核验，TERM 之后有界升级到 KILL）与子进程收养（未登记且故意
 * `setsid` 的后代在父进程退出后被内核重新挂回本人名下，因此仍可核实并收敛）。macOS 复用同一套会话生命周期，但没有子进程
 * 收养者：未登记且故意脱离的后代会被重新挂到 launchd，不能再被证明属于本次调用。Windows 用创建期归属的 Job（进程在内核里
 * 创建即入 Job，不存在「已创建但未归属」的窗口）。
 *
 * <p>这些字段描述的是生命周期能力，不是安全沙箱，也不存在「部分保证才允许启动」的模式。缺少收养机制不会禁用 macOS。
 */
export interface ProcessScopeCapabilities {
  platform: NodeJS.Platform
  /** POSIX：会话级生命周期，真实枚举会话成员并按启动身份核验。 */
  sessionConvergence: boolean
  /** Windows：Job 成员在进程创建期由内核直接归属，收敛证据是 `ActiveProcesses == 0`。 */
  jobMembership: boolean
  /** Linux：内核子进程收养（`PR_SET_CHILD_SUBREAPER`），覆盖未登记且故意 `setsid` 的后代。 */
  orphanAdoption: boolean
  /** 当前平台生命周期覆盖不到、或尚未在真实平台验收的限制。 */
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
        'macOS has no child subreaper: an unregistered descendant that deliberately calls setsid is reparented to launchd and is outside this session lifecycle',
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
