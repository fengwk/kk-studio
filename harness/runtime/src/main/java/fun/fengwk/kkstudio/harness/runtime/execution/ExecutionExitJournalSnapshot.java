package fun.fengwk.kkstudio.harness.runtime.execution;

import java.util.List;
import java.util.Objects;

/** 一次 journal 读取的不可变快照：健康记录与逐文件失败互不阻断。 */
public record ExecutionExitJournalSnapshot(
    List<ExecutionExitJournalRecord> records, List<ExecutionExitJournalFailure> failures) {

  public ExecutionExitJournalSnapshot {
    records = List.copyOf(Objects.requireNonNull(records, "records"));
    failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
  }
}
