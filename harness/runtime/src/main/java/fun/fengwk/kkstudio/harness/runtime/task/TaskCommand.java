package fun.fengwk.kkstudio.harness.runtime.task;

/** Strict, normalized task tool arguments. */
public record TaskCommand(
    String subagentType, String prompt, Long sessionId, WorkingCopyPolicy workingCopyPolicy) {
  public TaskCommand {
    subagentType = requireNonBlank(subagentType, "subagent_type");
    prompt = requireNonBlank(prompt, "prompt");
    if (sessionId != null && sessionId <= 0) {
      throw new IllegalArgumentException("session_id must be positive when present");
    }
    workingCopyPolicy =
        workingCopyPolicy == null ? WorkingCopyPolicy.defaultFor(subagentType) : workingCopyPolicy;
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
