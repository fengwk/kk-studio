package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** Provider 实现类型及其 Catalog 持久化 wire 值。 */
public enum ProviderType {
  OPENAI("openai"),
  OPENAI_RESPONSES("openai_response"),
  ANTHROPIC("anthropic"),
  GOOGLE("google");

  private final String wireValue;

  ProviderType(String wireValue) {
    this.wireValue = wireValue;
  }

  /** 返回 Catalog / HTTP / database 使用的稳定 wire 值。 */
  public String wireValue() {
    return wireValue;
  }

  /**
   * 严格解析 Catalog / HTTP / database wire 值。
   *
   * <p>解析不做 trim 或大小写折叠，避免把非法持久化数据静默修复为另一个 Provider。
   */
  public static ProviderType fromWireValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("provider type wire value must not be null");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("provider type wire value must not be blank");
    }
    for (ProviderType providerType : values()) {
      if (providerType.wireValue.equals(value)) {
        return providerType;
      }
    }
    throw new IllegalArgumentException("unsupported provider type wire value: " + value);
  }
}
