package fun.fengwk.kkstudio.harness.runtime.session;

public record ModelChangeEntryPayload(String modelId, String variant) implements SessionEntryPayload {
  public ModelChangeEntryPayload {
    if (modelId == null || modelId.isBlank()) {
      throw new IllegalArgumentException("modelId must not be blank");
    }
    if (variant == null || variant.isBlank()) {
      throw new IllegalArgumentException("variant must not be blank");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.MODEL_CHANGE;
  }
}
