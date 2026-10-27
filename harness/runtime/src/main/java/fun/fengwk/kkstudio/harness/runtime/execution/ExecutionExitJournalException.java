package fun.fengwk.kkstudio.harness.runtime.execution;

/** 退出确认 journal 无法安全写入、读取或删除。调用方必须保持 UNCONFIRMED，不能宣称 durable retry。 */
public class ExecutionExitJournalException extends RuntimeException {

  public ExecutionExitJournalException(String message) {
    super(message);
  }

  public ExecutionExitJournalException(String message, Throwable cause) {
    super(message, cause);
  }
}
