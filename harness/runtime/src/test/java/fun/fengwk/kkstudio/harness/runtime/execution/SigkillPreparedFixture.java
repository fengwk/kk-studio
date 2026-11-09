package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;

import java.nio.file.Path;
import java.util.UUID;

/**
 * SIGKILL 恢复测试子进程：写入一条 PREPARED 后等待被父进程强杀。
 *
 * <p>子进程只负责制造真实的进程内 journal 状态与内核文件锁，不连接业务数据库。
 */
public final class SigkillPreparedFixture {

  private SigkillPreparedFixture() {}

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    LocalExecutionIdentity identity =
        new LocalExecutionIdentity(
            UUID.fromString(args[1]),
            UUID.fromString(args[2]),
            UUID.fromString(args[3]),
            1,
            UUID.fromString(args[4]),
            ExecutionExitKind.TOOL);
    FileExecutionExitJournal journal = new FileExecutionExitJournal(directory);
    journal.write(ExecutionExitJournalRecord.prepared(identity));
    System.out.println("PREPARED");
    System.out.flush();
    Thread.sleep(Long.MAX_VALUE);
  }
}
