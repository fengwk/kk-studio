package fun.fengwk.kkstudio.harness.runtime.configuration;

/**
 * 冻结的 environment / workspace reference 快照。两个字段都可空；present 时非空白且不允许首尾空白。
 *
 * <p>不存在 "secret value" 字段；credential 走 {@code ModelDescriptor.providerResourceId}。
 */
public record EnvironmentSnapshot(String environmentName, String workspaceReference) {

  public EnvironmentSnapshot {
    environmentName = canonicalizeOptional(environmentName, "environmentName");
    workspaceReference = canonicalizeOptional(workspaceReference, "workspaceReference");
  }

  private static String canonicalizeOptional(String value, String name) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank when present");
    }
    if (!value.equals(value.trim())) {
      throw new IllegalArgumentException(name + " must not have leading or trailing whitespace");
    }
    return value;
  }
}
