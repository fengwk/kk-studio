package fun.fengwk.kkstudio.platform.daemon;

import java.util.Objects;

/**
 * 官方 Daemon 发布制品的固定形状：仓库、tag、按 os/arch 的归档名、校验文件与 URL。
 *
 * <p>Platform 用它构造受管更新目标；发布工作流按同一形状产出 Release 资产。它刻意不提供 "latest" 或任意 URL 解析——目标版本与平台必须由 Platform
 * 明确给出。
 */
public final class DaemonUpdateArtifact {

  /** 官方 Release 下载基址；只允许该仓库。 */
  public static final String RELEASE_BASE = "https://github.com/fengwk/kk-studio/releases/download";

  private DaemonUpdateArtifact() {}

  /** 发布 tag：{@code v<version>}。 */
  public static String tag(String version) {
    DaemonReadyMetadata.validateDaemonVersion(version);
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
    DaemonReadyMetadata.validateDaemonVersion(version);
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
}
