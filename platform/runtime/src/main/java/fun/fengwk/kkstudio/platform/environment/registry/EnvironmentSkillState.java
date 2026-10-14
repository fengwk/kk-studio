package fun.fengwk.kkstudio.platform.environment.registry;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Environment {@code skill_state} 中单个 Skill Package 的类型化同步投影。
 *
 * <p>{@code installedCommit} 与 {@code localPath} 只由成功安装推进；同步失败时它们保留上一次成功安装的事实（可能为空）， {@code status}
 * 推进为 {@code failed} 并记录去敏后的有界错误，因此失败的同步绝不伪造成「已安装」。
 *
 * <p>{@code localPath} 是可重建的本地事实：只在 {@code status = installed} 且 {@code installedCommit} 与 Package
 * 的 currentCommit 完全一致时才可用于模型可见路径，否则必须回退 platform URI。
 */
public record EnvironmentSkillState(
    String packageName, String status, String installedCommit, String localPath, String error) {

  /** 同步成功且本地树对应 {@code installedCommit}。 */
  public static final String STATUS_INSTALLED = "installed";

  /** 最近一次同步失败；{@code installedCommit}/{@code localPath} 可能仍是更早的可用安装。 */
  public static final String STATUS_FAILED = "failed";

  /** 错误摘要的字符上限；摘要必须去敏，绝不承载 stdout、凭据或带 userinfo 的 Git URL。 */
  public static final int MAX_ERROR_CHARS = 512;

  private static final int MAX_PACKAGE_NAME_CHARS = 128;
  private static final Pattern COMMIT_PATTERN = Pattern.compile("^[0-9a-f]{40}$|^[0-9a-f]{64}$");

  public EnvironmentSkillState {
    packageName = requirePackageName(packageName);
    status = requireStatus(status);
    if (installedCommit != null && !COMMIT_PATTERN.matcher(installedCommit).matches()) {
      throw new IllegalArgumentException(
          "installedCommit must be 40 or 64 lowercase hex characters");
    }
    if (localPath != null && localPath.isBlank()) {
      throw new IllegalArgumentException("localPath must not be blank when present");
    }
    error = requireError(error);
    if (STATUS_INSTALLED.equals(status)) {
      if (installedCommit == null || localPath == null) {
        throw new IllegalArgumentException(
            "installed skill state requires both installedCommit and localPath");
      }
      if (error != null) {
        throw new IllegalArgumentException("installed skill state must not carry an error");
      }
    } else if (error == null) {
      throw new IllegalArgumentException("failed skill state requires an error summary");
    }
  }

  /** 同步成功的投影。 */
  public static EnvironmentSkillState installed(
      String packageName, String installedCommit, String localPath) {
    return new EnvironmentSkillState(
        packageName, STATUS_INSTALLED, installedCommit, localPath, null);
  }

  /** 同步失败的投影：{@code previous} 为同一 Package 的既有投影时保留其已安装事实，否则记录为从未成功安装。 */
  public static EnvironmentSkillState failed(
      String packageName, EnvironmentSkillState previous, String error) {
    String installedCommit = previous == null ? null : previous.installedCommit();
    String localPath = previous == null ? null : previous.localPath();
    return new EnvironmentSkillState(packageName, STATUS_FAILED, installedCommit, localPath, error);
  }

  /** 该投影是否可用于给模型注入本地稳定路径。 */
  public boolean isInstalledAt(String commit) {
    return commit != null && STATUS_INSTALLED.equals(status) && commit.equals(installedCommit);
  }

  private static String requirePackageName(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("packageName must not be blank");
    }
    if (value.length() > MAX_PACKAGE_NAME_CHARS) {
      throw new IllegalArgumentException(
          "packageName must not exceed " + MAX_PACKAGE_NAME_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("packageName must not contain control characters");
    }
    return value;
  }

  private static String requireStatus(String value) {
    if (!STATUS_INSTALLED.equals(value) && !STATUS_FAILED.equals(value)) {
      throw new IllegalArgumentException("unknown skill state status: " + value);
    }
    return value;
  }

  private static String requireError(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = Objects.requireNonNull(value.strip(), "error");
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("error must not be blank when present");
    }
    if (trimmed.length() > MAX_ERROR_CHARS) {
      throw new IllegalArgumentException(
          "error must not exceed " + MAX_ERROR_CHARS + " characters");
    }
    if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("error must not contain control characters");
    }
    return trimmed;
  }
}
