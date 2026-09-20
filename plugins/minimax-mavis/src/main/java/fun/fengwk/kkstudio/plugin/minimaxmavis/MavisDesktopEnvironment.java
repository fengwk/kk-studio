package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * renewal 请求需要声明的桌面客户端身份。
 *
 * <p>网关观测到的桌面请求会带上一个会话级 {@code device_id} 与操作系统名。{@code device_id} 每个 JVM 会话随机生成一次，
 * 与参考实现的进程级随机值语义一致；{@link #session()} 之外只允许显式构造，以便测试用固定身份断言签名。
 */
public record MavisDesktopEnvironment(String deviceId, String osName) {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int DEVICE_ID_BOUND = 90_000_000;
  private static final int DEVICE_ID_BASE = 10_000_000;
  private static final String UNKNOWN_OS = "unknown";

  public MavisDesktopEnvironment {
    if (deviceId == null || deviceId.isBlank()) {
      throw new MavisValidationException("device id must not be empty");
    }
    if (osName == null || osName.isBlank()) {
      throw new MavisValidationException("os name must not be empty");
    }
  }

  /** 生成当前 JVM 会话的桌面身份。 */
  public static MavisDesktopEnvironment session() {
    return new MavisDesktopEnvironment(
        Integer.toString(RANDOM.nextInt(DEVICE_ID_BOUND) + DEVICE_ID_BASE),
        detectOsName(System.getProperty("os.name", "")));
  }

  /** 按参考实现的 {@code sys.platform} 语义把 JVM {@code os.name} 收敛为四种稳定取值之一。 */
  static String detectOsName(String osName) {
    String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("windows")) {
      return "Windows";
    }
    if (normalized.startsWith("mac")) {
      return "macOS";
    }
    if (normalized.startsWith("linux")) {
      return "Linux";
    }
    return UNKNOWN_OS;
  }
}
