package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 四种稳定 Provider terminal replay 格式。 */
public enum ProviderReplayFormat {
  ANTHROPIC_MESSAGES("anthropic_messages"),
  OPENAI_RESPONSES("openai_responses"),
  OPENAI_CHAT("openai_chat"),
  GEMINI_CONTENT("gemini_content");

  private final String wireValue;

  ProviderReplayFormat(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static ProviderReplayFormat fromWireValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("provider replay format wire value must not be null");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("provider replay format wire value must not be blank");
    }
    for (ProviderReplayFormat format : values()) {
      if (format.wireValue.equals(value) || format.name().equalsIgnoreCase(value)) {
        return format;
      }
    }
    throw new IllegalArgumentException("unsupported provider replay format: " + value);
  }
}
