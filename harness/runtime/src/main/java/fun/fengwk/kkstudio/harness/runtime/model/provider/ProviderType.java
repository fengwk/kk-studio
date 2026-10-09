package fun.fengwk.kkstudio.harness.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;

import java.util.Set;

/**
 * Provider 实现类型及其 Catalog 持久化 wire 值。
 *
 * <p>每个类型同时声明其协议编码器实际可编码的内联媒体模态（用户内容与工具结果分别声明）。这是协议事实：具体模型是否接受仍由模型输入模态约束， 具体连接配置是否允许仍由 adapter
 * 决定。Adapter 的 {@link ProviderAdapter#mediaCapabilities()} 必须与本声明一致，因此这里作为唯一事实源。
 */
public enum ProviderType {
  /** OpenAI Chat Completions：用户内容支持 IMAGE/AUDIO/DOCUMENT；tool message 只接受文本，不支持任何工具结果媒体。 */
  OPENAI(
      "openai",
      Set.of(ModelInputModality.IMAGE, ModelInputModality.AUDIO, ModelInputModality.DOCUMENT),
      Set.of()),

  /** OpenAI Responses：用户内容与工具结果都支持 IMAGE/DOCUMENT。 */
  OPENAI_RESPONSES(
      "openai_response",
      Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
      Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT)),

  /** Anthropic Messages：用户内容与工具结果都支持 IMAGE/DOCUMENT。 */
  ANTHROPIC(
      "anthropic",
      Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
      Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT)),

  /** Gemini GenerateContent：用户内容支持 IMAGE/AUDIO/VIDEO/DOCUMENT；工具结果只支持 IMAGE/DOCUMENT。 */
  GOOGLE(
      "google",
      Set.of(
          ModelInputModality.IMAGE,
          ModelInputModality.AUDIO,
          ModelInputModality.VIDEO,
          ModelInputModality.DOCUMENT),
      Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT));

  private final String wireValue;
  private final ProviderMediaCapabilities mediaCapabilities;

  ProviderType(
      String wireValue,
      Set<ModelInputModality> userModalities,
      Set<ModelInputModality> toolResultModalities) {
    this.wireValue = wireValue;
    this.mediaCapabilities = new ProviderMediaCapabilities(userModalities, toolResultModalities);
  }

  /** 返回 Catalog / HTTP / database 使用的稳定 wire 值。 */
  public String wireValue() {
    return wireValue;
  }

  /** 本协议编码器实际可编码的内联媒体模态（用户内容与工具结果）。 */
  public ProviderMediaCapabilities mediaCapabilities() {
    return mediaCapabilities;
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
