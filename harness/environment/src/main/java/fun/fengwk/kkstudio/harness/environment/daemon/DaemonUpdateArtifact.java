package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.regex.Pattern;

/**
 * 官方 Daemon 发布制品的固定形状：仓库、tag、制品名、校验文件与 URL 模板。
 *
 * <p>Platform 与 Daemon 共享这一事实源：Platform 用它构造受管更新目标，Daemon 用同一模式在下载前校验 URL。它刻意不提供 "latest" 或任意 URL
 * 解析——目标版本必须由 Platform 明确给出。
 */
public final class DaemonUpdateArtifact {

  /** 官方 Release 下载基址；只允许该仓库。 */
  public static final String RELEASE_BASE = "https://github.com/fengwk/kk-studio/releases/download";

  /** 官方 Daemon 制品 URL：固定仓库 + tag + 制品名。 */
  public static final Pattern ARTIFACT_URL_PATTERN =
      Pattern.compile(
          "^https://github\\.com/fengwk/kk-studio/releases/download/v[0-9A-Za-z._+-]{1,64}/"
              + "kk-studio-daemon-v[0-9A-Za-z._+-]{1,64}\\.jar$");

  /** SHA256 文本形状。 */
  public static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

  private DaemonUpdateArtifact() {}

  /** 发布 tag：{@code v<version>}。 */
  public static String tag(String version) {
    DaemonCapabilities.validateDaemonVersion(version);
    return "v" + version;
  }

  /** 制品文件名：{@code kk-studio-daemon-v<version>.jar}。 */
  public static String jarName(String version) {
    DaemonCapabilities.validateDaemonVersion(version);
    return "kk-studio-daemon-" + tag(version) + ".jar";
  }

  /** 制品下载 URL。 */
  public static String artifactUrl(String version) {
    return RELEASE_BASE + "/" + tag(version) + "/" + jarName(version);
  }

  /** 校验文件下载 URL。 */
  public static String checksumUrl(String version) {
    return artifactUrl(version) + ".sha256";
  }
}
