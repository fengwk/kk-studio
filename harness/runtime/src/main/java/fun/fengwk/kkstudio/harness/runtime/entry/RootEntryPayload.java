package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.kernel.session.EntryType;

/** Session 语义根 Entry；保证 Main Thread 拥有稳定非空 head，不携带运行时配置。 */
public record RootEntryPayload() implements RuntimeEntryPayload {

  @Override
  public EntryType type() {
    return EntryType.ROOT;
  }
}
