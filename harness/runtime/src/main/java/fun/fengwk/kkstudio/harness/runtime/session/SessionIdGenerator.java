package fun.fengwk.kkstudio.harness.runtime.session;

/** Session/Entry 标识生成端口。 */
public interface SessionIdGenerator {
  String newSessionId();

  String newEntryId();
}
