package fun.fengwk.kkstudio.harness.environment.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/** 文件变更的执行前置条件：SHA 为 null 表示目标在审查时不存在；不进入模型 arguments。 */
public record ExpectedFile(String path, String sha256) {

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public ExpectedFile {
    Objects.requireNonNull(path, "path");
    if (path.isBlank()) {
      throw new IllegalArgumentException("expectedFile.path must not be blank");
    }
    if (sha256 != null && !SHA256.matcher(sha256).matches()) {
      throw new IllegalArgumentException("expectedFile.sha256 must be lowercase SHA-256");
    }
  }
}
