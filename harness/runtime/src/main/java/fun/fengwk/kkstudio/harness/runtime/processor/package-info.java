/**
 * Target 级 execution processors：消费 dispatcher 已 claim 的 {@link
 * fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork}，实施所有权围栏并驱动单次 target execution 的持久化生命周期。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor} 输入必须是 MODEL target 的
 * claim，核心持久化与执行端口为单一 {@link fun.fengwk.kkstudio.harness.runtime.store.HarnessStore}、{@link
 * fun.fengwk.kkstudio.harness.runtime.port.ModelGateway} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink}；{@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor} 输入必须是 TOOL target 的 claim，以 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.ToolGateway} 做本地
 * admission，并在独占虚拟线程上阻塞执行至终态。所有持久化状态变更都在短事务内通过 store 锁序（Thread -&gt; ModelInvocation -&gt;
 * ToolInvocation -&gt; Work，同层 Work 按 (type, id) 升序）与 lease/token 校验完成；Tool 在副作用之前持久化 READY -&gt;
 * RUNNING，取消即对执行线程 interrupt，资源只在终态决议与执行体返回之后释放。
 *
 * <p>admission 前先用 Work-only 短事务验证 claim 真实 owned，再进 per-invocation guard 防止同一 claim 重复 / 并发投递误伤合法
 * execution（伪造 / 错误 token 一律 LOST no-op，绝不 cancel 合法 active execution）；READY dispatch 前若 claim
 * lease 剩余不足会在首个 heartbeat 前 renew 出完整 margin。Tool preflight 在事务外执行（期间 heartbeat 维持 lease），Allow /
 * Ask / Deny / 异常四种结果分别收敛为 not-required approval、WAITING_APPROVAL、FAILED 与保持 READY
 * reschedule，提交前二次校验 claim + READY + attempt + approval null。两个 processor 都实现 {@link
 * java.lang.AutoCloseable}：close 与 process 竞态下，registry 插入后启动 heartbeat 前会再次检查 closed，已关闭时立即
 * abandon 并 reschedule 仍 owned 的 READY，绝不启动 Gateway，也不留下新 execution。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor} 输入必须是 THREAD target 的
 * claim，核心状态与解析端口为单一 {@link fun.fengwk.kkstudio.harness.runtime.store.HarnessStore} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.TurnResolver}，是每个 claim 恰执行一个持久化 action 的 single-action
 * reducer（terminal Model apply -&gt; Tool sibling batch -&gt; blocker -&gt; continuation -&gt;
 * input -&gt; idle），下一 action 一律由同事务 {@code requestWork} 驱动，绝不内部循环。Turn 启动在单个 store transaction 内构造
 * candidate、同步调用 resolve、校验并提交，不存在事务外解析、二次 CAS 或可恢复的持久化 TURN_START-without-Invocation 中间态；Resolved
 * 请求在提交前由 {@link fun.fengwk.kkstudio.harness.runtime.thread.ResolvedRequestValidator} 按 candidate
 * branch 事实（route / model / variant / tools / compaction）做机械一致性校验，任何不一致都是 Resolver 契约错误，转为 durable
 * FAILED 且不产生 ModelInvocation。终端 Model/Tool 应用只由 ThreadProcessor 写 Entry/head。Result / Config 类型见
 * {@link fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig}。
 */
package fun.fengwk.kkstudio.harness.runtime.processor;
