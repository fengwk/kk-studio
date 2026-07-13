package fun.fengwk.kkstudio.harness.runtime.session;

public record LabelEntryPayload(String label) implements SessionEntryPayload {
  public LabelEntryPayload {
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("label must not be blank");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.LABEL;
  }
}
