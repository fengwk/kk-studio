package fun.fengwk.kkstudio.harness.runtime.entry;

/** 不投影到 Provider Context 的路径标签 Entry。 */
public record LabelEntryPayload(String label) implements RuntimeEntryPayload {

  public LabelEntryPayload {
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("label must not be blank");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.LABEL;
  }
}
