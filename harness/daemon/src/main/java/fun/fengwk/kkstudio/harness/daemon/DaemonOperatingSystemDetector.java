package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Daemon 本机 OS family 检测；未知平台 fail closed，Linux 下优先识别 WSL。 */
public final class DaemonOperatingSystemDetector {

  private static final Path PROC_VERSION = Path.of("/proc/version");

  private DaemonOperatingSystemDetector() {}

  public static DaemonOperatingSystem detectCurrent() {
    String procVersion = "";
    try {
      if (Files.isRegularFile(PROC_VERSION)) {
        procVersion = Files.readString(PROC_VERSION);
      }
    } catch (IOException ignored) {
      // 环境变量仍可识别 WSL；读取失败时保持空字符串并继续做 fail-closed 判定。
    }
    return classify(System.getProperty("os.name"), System.getenv(), procVersion);
  }

  /** 纯判定核心，便于覆盖 Windows、WSL、Linux、macOS 与未知平台。 */
  public static DaemonOperatingSystem classify(
      String osName, Map<String, String> environment, String procVersion) {
    String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    if (normalized.contains("windows")) {
      return DaemonOperatingSystem.WINDOWS;
    }
    if (normalized.contains("mac") || normalized.contains("darwin")) {
      return DaemonOperatingSystem.MACOS;
    }
    if (normalized.contains("linux")) {
      Map<String, String> env = environment == null ? Map.of() : environment;
      if (nonBlank(env.get("WSL_DISTRO_NAME"))
          || nonBlank(env.get("WSL_INTEROP"))
          || containsMicrosoft(procVersion)) {
        return DaemonOperatingSystem.WSL;
      }
      return DaemonOperatingSystem.LINUX;
    }
    throw new IllegalStateException("unsupported daemon operating system: " + osName);
  }

  private static boolean nonBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean containsMicrosoft(String value) {
    return value != null && value.toLowerCase(Locale.ROOT).contains("microsoft");
  }
}
