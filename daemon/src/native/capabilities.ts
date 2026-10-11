/**
 * 原生进程范围的**能力自述**：把「这套实现在当前平台能证明什么」显式暴露给调用方，而不是用同一套 API 假装所有平台等价。
 *
 * <p>只有能证明「任意后代都被收敛」的平台才声明保证：Linux 同时提供会话收敛（真实枚举会话成员并按启动身份核验）与子进程
 * 收养（未登记且故意 `setsid` 的后代在父进程退出后被内核重新挂回本人名下，因此仍可核实并收敛）；Windows 用创建期归属的 Job
 * （进程在内核里创建即入 Job，不存在「已创建但未归属」的窗口）。macOS 没有子进程收养者，脱离会话的未登记后代无法被证明收敛，
 * 因此不声明任何保证，生产工厂也会拒绝创建 supervisor（fail-closed）。
 *
 * <p>`unverifiedLimits` 记录当前无法证明或尚未在真实平台验收的限制；调用方绝不能把「未验收」当成「已支持」，也不存在
 * 「部分保证」这种可接受状态。
 *
 * <p>下面每个布尔字段描述的是**生产可用保证**（工厂会实际启用并负责到底的收敛能力），而不是底层原语是否存在：某个平台即使
 * 具备部分 POSIX 能力，只要生产工厂拒绝启用，字段就必须是 `false`。例如 macOS 仍有会话枚举原语，但缺少子进程收养使其无法
 * 保证任意后代收敛，因此自述不声明任何保证。
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
      // 字段是生产可用保证：工厂在 darwin 上拒绝创建 supervisor，因此这里不能声明会话收敛（尽管 libproc 原语仍在）。
      sessionConvergence: false,
      jobMembership: false,
      orphanAdoption: false,
      unverifiedLimits: [
        'macOS cannot provide strict process confinement: without a child subreaper an unregistered descendant that calls setsid is reparented to launchd and cannot be proven converged',
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
