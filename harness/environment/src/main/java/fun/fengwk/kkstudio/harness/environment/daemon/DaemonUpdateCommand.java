package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;
import java.util.UUID;

/**
 * 一条受管更新命令：Platform 在专用管理通道上向 Daemon 下发唯一更新目标。
 *
 * <p>该命令只用于已受管安装：{@code artifactUrl} 被绑定到官方发布形态（ {@code
 * https://github.com/fengwk/kk-studio/releases/download/<tag>/kk-studio-daemon-<tag>.jar}），Daemon
 * 在下载前再次校验 URL 形状、SHA256 与 JAR manifest 版本；不接受浏览者提供的任意 URL 或 "latest"。{@code operationId} 是幂等键：同一
 * operation 的重发不得产生第二次下载或第二次 handoff。
 */
public record DaemonUpdateCommand(
    String operationId, String targetVersion, String artifactUrl, String artifactSha256) {

  public DaemonUpdateCommand {
    operationId = validateOperationId(operationId);
    targetVersion = DaemonCapabilities.validateDaemonVersion(targetVersion);
    artifactUrl = validateArtifactUrl(artifactUrl);
    artifactSha256 = validateSha256(artifactSha256);
  }

  /**
   * 校验 operationId 为规范小写 UUID。
   *
   * <p>它会被用作磁盘暂存子目录名，因此必须是固定、无路径语义的规范 UUID；任何 {@code ../}、绝对路径、大小写或形态偏差都拒绝， 避免命令把更新写进数据目录之外。
   */
  public static String validateOperationId(String value) {
    Objects.requireNonNull(value, "operationId");
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("operationId must be a canonical UUID");
    }
    String canonical = parsed.toString();
    if (!canonical.equals(value)) {
      throw new IllegalArgumentException("operationId must be a canonical lowercase UUID");
    }
    return value;
  }

  /**
   * 校验官方发布制品 URL 形状：只接受固定官方 Release 仓库与 tag 前缀。
   *
   * <p>这是「不下载任意 URL」的 wire 边界防御：即使命令来自被攻破的 Platform，Daemon 也只连接官方发布地址的直接下载路径。
   */
  public static String validateArtifactUrl(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("artifactUrl must not be blank");
    }
    if (!DaemonUpdateArtifact.ARTIFACT_URL_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "artifactUrl must be the official fengwk/kk-studio release download for a daemon jar");
    }
    return value;
  }

  /** 校验 SHA256 为 64 位十六进制文本。 */
  public static String validateSha256(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("artifactSha256 must not be blank");
    }
    if (!DaemonUpdateArtifact.SHA256_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("artifactSha256 must be 64 hex characters");
    }
    return value;
  }
}
