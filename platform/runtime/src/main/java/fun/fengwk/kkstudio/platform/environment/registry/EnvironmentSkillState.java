package fun.fengwk.kkstudio.platform.environment.registry;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Environment {@code skill_state} 中单个 Skill Package 的安装投影：{@code {packageName, installedSha256,
 * localPath, error}}。
 *
 * <p>{@code installedSha256}/{@code localPath} 只由成功安装推进；同步失败时它们保留上一次成功安装的事实（可能为空）， {@code error}
 * 记录去敏后的有界错误，因此失败的同步绝不伪造成「已安装」。{@code localPath} 只在 {@code installedSha256} 等于 Package
 * 当前归档摘要时才可用于模型可见路径，否则必须回退 platform URI。
 */
public record EnvironmentSkillState(
    String packageName, String installedSha256, String localPath, String error) {

  /** 错误摘要的字符上限；摘要必须去敏，绝不承载 stdout、凭据或带 userinfo 的 Git URL。 */
  public static final int MAX_ERROR_CHARS = 512;

  private static final int MAX_PACKAGE_NAME_CHARS = 128;
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

  public EnvironmentSkillState {
    packageName = requirePackageName(packageName);
    if (installedSha256 != null && !SHA256.matcher(installedSha256).matches()) {
      throw new IllegalArgumentException("installedSha256 must be 64 lowercase hex characters");
    }
    if (localPath != null && localPath.isBlank()) {
      throw new IllegalArgumentException("localPath must not be blank when present");
    }
    if ((installedSha256 == null) != (localPath == null)) {
      throw new IllegalArgumentException(
          "installedSha256 and localPath must both be present or both be null");
    }
    error = requireError(error);
  }

  /** 同步成功的投影。 */
  public static EnvironmentSkillState installed(
      String packageName, String installedSha256, String localPath) {
    return new EnvironmentSkillState(packageName, installedSha256, localPath, null);
  }

  /** 同步失败的投影：{@code previous} 为同一 Package 的既有投影时保留其已安装事实，否则记录为从未成功安装。 */
  public static EnvironmentSkillState failed(
      String packageName, EnvironmentSkillState previous, String error) {
    String installedSha256 = previous == null ? null : previous.installedSha256();
    String localPath = previous == null ? null : previous.localPath();
    return new EnvironmentSkillState(packageName, installedSha256, localPath, error);
  }

  /** 该投影是否可用于给模型注入本地稳定路径。 */
  public boolean isInstalledAt(String sha256) {
    return sha256 != null && error == null && sha256.equals(installedSha256);
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
