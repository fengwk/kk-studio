/**
 * Canonical HarnessThread / ThreadInput domain, command orchestration, transactions and activation
 * kick.
 *
 * <p>类型对齐 PostgreSQL {@code harness_thread}/{@code harness_thread_input}，并保留 typed payload 与
 * fencing 语义。{@link ThreadCommandCoordinator} 拥有 framework-free command 编排，对外返回 coordinator-owned
 * result records；持久化 outbound SPI 为 {@link ThreadCommandTransactions}。
 *
 * <p>{@link ThreadState} 是目标 {@code harness_thread} 的 durable current state：它只保存 Thread 自身拥有的 head
 * cursor、YOLO policy、Command sequence 与对外 revision，不复制 Environment/Agent/ Model 等 Entry branch
 * facts，也不保存 status、open turn、epoch 或 processor lease。
 *
 * <p>{@link ThreadContext} 与 {@link ThreadContextClassifier} 是纯、非持久化的 Thread live/historical
 * 适用性分类：分类器以当前 ThreadState + root-to-head EntryPath + 本 Thread 当前 open Turn 的 ModelInvocation（无则
 * null）+ 仅在 Model 结果恰为当前 Assistant head 时加载的 Tool siblings 为输入，不接触 Store、不产生锁，返回唯一的 {@link
 * ThreadContext} 上下文（terminal apply / Work-only 挂起 / continuation / 输入或静止）；不变量被破坏的形状抛 {@link
 * IllegalStateException}。ThreadProcessor 与未来的 root HarnessRuntime 控制共用同一分类作为适用性事实源。
 */
package fun.fengwk.kkstudio.harness.runtime.thread;
