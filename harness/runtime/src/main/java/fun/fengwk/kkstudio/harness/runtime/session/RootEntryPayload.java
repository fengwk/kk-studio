package fun.fengwk.kkstudio.harness.runtime.session;

/** Session 语义根 Entry；保证 Main Thread 拥有稳定非空 head，不携带运行时配置。 */
public record RootEntryPayload() implements SessionEntryPayload {
  @Override
  public SessionEntryType type() {
    return SessionEntryType.ROOT;
  }
}
