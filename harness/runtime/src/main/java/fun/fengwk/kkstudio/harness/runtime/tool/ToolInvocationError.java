package fun.fengwk.kkstudio.harness.runtime.tool;

/** 持久化 ToolInvocation 终态错误的最小语义快照。 */
public record ToolInvocationError(String kind, String message) {

  public ToolInvocationError {
    kind = requireNonBlank(kind, "kind");
    message = requireNonBlank(message, "message");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
