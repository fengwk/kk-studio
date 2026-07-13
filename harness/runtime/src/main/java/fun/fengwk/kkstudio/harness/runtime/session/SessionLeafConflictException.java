package fun.fengwk.kkstudio.harness.runtime.session;

/** 原子 append 或 checkout 的 leaf/version CAS 失败。 */
public class SessionLeafConflictException extends RuntimeException {
  public SessionLeafConflictException(long sessionId, Long expectedLeafEntryId) {
    super(
        "session leaf changed: sessionId="
            + sessionId
            + ", expectedLeafEntryId="
            + expectedLeafEntryId);
  }
}
