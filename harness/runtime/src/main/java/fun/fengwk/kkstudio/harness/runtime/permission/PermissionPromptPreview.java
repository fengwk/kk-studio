package fun.fengwk.kkstudio.harness.runtime.permission;

/** 持久 permission_requested prompt 摘要。 */
public record PermissionPromptPreview(String tool, String workdir, String arguments) {
  public PermissionPromptPreview {
    tool = requireNonBlank(tool, "tool");
    workdir = requireNonBlank(workdir, "workdir");
    arguments = requireNonBlank(arguments, "arguments");
    if (!isSingleLine(workdir)) {
      throw new IllegalArgumentException("workdir preview must be a single line");
    }
    if (arguments.length() > 120 || !isSingleLine(arguments)) {
      throw new IllegalArgumentException(
          "arguments preview must be a single line of at most 120 chars");
    }
  }

  private static boolean isSingleLine(String value) {
    return value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\t') < 0;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
