package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 意图：官方发布归档名必须与打包矩阵（{@code linux/macos/windows} × {@code amd64/arm64}，windows 仅 amd64）逐字一致，
 * 并与发布工作流、{@code prepare-release.sh} 及 Node 更新器共享同一形状；未覆盖平台明确失败，绝不猜测替代归档。
 */
class DaemonUpdateArtifactTest {

  private static final String VERSION = "1.0.12";
  private static final String BASE =
      "https://github.com/fengwk/kk-studio/releases/download/v1.0.12/";

  @Test
  void namesMatchTheReleaseMatrix() {
    assertEquals(
        "kk-studio-daemon-v1.0.12-linux-amd64.tar.gz",
        DaemonUpdateArtifact.artifactName(
            VERSION, DaemonOperatingSystem.LINUX, DaemonArchitecture.AMD64));
    assertEquals(
        "kk-studio-daemon-v1.0.12-linux-arm64.tar.gz",
        DaemonUpdateArtifact.artifactName(
            VERSION, DaemonOperatingSystem.LINUX, DaemonArchitecture.ARM64));
    assertEquals(
        "kk-studio-daemon-v1.0.12-macos-amd64.tar.gz",
        DaemonUpdateArtifact.artifactName(
            VERSION, DaemonOperatingSystem.MACOS, DaemonArchitecture.AMD64));
    assertEquals(
        "kk-studio-daemon-v1.0.12-macos-arm64.tar.gz",
        DaemonUpdateArtifact.artifactName(
            VERSION, DaemonOperatingSystem.MACOS, DaemonArchitecture.ARM64));
    assertEquals(
        "kk-studio-daemon-v1.0.12-windows-amd64.tar.gz",
        DaemonUpdateArtifact.artifactName(
            VERSION, DaemonOperatingSystem.WINDOWS, DaemonArchitecture.AMD64));
  }

  /** WSL 运行 Linux 二进制，必须与 linux 共享同一归档，而不是落进未覆盖平台。 */
  @Test
  void wslSharesTheLinuxArtifact() {
    assertEquals(
        DaemonUpdateArtifact.artifactName(
            VERSION, DaemonOperatingSystem.LINUX, DaemonArchitecture.AMD64),
        DaemonUpdateArtifact.artifactName(
            VERSION, DaemonOperatingSystem.WSL, DaemonArchitecture.AMD64));
    assertTrue(
        DaemonUpdateArtifact.isSupported(DaemonOperatingSystem.WSL, DaemonArchitecture.ARM64));
  }

  @Test
  void urlsAndMetadataMatchThePublishedLayout() {
    assertEquals(
        BASE + "kk-studio-daemon-v1.0.12-linux-arm64.tar.gz",
        DaemonUpdateArtifact.artifactUrl(
            VERSION, DaemonOperatingSystem.LINUX, DaemonArchitecture.ARM64));
    assertEquals(
        BASE + "kk-studio-daemon-v1.0.12-linux-arm64.tar.gz.sha256",
        DaemonUpdateArtifact.checksumUrl(
            VERSION, DaemonOperatingSystem.LINUX, DaemonArchitecture.ARM64));
    assertEquals(
        "kk-studio-daemon-v1.0.12-linux-arm64.json",
        DaemonUpdateArtifact.metadataName(
            VERSION, DaemonOperatingSystem.LINUX, DaemonArchitecture.ARM64));
  }

  @Test
  void unsupportedPlatformHasNoArtifact() {
    assertFalse(
        DaemonUpdateArtifact.isSupported(DaemonOperatingSystem.WINDOWS, DaemonArchitecture.ARM64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonUpdateArtifact.artifactName(
                VERSION, DaemonOperatingSystem.WINDOWS, DaemonArchitecture.ARM64));
  }

  /** URL 模式只接受发布矩阵内的官方归档，拒绝旧 JAR、未发布平台、缺失架构段与其他仓库。 */
  @Test
  void urlPatternOnlyAcceptsPublishedArchives() {
    for (String platform :
        new String[] {
          "linux-amd64", "linux-arm64", "macos-amd64", "macos-arm64", "windows-amd64"
        }) {
      String url = BASE + "kk-studio-daemon-v1.0.12-" + platform + ".tar.gz";
      assertTrue(DaemonUpdateArtifact.ARTIFACT_URL_PATTERN.matcher(url).matches(), url);
    }
    for (String rejected : new String[] {"windows-arm64", "linux", "plan9-amd64"}) {
      assertFalse(
          DaemonUpdateArtifact.ARTIFACT_URL_PATTERN
              .matcher(BASE + "kk-studio-daemon-v1.0.12-" + rejected + ".tar.gz")
              .matches(),
          rejected);
    }
    assertFalse(
        DaemonUpdateArtifact.ARTIFACT_URL_PATTERN
            .matcher(BASE + "kk-studio-daemon-v1.0.12.jar")
            .matches());
    assertFalse(
        DaemonUpdateArtifact.ARTIFACT_URL_PATTERN
            .matcher("https://evil.example/kk-studio-daemon-v1.0.12-linux-amd64.tar.gz")
            .matches());
  }
}
