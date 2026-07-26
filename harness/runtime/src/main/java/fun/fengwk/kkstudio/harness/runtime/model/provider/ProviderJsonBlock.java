package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 保留 Provider 或 Tool 返回的结构化 JSON 内容。 */
public record ProviderJsonBlock(String json) implements ProviderContentBlock {

  public ProviderJsonBlock {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("json must not be blank");
    }
  }
}
