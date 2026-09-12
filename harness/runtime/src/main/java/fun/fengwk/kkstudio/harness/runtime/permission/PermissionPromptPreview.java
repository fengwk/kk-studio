package fun.fengwk.kkstudio.harness.runtime.permission;

/**
 * 持久 permission_requested prompt 摘要。
 *
 * <p>{@code workdir} 可空：只有该调用真实携带 {@code arguments.workdir} 时才有值；没有 workdir 语义的工具（例如 {@code
 * load_skill}、MCP）显示 null，不产生任何虚构默认目录。
 */
public record PermissionPromptPreview(String tool, String workdir, String arguments) {

  public PermissionPromptPreview {
    tool = requireNonBlank(tool, "tool");
    if (workdir != null) {
      workdir = requireNonBlank(workdir, "workdir");
      if (!isSingleLine(workdir)) {
        throw new IllegalArgumentException("workdir preview must be a single line");
      }
    }
    arguments = requireNonBlank(arguments, "arguments");
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
