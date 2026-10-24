/**
 * 具体执行代次的窄退出账本：{@link fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact} 冻结一次
 * Model、Tool 或 Remote 执行的 thread / invocation / attempt / lease / kind / parent identity，并以 {@link
 * fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState} 单向记录未确认、Studio 退出、远端 ACK
 * 或确定未启动。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger}
 * 只负责登记、身份匹配确认与确认后的持久推进，不复制 Invocation 状态机。确认必须使用登记时冻结的完整身份，重复相同证据幂等、确认后的不同证据冲突；只有真正从未确认推进到终态的 ACK
 * 才递增 Thread version，并在同一事务内补结算已物化普通终态的 Join，或仅在存在可结算未完成 Join 时请求 THREAD Work。执行资源的 close 由
 * Processor 的 finally 唯一负责，close 失败保持未确认；未进入 body 的已登记代次只确认 NOT_STARTED。
 */
package fun.fengwk.kkstudio.harness.runtime.execution;
