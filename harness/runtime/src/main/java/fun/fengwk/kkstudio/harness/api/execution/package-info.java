/**
 * 具体执行代次的公开退出证据：{@link fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact} 冻结一次
 * Model、Tool、 Agent 或 Remote 执行的 thread / invocation / attempt / lease / kind / parent identity，并以
 * {@link fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState} 单向记录未确认、Studio 退出、远端
 * ACK、确定未启动或放弃。
 *
 * <p>身份登记后不可更换，确认与放弃都返回新事实：相同证据幂等，确认后的不同证据冲突，已确认的事实不能被改写成放弃。{@link
 * fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind} 区分本地代次与挂在本地代次下的远端代次。
 *
 * <p>登记、身份匹配确认、持久推进与恢复扫描由 runtime 侧的退出账本、journal 与确认器实现，本包只定义证据形状。
 */
package fun.fengwk.kkstudio.harness.api.execution;
