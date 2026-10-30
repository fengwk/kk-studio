/**
 * Target 级 execution processors：消费 dispatcher 已 claim 的 {@link
 * fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork}，实施所有权围栏并驱动单次 target execution 的持久化生命周期。
 *
 * <p><b>Driver SPI：</b>本包同时向 Kernel 之外的 Driver 暴露最小的已认领执行骨架 {@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ClaimedExecutionSupport}：Work-only 所有权校验、按
 * invocation 的准入与 execution 登记、派发前的租约 margin、本地取消 / 关闭与活跃执行查询。Driver 的 Processor 以组合方式复用它，自己只负责请求
 * 物化、Gateway 准入与终态提交；事务内最终所有权围栏失败一律抛出唯一的 {@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ClaimLostSignal} 使事务整体回滚。阻塞执行期间的心跳由 {@link
 * fun.fengwk.kkstudio.harness.runtime.processor.WorkHeartbeat} 续租，参数见 {@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig}。骨架不解析请求、不写 invocation
 * 状态；只有准入检查会开启 Work-only 短事务，其余写入使用调用方事务，不提供基类或模板方法。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor} 输入必须是 TOOL target 的
 * claim，以 {@link fun.fengwk.kkstudio.harness.runtime.port.ToolGateway} 做本地
 * admission，并在独占虚拟线程上阻塞执行至终态。所有持久化状态变更都在短事务内通过 store 锁序（Thread -&gt; ModelInvocation -&gt;
 * ToolInvocation -&gt; Work，同层 Work 按 (type, id) 升序）与 lease/token 校验完成，lost / stale 完整 no-op；Tool
 * 在副作用之前持久化 READY -&gt; RUNNING，取消即对执行线程 interrupt，资源只在终态决议与执行体返回之后释放。
 *
 * <p>admission 前先用 Work-only 短事务验证 claim 真实 owned（{@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ClaimedExecutionSupport#admit}），再进入同时充当 execution
 * registry 的 per-invocation guard（{@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ClaimAdmissionGuard}）：ticket 覆盖 prepare 到 execution
 * 登记，guard 在原子计算内再次复核 DB ownership；同一 claim（同 lease token）重复 / 并发投递拒绝准入，不同有效 token 若存在旧 entry，则标记
 * superseded、捕获旧 execution 并在原子计算外 abandon（伪造 / 错误 token 一律 LOST no-op，绝不 cancel 合法 active
 * execution）；prepare 与外部调用均在准入锁外执行，持久化仍须复核所有权。READY dispatch 前若 claim lease 剩余不足会在首个 heartbeat 前
 * renew 出完整 margin。Tool preflight 在事务外执行（期间 heartbeat 维持 lease），提交前二次校验 claim + READY + attempt +
 * approval null。
 *
 * <p>Processor 的 cancel 是唯一本地取消入口，只 abandon execution，不反写 durable 状态；close 取消全部并停止各自 heartbeat， 不
 * shutdown 注入的 scheduler / executor，关闭后 process 拒绝新 claim。guard 在 close 期间不创建新 slot，close / cancel
 * 后未 attach 的 ticket 拒绝 attach。 execution 登记后启动 heartbeat 前会再次检查 closed，已关闭时立即 abandon 并 reschedule
 * 仍 owned 的 READY，绝不启动 Gateway，也不留下新 execution。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor} 输入必须是 THREAD target 的
 * claim，通过单一 {@link fun.fengwk.kkstudio.harness.runtime.store.HarnessStore} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver} 协作，是每个 claim 恰执行一个持久化 action 的
 * single-action reducer（Driver 结果应用 -&gt; Tool sibling batch -&gt; blocker -&gt; continuation -&gt;
 * input -&gt; idle），下一 action 一律由同事务 {@code requestWork} 驱动，绝不内部循环。Turn 启动在单个 store transaction 内构造
 * candidate，由 Driver 同步解析与校验后提交，不存在事务外解析、二次 CAS 或可恢复的 TURN_START-without-Invocation 中间态。 Driver
 * 负责检查具体执行后端的请求与分支事实是否一致；契约错误转为 durable FAILED。终态应用只由 ThreadProcessor 写 Entry/head。Result / Config
 * 类型见 {@link fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig}。
 */
package fun.fengwk.kkstudio.harness.runtime.processor;
