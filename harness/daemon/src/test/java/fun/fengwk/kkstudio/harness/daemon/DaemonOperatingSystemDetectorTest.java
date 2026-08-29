package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.util.Map;

/** OS family 纯判定核心覆盖原生平台、WSL 优先级与未知平台 fail-closed。 */
class DaemonOperatingSystemDetectorTest {

  @Test
  void classifiesWindowsMacosAndLinux() {
    assertEquals(
        DaemonOperatingSystem.WINDOWS,
        DaemonOperatingSystemDetector.classify("Windows 11", Map.of(), "", ""));
    assertEquals(
        DaemonOperatingSystem.MACOS,
        DaemonOperatingSystemDetector.classify("Mac OS X", Map.of(), "", ""));
    assertEquals(
        DaemonOperatingSystem.MACOS,
        DaemonOperatingSystemDetector.classify("Darwin", Map.of(), "", ""));
    assertEquals(
        DaemonOperatingSystem.LINUX,
        DaemonOperatingSystemDetector.classify("Linux", Map.of(), "Linux version 6.8", "6.8"));
  }

  @Test
  void classifiesWslBeforeGenericLinux() {
    assertEquals(
        DaemonOperatingSystem.WSL,
        DaemonOperatingSystemDetector.classify(
            "Linux", Map.of("WSL_DISTRO_NAME", "Ubuntu"), "", ""));
    assertEquals(
        DaemonOperatingSystem.WSL,
        DaemonOperatingSystemDetector.classify(
            "Linux", Map.of("WSL_INTEROP", "/run/WSL/1_interop"), "", ""));
    assertEquals(
        DaemonOperatingSystem.WSL,
        DaemonOperatingSystemDetector.classify(
            "Linux", Map.of(), "Linux version 5.15.90.1-microsoft-standard-WSL2", "5.15.90.1"));
    assertEquals(
        DaemonOperatingSystem.WSL,
        DaemonOperatingSystemDetector.classify(
            "Linux", Map.of(), "Linux version 6.8", "5.15.90.1-microsoft-standard-WSL2"));
  }

  @Test
  void rejectsUnknownOperatingSystem() {
    assertThrows(
        IllegalStateException.class,
        () -> DaemonOperatingSystemDetector.classify("Plan 9", Map.of(), "", ""));
  }
}
