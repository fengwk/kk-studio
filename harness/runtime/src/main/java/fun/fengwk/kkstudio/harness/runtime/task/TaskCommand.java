package fun.fengwk.kkstudio.harness.runtime.task;

/** Strict, normalized task tool arguments. */
public record TaskCommand(String subagentType, String prompt, WorkingCopyPolicy workingCopyPolicy) {
  public TaskCommand {
    subagentType = requireNonBlank(subagentType, "subagent_type");
    prompt = requireNonBlank(prompt, "prompt");
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
