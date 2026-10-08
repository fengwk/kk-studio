package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Daemon READY 上报的版本化宿主 metadata。
 *
 * <p>这是 READY 的唯一 wire 形状：capabilities 协议版本、Daemon 自身构建版本（JAR manifest {@code
 * Implementation-Version}，未打包时为固定标记 {@code development}）与一份 {@link DaemonEnvironmentInfo}。READY
 * 只描述宿主 本身，不携带任何产品目录事实；{@code daemonVersion} 是构建事实，与 Card 上的 CAS {@code version} 无关。
 */
public record DaemonCapabilities(
    int version, String daemonVersion, DaemonEnvironmentInfo environment) {

  /** READY capabilities 协议版本；与 {@link DaemonCapabilitiesCodec} 共享。 */
  public static final int VERSION = 3;

  /** daemon 构建版本上界；manifest 版本与 {@code development} 标记都远小于该值。 */
  public static final int MAX_DAEMON_VERSION_CHARS = 64;

  /** daemon 构建版本允许的字符集合，与发布脚本的 manifest/tag 校验一致；拒绝空格与任意自由文本。 */
  private static final Pattern DAEMON_VERSION_PATTERN =
      Pattern.compile("[0-9A-Za-z._+-]{1," + MAX_DAEMON_VERSION_CHARS + "}");

  public DaemonCapabilities {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported capabilities version: " + version);
    }
    daemonVersion = validateDaemonVersion(daemonVersion);
    environment = Objects.requireNonNull(environment, "environment");
  }

  /**
   * 校验 daemon 自报的构建版本：非空白且只含版本字符。
   *
   * <p>该值是 manifest 派生的构建事实，进入 Card 展示与更新目标判定，因此不接受空白、控制字符或任意自由文本。
   */
  public static String validateDaemonVersion(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("daemonVersion must not be blank");
    }
    if (value.length() > MAX_DAEMON_VERSION_CHARS) {
      throw new IllegalArgumentException(
          "daemonVersion exceeds " + MAX_DAEMON_VERSION_CHARS + " characters");
    }
    if (!DAEMON_VERSION_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("daemonVersion must match [0-9A-Za-z._+-]+");
    }
    return value;
  }
}
