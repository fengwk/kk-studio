/**
 * Canonical HarnessThread / ThreadInput domain, command orchestration, transactions and activation
 * kick.
 *
 * <p>类型对齐 PostgreSQL {@code harness_thread}/{@code harness_thread_input}，并保留 typed payload 与
 * fencing 语义。{@link ThreadCommandCoordinator} 拥有 framework-free command 编排；持久化由 {@link
 * ThreadCommandTransactions} 完成。
 */
package fun.fengwk.kkstudio.harness.runtime.thread;
