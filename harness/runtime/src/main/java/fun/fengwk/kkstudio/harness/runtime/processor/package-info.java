/**
 * Target 级 execution processors：消费 dispatcher 已 claim 的 {@link
 * fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork}， fence 并驱动单次 target execution 的 durable
 * 生命周期。
 *
 * <p>{@link ModelProcessor} 输入必须是 MODEL target 的 claim，只依赖单一 {@link
 * fun.fengwk.kkstudio.harness.runtime.store.HarnessStore}、{@link
 * fun.fengwk.kkstudio.harness.runtime.port.ModelGateway} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink}；{@link ToolProcessor} 输入必须是 TOOL
 * target 的 claim，只依赖 {@link fun.fengwk.kkstudio.harness.runtime.port.ToolGateway} 替代 Model
 * Gateway，其余协议一致。所有 durable mutation 都在短事务内通过 store 锁序（Thread -&gt; ModelInvocation -&gt;
 * ToolInvocation -&gt; Work，同层 Work 按 (type, id) 升序）与 lease/token 校验完成；本地 listener 在 durable
 * RUNNING 之前门控缓冲，任何 callback 都不能早于 RUNNING 落地。
 *
 * <p>admission 前先用 Work-only 短事务验证 claim 真实 owned，再进 per-invocation guard 防止同一 claim 重复 / 并发投递 误伤合法
 * execution（伪造 / 错误 token 一律 LOST no-op，绝不 cancel 合法 active execution）；READY dispatch 前若 claim
 * lease 剩余不足会在首个 heartbeat 前 renew 出完整 margin。Tool preflight 在事务外执行（期间 heartbeat 维持 lease），Allow /
 * Ask / Deny / 异常四种结果分别收敛为 beginDispatch、WAITING_APPROVAL、FAILED 与保持 READY reschedule，提交前二次校验 claim
 * + READY + attempt + approval null。两个 processor 都实现 {@link java.lang.AutoCloseable}：close 与
 * process 竞态下，registry 插入后启动 heartbeat 前会再次检查 closed，已关闭时立即 abandon 并把仍 owned 的 DISPATCHING 安全
 * bounce 回 READY + reschedule，绝不启动 Gateway，也不留下新 execution。
 *
 * <p>{@link ThreadProcessor} 输入必须是 THREAD target 的 claim，只依赖单一 {@link
 * fun.fengwk.kkstudio.harness.runtime.store.HarnessStore} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.port.TurnResolver}，是每个 claim 恰执行一个 durable action 的
 * single-action reducer（terminal Model apply -&gt; Tool sibling batch -&gt; blocker -&gt;
 * continuation -&gt; input -&gt; idle），下一 action 一律由同事务 {@code requestWork} 驱动，绝不内部循环。Turn 启动采用
 * speculative plan + 事务外 resolve + 第二事务 CAS 提交，不存在可恢复的 durable TURN_START-without-Invocation
 * 中间态；Resolved 请求在提交前由 {@link ResolvedRequestValidator} 按 candidate branch 事实（route / model /
 * variant / tools / compaction）做机械一致性校验，任何不一致都是 Resolver 契约错误，抛错且零 durable mutation，绝不转 typed
 * rejection。终端 Model/Tool 应用只由 ThreadProcessor 写 Entry/head。Result / Config 类型见 {@link
 * ThreadProcessResult} 与 {@link ThreadProcessorConfig}。
 */
package fun.fengwk.kkstudio.harness.runtime.processor;
