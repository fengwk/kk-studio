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
 */
package fun.fengwk.kkstudio.harness.runtime.thread;
