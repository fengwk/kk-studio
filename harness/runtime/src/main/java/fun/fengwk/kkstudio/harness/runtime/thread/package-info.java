/**
 * Canonical Thread domain：持久化 Thread 当前状态、live/historical 分类与命令邮箱。
 *
 * <p>类型对齐 PostgreSQL {@code harness_thread}。{@link
 * fun.fengwk.kkstudio.harness.runtime.thread.ThreadState} 是目标 {@code harness_thread} 的持久化当前状态：它只保存
 * Thread 自身拥有的 head cursor、YOLO policy、Command sequence 与对外 version，不复制 Environment/Agent/Model 等
 * Entry branch facts，也不保存 status、open turn、epoch 或 processor lease。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier} 是纯、非持久化的 Thread
 * live/historical 适用性分类：分类器以当前 ThreadState + root-to-head EntryPath + 本 Thread 当前 open Turn 的
 * ModelInvocation（无则 null）+ 仅在 Model 结果恰为当前 Assistant head 时加载的 Tool siblings 为输入，不接触
 * Store、不产生锁，返回唯一的 {@link fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext} 上下文（terminal
 * apply / Work-only 挂起 / continuation / 输入或静止）；{@link
 * fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus} 是该上下文唯一的对外状态投影。不变量被破坏的形状抛 {@link
 * IllegalStateException}。ThreadProcessor 与 root 包的 HarnessRuntime 控制面通过共享的锁定上下文辅助（ {@code
 * fun.fengwk.kkstudio.harness.runtime.ThreadContextLock}）使用同一分类作为适用性事实源，控制面先锁 Thread（以及需要的
 * Commands）再锁 Model/Tool，分类结果不会漂移。
 *
 * <p>{@code fun.fengwk.kkstudio.harness.runtime.thread.command} 包承载 Command mailbox 的持久化
 * payload、batch 与 harvest reducer；{@link
 * fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector} 把语义 Context 投影为与 Provider
 * SDK 无关的 ProviderMessage。
 */
package fun.fengwk.kkstudio.harness.runtime.thread;
