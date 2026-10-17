package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 官方 Daemon 发布制品的固定形状：仓库、tag、按 os/arch 的归档名、校验文件与 URL 模板。
 *
 * <p>Platform 与 Daemon 共享这一事实源：Platform 用它构造受管更新目标，Daemon 用同一模式在下载前校验 URL 是否就是本机 os/arch 的官方归档。
 * 它刻意不提供 "latest" 或任意 URL 解析——目标版本与平台必须由 Platform 明确给出。
 *
 * <p><b>确定性退役清单（最终不可保留）：</b>以下成员只为当前仍存活的旧 JAR 产品路径提供过渡期编译，必须在父合并 N4 更新服务并删除 JavaDaemon 后统一移除：
 *
 * <ul>
 *   <li>{@link #LEGACY_JAR_URL_PATTERN}
 *   <li>{@link #jarName(String)}
 *   <li>{@link #artifactUrl(String)}
 *   <li>{@link #checksumUrl(String)}
 * </ul>
 *
 * <p>退役后本类只剩五平台 tar.gz 形状；{@code DaemonUpdateCommand} 与 Node UPDATE 入口都只接受 {@link
 * #ARTIFACT_URL_PATTERN}。
 */
public final class DaemonUpdateArtifact {

  /** 官方 Release 下载基址；只允许该仓库。 */
  public static final String RELEASE_BASE = "https://github.com/fengwk/kk-studio/releases/download";

  /** 官方 Daemon 制品 URL：固定仓库 + tag + 按 os/arch 的归档名，且只覆盖发布矩阵内的平台。 */
  public static final Pattern ARTIFACT_URL_PATTERN =
      Pattern.compile(
          "^https://github\\.com/fengwk/kk-studio/releases/download/v[0-9A-Za-z._+-]{1,64}/"
              + "kk-studio-daemon-v[0-9A-Za-z._+-]{1,64}-"
              + "((linux|macos)-(amd64|arm64)|windows-amd64)\\.tar\\.gz$");

  /**
   * 旧 JavaDaemon JAR 制品 URL 形状。
   *
   * <p>仅用于仍待删除的 JavaDaemon 产品路径的过渡期编译；正式 Node 更新只接受 {@link #ARTIFACT_URL_PATTERN} 的五平台归档。
   */
  public static final Pattern LEGACY_JAR_URL_PATTERN =
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

  /**
   * 归档中的 OS 段。
   *
   * <p>WSL 直接运行 Linux 二进制，与 {@code linux} 共享同一发行归档；其余取值与 READY {@code operatingSystem} 一致。
   */
  public static String osSegment(DaemonOperatingSystem operatingSystem) {
    return switch (Objects.requireNonNull(operatingSystem, "operatingSystem")) {
      case LINUX, WSL -> "linux";
      case MACOS -> "macos";
      case WINDOWS -> "windows";
    };
  }

  /** 官方发布矩阵是否覆盖该宿主平台；未覆盖时不猜测替代归档。 */
  public static boolean isSupported(
      DaemonOperatingSystem operatingSystem, DaemonArchitecture architecture) {
    return !(operatingSystem == DaemonOperatingSystem.WINDOWS
        && architecture == DaemonArchitecture.ARM64);
  }

  /** 制品文件名：{@code kk-studio-daemon-<tag>-<os>-<arch>.tar.gz}。 */
  public static String artifactName(
      String version, DaemonOperatingSystem operatingSystem, DaemonArchitecture architecture) {
    DaemonCapabilities.validateDaemonVersion(version);
    Objects.requireNonNull(architecture, "architecture");
    if (!isSupported(operatingSystem, architecture)) {
      throw new IllegalArgumentException(
          "no official daemon artifact for "
              + osSegment(operatingSystem)
              + "-"
              + architecture.wireValue());
    }
    return "kk-studio-daemon-"
        + tag(version)
        + "-"
        + osSegment(operatingSystem)
        + "-"
        + architecture.wireValue()
        + ".tar.gz";
  }

  /** 发布元数据文件名：{@code kk-studio-daemon-<tag>-<os>-<arch>.json}。 */
  public static String metadataName(
      String version, DaemonOperatingSystem operatingSystem, DaemonArchitecture architecture) {
    return "kk-studio-daemon-"
        + tag(version)
        + "-"
        + osSegment(operatingSystem)
        + "-"
        + Objects.requireNonNull(architecture, "architecture").wireValue()
        + ".json";
  }

  /** 制品下载 URL。 */
  public static String artifactUrl(
      String version, DaemonOperatingSystem operatingSystem, DaemonArchitecture architecture) {
    return RELEASE_BASE
        + "/"
        + tag(version)
        + "/"
        + artifactName(version, operatingSystem, architecture);
  }

  /** 校验文件下载 URL。 */
  public static String checksumUrl(
      String version, DaemonOperatingSystem operatingSystem, DaemonArchitecture architecture) {
    return artifactUrl(version, operatingSystem, architecture) + ".sha256";
  }

  /** 旧 JavaDaemon JAR 制品文件名；JavaDaemon 删除后随本方法一起移除。 */
  public static String jarName(String version) {
    DaemonCapabilities.validateDaemonVersion(version);
    return "kk-studio-daemon-" + tag(version) + ".jar";
  }

  /** 旧 JavaDaemon JAR 制品 URL；JavaDaemon 删除后随本方法一起移除。 */
  public static String artifactUrl(String version) {
    return RELEASE_BASE + "/" + tag(version) + "/" + jarName(version);
  }

  /** 旧 JavaDaemon JAR 校验 URL；JavaDaemon 删除后随本方法一起移除。 */
  public static String checksumUrl(String version) {
    return artifactUrl(version) + ".sha256";
  }
}
