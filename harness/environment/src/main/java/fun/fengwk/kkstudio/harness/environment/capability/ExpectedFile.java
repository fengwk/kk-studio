package fun.fengwk.kkstudio.harness.environment.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/** 原文件的字节级快照；作为受信执行元数据传输，不进入模型可见的 arguments。 */
public record ExpectedFile(String path, String sha256) {

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public ExpectedFile {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(sha256, "sha256");
    if (path.isBlank()) {
      throw new IllegalArgumentException("expectedFile.path must not be blank");
    }
    if (!SHA256.matcher(sha256).matches()) {
      throw new IllegalArgumentException("expectedFile.sha256 must be lowercase SHA-256");
    }
  }
}
