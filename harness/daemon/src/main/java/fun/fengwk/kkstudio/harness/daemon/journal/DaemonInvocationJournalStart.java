package fun.fengwk.kkstudio.harness.daemon.journal;

import java.util.Objects;

/** 原子开始 invocation 的结果；{@code created} 为 false 表示重复消息。 */
public record DaemonInvocationJournalStart(boolean created, DaemonInvocationJournalEntry entry) {

  public DaemonInvocationJournalStart {
    entry = Objects.requireNonNull(entry, "entry");
  }
}
