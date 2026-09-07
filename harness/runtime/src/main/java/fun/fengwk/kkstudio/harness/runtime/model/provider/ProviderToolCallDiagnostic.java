package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 模型输出被截断或无法构成合法 ToolCall 时的安全诊断信息。 */
public record ProviderToolCallDiagnostic(
    int callIndex, String id, String name, String partialArguments, String message) {

  public ProviderToolCallDiagnostic {
    if (callIndex < 0) {
      throw new IllegalArgumentException("callIndex must not be negative");
    }
    if (id != null && id.isBlank()) {
      throw new IllegalArgumentException("id must be null or non-blank");
    }
    if (name != null && name.isBlank()) {
      throw new IllegalArgumentException("name must be null or non-blank");
    }
    partialArguments = partialArguments == null ? "" : partialArguments;
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
  }
}
