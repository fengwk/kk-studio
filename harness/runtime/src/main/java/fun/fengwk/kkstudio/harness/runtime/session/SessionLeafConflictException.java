package fun.fengwk.kkstudio.harness.runtime.session;

/** 原子 append 的 leaf CAS 失败。 */
public class SessionLeafConflictException extends RuntimeException {
  public SessionLeafConflictException(String sessionId, String expectedLeafEntryId) {
    super("session leaf changed: sessionId=" + sessionId + ", expectedLeafEntryId=" + expectedLeafEntryId);
  }
}
