package fun.fengwk.kkstudio.harness.model.provider;

/** Tool-name reference removed inline from a mid-conversation system message. */
public record ProviderSystemToolRemovalBlock(String name) implements ProviderContentBlock {

  public ProviderSystemToolRemovalBlock {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }
}
