/**
 * Daemon 进程内 Invocation 执行事实存储与去重日志。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal} 维护 Invocation
 * 生命周期状态（{@link fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState#RUNNING}、{@link
 * fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState#COMPLETED}、{@link
 * fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState#FAILED}、{@link
 * fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState#CANCELLED}），通过原子 {@link
 * fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal#start(String)} 保证同一 {@code
 * invocationId} 仅创建一次执行记录，并由 {@link
 * fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal#complete(String,
 * fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage)} 确保仅从 RUNNING 跃迁至终态，支撑
 * terminal-once 契约。
 *
 * <p>在连接断开与重连恢复时，journal 作为进程内执行事实源支撑对重复 {@code INVOKE} 的 {@code STARTED(replayed=true)} 或 terminal
 * 报文幂等重放（replay）； journal 状态独立于网络连接生命周期，连接断开不清空 journal。{@link
 * fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal} 提供线程安全的内存实现。
 */
package fun.fengwk.kkstudio.harness.daemon.journal;
