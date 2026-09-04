package fun.fengwk.kkstudio.harness.daemon.journal;

import java.util.Optional;

/**
 * Invocation 去重和终态重放的最小 journal 端口。
 *
 * <p>实现可替换为持久化存储；Daemon Runtime 不把当前 WebSocket 连接当作 Invocation 事实源，journal 是进程内执行事实源。
 * 支撑网络重连时的幂等去重、STARTED 重放与终态重放，并为 Capability 执行提供 terminal-once 语义保障。
 */
public interface DaemonInvocationJournal {

  /**
   * 原子创建初始为 RUNNING 的 Invocation 记录，或返回已有记录。
   *
   * @param invocationId 唯一调用标识，不得为 null
   * @return 若首次创建则其 created 为 true，若已存在则 created 为 false 并携带既有记录
   */
  DaemonInvocationJournalStart start(String invocationId);

  /** 查询当前记录。 */
  Optional<DaemonInvocationJournalEntry> find(String invocationId);

  /**
   * 仅将处于 RUNNING 状态的记录原子转换为终态（COMPLETED、FAILED 或 CANCELLED）。
   *
   * <p>终态不可逆；任何重复、迟到或并发终态回调必须返回 false，确保 terminal-once 契约。
   *
   * @param invocationId 唯一调用标识
   * @param terminalMessage 终态报文
   * @return 若且仅若成功将状态从 RUNNING 跃迁为终态时返回 true，若已为终态或不存在则返回 false
   */
  boolean complete(String invocationId, DaemonTerminalMessage terminalMessage);
}
