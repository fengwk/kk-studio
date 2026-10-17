package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * 一条受管更新命令：Platform 在专用管理通道上向 Daemon 下发唯一更新目标。
 *
 * <p>该命令只用于已受管安装：{@code artifactUrl} 被绑定到官方发布形态（ {@code
 * https://github.com/fengwk/kk-studio/releases/download/<tag>/kk-studio-daemon-<tag>-<os>-<arch>.tar.gz}），Daemon
 * 在下载前再次校验 URL 形状、SHA256 与本机 os/arch 对应的官方归档；不接受浏览者提供的任意 URL 或 "latest"。{@code operationId} 是幂等键：同一
 * operation 的重发不得产生第二次下载或第二次 handoff。
 */
public record DaemonUpdateCommand(
    String operationId, String targetVersion, String artifactUrl, String artifactSha256) {

  public DaemonUpdateCommand {
    operationId = validateOperationId(operationId);
    targetVersion = DaemonCapabilities.validateDaemonVersion(targetVersion);
    artifactUrl = validateArtifactUrl(targetVersion, artifactUrl);
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
   * 校验官方发布制品 URL：固定官方 Release 仓库、发布矩阵内的平台，且 Release tag、归档文件名版本与 {@code targetVersion} 必须是同一个版本事实。
   *
   * <p>这是「不下载任意 URL / 不下错版本」的 wire 边界防御：即使命令来自被攻破的 Platform，Daemon 也只连接官方发布地址的直接下载路径，
   * 且只下载与目标版本一致的归档。URL 形态由 {@link DaemonUpdateArtifact#ARTIFACT_URL_PATTERN} 强制 tag 与归档版本逐字相等；这里再与
   * {@code targetVersion} 比较，使三者不一致在构造与 codec 边界同样被拒绝。
   */
  public static String validateArtifactUrl(String targetVersion, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("artifactUrl must not be blank");
    }
    Matcher matcher = DaemonUpdateArtifact.ARTIFACT_URL_PATTERN.matcher(value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "artifactUrl must be the official fengwk/kk-studio daemon distribution archive");
    }
    if (!targetVersion.equals(matcher.group(1))) {
      throw new IllegalArgumentException(
          "artifactUrl release tag, artifact version and targetVersion must be the same version");
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
