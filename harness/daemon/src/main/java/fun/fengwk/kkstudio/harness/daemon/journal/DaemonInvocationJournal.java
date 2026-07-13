package fun.fengwk.kkstudio.harness.daemon.journal;

import java.util.Optional;

/**
 * invocation 去重和终态重放的最小 journal 端口。
 *
 * <p>实现可替换为持久化存储；Runtime 不把当前 WebSocket 连接当作 invocation 事实源。
 */
public interface DaemonInvocationJournal {

  /** 原子创建 RUNNING 记录，或返回已有记录。 */
  DaemonInvocationJournalStart start(String invocationId);

  /** 查询当前记录。 */
  Optional<DaemonInvocationJournalEntry> find(String invocationId);

  /** 仅将 RUNNING 记录转换为终态；重复或迟到回调必须返回 false。 */
  boolean complete(String invocationId, DaemonTerminalMessage terminalMessage);
}
